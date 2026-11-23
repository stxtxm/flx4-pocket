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

    @Test
    fun `fft of a sine peaks at the expected bin`() {
        val n = 256
        val input = FloatArray(n) { i ->
            Math.sin(2.0 * Math.PI * 1000.0 * i / 44100.0).toFloat()
        }
        val power = Spectrum.fft(input)
        assertEquals(n / 2 + 1, power.size)
        // 1000 Hz at 44100 Hz with n = 256 -> bin 1000 / (44100 / 256) = 5.81
        val peak = power.indices.maxByOrNull { power[it] }!!
        assertTrue("peak bin should be near 6, got $peak", peak in 5..7)
    }

    @Test
    fun `spectrum peaks in the band of the sine`() {
        val n = 256
        val input = FloatArray(n) { i ->
            Math.sin(2.0 * Math.PI * 1000.0 * i / 44100.0).toFloat()
        }
        val bands = Spectrum.spectrum(input, 32)
        assertEquals(32, bands.size)
        // 1000 Hz lands in bin ~5.8; the 4-bin bands of a 256-point FFT make
        // that band 1.
        val peak = bands.indices.maxByOrNull { bands[it] }!!
        assertEquals("peak band should be 1, got $peak", 1, peak)
        assertTrue("spectrum should be peak-normalized", bands.max()!! <= 1.0f + 1e-6f)
    }

    @Test
    fun `spectrum of a step has its peak in the low bands`() {
        val n = 256
        val input = FloatArray(n) { if (it < n / 2) 1f else 0f }
        val bands = Spectrum.spectrum(input)
        val peak = bands.indices.maxByOrNull { bands[it] }!!
        assertEquals("step energy should peak in the low bands, got $peak", 0, peak)
    }

    @Test
    fun `spectrum of silence is zero`() {
        val bands = Spectrum.spectrum(FloatArray(256), 32)
        assertTrue(bands.all { it == 0f })
    }
}
