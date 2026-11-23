package io.github.timotheegrollier.flx4pocket

import kotlin.math.PI
import kotlin.math.sin

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

    /** EQ knobs are 0..1 with 0.5 at unity; range is +/- 24 dB. */
    fun eqDecibels(knob: Float): Double {
        val v = knob.coerceIn(0f, 1f)
        return (v - 0.5f) * 48.0
    }

    /** Trim knob 0..1 with 0.5 at unity, up to +12 dB and down to silence. */
    fun trimGain(knob: Float): Float {
        val g = knob.coerceIn(0f, 1f) * 2f
        return g * g
    }

    fun filterAmount(knob: Float): Float = (knob.coerceIn(0f, 1f) - 0.5f) * 2f
}
