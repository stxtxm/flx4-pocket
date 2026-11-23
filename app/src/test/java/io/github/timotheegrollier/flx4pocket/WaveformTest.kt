package io.github.timotheegrollier.flx4pocket

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.PI
import kotlin.math.sin

class WaveformTest {

    private fun build(seconds: Double, hz: Double, rate: Int = 44100): Waveform {
        val builder = WaveformBuilder(rate, seconds)
        val frames = (seconds * rate).toInt()
        val stereo = FloatArray(frames * 2) { (0.8 * sin(2.0 * PI * hz * (it / 2) / rate)).toFloat() }
        builder.add(stereo, frames)
        return builder.waveform
    }

    private fun average(band: ByteArray, size: Int): Double =
        (10 until size).sumOf { band[it].toInt() and 0xFF }.toDouble() / (size - 10)

    @Test
    fun `resolution is about 150 points per second`() {
        val wave = build(2.0, 440.0)
        assertEquals(150.0, wave.pointsPerSecond, 1.0)
        assertEquals(300.0, wave.size.toDouble(), 2.0)
    }

    @Test
    fun `bass lands in the low band`() {
        val wave = build(1.0, 60.0)
        assertTrue(average(wave.low, wave.size) > 150)
        assertTrue(average(wave.high, wave.size) < 30)
    }

    @Test
    fun `hi-hats land in the high band`() {
        val wave = build(1.0, 10000.0)
        assertTrue(average(wave.high, wave.size) > 150)
        assertTrue(average(wave.low, wave.size) < 30)
    }

    @Test
    fun `a track longer than announced does not overflow`() {
        val builder = WaveformBuilder(8000, durationSec = 1.0)
        builder.add(FloatArray(8000 * 2 * 60), 8000 * 60)
        assertEquals(builder.waveform.low.size, builder.waveform.size)
    }

    @Test
    fun `survives a round trip through the cache file`() {
        val wave = build(1.0, 1000.0)
        val file = File.createTempFile("wave", ".bin")
        try {
            wave.write(file)
            val loaded = Waveform.read(file)
            assertNotNull(loaded)
            assertEquals(wave.size, loaded!!.size)
            assertEquals(wave.pointsPerSecond, loaded.pointsPerSecond, 0.0)
            assertTrue(loaded.complete)
            for (i in 0 until wave.size) assertEquals(wave.mid[i], loaded.mid[i])

            file.writeText("not a waveform")
            assertNull(Waveform.read(file))
        } finally {
            file.delete()
        }
    }
}
