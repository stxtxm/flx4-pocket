package io.github.timotheegrollier.flx4pocket

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.tan

enum class FxType(val label: String) {
    ECHO("ECHO"),
    LOW_CUT_ECHO("LC ECHO"),
    REVERB("REVERB"),
    FLANGER("FLANGER"),
    PHASER("PHASER"),
    TRANS("TRANS")
}

/**
 * One tempo-synced insert effect on a stereo stream. Used for the Beat FX
 * section, the Pad FX modes and the Smart CFX echo.
 *
 * Parameters are volatile and read once per block; [process] must only be
 * called from the audio thread. Android-free, so it is unit tested on the JVM.
 */
class FxUnit(private val sampleRate: Int = 44100) {

    @Volatile
    var type: FxType = FxType.ECHO

    /** Effect time in beats: echo delay, trans gate period, LFO cycle = 8 x beats. */
    @Volatile
    var beats: Double = 0.5

    @Volatile
    var depth: Float = 0.5f

    @Volatile
    var enabled: Boolean = false

    private val size = sampleRate * 2
    private val bufL = FloatArray(size)
    private val bufR = FloatArray(size)
    private var write = 0

    /** Frames of echo / reverb tail left to render after the effect is switched off. */
    private var tail = 0
    private var lastType = FxType.ECHO
    private var lfo = 0.0
    private var gate = 1f
    private var lowL = 0f
    private var lowR = 0f
    private val lowCutCoef = (1.0 - exp(-2.0 * PI * 500.0 / sampleRate)).toFloat()

    // Reverb: four damped combs and two all-passes per channel (Freeverb layout).
    private val combs = Array(8) { FloatArray(COMB_LENGTHS[it % 4] + if (it >= 4) 23 else 0) }
    private val combPos = IntArray(8)
    private val combLow = FloatArray(8)
    private val allPass = Array(4) { FloatArray(ALL_PASS_LENGTHS[it % 2] + if (it >= 2) 23 else 0) }
    private val allPassPos = IntArray(4)

    private val phaser = FloatArray(8)
    private var phaserCoef = 0f

    /**
     * Processes [frames] in place. [bpm] is the audible tempo of the stream and
     * [beatPosition] its position in beats at the start of the block, which
     * keeps the TRANS gate locked to the grid.
     */
    fun process(l: FloatArray, r: FloatArray, frames: Int, bpm: Double, beatPosition: Double = 0.0) {
        val on = enabled
        val current = type
        if (current != lastType) {
            clear()
            lastType = current
        }
        if (on) tail = TAIL_SECONDS * sampleRate else if (tail <= 0) return
        if (!on) tail -= frames

        val tempo = if (bpm.isFinite() && bpm in 40.0..300.0) bpm else 120.0
        val beatFrames = sampleRate * 60.0 / tempo
        val length = beats.coerceIn(1.0 / 16.0, 16.0)
        val amount = depth.coerceIn(0f, 1f)
        when (current) {
            FxType.ECHO -> echo(l, r, frames, on, amount, (beatFrames * length).toInt(), false)
            FxType.LOW_CUT_ECHO -> echo(l, r, frames, on, amount, (beatFrames * length).toInt(), true)
            FxType.REVERB -> reverb(l, r, frames, on, amount)
            FxType.FLANGER -> if (on) flanger(l, r, frames, amount, beatFrames * length * 8.0) else tail = 0
            FxType.PHASER -> if (on) phaser(l, r, frames, amount, beatFrames * length * 8.0) else tail = 0
            FxType.TRANS -> if (on) trans(l, r, frames, amount, beatPosition / length, 1.0 / (beatFrames * length)) else tail = 0
        }
    }

    private fun clear() {
        bufL.fill(0f)
        bufR.fill(0f)
        combs.forEach { it.fill(0f) }
        allPass.forEach { it.fill(0f) }
        combLow.fill(0f)
        phaser.fill(0f)
        lowL = 0f
        lowR = 0f
        gate = 1f
        tail = 0
    }

    private fun echo(
        l: FloatArray, r: FloatArray, frames: Int, on: Boolean, amount: Float,
        delayFrames: Int, lowCut: Boolean
    ) {
        val delay = delayFrames.coerceIn(64, size - 1)
        val feedback = 0.3f + 0.45f * amount
        val input = if (on) 1f else 0f // off: stop feeding, let the repeats die out
        for (i in 0 until frames) {
            var read = write - delay
            if (read < 0) read += size
            var dl = bufL[read]
            var dr = bufR[read]
            if (lowCut) {
                lowL += lowCutCoef * (dl - lowL)
                lowR += lowCutCoef * (dr - lowR)
                dl -= lowL
                dr -= lowR
            }
            bufL[write] = l[i] * input + dl * feedback
            bufR[write] = r[i] * input + dr * feedback
            l[i] += dl * amount
            r[i] += dr * amount
            if (++write == size) write = 0
        }
    }

    private fun reverb(l: FloatArray, r: FloatArray, frames: Int, on: Boolean, amount: Float) {
        val input = if (on) 0.2f else 0f
        for (i in 0 until frames) {
            l[i] += reverbChannel(l[i] * input, 0) * amount
            r[i] += reverbChannel(r[i] * input, 1) * amount
        }
    }

    private fun reverbChannel(x: Float, channel: Int): Float {
        var out = 0f
        for (c in channel * 4 until channel * 4 + 4) {
            val buffer = combs[c]
            val pos = combPos[c]
            val y = buffer[pos]
            combLow[c] = y * 0.8f + combLow[c] * 0.2f
            buffer[pos] = x + combLow[c] * 0.84f
            combPos[c] = if (pos + 1 == buffer.size) 0 else pos + 1
            out += y
        }
        for (a in channel * 2 until channel * 2 + 2) {
            val buffer = allPass[a]
            val pos = allPassPos[a]
            val y = buffer[pos]
            buffer[pos] = out + y * 0.5f
            allPassPos[a] = if (pos + 1 == buffer.size) 0 else pos + 1
            out = y - out
        }
        return out
    }

    private fun flanger(l: FloatArray, r: FloatArray, frames: Int, amount: Float, cycleFrames: Double) {
        val step = 1.0 / cycleFrames
        for (i in 0 until frames) {
            lfo += step
            if (lfo >= 1.0) lfo -= 1.0
            // 1..5 ms swept delay
            val delay = sampleRate * (0.003 + 0.002 * sin(2.0 * PI * lfo))
            var position = write - delay
            if (position < 0) position += size
            val index = position.toInt()
            val fraction = (position - index).toFloat()
            val next = if (index + 1 == size) 0 else index + 1
            val dl = bufL[index] + (bufL[next] - bufL[index]) * fraction
            val dr = bufR[index] + (bufR[next] - bufR[index]) * fraction
            bufL[write] = l[i] + dl * 0.5f
            bufR[write] = r[i] + dr * 0.5f
            l[i] += dl * amount
            r[i] += dr * amount
            if (++write == size) write = 0
        }
    }

    private fun phaser(l: FloatArray, r: FloatArray, frames: Int, amount: Float, cycleFrames: Double) {
        val step = 1.0 / cycleFrames
        for (i in 0 until frames) {
            lfo += step
            if (lfo >= 1.0) lfo -= 1.0
            if (i and 31 == 0) { // tan() is too slow to run per sample
                val cutoff = 300.0 + 2700.0 * (0.5 + 0.5 * sin(2.0 * PI * lfo))
                val t = tan(PI * cutoff / sampleRate)
                phaserCoef = ((t - 1.0) / (t + 1.0)).toFloat()
            }
            l[i] += phaserChannel(l[i], 0) * amount
            r[i] += phaserChannel(r[i], 4) * amount
        }
    }

    private fun phaserChannel(x: Float, offset: Int): Float {
        var v = x
        for (stage in offset until offset + 4) {
            val y = phaserCoef * v + phaser[stage]
            phaser[stage] = v - phaserCoef * y
            v = y
        }
        return v
    }

    private fun trans(
        l: FloatArray, r: FloatArray, frames: Int, amount: Float,
        startPhase: Double, step: Double
    ) {
        var phase = startPhase
        for (i in 0 until frames) {
            val target = if (phase - floor(phase) < 0.5) 1f else 1f - amount
            gate += (target - gate) * 0.02f // ~1 ms ramp instead of a click
            l[i] *= gate
            r[i] *= gate
            phase += step
        }
    }

    companion object {
        private const val TAIL_SECONDS = 8
        private val COMB_LENGTHS = intArrayOf(1557, 1617, 1491, 1422)
        private val ALL_PASS_LENGTHS = intArrayOf(225, 556)
    }
}
