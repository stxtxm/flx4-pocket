package io.github.timotheegrollier.flx4pocket

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PcmRingTest {

    private fun stereo(vararg frames: Pair<Float, Float>): FloatArray {
        val out = FloatArray(frames.size * 2)
        frames.forEachIndexed { i, f ->
            out[i * 2] = f.first
            out[i * 2 + 1] = f.second
        }
        return out
    }

    @Test
    fun `writes and reads interpolated frames`() {
        val ring = PcmRing(sampleRate = 100, seconds = 1)
        val data = stereo(
            0.0f to 0.0f,
            0.5f to 0.25f,
            1.0f to 0.5f,
            1.5f to 0.75f
        )
        ring.write(0L, data, 4)
        val out = FloatArray(2)
        assertTrue(ring.sample(0.0, out))
        assertEquals(0.0f, out[0], 1e-6f)
        assertTrue(ring.sample(0.5, out))
        assertEquals(0.25f, out[0], 1e-6f)
        assertEquals(0.125f, out[1], 1e-6f)
        assertTrue(ring.sample(1.0, out))
        assertEquals(0.5f, out[0], 1e-6f)
        assertFalse(ring.sample(3.0, out))
    }

    @Test
    fun `reset discards stale audio`() {
        val ring = PcmRing(sampleRate = 100, seconds = 1)
        ring.write(0L, stereo(1.0f to 1.0f, 1.0f to 1.0f), 2)
        val out = FloatArray(2)
        assertTrue(ring.sample(0.0, out))
        ring.reset(100L)
        assertFalse(ring.sample(0.0, out))
        ring.write(100L, stereo(2.0f to 2.0f, 2.0f to 2.0f), 2)
        assertTrue(ring.sample(100.0, out))
        assertEquals(2.0f, out[0], 1e-6f)
    }

    @Test
    fun `oldest audio is dropped when the window is full`() {
        val ring = PcmRing(sampleRate = 10, seconds = 1)
        val first = FloatArray(20) { 1.0f }
        val second = FloatArray(20) { 2.0f }
        ring.write(0L, first, 10)
        ring.write(10L, second, 10)
        val out = FloatArray(2)
        assertFalse("frame 0 should be evicted", ring.sample(0.0, out))
        assertTrue(ring.sample(10.0, out))
        assertEquals(2.0f, out[0], 1e-6f)
    }
}
