package io.github.timotheegrollier.flx4pocket

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.tanh

object MixerMath {

    /** Squared fader curve gives a usable resolution at the bottom of the throw. */
    fun faderGain(value: Float): Float {
        val v = value.coerceIn(0f, 1f)
        return v * v
    }

    /**
     * Equal-power crossfader. [crossfader] is 0 for deck A, 1 for deck B.
     */
    fun crossGain(crossfader: Float, deckIndex: Int): Float {
        val x = crossfader.coerceIn(0f, 1f)
        val weight = if (deckIndex == 0) 1f - x else x
        return sin(weight * (PI / 2.0)).toFloat()
    }

    /**
     * EQ knobs are 0..1 with 0.5 at unity and the range of a Pioneer DJ mixer:
     * -26 dB fully down, +6 dB fully up. More boost only drives the master
     * into its limiter.
     */
    fun eqDecibels(knob: Float): Double {
        val v = knob.coerceIn(0f, 1f) - 0.5
        return if (v >= 0.0) v * 12.0 else v * 52.0
    }

    /** Trim knob 0..1 with 0.5 at unity, up to +12 dB and down to silence. */
    fun trimGain(knob: Float): Float {
        val g = knob.coerceIn(0f, 1f) * 2f
        return g * g
    }

    /**
     * Where a peak (linear, 1 = full scale) sits on a meter that shows [floorDb] to [topDb]
     * dBFS: 0 at or below the floor, 1 at or above the top, evenly in decibels in between.
     */
    fun meterPosition(peak: Float, floorDb: Float, topDb: Float): Float {
        if (!(peak > 0f)) return 0f
        val db = 20f * log10(peak)
        return ((db - floorDb) / (topDb - floorDb)).coerceIn(0f, 1f)
    }

    /**
     * The FLX4 channel level LEDs for a peak: from the bottom, 2 green, 2 orange, 1 red, lit
     * from -24, -12, -6, -3 and 0 dBFS (the screen meter's segments 4, 8, 10, 11 and 12). The
     * values are the middles of the bands in Pioneer's MIDI message list (38, 65, 87, 101 and
     * 119 light 1 to 5 LEDs). A well-trimmed track lights orange on its loudest parts.
     */
    fun ledLevel(peak: Float): Int {
        if (!(peak > 0f)) return 0
        val db = 20f * log10(peak)
        return LED_VALUES[LED_DB.count { db >= it }]
    }

    private val LED_DB = floatArrayOf(-24f, -12f, -6f, -3f, 0f)
    private val LED_VALUES = intArrayOf(0, 0x33, 0x4C, 0x5E, 0x6E, 0x7F)

    fun filterAmount(knob: Float): Float = (knob.coerceIn(0f, 1f) - 0.5f) * 2f

    /**
     * Headphone safety curve: linear up to 0.8, then eases into +/-1 instead
     * of hard-clipping. The master uses [Limiter], which does not distort.
     */
    fun softClip(x: Float): Float {
        val magnitude = abs(x)
        if (magnitude <= 0.8f) return x
        val shaped = 0.8f + 0.2f * tanh((magnitude - 0.8f) / 0.2f)
        return if (x < 0f) -shaped else shaped
    }
}

/**
 * Master peak limiter. When two loud tracks overlap it turns the mix down at
 * once and lets it back up over about 100 ms, instead of reshaping every peak
 * above a fixed knee as a clipping curve does. Stereo-linked, so the image
 * does not move. Audio thread only.
 */
class Limiter(sampleRate: Int) {

    private val release = (1.0 - exp(-1.0 / (0.1 * sampleRate))).toFloat()
    private var gain = 1f

    /** Gain for one frame whose louder channel has magnitude [peak]. */
    fun gainFor(peak: Float): Float {
        gain += (1f - gain) * release
        if (peak * gain > CEILING) gain = CEILING / peak
        return gain
    }

    companion object {
        const val CEILING = 0.95f
    }
}
