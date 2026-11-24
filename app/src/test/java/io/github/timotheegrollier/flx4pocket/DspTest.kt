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
    fun `eq range is that of a DJ mixer`() {
        assertEquals(-26.0, MixerMath.eqDecibels(0f), 1e-6)
        assertEquals(6.0, MixerMath.eqDecibels(1f), 1e-6)
    }

    @Test
    fun `limiter turns loud passages down instead of clipping them`() {
        val limiter = Limiter(44100)
        assertEquals("quiet audio passes untouched", 1f, limiter.gainFor(0.5f), 0f)
        var loudest = 0f
        for (i in 0 until 44100) {
            val x = 1.6f * Math.sin(2.0 * Math.PI * 100.0 * i / 44100.0).toFloat()
            loudest = maxOf(loudest, Math.abs(x * limiter.gainFor(Math.abs(x))))
        }
        assertTrue("peaks stay under the ceiling, got $loudest", loudest <= Limiter.CEILING + 1e-6f)
        assertTrue("and still reach it", loudest > 0.9f)
        repeat(44100) { limiter.gainFor(0.1f) }
        assertEquals("the gain recovers once it is quiet again", 1f, limiter.gainFor(0.1f), 1e-3f)
    }

    @Test
    fun `meters read in decibels`() {
        assertEquals(1f, MixerMath.meterPosition(1f, -36f, 0f), 1e-6f)
        assertEquals("-6 dBFS", (36f - 6.0206f) / 36f, MixerMath.meterPosition(0.5f, -36f, 0f), 1e-4f)
        assertEquals("over full scale stays at the top", 1f, MixerMath.meterPosition(2f, -36f, 0f), 0f)
        assertEquals("below the floor", 0f, MixerMath.meterPosition(0.001f, -36f, 0f), 0f)
        assertEquals("silence", 0f, MixerMath.meterPosition(0f, -36f, 0f), 0f)
        assertEquals("NaN", 0f, MixerMath.meterPosition(Float.NaN, -36f, 0f), 0f)
    }

    @Test
    fun `FLX4 level LEDs light at -24, -12, -6, -3 and 0 dBFS`() {
        fun at(db: Double) = MixerMath.ledLevel(Math.pow(10.0, db / 20.0).toFloat())
        // Pioneer's bands: 38-64 one LED, 65-86 two, 87-100 three, 101-118 four, 119-127 five (red).
        fun leds(value: Int) = listOf(38, 65, 87, 101, 119).count { value >= it }
        val expected = mapOf(
            0.1 to 5, -0.1 to 4, -1.0 to 4, -2.9 to 4, -3.1 to 3, -5.9 to 3, -6.1 to 2,
            -10.0 to 2, -11.9 to 2, -12.1 to 1, -15.0 to 1, -23.9 to 1, -24.1 to 0, -30.0 to 0
        )
        for ((db, lit) in expected) assertEquals("$db dBFS", lit, leds(at(db)))
        assertEquals("a normal master's loudest parts: orange", 4, leds(at(-1.0)))
        assertEquals("over full scale", 127, MixerMath.ledLevel(2f))
        assertEquals("silence", 0, MixerMath.ledLevel(0f))
        assertEquals("NaN", 0, MixerMath.ledLevel(Float.NaN))
    }

    @Test
    fun `soft clip is transparent below the knee and never exceeds full scale`() {
        assertEquals(0.5f, MixerMath.softClip(0.5f), 0f)
        assertEquals(-0.8f, MixerMath.softClip(-0.8f), 0f)
        for (x in listOf(0.9f, 1.5f, 4f, 100f)) {
            assertTrue(MixerMath.softClip(x) in 0.8f..1f)
            assertEquals(-MixerMath.softClip(x), MixerMath.softClip(-x), 0f)
        }
        assertTrue(MixerMath.softClip(1.5f) > MixerMath.softClip(1.0f))
    }
}
