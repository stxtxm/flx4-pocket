package io.github.timotheegrollier.flx4pocket

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlin.math.sqrt

/**
 * One deck of the mixer: transport, tempo, sync, loops, cues, scratching, EQ,
 * filter and pad effects.
 *
 * Mutating operations are synchronized on the deck, so the audio thread
 * renders a block without seeing a half-applied seek or load. Plain mixer
 * parameters are volatile and read once per block. Android-free.
 */
class Deck(val index: Int) {

    @Volatile
    var source: PcmSource? = null
        private set

    @Volatile
    var title: String = ""
        private set
    @Volatile
    var artist: String = ""
        private set
    @Volatile
    var durationSec: Double = 0.0
        private set

    /** Filled progressively by the track analysis; null until it starts. */
    @Volatile
    var waveform: Waveform? = null

    @Volatile
    var playing: Boolean = false
        private set
    @Volatile
    var positionSec: Double = 0.0
        private set

    /** Audible playback speed of the last block (0 when stopped); lets the UI interpolate. */
    @Volatile
    var speed: Double = 0.0
        private set

    @Volatile
    var tempoRate: Double = 1.0
        private set
    @Volatile
    var tempoRange: Double = 0.10
        private set
    @Volatile
    var syncEnabled: Boolean = false
        private set
    @Volatile
    var syncFactor: Double = 1.0
        private set
    @Volatile
    var grid: BeatGrid = BeatGrid()
        private set
    @Volatile
    var gridConfidence: Double = 0.0
        private set

    @Volatile
    var volume: Float = 1f
    @Volatile
    var trim: Float = 0.5f
    @Volatile
    var eqHigh: Float = 0.5f
    @Volatile
    var eqMid: Float = 0.5f
    @Volatile
    var eqLow: Float = 0.5f
    @Volatile
    var filter: Float = 0.5f

    /** Smart CFX: the filter knob gets a resonant sweep plus a synced echo. */
    @Volatile
    var smartCfx: Boolean = false
    @Volatile
    var cueEnabled: Boolean = false
    @Volatile
    var quantize: Boolean = true

    /** Keylock (master tempo): the tempo fader, sync and nudges change the speed, not the pitch. */
    @Volatile
    var keyLock: Boolean = false

    /** Pitch bend held from the touch UI, as a fraction of speed (e.g. 0.04). */
    @Volatile
    var nudge: Double = 0.0

    @Volatile
    var cuePointSec: Double = 0.0
        private set
    @Volatile
    var loopInSec: Double = -1.0
        private set
    @Volatile
    var loopOutSec: Double = -1.0
        private set
    @Volatile
    var loopActive: Boolean = false
        private set

    /** Loop point the jog (or a drag on the waveform) moves: [ADJUST_IN], [ADJUST_OUT] or 0. */
    @Volatile
    var loopAdjust: Int = 0
        private set
    @Volatile
    var padMode: Int = PAD_HOT_CUE
    @Volatile
    var hotCues: Array<Double?> = arrayOfNulls(8)
        private set

    /** rekordbox memory cues, in track order: points and loops called with CUE/LOOP CALL. */
    @Volatile
    var memoryCues: List<MemoryCue> = emptyList()
        private set
    private var calledMemory: MemoryCue? = null

    /** Pad currently held in a Pad FX mode, or -1. */
    @Volatile
    var heldFxPad: Int = -1
        private set
    @Volatile
    var scratching: Boolean = false
        private set

    val padFx = FxUnit(SAMPLE_RATE)
    private val cfxEcho = FxUnit(SAMPLE_RATE).apply { beats = 0.75 }

    /** Rate the deck renders at: that of the output, which the engine opens at the device's own rate. */
    @Volatile
    var outputRate: Int = SAMPLE_RATE
        private set

    // State below is only touched while holding the deck lock.
    private var cursor = 0.0 // source frames
    private var tempoNormalized = 0.0
    private var jogBend = 0.0
    private var scratchPending = 0.0 // source frames still to travel
    private var scratchRate = 0.0 // the platter's speed as followed so far, in source frames per block
    private var rolling = false
    private var rollStart = 0.0
    private var rollLength = 0.0
    private var slipCursor = 0.0
    private var cuePreview = false
    private var syncOctave = 1.0 // 0.5 or 2.0 when the master's tempo is nearer double or half this deck's
    private var syncGrids = Double.NaN // the grids' tempo ratio the octave was chosen for
    private var syncOffset = 0.0 // beats the DJ nudged this deck off the master's beat: sync holds the tempo around it
    private var syncAlign = false // SYNC pressed on a playing deck: onto the master's beat in the next block
    private var syncHold = false // started or jumped by the DJ: sync keeps the beat where the deck now is
    private var heldMasterGrid: BeatGrid? = null // the master's grid the held place was measured against
    private var misses = 0
    private val sample = FloatArray(2)

    // Keylock grains (WSOLA) and click-free jumps, in source frames.
    private var stretching = false // the last block played grains
    private var grainPos = 0.0 // next frame of the grain playing now
    private var grainLeft = 0 // output frames before the next grain starts
    private var tailPos = 0.0 // next frame of the audio fading out (old grain, or before a jump)
    private var tailLeft = 0
    private var tailLength = 1
    private val reference = FloatArray(GRAIN_OVERLAP)
    private val candidates = FloatArray(2 * GRAIN_SEEK + GRAIN_OVERLAP)

    private val lowL = Biquad()
    private val lowR = Biquad()
    private val midL = Biquad()
    private val midR = Biquad()
    private val highL = Biquad()
    private val highR = Biquad()
    private val filterL = Biquad()
    private val filterR = Biquad()
    private var lastLowDb = Double.NaN
    private var lastMidDb = Double.NaN
    private var lastHighDb = Double.NaN
    private var lastFilter = Float.NaN
    private var lastSmart = false
    private var filterBypass = true

    /** Called by the engine, with the output stopped, when the output's rate changes. */
    @Synchronized
    fun setOutputRate(rate: Int) {
        outputRate = rate
        padFx.sampleRate = rate
        cfxEcho.sampleRate = rate
        // Filter coefficients belong to a rate: have updateFilters work them out again.
        lastLowDb = Double.NaN
        lastMidDb = Double.NaN
        lastHighDb = Double.NaN
        lastFilter = Float.NaN
    }

    // ------------------------------------------------------------------
    // Loading
    // ------------------------------------------------------------------

    @Synchronized
    fun load(newSource: PcmSource, title: String, artist: String, duration: Double) {
        val old = source
        clearState()
        source = newSource
        this.title = title
        this.artist = artist
        durationSec = if (duration > 0.0) duration else newSource.durationSec
        old?.let(::retire)
    }

    @Synchronized
    fun clear() {
        val old = source
        clearState()
        old?.let(::retire)
    }

    @Synchronized
    fun close() {
        source?.close()
        source = null
        playing = false
    }

    private fun clearState() {
        source = null
        title = ""
        artist = ""
        durationSec = 0.0
        waveform = null
        playing = false
        cursor = 0.0
        positionSec = 0.0
        grid = BeatGrid()
        gridConfidence = 0.0
        hotCues = arrayOfNulls(8)
        memoryCues = emptyList()
        calledMemory = null
        cuePointSec = 0.0
        loopInSec = -1.0
        loopOutSec = -1.0
        loopActive = false
        loopAdjust = 0
        rolling = false
        scratching = false
        scratchPending = 0.0
        scratchRate = 0.0
        cuePreview = false
        heldFxPad = -1
        padFx.enabled = false
        releaseSyncInternal()
        resetFilters()
    }

    private fun retire(old: PcmSource) {
        // The audio thread may still be inside a block that reads the old source.
        Thread {
            try {
                Thread.sleep(300)
            } catch (_: InterruptedException) {
            }
            old.close()
        }.start()
    }

    // ------------------------------------------------------------------
    // Transport
    // ------------------------------------------------------------------

    fun hasTrack(): Boolean = source != null

    @Synchronized
    fun togglePlay() {
        if (source == null) return
        if (playing) {
            playing = false
            cuePreview = false
            return
        }
        if (durationSec > 0 && positionSec >= durationSec - 0.05) seekToInternal(0.0)
        playing = true
    }

    @Synchronized
    fun pause() {
        playing = false
        cuePreview = false
    }

    /**
     * CDJ-style CUE press. Playing: back to the cue point and stop. Paused on
     * the cue point: play for as long as the button is held. Paused elsewhere:
     * set the cue point here.
     */
    @Synchronized
    fun cueDown() {
        if (source == null) return
        when {
            playing -> {
                playing = false
                seekToInternal(cuePointSec)
            }
            abs(positionSec - cuePointSec) < 0.03 -> {
                cuePreview = true
                playing = true
            }
            else -> {
                cuePointSec = snap(positionSec)
                seekToInternal(cuePointSec)
            }
        }
    }

    @Synchronized
    fun cueUp() {
        if (!cuePreview) return
        cuePreview = false
        playing = false
        seekToInternal(cuePointSec)
    }

    @Synchronized
    fun seekTo(seconds: Double) = seekToInternal(seconds)

    @Synchronized
    fun seekBy(seconds: Double) = seekToInternal(positionSec + seconds)

    // ------------------------------------------------------------------
    // Tempo, bend, scratch
    // ------------------------------------------------------------------

    /** [normalized] is -1 (slowest) .. +1 (fastest) across the tempo range. */
    @Synchronized
    fun setTempoNormalized(normalized: Double) {
        tempoNormalized = normalized.coerceIn(-1.0, 1.0)
        tempoRate = 1.0 + tempoNormalized * tempoRange
        releaseSyncInternal()
    }

    /**
     * Where a tempo fader (-1..1) must sit to give the speed the deck plays at
     * now, sync included: the point where a fader can take over without a jump.
     */
    val tempoPosition: Double get() = if (tempoRange > 0.0) (tempoRate * syncFactor - 1.0) / tempoRange else 0.0

    @Synchronized
    fun cycleTempoRange() {
        tempoRange = TEMPO_RANGES[(TEMPO_RANGES.indexOfFirst { it >= tempoRange - 1e-9 } + 1) % TEMPO_RANGES.size]
        tempoRate = 1.0 + tempoNormalized * tempoRange
        releaseSyncInternal()
    }

    /** Jog-wheel pitch bend: each tick adds a short-lived speed offset. */
    @Synchronized
    fun bend(ticks: Int) {
        if (adjusting) return adjustLoop(ticks * JOG_TICK_SECONDS)
        if (playing) {
            // Decaying by 10 % per block, 0.02 shifts the track about 1 ms per tick.
            jogBend = (jogBend + ticks * 0.02).coerceIn(-0.5, 0.5)
        } else {
            seekToInternal(positionSec + ticks * JOG_TICK_SECONDS)
        }
    }

    @Synchronized
    fun scratchBegin() {
        if (source == null || adjusting) return
        scratching = true
        scratchPending = 0.0
        scratchRate = 0.0
    }

    /** Moves the "record" by [seconds] of track time; the audio follows within a few ms. */
    @Synchronized
    fun scratchMove(seconds: Double) {
        val src = source ?: return
        // While a loop point is selected, a drag on the waveform moves it with the finger.
        if (adjusting) return adjustLoop(-seconds)
        if (!scratching) {
            seekToInternal(positionSec + seconds)
            return
        }
        scratchPending += seconds * src.sampleRate
    }

    @Synchronized
    fun scratchEnd() {
        scratching = false
        scratchPending = 0.0
        scratchRate = 0.0
    }

    // ------------------------------------------------------------------
    // Beat grid and sync
    // ------------------------------------------------------------------

    @Synchronized
    fun setGrid(bpm: Double, anchorSec: Double, confidence: Double) {
        grid = BeatGrid(bpm = bpm.coerceIn(0.0, 300.0), anchorSec = anchorSec)
        gridConfidence = confidence
        syncHold = true // a new grid (TAP, analysis) must not make sync slide a synced deck: it holds where it plays
    }

    /**
     * TAP: the DJ taps the beat, the last tap now. The analysed tempo is far finer than a
     * finger, so when the taps agree with it (or with its half or double) it is kept, and the
     * grid only moves by half a beat if the taps fall between its beats. Otherwise the
     * analysis was wrong and the grid is rebuilt from the taps.
     */
    @Synchronized
    fun tapGrid(tappedBpm: Double, latencySec: Double = 0.0) {
        val heard = positionSec - latencySec * speed // the taps follow the speaker, which lags the mixer
        val known = doubleArrayOf(1.0, 2.0, 0.5).map { grid.bpm * it }
            .firstOrNull { grid.valid && abs(it / tappedBpm - 1.0) < 0.04 }
            ?: return setGrid(tappedBpm, heard, 2.0)
        val beat = 60.0 / known
        val into = BeatGrid(known, grid.anchorSec).phase(heard)
        setGrid(known, if (into >= 0.25 && into < 0.75) grid.anchorSec + beat / 2.0 else grid.anchorSec, gridConfidence)
    }

    fun phase(): Double = if (grid.valid) grid.phase(positionSec) else 0.0

    fun effectiveBpm(): Double = if (grid.valid) grid.bpm * tempoRate * syncFactor else 0.0

    /** Position in beats from the grid anchor, 0 without a grid. */
    fun beatPosition(): Double =
        if (grid.valid) (positionSec - grid.anchorSec) / grid.secondsPerBeat() else 0.0

    /** Track seconds per beat; assumes 120 BPM until the grid is known. */
    private fun beatSeconds(): Double = if (grid.valid) grid.secondsPerBeat() else 0.5

    /** Nearest beat when quantize is on and a grid exists, otherwise [seconds] itself. */
    private fun snap(seconds: Double): Double {
        if (!quantize || !grid.valid) return seconds
        val beat = grid.secondsPerBeat()
        val snapped = grid.anchorSec + ((seconds - grid.anchorSec) / beat).roundToLong() * beat
        return if (snapped < 0.0) snapped + beat else snapped // in a lead-in: the first beat, not a time before the track
    }

    /**
     * SYNC: this deck takes [master]'s tempo and keeps it as the master's tempo moves.
     * Pressed while the deck plays, it also lands on the master's beat at once. A deck
     * started, cued or jumped afterwards plays from where the DJ put it: sync keeps the
     * tempo and that place against the beat, never moves the deck by itself (see [render]).
     */
    @Synchronized
    fun engageSync(master: Deck): Boolean {
        if (!grid.valid || !master.grid.valid || source == null) return false
        syncGrids = Double.NaN // choose the octave again
        followTempo(master)
        syncEnabled = true
        syncOffset = 0.0
        jogBend = 0.0 // a push just before SYNC must not be kept as a nudge
        syncAlign = playing
        return true
    }

    /**
     * Takes [master]'s tempo. 87 against 174 BPM is the same tempo: the nearer of half, same and
     * double speed, chosen at SYNC or when a grid changes, never because the master's fader moved.
     */
    private fun followTempo(master: Deck) {
        val ratio = master.effectiveBpm() / (grid.bpm * tempoRate)
        val grids = master.grid.bpm / grid.bpm
        if (grids != syncGrids) {
            syncGrids = grids
            syncOctave = if (ratio > 1.42) 0.5 else if (ratio < 0.705) 2.0 else 1.0
        }
        syncFactor = ratio * syncOctave
    }

    /**
     * SYNC off: the deck keeps the tempo it plays at, and the tempo fader takes over from there.
     * Beyond the fader's range the range widens, as rekordbox does, or the fader could never get there.
     */
    @Synchronized
    fun releaseSync() {
        tempoRate *= syncFactor
        tempoRange = TEMPO_RANGES.firstOrNull { it >= tempoRange - 1e-9 && it >= abs(tempoRate - 1.0) } ?: TEMPO_RANGES.last()
        setTempoNormalized((tempoRate - 1.0) / tempoRange)
    }

    private fun releaseSyncInternal() {
        syncEnabled = false
        syncFactor = 1.0
    }

    /** Beats this deck is behind [master]; at half or double tempo, on the beats both grids share. */
    private fun beatError(master: Deck): Double {
        val own = max(1.0 / syncOctave, 1.0)
        return BeatGrid.phaseError(master.phase() * max(syncOctave, 1.0), phase() * own) / own
    }

    /** Seconds to move for this deck's beats to fall on [master]'s (the DJ's nudge kept); never before the track start. */
    private fun beatShift(master: Deck): Double {
        val beat = grid.secondsPerBeat()
        val shift = BeatGrid.phaseError(beatError(master), syncOffset) * beat
        return if (positionSec + shift < 0.0) shift + beat else shift
    }

    // ------------------------------------------------------------------
    // Loops, played like rekordbox with the FLX4: points snap to the grid
    // when quantize is on, a loop never throws the deck off the beat, and
    // the seam crossfades instead of clicking.
    // ------------------------------------------------------------------

    /** IN: sets the loop in point. During a loop: the jog moves it, until IN again. */
    @Synchronized
    fun setLoopIn() {
        if (source == null) return
        if (loopActive) return toggleAdjust(ADJUST_IN)
        loopInSec = snap(positionSec)
        loopOutSec = -1.0
    }

    /** OUT: sets the loop out point and loops. During a loop: the jog moves it, until OUT again. */
    @Synchronized
    fun setLoopOut() {
        if (source == null || loopInSec < 0.0) return
        if (loopActive) return toggleAdjust(ADJUST_OUT)
        val out = snap(positionSec)
        if (out > loopInSec + MIN_LOOP_SECONDS) setLoop(loopInSec, out)
    }

    /** 4 BEAT/EXIT: a 4-beat loop from here, or out of the loop playing. */
    @Synchronized
    fun fourBeatLoopOrExit() = if (loopActive) exitLoop() else toggleBeatLoop(4.0)

    /** SHIFT + 4 BEAT/EXIT (active loop): turns the last loop on or off, without leaving the beat. */
    @Synchronized
    fun toggleLoop() {
        if (loopActive) {
            exitLoop()
        } else if (loopInSec >= 0.0 && loopOutSec > loopInSec) {
            setLoop(loopInSec, loopOutSec)
        }
    }

    @Synchronized
    fun exitLoop() {
        loopActive = false
        loopAdjust = 0
    }

    /** Loop of [beats] from the nearest beat (or step, for loops under a beat); the same size again exits it. */
    @Synchronized
    fun toggleBeatLoop(beats: Double) {
        if (source == null) return
        if (loopActive && abs(loopBeats() - beats) < 1e-3) return exitLoop()
        val length = beats * beatSeconds()
        // Nearest, not last: pressed just before the beat, the loop starts on it.
        val step = min(length, beatSeconds())
        val start = if (grid.valid && quantize) {
            val nearest = grid.anchorSec + ((positionSec - grid.anchorSec) / step).roundToLong() * step
            if (nearest < 0.0) nearest + step else nearest // before the track starts: the next step, so the loop keeps its length
        } else {
            positionSec
        }
        setLoop(start, start + length)
    }

    /**
     * CUE/LOOP CALL ◁ ▷: during a loop, halves or doubles it; otherwise calls the
     * previous or next memory cue as rekordbox does: the deck waits on it, a point
     * as the cue point, a loop ready to play.
     */
    @Synchronized
    fun cueLoopCall(direction: Int) {
        if (loopActive) return scaleLoop(if (direction < 0) 0.5 else 2.0)
        val here = positionSec
        val cue = if (direction < 0) {
            memoryCues.lastOrNull { it.inSec < here - CALL_TOLERANCE }
        } else {
            memoryCues.firstOrNull { it.inSec > here + CALL_TOLERANCE }
        } ?: return
        calledMemory = cue
        cuePointSec = cue.inSec
        playing = false
        cuePreview = false
        seekToInternal(cue.inSec)
        if (cue.isLoop) setLoop(cue.inSec, cue.outSec)
    }

    /** SHIFT + CUE/LOOP CALL ▷: saves the loop playing, or else the cue point, as a memory cue. */
    @Synchronized
    fun saveMemoryCue() {
        if (source == null) return
        val cue = if (loopActive) MemoryCue(loopInSec, loopOutSec) else MemoryCue(cuePointSec)
        if (memoryCues.any { abs(it.inSec - cue.inSec) < 0.01 && it.isLoop == cue.isLoop }) return
        memoryCues = (memoryCues + cue).sortedBy { it.inSec }
        calledMemory = cue
    }

    /** SHIFT + CUE/LOOP CALL ◁: deletes the memory cue called last, or the one at the playhead. */
    @Synchronized
    fun deleteMemoryCue() {
        val cue = calledMemory?.takeIf { it in memoryCues }
            ?: memoryCues.firstOrNull { abs(it.inSec - positionSec) < CALL_TOLERANCE }
            ?: return
        memoryCues = memoryCues - cue
        calledMemory = null
    }

    /** Restores saved memory cues after a track load. */
    @Synchronized
    fun setMemoryCues(cues: List<MemoryCue>) {
        memoryCues = cues.sortedBy { it.inSec }
        calledMemory = null
    }

    /** Instant doubles: plays along with [other], which has the same track, at its speed and position. */
    @Synchronized
    fun playAlong(other: Deck) {
        keyLock = other.keyLock
        tempoRange = other.tempoRange
        setTempoNormalized((other.tempoRate * other.syncFactor - 1.0) / tempoRange)
        seekToInternal(other.positionSec)
        playing = other.playing
    }

    /** Halves ([factor] 0.5) or doubles (2.0) the active loop. */
    @Synchronized
    fun scaleLoop(factor: Double) {
        if (!loopActive) return
        val length = (loopOutSec - loopInSec) * factor
        if (length < MIN_LOOP_SECONDS || length > 120.0) return
        setLoop(loopInSec, loopInSec + length)
    }

    /** Length of the active loop in beats, 0 when no loop is active; a pad size comes back exact, so its pad can light. */
    fun loopBeats(): Double {
        if (!loopActive) return 0.0
        val beats = (loopOutSec - loopInSec) / beatSeconds()
        return LOOP_BEATS.firstOrNull { abs(beats - it) < it * 1e-6 } ?: beats
    }

    /** Before the loop the deck plays into it; past its end it wraps now, at the same place in the beat. */
    private fun setLoop(start: Double, end: Double) {
        loopInSec = start.coerceAtLeast(0.0)
        loopOutSec = end
        loopActive = true
        val length = loopOutSec - loopInSec
        if (positionSec >= loopOutSec && length > 0.0) {
            seekToInternal(loopInSec + (positionSec - loopInSec) % length)
            syncHold = false // the wrap keeps the place in the beat
        }
    }

    private val adjusting: Boolean get() = loopActive && loopAdjust != 0

    private fun toggleAdjust(point: Int) {
        loopAdjust = if (loopAdjust == point) 0 else point
        scratching = false // from now on the jog moves the loop point, not the record
        scratchPending = 0.0
        scratchRate = 0.0
    }

    /** Moves the selected loop point by [seconds] (later when positive), never closer than [MIN_LOOP_SECONDS]. */
    private fun adjustLoop(seconds: Double) {
        if (loopAdjust == ADJUST_IN) {
            loopInSec = max(0.0, min(loopInSec + seconds, loopOutSec - MIN_LOOP_SECONDS))
        } else {
            val end = if (durationSec > 0.0) durationSec else Double.MAX_VALUE
            loopOutSec = min(end, max(loopOutSec + seconds, loopInSec + MIN_LOOP_SECONDS))
        }
    }

    @Synchronized
    fun beatJump(beats: Double) {
        if (source == null) return
        var shift = beats * beatSeconds()
        if (loopActive) {
            // rekordbox's loop move: during a loop, the loop jumps with the deck.
            shift = max(shift, -loopInSec)
            loopInSec += shift
            loopOutSec += shift
        }
        seekToInternal(positionSec + shift)
    }

    // ------------------------------------------------------------------
    // Pads
    // ------------------------------------------------------------------

    /** Restores saved hot cues after a track load. */
    @Synchronized
    fun setHotCues(cues: Array<Double?>) {
        hotCues = cues.copyOf(8)
    }

    /**
     * A performance pad was pressed. The touch UI passes the deck's own
     * [padMode]; the controller passes the mode its pad note belongs to.
     */
    @Synchronized
    fun padDown(mode: Int, pad: Int, shift: Boolean = false) {
        if (pad !in 0..7 || source == null) return
        when (mode) {
            PAD_HOT_CUE -> {
                val existing = hotCues[pad]
                when {
                    shift -> hotCues = hotCues.copyOf().also { it[pad] = null }
                    existing == null -> hotCues = hotCues.copyOf().also { it[pad] = snap(positionSec) }
                    // Quantize, as in rekordbox: a playing deck jumps at once but stays as far into its beat.
                    quantize && playing && grid.valid -> {
                        val beat = grid.secondsPerBeat()
                        val target = existing + BeatGrid.phaseError(phase(), grid.phase(existing)) * beat
                        seekToInternal(if (target < 0.0) target + beat else target)
                    }
                    else -> seekToInternal(existing)
                }
            }
            PAD_BEAT_LOOP -> toggleBeatLoop(LOOP_BEATS[pad])
            PAD_BEAT_JUMP -> beatJump(JUMP_BEATS[pad])
            PAD_FX1, PAD_FX2 -> {
                releaseFxPad()
                val fx = (if (mode == PAD_FX1) PAD_FX1_SLOTS else PAD_FX2_SLOTS)[pad]
                heldFxPad = pad
                if (fx.type == null) {
                    startRoll(fx.beats)
                } else {
                    padFx.type = fx.type
                    padFx.beats = fx.beats
                    padFx.depth = 0.65f
                    padFx.enabled = true
                }
            }
        }
    }

    @Synchronized
    fun padUp(mode: Int, pad: Int) {
        if ((mode == PAD_FX1 || mode == PAD_FX2) && pad == heldFxPad) releaseFxPad()
    }

    private fun releaseFxPad() {
        heldFxPad = -1
        padFx.enabled = false
        if (rolling) {
            // Slip: resume where the track would be had the roll never happened (inside the loop, if one plays).
            rolling = false
            val resume = slipCursor / (source?.sampleRate ?: SAMPLE_RATE)
            val length = loopOutSec - loopInSec
            seekToInternal(if (loopActive && resume >= loopOutSec) loopInSec + (resume - loopInSec) % length else resume)
        }
    }

    private fun startRoll(beats: Double) {
        val src = source ?: return
        rollStart = cursor
        rollLength = max(beats * beatSeconds(), MIN_LOOP_SECONDS) * src.sampleRate
        slipCursor = cursor
        rolling = true
    }

    // ------------------------------------------------------------------
    // Audio thread
    // ------------------------------------------------------------------

    /**
     * Renders [frames] at [outputRate]. The result is post-trim, post-EQ,
     * post-filter and post-pad-FX, but pre-fader, so the engine can build both
     * the master and the cue bus from it.
     */
    @Synchronized
    fun render(frames: Int, outL: FloatArray, outR: FloatArray, syncMaster: Deck?) {
        val src = source
        if (src == null || (!playing && !scratching)) {
            java.util.Arrays.fill(outL, 0, frames, 0f)
            java.util.Arrays.fill(outR, 0, frames, 0f)
            speed = 0.0
            jogBend = 0.0
            stretching = false
            tailLeft = 0
            syncHold = true // a synced deck starts where the DJ starts it
            syncAlign = false // a SYNC press is only for a deck that plays
            renderFx(outL, outR, frames) // let echo and reverb tails ring out
            return
        }

        val rate = src.sampleRate.toDouble()
        val ratio = rate / outputRate
        val playStep = if (playing) {
            val sync = if (syncEnabled && grid.valid && syncMaster != null && syncMaster.grid.valid && syncMaster.playing) {
                // Follow the master's tempo as it moves, and keep the beats together.
                followTempo(syncMaster)
                if (syncMaster.grid !== heldMasterGrid) { // the master's grid changed (TAP, analysis): hold, don't slide
                    heldMasterGrid = syncMaster.grid
                    syncHold = true
                }
                if (syncAlign && !cuePreview) {
                    // SYNC pressed while both play: onto the master's beat at once, crossfaded like any jump.
                    val shift = beatShift(syncMaster) * rate
                    if (stretching) grainLeft = min(grainLeft, tailLeft) else if (tailLeft == 0) startTail(cursor, JUMP_FADE)
                    cursor += shift
                    positionSec = cursor / rate
                    if (!src.hasFrame(cursor)) src.seek(positionSec)
                } else if (syncHold || scratching || nudge != 0.0 || abs(jogBend) > 1e-3) {
                    // Started, jumped or nudged by the DJ: from here sync holds the tempo around that place.
                    syncOffset = beatError(syncMaster)
                }
                val error = BeatGrid.phaseError(beatError(syncMaster), syncOffset)
                syncFactor * (1.0 + (error * 0.05).coerceIn(-0.004, 0.004))
            } else {
                syncFactor
            }
            syncAlign = false
            syncHold = false
            jogBend *= 0.9
            ratio * tempoRate * sync * (1.0 + nudge + jogBend)
        } else {
            0.0
        }
        val step = if (scratching) {
            // Follow the platter's speed and take up part of what is left to travel: at a steady
            // speed the sound no longer trails the hand, and the jog's coarse ticks stay smoothed.
            val behind = scratchPending - scratchRate
            val move = scratchRate + SCRATCH_CATCH_UP * behind
            scratchRate += SCRATCH_FOLLOW * behind
            scratchPending -= move
            move / frames
        } else {
            playStep
        }
        speed = step / ratio

        val wrap = rolling || loopActive
        val wrapStart = if (rolling) rollStart else loopInSec * rate
        val wrapLength = if (rolling) rollLength else (loopOutSec - loopInSec) * rate
        val durationFrames = durationSec * rate

        // Keylock: grains read at the track's own pitch while the playhead moves at `step`.
        val stretch = keyLock && playing && !scratching
        if (stretch != stretching) {
            if (stretch) {
                grainPos = Math.rint(cursor)
                grainLeft = GRAIN_HOP
            } else {
                startTail(grainPos, GRAIN_OVERLAP) // back to reading at the playhead, without a click
            }
            stretching = stretch
        }

        updateFilters()
        val gain = MixerMath.trimGain(trim)
        var i = 0
        while (i < frames) {
            if (wrap && wrapLength > 1.0 && cursor >= wrapStart + wrapLength) {
                // Seamless: what follows the loop end fades out while the loop start fades
                // in; with KEY, through a grain started right here.
                if (stretching) grainLeft = 0 else startTail(cursor, JUMP_FADE)
                cursor = wrapStart + (cursor - wrapStart) % wrapLength
                if (!src.hasFrame(cursor)) src.seek(cursor / rate)
            }
            if (stretching && grainLeft <= 0) startGrain(src, ratio)
            if (!src.sampleFrame(if (stretching) grainPos else cursor, sample)) {
                // Not decoded yet: output silence and hold the position.
                outL[i] = 0f
                outR[i] = 0f
                i++
                if (++misses > outputRate / 4) {
                    misses = 0
                    if (durationFrames > 0.0 && cursor > durationFrames - rate) {
                        playing = false // metadata duration was longer than the audio
                    } else {
                        src.seek((cursor / rate - if (step < 0) 2.0 else 0.0).coerceAtLeast(0.0))
                    }
                }
                continue
            }
            misses = 0
            var l = sample[0].toDouble()
            var r = sample[1].toDouble()
            if (tailLeft > 0) {
                // Crossfade from the audio fading out: the previous grain, or where a jump left.
                val w = tailLeft.toDouble() / tailLength
                val heard = src.sampleFrame(tailPos, sample)
                l = l * (1.0 - w) + if (heard) sample[0] * w else 0.0
                r = r * (1.0 - w) + if (heard) sample[1] * w else 0.0
                tailPos += ratio
                tailLeft--
            }

            var left = highL.process(midL.process(lowL.process(l)))
            var right = highR.process(midR.process(lowR.process(r)))
            if (!filterBypass) {
                left = filterL.process(left)
                right = filterR.process(right)
            }
            outL[i] = (left * gain).toFloat()
            outR[i] = (right * gain).toFloat()
            i++

            if (stretching) {
                grainPos += ratio
                grainLeft--
            }
            cursor += step
            if (rolling) slipCursor += playStep
            if (cursor < 0.0) cursor = 0.0
            if (durationFrames > 0.0 && cursor >= durationFrames) {
                cursor = durationFrames
                if (!scratching) {
                    playing = false
                    java.util.Arrays.fill(outL, i, frames, 0f)
                    java.util.Arrays.fill(outR, i, frames, 0f)
                    break
                }
            }
        }
        positionSec = cursor / rate
        renderFx(outL, outR, frames)
    }

    private fun renderFx(outL: FloatArray, outR: FloatArray, frames: Int) {
        val bpm = effectiveBpm()
        val beatPosition = beatPosition()
        val amount = abs(MixerMath.filterAmount(filter))
        cfxEcho.enabled = smartCfx && amount >= FILTER_DEAD_ZONE
        // Keep the last depth once the knob is back at centre, so the echo fades out naturally.
        if (cfxEcho.enabled) cfxEcho.depth = amount * 0.6f
        cfxEcho.process(outL, outR, frames, bpm, beatPosition)
        padFx.process(outL, outR, frames, bpm, beatPosition)
    }

    private fun seekToInternal(seconds: Double) {
        val src = source ?: return
        val clamped = seconds.coerceIn(0.0, max(0.0, durationSec))
        // A jump past the loop (hot cue, needle, search) leaves it: it would fall straight back in.
        if (loopActive && clamped >= loopOutSec) exitLoop()
        if (playing || scratching) {
            // Fade the old position out over a few ms rather than cutting it. A grain
            // already fading out finishes first, then the next grain starts at the target.
            if (stretching) grainLeft = min(grainLeft, tailLeft) else startTail(cursor, JUMP_FADE)
        }
        cursor = clamped * src.sampleRate
        positionSec = clamped
        misses = 0
        syncHold = true
        if (!src.hasFrame(cursor)) src.seek(clamped)
    }

    private fun startTail(position: Double, length: Int) {
        tailPos = position
        tailLeft = length
        tailLength = length
    }

    /**
     * Keylock (WSOLA): the grain that played so far fades out while the next one
     * starts at the playhead, shifted to where its waveform best continues the old one.
     */
    private fun startGrain(src: PcmSource, ratio: Double) {
        startTail(grainPos, GRAIN_OVERLAP)
        for (j in reference.indices) reference[j] = mono(src, tailPos + j * ratio)
        for (k in candidates.indices) candidates[k] = mono(src, cursor + (k - GRAIN_SEEK) * ratio)
        var best = 0
        var bestScore = match(0)
        for (pass in 0..1) { // every 8th offset, then the ones around the best
            val from = if (pass == 0) -GRAIN_SEEK else max(-GRAIN_SEEK, best - 7)
            val to = if (pass == 0) GRAIN_SEEK else min(GRAIN_SEEK, best + 7)
            val stride = if (pass == 0) 8 else 1
            for (offset in from..to step stride) {
                val score = match(offset)
                if (score > bestScore) {
                    bestScore = score
                    best = offset
                }
            }
        }
        // On a whole frame: at the track's own rate a grain then reads its samples as they are.
        grainPos = Math.rint(max(0.0, cursor + best * ratio)) // before the track nothing can be read: the deck would freeze
        grainLeft = GRAIN_HOP
    }

    /** How well the candidate grain starting [offset] frames from the playhead continues the old one. */
    private fun match(offset: Int): Float {
        val start = offset + GRAIN_SEEK
        var dot = 0f
        var energy = 1e-9f
        for (j in reference.indices) {
            val c = candidates[start + j]
            dot += reference[j] * c
            energy += c * c
        }
        return dot / sqrt(energy)
    }

    private fun mono(src: PcmSource, position: Double): Float =
        if (src.sampleFrame(position, sample)) (sample[0] + sample[1]) * 0.5f else 0f

    private fun resetFilters() {
        lowL.reset(); lowR.reset()
        midL.reset(); midR.reset()
        highL.reset(); highR.reset()
        filterL.reset(); filterR.reset()
    }

    private fun updateFilters() {
        val fs = outputRate.toDouble()
        val lowDb = MixerMath.eqDecibels(eqLow)
        if (lowDb != lastLowDb) {
            lowL.setLowShelf(fs, 120.0, lowDb)
            lowR.setLowShelf(fs, 120.0, lowDb)
            lastLowDb = lowDb
        }
        val midDb = MixerMath.eqDecibels(eqMid)
        if (midDb != lastMidDb) {
            midL.setPeaking(fs, 1000.0, midDb)
            midR.setPeaking(fs, 1000.0, midDb)
            lastMidDb = midDb
        }
        val highDb = MixerMath.eqDecibels(eqHigh)
        if (highDb != lastHighDb) {
            highL.setHighShelf(fs, 8000.0, highDb)
            highR.setHighShelf(fs, 8000.0, highDb)
            lastHighDb = highDb
        }

        val amount = MixerMath.filterAmount(filter)
        val smart = smartCfx
        val bypass = abs(amount) < FILTER_DEAD_ZONE
        if (bypass == filterBypass && (bypass || (amount == lastFilter && smart == lastSmart))) return
        if (filterBypass && !bypass) {
            filterL.reset()
            filterR.reset()
        }
        filterBypass = bypass
        lastFilter = amount
        lastSmart = smart
        if (bypass) return
        val q = if (smart) 2.5 else 0.9
        if (amount < 0f) {
            val cutoff = (20000.0 * (1.0 + amount).toDouble().pow(3.0)).coerceIn(60.0, 20000.0)
            filterL.setLowPass(fs, cutoff, q)
            filterR.setLowPass(fs, cutoff, q)
        } else {
            val cutoff = (20.0 + 12000.0 * amount.toDouble().pow(3.0)).coerceIn(20.0, 12000.0)
            filterL.setHighPass(fs, cutoff, q)
            filterR.setHighPass(fs, cutoff, q)
        }
    }

    /** A saved cue point, or a loop when [outSec] is after [inSec] (rekordbox memory cue). */
    data class MemoryCue(val inSec: Double, val outSec: Double = -1.0) {
        val isLoop: Boolean get() = outSec > inSec
    }

    /** A Pad FX slot; a null [type] is a slip loop roll. */
    class PadFx(val label: String, val type: FxType?, val beats: Double)

    companion object {
        /** The usual rate of music files, and the output rate until the engine sets another. */
        const val SAMPLE_RATE = 44100

        // Pad modes, numbered like the high nibble of the FLX4 pad notes.
        const val PAD_HOT_CUE = 0
        const val PAD_FX1 = 1
        const val PAD_BEAT_JUMP = 2
        const val PAD_SAMPLER = 3
        const val PAD_KEYBOARD = 4
        const val PAD_FX2 = 5
        const val PAD_BEAT_LOOP = 6
        const val PAD_KEY_SHIFT = 7

        // Loop point the jog adjusts after IN or OUT is pressed during a loop.
        const val ADJUST_IN = 1
        const val ADJUST_OUT = 2

        val TEMPO_RANGES = doubleArrayOf(0.06, 0.10, 0.16, 0.50)
        val LOOP_BEATS = doubleArrayOf(0.25, 0.5, 1.0, 2.0, 4.0, 8.0, 16.0, 32.0)
        val JUMP_BEATS = doubleArrayOf(-1.0, 1.0, -2.0, 2.0, -4.0, 4.0, -8.0, 8.0)

        val PAD_FX1_SLOTS = arrayOf(
            PadFx("ECHO 1/4", FxType.ECHO, 0.25),
            PadFx("ECHO 1/2", FxType.ECHO, 0.5),
            PadFx("ECHO 3/4", FxType.ECHO, 0.75),
            PadFx("ECHO 1", FxType.ECHO, 1.0),
            PadFx("ROLL 1/8", null, 0.125),
            PadFx("ROLL 1/4", null, 0.25),
            PadFx("ROLL 1/2", null, 0.5),
            PadFx("ROLL 1", null, 1.0)
        )
        val PAD_FX2_SLOTS = arrayOf(
            PadFx("LC ECHO 1/2", FxType.LOW_CUT_ECHO, 0.5),
            PadFx("LC ECHO 1", FxType.LOW_CUT_ECHO, 1.0),
            PadFx("REVERB", FxType.REVERB, 1.0),
            PadFx("FLANGER", FxType.FLANGER, 1.0),
            PadFx("PHASER", FxType.PHASER, 1.0),
            PadFx("TRANS 1/8", FxType.TRANS, 0.125),
            PadFx("TRANS 1/4", FxType.TRANS, 0.25),
            PadFx("TRANS 1/2", FxType.TRANS, 0.5)
        )

        /** One jog tick: the platter is 720 ticks per turn at 33 1/3 rpm. */
        const val JOG_TICK_SECONDS = 1.8 / 720.0
        private const val MIN_LOOP_SECONDS = 0.02

        /** How far past a memory cue the playhead must be for CUE/LOOP CALL ◁ to go to an earlier one. */
        private const val CALL_TOLERANCE = 0.05
        private const val FILTER_DEAD_ZONE = 0.02f

        // Keylock grains, in 44.1 kHz frames: a new grain every 70 ms, crossfaded over
        // 12 ms, placed up to 12 ms either side of the playhead (SoundTouch's music settings).
        private const val GRAIN_HOP = 3072
        private const val GRAIN_OVERLAP = 512
        private const val GRAIN_SEEK = 512

        // Scratch tracking (an alpha-beta filter): the share of the remaining travel taken per
        // block, and how fast the followed speed adapts. Simulated with jog reports every 1 to
        // 8 ms: at a steady speed the sound trails the platter by 2 to 5 ms instead of 10 to 14;
        // strokes of 2 to 10 per second travel within 5 % of the hand (the plain 0.4 it replaces
        // fell 7 to 17 % short); a dead stop is overshot by under 5 ms; a slow drag is a little
        // rougher (speed ripple 27 % instead of 21 % at a third of normal speed). shortcut: tuned
        // by simulation, not by ear on the FLX4.
        private const val SCRATCH_CATCH_UP = 0.5
        private const val SCRATCH_FOLLOW = 0.05

        /** Crossfade on a jump (hot cue, loop, seek): 3 ms, enough to avoid a click. */
        private const val JUMP_FADE = 128
    }
}
