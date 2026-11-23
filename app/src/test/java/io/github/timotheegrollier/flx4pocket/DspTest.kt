package io.github.timotheegrollier.flx4pocket

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DspTest {

    @Test
    fun `zero gain shelf is transparent`() {
        val biquad = Biquad()
        biquad.setLowShelf(44100.0, 120.0, 0.0)
        for (i in 0 until 64) {
            assertEquals(0.25, biquad.process(0.25), 1e-12)
        }
    }

    @Test
    fun `low pass keeps DC and attenuates high frequency`() {
        val biquad = Biquad()
        biquad.setLowPass(44100.0, 500.0)
        var dc = 0.0
        repeat(20000) { dc = biquad.process(1.0) }
        assertEquals(1.0, dc, 0.01)

        biquad.reset()
        var peak = 0.0
        val samples = 44100
        for (i in 0 until samples) {
            val y = biquad.process(Math.sin(2.0 * Math.PI * 10000.0 * i / 44100.0))
            if (i > 1000) peak = maxOf(peak, Math.abs(y))
        }
        assertTrue("10 kHz should be attenuated, got $peak", peak < 0.05)
    }

    @Test
    fun `high pass removes DC`() {
        val biquad = Biquad()
        biquad.setHighPass(44100.0, 1000.0)
        var y = 0.0
        repeat(20000) { y = biquad.process(1.0) }
        assertEquals(0.0, y, 0.001)
    }

    @Test
    fun `crossfader is equal power`() {
        assertEquals(1.0, MixerMath.crossGain(0f, 0).toDouble(), 1e-6)
        assertEquals(0.0, MixerMath.crossGain(0f, 1).toDouble(), 1e-6)
        assertEquals(0.7071, MixerMath.crossGain(0.5f, 0).toDouble(), 1e-3)
        assertEquals(0.7071, MixerMath.crossGain(0.5f, 1).toDouble(), 1e-3)
    }

    @Test
    fun `eq and trim have a unity centre`() {
        assertEquals(0.0, MixerMath.eqDecibels(0.5f), 1e-9)
        assertEquals(1.0, MixerMath.trimGain(0.5f).toDouble(), 1e-6)
        assertEquals(0.25, MixerMath.faderGain(0.5f).toDouble(), 1e-6)
    }
}
