package io.github.timotheegrollier.flx4pocket

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sin

class FxTest {

    private val rate = 44100

    /** Runs [frames] of [input] through the unit in mixer-sized blocks; returns the left channel. */
    private fun run(unit: FxUnit, frames: Int, bpm: Double = 120.0, input: (Int) -> Float): FloatArray {
        val out = FloatArray(frames)
        val l = FloatArray(256)
        val r = FloatArray(256)
        var done = 0
        while (done < frames) {
            val n = minOf(256, frames - done)
            for (i in 0 until n) {
                l[i] = input(done + i)
                r[i] = l[i]
            }
            unit.process(l, r, n, bpm, done * bpm / 60.0 / rate)
            System.arraycopy(l, 0, out, done, n)
            done += n
        }
        return out
    }

    @Test
    fun `a disabled unit leaves the signal untouched`() {
        for (type in FxType.values()) {
            val unit = FxUnit(rate).apply { this.type = type }
            val out = run(unit, 4096) { sin(it * 0.05).toFloat() }
            assertArrayEquals(type.name, FloatArray(4096) { sin(it * 0.05).toFloat() }, out, 0f)
        }
    }

    @Test
    fun `echo repeats an impulse after the beat length`() {
        val unit = FxUnit(rate).apply {
            type = FxType.ECHO
            beats = 0.5
            depth = 0.5f
            enabled = true
        }
        // Half a beat at 120 BPM is 0.25 s.
        val delay = rate / 4
        val out = run(unit, delay * 2 + 10) { if (it == 0) 1f else 0f }
        assertEquals(1f, out[0], 1e-6f)
        assertEquals(0.5f, out[delay], 1e-6f)
        assertEquals(0f, out[delay - 1], 1e-6f)
        assertTrue("second repeat is quieter", out[delay * 2] > 0f && out[delay * 2] < out[delay])
    }

    @Test
    fun `echo tail rings out after switch-off but takes no new input`() {
        val unit = FxUnit(rate).apply {
            type = FxType.ECHO
            beats = 0.5
            depth = 1f
            enabled = true
        }
        run(unit, 100) { if (it == 0) 1f else 0f }
        unit.enabled = false
        val delay = rate / 4
        val out = run(unit, delay * 2) { if (it == 500) 1f else 0f }
        assertTrue("repeat of the earlier impulse", out[delay - 100] > 0.5f)
        assertEquals("input after switch-off is not echoed", 0f, out[500 + delay], 1e-6f)
    }

    @Test
    fun `trans gates the second half of each period`() {
        val unit = FxUnit(rate).apply {
            type = FxType.TRANS
            beats = 1.0
            depth = 1f
            enabled = true
        }
        val beat = rate / 2 // 120 BPM
        val out = run(unit, beat * 2) { 1f }
        assertEquals(1f, out[beat / 4], 0.01f)
        assertEquals(0f, out[beat * 3 / 4], 0.01f)
        assertEquals(1f, out[beat + beat / 4], 0.01f)
    }

    @Test
    fun `every effect stays finite and bounded at full depth`() {
        for (type in FxType.values()) {
            val unit = FxUnit(rate).apply {
                this.type = type
                depth = 1f
                beats = 0.25
                enabled = true
            }
            val out = run(unit, rate * 3) { sin(it * 0.3).toFloat() * 0.9f }
            assertTrue(type.name, out.all { it.isFinite() && abs(it) < 8f })
            assertTrue("$type changes the signal", (0 until out.size).any { abs(out[it] - sin(it * 0.3).toFloat() * 0.9f) > 0.01f })
        }
    }

    @Test
    fun `an invalid tempo falls back to 120 bpm`() {
        val unit = FxUnit(rate).apply {
            type = FxType.ECHO
            beats = 1.0
            depth = 1f
            enabled = true
        }
        val out = run(unit, rate, bpm = 0.0) { if (it == 0) 1f else 0f }
        assertEquals(1f, out[rate / 2], 1e-6f)
    }
}
