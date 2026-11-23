package io.github.timotheegrollier.flx4pocket

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackBufferTest {

    @Test
    fun `decoded frames read back and the rest waits`() {
        val buffer = TrackBuffer(sampleRate = 100, capacityFrames = 10)
        val out = FloatArray(2)
        assertFalse(buffer.has(0.0))

        assertTrue(buffer.append(FloatArray(8) { if (it % 2 == 0) 0.5f else -0.25f }, 4))
        assertTrue(buffer.sample(2.0, out))
        assertEquals(0.5f, out[0], 1e-4f)
        assertEquals(-0.25f, out[1], 1e-4f)
        assertFalse("not decoded yet", buffer.sample(3.5, out))

        assertFalse("only six more frames fit", buffer.append(FloatArray(20) { 1f }, 10))
        assertTrue(buffer.has(8.5))
        assertTrue(buffer.sample(9.0 - 1e-9, out))
        assertEquals(1f, out[0], 1e-3f)
    }
}
