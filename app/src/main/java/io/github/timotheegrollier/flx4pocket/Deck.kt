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
    @Volatile
    var padMode: Int = PAD_HOT_CUE
    @Volatile
    var hotCues: Array<Double?> = arrayOfNulls(8)
        private set

    /** Pad currently held in a Pad FX mode, or -1. */
    @Volatile
    var heldFxPad: Int = -1
        private set
    @Volatile
    var scratching: Boolean = false
        private set

    val padFx = FxUnit(SAMPLE_RATE)
    private val cfxEcho = FxUnit(SAMPLE_RATE).apply { beats = 0.75 }

    // State below is only touched while holding the deck lock.
    private var cursor = 0.0 // source frames
    private var tempoNormalized = 0.0
    private var jogBend = 0.0
    private var scratchPending = 0.0 // source frames still to travel
    private var rolling = false
    private var rollStart = 0.0
    private var rollLength = 0.0
    private var slipCursor = 0.0
    private var cuePreview = false
    private var misses = 0
    private val sample = FloatArray(2)

    // Keylock grains (WSOLA) and click-free jumps, in source frames.
    private var stretching = false // the last block played grains
    private var grainPos = 0.0 // next frame of the grain playing now
    private var grainLeft = 0 // output frames before the next grain starts
    private var tailPos = 0.0 // next frame of the audio fading out (old grain, or before a jump)
    private var tailLeft = 0
    private var tailLength = 1
    private var foldStart = 0.0 // the loop or roll a read wraps around
    private var foldLength = 0.0
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
        cuePointSec = 0.0
        loopInSec = -1.0
        loopOutSec = -1.0
        loopActive = false
        rolling = false
        scratching = false
        scratchPending = 0.0
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
        if (playing) {
            // Decaying by 10 % per block, 0.02 shifts the track about 1 ms per tick.
            jogBend = (jogBend + ticks * 0.02).coerceIn(-0.5, 0.5)
        } else {
            seekToInternal(positionSec + ticks * JOG_TICK_SECONDS)
        }
    }

    @Synchronized
    fun scratchBegin() {
        if (source == null) return
        scratching = true
        scratchPending = 0.0
    }

    /** Moves the "record" by [seconds] of track time; the audio follows within a few ms. */
    @Synchronized
    fun scratchMove(seconds: Double) {
        val src = source ?: return
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
    }

    // ------------------------------------------------------------------
    // Beat grid and sync
    // ------------------------------------------------------------------

    @Synchronized
    fun setGrid(bpm: Double, anchorSec: Double, confidence: Double) {
        grid = BeatGrid(bpm = bpm.coerceIn(0.0, 300.0), anchorSec = anchorSec)
        gridConfidence = confidence
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
        return grid.anchorSec + ((seconds - grid.anchorSec) / beat).roundToLong() * beat
    }

    @Synchronized
    fun engageSync(master: Deck): Boolean {
        if (!grid.valid || !master.grid.valid || source == null) return false
        val targetBpm = master.effectiveBpm()
        val baseBpm = grid.bpm * tempoRate
        if (targetBpm <= 0.0 || baseBpm <= 0.0) return false
        syncFactor = (targetBpm / baseBpm).coerceIn(0.5, 2.0)
        syncEnabled = true
        if (master.playing) {
            val errorBeats = BeatGrid.phaseError(master.phase(), phase())
            seekToInternal(positionSec + errorBeats * grid.secondsPerBeat())
        }
        return true
    }

    @Synchronized
    fun releaseSync() = releaseSyncInternal()

    private fun releaseSyncInternal() {
        syncEnabled = false
        syncFactor = 1.0
    }

    // ------------------------------------------------------------------
    // Loops
    // ------------------------------------------------------------------

    @Synchronized
    fun setLoopIn() {
        if (source == null) return
        loopInSec = snap(positionSec)
        loopOutSec = -1.0
        loopActive = false
    }

    @Synchronized
    fun setLoopOut() {
        if (source == null || loopInSec < 0.0) return
        val out = snap(positionSec)
        if (out <= loopInSec + MIN_LOOP_SECONDS) return
        loopOutSec = out
        loopActive = true
        seekToInternal(loopInSec)
    }

    /** RELOOP/EXIT: leaves the active loop, or re-enters the last one. */
    @Synchronized
    fun toggleLoop() {
        if (loopActive) {
            loopActive = false
        } else if (loopInSec >= 0.0 && loopOutSec > loopInSec) {
            loopActive = true
            seekToInternal(loopInSec)
        }
    }

    @Synchronized
    fun exitLoop() {
        loopActive = false
    }

    /** Starts a loop of [beats] at the current beat; the same size again exits it. */
    @Synchronized
    fun toggleBeatLoop(beats: Double) {
        if (source == null) return
        if (loopActive && abs(loopBeats() - beats) < 1e-3) {
            loopActive = false
            return
        }
        val beat = beatSeconds()
        val start = if (grid.valid && quantize) {
            grid.anchorSec + grid.beatIndex(positionSec) * beat
        } else {
            positionSec
        }
        setLoop(start, start + beats * beat)
    }

    /** Auto loop of [beats] starting at the loop-in point (IN / 4 BEAT long press). */
    @Synchronized
    fun loopFromIn(beats: Double) {
        if (source == null || loopInSec < 0.0) return
        setLoop(loopInSec, loopInSec + beats * beatSeconds())
    }

    /** Halves ([factor] 0.5) or doubles (2.0) the active loop. */
    @Synchronized
    fun scaleLoop(factor: Double) {
        if (!loopActive) return
        val length = (loopOutSec - loopInSec) * factor
        if (length < MIN_LOOP_SECONDS || length > 120.0) return
        setLoop(loopInSec, loopInSec + length)
    }

    /** Length of the active loop in beats, 0 when no loop is active. */
    fun loopBeats(): Double = if (loopActive) (loopOutSec - loopInSec) / beatSeconds() else 0.0

    private fun setLoop(start: Double, end: Double) {
        loopInSec = start.coerceAtLeast(0.0)
        loopOutSec = end
        loopActive = true
        if (positionSec < loopInSec || positionSec >= loopOutSec) seekToInternal(loopInSec)
    }

    @Synchronized
    fun beatJump(beats: Double) {
        if (source != null) seekToInternal(positionSec + beats * beatSeconds())
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
            // Slip: resume where the track would be had the roll never happened.
            rolling = false
            seekToInternal(slipCursor / (source?.sampleRate ?: SAMPLE_RATE))
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
     * Renders [frames] at [SAMPLE_RATE]. The result is post-trim, post-EQ,
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
            renderFx(outL, outR, frames) // let echo and reverb tails ring out
            return
        }

        val rate = src.sampleRate.toDouble()
        val ratio = rate / SAMPLE_RATE
        val playStep = if (playing) {
            val sync = if (syncEnabled && grid.valid && syncMaster != null && syncMaster.grid.valid && syncMaster.playing) {
                // Follow the master's tempo as it moves, and pull the beats together.
                syncFactor = (syncMaster.effectiveBpm() / (grid.bpm * tempoRate)).coerceIn(0.5, 2.0)
                val error = BeatGrid.phaseError(syncMaster.phase(), phase())
                syncFactor * (1.0 + (error * 0.05).coerceIn(-0.004, 0.004))
            } else {
                syncFactor
            }
            jogBend *= 0.9
            ratio * tempoRate * sync * (1.0 + nudge + jogBend)
        } else {
            0.0
        }
        val step = if (scratching) {
            // Spread the pending movement over a few blocks: smooth, but still tight.
            val move = scratchPending * 0.4
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
        foldStart = wrapStart
        foldLength = if (wrap) wrapLength else 0.0

        // Keylock: grains read at the track's own pitch while the playhead moves at `step`.
        val stretch = keyLock && playing && !scratching
        if (stretch != stretching) {
            if (stretch) {
                grainPos = cursor
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
                cursor = wrapStart + (cursor - wrapStart) % wrapLength
                if (!src.hasFrame(cursor)) src.seek(cursor / rate)
            }
            if (stretching && grainLeft <= 0) startGrain(src, ratio)
            if (!src.sampleFrame(if (stretching) fold(grainPos) else cursor, sample)) {
                // Not decoded yet: output silence and hold the position.
                outL[i] = 0f
                outR[i] = 0f
                i++
                if (++misses > SAMPLE_RATE / 4) {
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
                val heard = src.sampleFrame(fold(tailPos), sample)
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
        if (playing || scratching) {
            // Fade the old position out over a few ms rather than cutting it. A grain
            // already fading out finishes first, then the next grain starts at the target.
            if (stretching) grainLeft = min(grainLeft, tailLeft) else startTail(cursor, JUMP_FADE)
        }
        cursor = clamped * src.sampleRate
        positionSec = clamped
        misses = 0
        if (!src.hasFrame(cursor)) src.seek(clamped)
    }

    private fun startTail(position: Double, length: Int) {
        tailPos = position
        tailLeft = length
        tailLength = length
    }

    /** Reads that run past the end of the active loop or roll continue from its start. */
    private fun fold(position: Double): Double =
        if (foldLength > 1.0 && position >= foldStart + foldLength) foldStart + (position - foldStart) % foldLength else position

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
        grainPos = cursor + best * ratio
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
        if (src.sampleFrame(fold(position), sample)) (sample[0] + sample[1]) * 0.5f else 0f

    private fun resetFilters() {
        lowL.reset(); lowR.reset()
        midL.reset(); midR.reset()
        highL.reset(); highR.reset()
        filterL.reset(); filterR.reset()
    }

    private fun updateFilters() {
        val fs = SAMPLE_RATE.toDouble()
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

    /** A Pad FX slot; a null [type] is a slip loop roll. */
    class PadFx(val label: String, val type: FxType?, val beats: Double)

    companion object {
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
        private const val FILTER_DEAD_ZONE = 0.02f

        // Keylock grains, in 44.1 kHz frames: a new grain every 70 ms, crossfaded over
        // 12 ms, placed up to 12 ms either side of the playhead (SoundTouch's music settings).
        private const val GRAIN_HOP = 3072
        private const val GRAIN_OVERLAP = 512
        private const val GRAIN_SEEK = 512

        /** Crossfade on a jump (hot cue, loop, seek): 3 ms, enough to avoid a click. */
        private const val JUMP_FADE = 128
    }
}
