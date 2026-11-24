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
        assertTrue(ring.sample(1.5, out))
        assertEquals(0.75f, out[0], 1e-6f)
        assertEquals(0.375f, out[1], 1e-6f)
        assertTrue(ring.sample(1.0, out))
        assertEquals(0.5f, out[0], 1e-6f)
        assertFalse(ring.sample(3.0, out))
    }

    @Test
    fun `interpolation keeps the highs`() {
        val rate = 44100
        val ring = PcmRing(rate, seconds = 1)
        val frames = 4410
        val out = FloatArray(2)
        // Between frames, a linear read is off by up to 0.06 at 5 kHz and a cubic one by 0.3 at 15 kHz.
        for (hz in listOf(5000.0, 15000.0)) {
            val sine = FloatArray(frames * 2) { Math.sin(2.0 * Math.PI * hz * (it / 2) / rate).toFloat() }
            ring.write(0, sine, frames)
            var worst = 0.0
            for (i in 10 until frames - 10) {
                for (between in listOf(0.25, 0.5, 0.9)) {
                    assertTrue(ring.sample(i + between, out))
                    worst = maxOf(worst, Math.abs(out[0] - Math.sin(2.0 * Math.PI * hz * (i + between) / rate)))
                }
            }
            assertTrue("worst error at $hz Hz: $worst", worst < 0.002)
        }
    }

    @Test
    fun `a whole frame is read as it is`() {
        val ring = PcmRing(sampleRate = 100, seconds = 1)
        val noise = java.util.Random(3)
        val data = FloatArray(200) { noise.nextFloat() - 0.5f }
        ring.write(0L, data, 100)
        val out = FloatArray(2)
        for (i in 0 until 99) {
            assertTrue(ring.sample(i.toDouble(), out))
            assertEquals("an unshifted track comes out sample for sample", data[i * 2], out[0], 0f)
            assertEquals(data[i * 2 + 1], out[1], 0f)
        }
    }

    @Test
    fun `the window keeps rolling over many capacities`() {
        val ring = PcmRing(sampleRate = 10, seconds = 1)
        val out = FloatArray(2)
        for (block in 0 until 7) {
            ring.write(block * 4L, FloatArray(8) { block.toFloat() }, 4)
        }
        assertFalse("frame 17 was evicted", ring.sample(17.0, out))
        assertTrue(ring.sample(20.0, out))
        assertEquals(5f, out[0], 0f)
        assertTrue(ring.sample(26.0, out))
        assertEquals(6f, out[1], 0f)
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

    @Test
    fun `decoder is throttled to a window ahead of the reader`() {
        val ring = PcmRing(sampleRate = 100, seconds = 4)
        val out = FloatArray(2)
        assertTrue("an empty ring wants data", ring.wantsData(aheadFrames = 200))

        ring.write(0, FloatArray(200 * 2) { 0.5f }, 200)
        assertFalse("200 frames ahead of frame 0 is enough", ring.wantsData(200))

        // The mixer reads on: the decoder must be allowed to continue...
        assertTrue(ring.sample(150.0, out))
        assertTrue(ring.wantsData(200))

        // ...and what it then writes must not evict what is about to play.
        ring.write(200, FloatArray(150 * 2) { 0.25f }, 150)
        assertFalse(ring.wantsData(200))
        assertTrue(ring.sample(151.0, out))
        assertEquals(0.5f, out[0], 1e-6f)
    }

    @Test
    fun `a seek restarts the throttle window at the new position`() {
        val ring = PcmRing(sampleRate = 100, seconds = 4)
        ring.write(0, FloatArray(300 * 2), 300)
        assertFalse(ring.wantsData(200))
        ring.reset(5000)
        assertTrue(ring.wantsData(200))
    }
}
