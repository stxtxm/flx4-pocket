package io.github.timotheegrollier.flx4pocket

import android.os.SystemClock
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.pow

/**
 * One deck of the mixer. All mutating transport operations are synchronized on
 * the deck, so the audio thread can render a block without seeing a half-applied
 * seek or load.
 */
class Deck(val index: Int) {

    @Volatile
    var source: AudioSource? = null
        private set

    @Volatile
    var title: String = ""
    @Volatile
    var artist: String = ""
    @Volatile
    var durationSec: Double = 0.0

    @Volatile
    var playing: Boolean = false
        private set

    @Volatile
    var positionSec: Double = 0.0
        private set

    @Volatile
    var tempoRate: Double = 1.0
        private set

    @Volatile
    var tempoRange: Double = 0.08
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
    var volume: Float = 0.8f
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
    @Volatile
    var cueEnabled: Boolean = false
    @Volatile
    var cuePointSec: Double = 0.0
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
        private set
    @Volatile
    var hotCues: Array<Double?> = arrayOfNulls(8)
        private set
    @Volatile
    var quantize: Boolean = true
    @Volatile
    var endReached: Boolean = false
        private set

    // Audio-thread state.
    private var cursor: Double = 0.0
    private var tempoNormalized: Double = 0.0
    private var syncBaseFactor: Double = 1.0
    private val sample = FloatArray(2)

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
    private var filterBypass = true

    @Synchronized
    fun load(newSource: AudioSource, title: String, artist: String, duration: Double) {
        val old = source
        source = newSource
        this.title = title
        this.artist = artist
        durationSec = if (duration > 0.0) duration else newSource.durationSec
        cursor = 0.0
        positionSec = 0.0
        playing = false
        endReached = false
        grid = BeatGrid()
        gridConfidence = 0.0
        hotCues = arrayOfNulls(8)
        cuePointSec = 0.0
        loopInSec = -1.0
        loopOutSec = -1.0
        loopActive = false
        releaseSyncInternal()
        resetFilters()
        if (old != null) retire(old)
    }

    @Synchronized
    fun clear() {
        val old = source
        source = null
        playing = false
        cursor = 0.0
        positionSec = 0.0
        title = ""
        artist = ""
        durationSec = 0.0
        grid = BeatGrid()
        hotCues = arrayOfNulls(8)
        loopInSec = -1.0
        loopOutSec = -1.0
        loopActive = false
        releaseSyncInternal()
        if (old != null) retire(old)
    }

    @Synchronized
    fun togglePlay() {
        val src = source ?: return
        if (playing) {
            playing = false
            return
        }
        if (endReached || (durationSec > 0 && positionSec >= durationSec - 0.05)) {
            seekToInternal(0.0)
        }
        if (!src.hasFrame(cursor) && positionSec > 0.0) src.seek(positionSec)
        endReached = false
        playing = true
    }

    /**
     * CDJ-style CUE: while playing, jump to the cue and stop. While paused, set
     * the cue at the current position.
     */
    @Synchronized
    fun pause() {
        playing = false
    }

    @Synchronized
    fun resume() {
        if (source == null) return
        if (endReached) seekToInternal(0.0)
        endReached = false
        playing = true
    }

    @Synchronized
    fun cue() {
        if (source == null) return
        if (playing) {
            playing = false
            seekToInternal(cuePointSec)
        } else {
            cuePointSec = positionSec
        }
    }

    @Synchronized
    fun seekTo(seconds: Double) {
        seekToInternal(seconds)
    }

    @Synchronized
    fun setCuePoint(seconds: Double) {
        cuePointSec = seconds.coerceIn(0.0, maxOf(0.0, durationSec))
    }

    @Synchronized
    fun setTempoNormalized(normalized: Double) {
        tempoNormalized = normalized.coerceIn(-1.0, 1.0)
        tempoRate = 1.0 + tempoNormalized * tempoRange
        releaseSyncInternal()
    }

    @Synchronized
    fun cycleTempoRange() {
        tempoRange = when {
            tempoRange < 0.08 -> 0.08
            tempoRange < 0.12 -> 0.16
            tempoRange < 0.20 -> 0.25
            else -> 0.06
        }
        tempoRate = 1.0 + tempoNormalized * tempoRange
        releaseSyncInternal()
    }

    @Synchronized
    fun engageSync(master: Deck) {
        if (!grid.valid || !master.grid.valid || source == null || master.source == null) return
        val targetBpm = master.effectiveBpm()
        val baseBpm = grid.bpm * tempoRate
        if (targetBpm <= 0.0 || baseBpm <= 0.0) return
        syncFactor = (targetBpm / baseBpm).coerceIn(0.5, 1.5)
        syncBaseFactor = syncFactor
        syncEnabled = true
        val errorBeats = BeatGrid.phaseError(master.phase(), phase())
        val deltaSec = errorBeats * 60.0 / targetBpm
        seekToInternal(positionSec + deltaSec)
    }

    @Synchronized
    fun releaseSync() {
        releaseSyncInternal()
    }

    @Synchronized
    fun setGrid(bpm: Double, anchorSec: Double, confidence: Double) {
        grid = BeatGrid(bpm = bpm.coerceIn(0.0, 300.0), anchorSec = anchorSec)
        gridConfidence = confidence
    }

    @Synchronized
    fun setLoopIn() {
        loopInSec = positionSec
        loopOutSec = -1.0
        loopActive = false
    }

    @Synchronized
    fun setLoopOut() {
        if (loopInSec < 0.0) loopInSec = positionSec
        loopOutSec = positionSec
        loopActive = loopOutSec > loopInSec + 0.02
        if (loopActive) seekToInternal(loopInSec)
    }

    @Synchronized
    fun toggleLoop() {
        if (loopActive) {
            loopActive = false
        } else if (loopInSec >= 0.0 && loopOutSec > loopInSec + 0.02) {
            loopActive = true
            seekToInternal(loopInSec)
        }
    }

    @Synchronized
    fun setBeatLoop(beats: Double) {
        if (!grid.valid) return
        val secondsPerBeat = grid.secondsPerBeat()
        val beat = grid.beatIndex(positionSec)
        val start = grid.anchorSec + beat * secondsPerBeat
        loopInSec = start
        loopOutSec = start + beats * secondsPerBeat
        loopActive = true
        seekToInternal(start)
    }

    @Synchronized
    fun beatJump(beats: Double) {
        if (!grid.valid) return
        seekToInternal(positionSec + beats * grid.secondsPerBeat())
    }

    @Synchronized
    fun triggerHotCue(slot: Int) {
        if (slot !in 0 until 8 || source == null) return
        val existing = hotCues[slot]
        if (existing == null) {
            val target = if (quantize && grid.valid) {
                val spb = grid.secondsPerBeat()
                grid.anchorSec + Math.round((positionSec - grid.anchorSec) / spb) * spb
            } else {
                positionSec
            }
            hotCues = hotCues.copyOf().also { it[slot] = target }
        } else {
            seekToInternal(existing)
        }
    }

    @Synchronized
    fun deleteHotCue(slot: Int) {
        if (slot !in 0 until 8) return
        hotCues = hotCues.copyOf().also { it[slot] = null }
    }

    @Synchronized
    fun setPadMode(mode: Int) {
        padMode = mode
    }

    fun phase(): Double = if (grid.valid) grid.phase(positionSec) else 0.0

    fun effectiveBpm(): Double = if (grid.valid) grid.bpm * tempoRate * syncFactor else 0.0

    fun hasTrack(): Boolean = source != null

    /**
     * Renders [frames] at [SAMPLE_RATE]. The returned audio is post-trim,
     * post-EQ and post-filter, but pre-channel-fader and pre-crossfader, which
     * lets the engine build both the master and the cue buses from it.
     */
    @Synchronized
    fun render(frames: Int, outL: FloatArray, outR: FloatArray, syncMaster: Deck?) {
        val src = source
        if (src == null) {
            java.util.Arrays.fill(outL, 0, frames, 0f)
            java.util.Arrays.fill(outR, 0, frames, 0f)
            positionSec = 0.0
            return
        }
        if (!playing) {
            java.util.Arrays.fill(outL, 0, frames, 0f)
            java.util.Arrays.fill(outR, 0, frames, 0f)
            positionSec = cursor / src.sampleRate
            return
        }

        val rate = src.sampleRate.toDouble()
        val effectiveSync = if (syncEnabled && syncMaster != null && syncMaster.grid.valid) {
            val error = BeatGrid.phaseError(syncMaster.phase(), phase())
            syncBaseFactor * (1.0 + (error * 0.02).coerceIn(-0.002, 0.002))
        } else {
            syncFactor
        }
        val speed = tempoRate * effectiveSync
        val step = rate / SAMPLE_RATE * speed
        val loopInFrames = loopInSec * rate
        val loopOutFrames = loopOutSec * rate
        val looping = loopActive && loopOutSec > loopInSec + 0.02
        val durationFrames = durationSec * rate

        updateFilters()
        val trimGain = MixerMath.trimGain(trim)
        var i = 0
        while (i < frames) {
            if (looping && cursor >= loopOutFrames) {
                cursor -= (loopOutFrames - loopInFrames)
                resetFilters()
                if (!src.hasFrame(cursor)) src.seek(cursor / rate)
            }
            val available = src.sampleFrame(cursor, sample)
            if (!available) {
                outL[i] = 0f
                outR[i] = 0f
                i++
                continue
            }

            var left = sample[0].toDouble()
            var right = sample[1].toDouble()
            left = highL.process(midL.process(lowL.process(left)))
            right = highR.process(midR.process(lowR.process(right)))
            if (!filterBypass) {
                left = filterL.process(left)
                right = filterR.process(right)
            }

            outL[i] = (left * trimGain).toFloat()
            outR[i] = (right * trimGain).toFloat()
            cursor += step
            i++

            if (!looping && durationFrames > 0.0 && cursor >= durationFrames) {
                cursor = durationFrames
                playing = false
                endReached = true
                while (i < frames) {
                    outL[i] = 0f
                    outR[i] = 0f
                    i++
                }
            }
        }
        positionSec = cursor / rate
    }

    @Synchronized
    fun close() {
        val old = source
        source = null
        old?.close()
    }

    private fun seekToInternal(seconds: Double) {
        val src = source ?: return
        val clamped = seconds.coerceIn(0.0, maxOf(0.0, durationSec))
        val newCursor = clamped * src.sampleRate
        val jumped = abs(newCursor - cursor) > src.sampleRate * 0.25
        cursor = newCursor
        positionSec = cursor / src.sampleRate
        endReached = false
        if (jumped) resetFilters()
        if (!src.hasFrame(cursor)) src.seek(clamped)
    }

    private fun releaseSyncInternal() {
        syncEnabled = false
        syncFactor = 1.0
        syncBaseFactor = 1.0
    }

    private fun resetFilters() {
        lowL.reset(); lowR.reset()
        midL.reset(); midR.reset()
        highL.reset(); highR.reset()
        filterL.reset(); filterR.reset()
    }

    private fun updateFilters() {
        val lowDb = MixerMath.eqDecibels(eqLow)
        if (lowDb != lastLowDb) {
            lowL.setLowShelf(SAMPLE_RATE.toDouble(), 120.0, lowDb)
            lowR.setLowShelf(SAMPLE_RATE.toDouble(), 120.0, lowDb)
            lastLowDb = lowDb
        }
        val midDb = MixerMath.eqDecibels(eqMid)
        if (midDb != lastMidDb) {
            midL.setPeaking(SAMPLE_RATE.toDouble(), 1000.0, midDb)
            midR.setPeaking(SAMPLE_RATE.toDouble(), 1000.0, midDb)
            lastMidDb = midDb
        }
        val highDb = MixerMath.eqDecibels(eqHigh)
        if (highDb != lastHighDb) {
            highL.setHighShelf(SAMPLE_RATE.toDouble(), 8000.0, highDb)
            highR.setHighShelf(SAMPLE_RATE.toDouble(), 8000.0, highDb)
            lastHighDb = highDb
        }

        val amount = MixerMath.filterAmount(filter)
        val bypass = abs(amount) < 0.02
        if (bypass != filterBypass || (!bypass && amount != lastFilter)) {
            filterBypass = bypass
            if (!bypass) {
                val cutoff = if (amount < 0.0) {
                    (20000.0 * (1.0 + amount).pow(3.0)).coerceIn(30.0, 20000.0)
                } else {
                    (20.0 + 19980.0 * amount.toDouble().pow(3.0)).coerceIn(20.0, 20000.0)
                }
                if (amount < 0.0) {
                    filterL.setLowPass(SAMPLE_RATE.toDouble(), cutoff)
                    filterR.setLowPass(SAMPLE_RATE.toDouble(), cutoff)
                } else {
                    filterL.setHighPass(SAMPLE_RATE.toDouble(), cutoff)
                    filterR.setHighPass(SAMPLE_RATE.toDouble(), cutoff)
                }
            }
            lastFilter = amount
        }
    }

    private fun retire(old: AudioSource) {
        Thread {
            try {
                Thread.sleep(1500)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            old.close()
        }.start()
    }

    companion object {
        const val SAMPLE_RATE = 44100
        const val PAD_HOT_CUE = 0
        const val PAD_BEAT_LOOP = 1
        const val PAD_BEAT_JUMP = 2
    }
}
