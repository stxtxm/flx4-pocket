package io.github.timotheegrollier.flx4pocket

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max

/**
 * Three-band peak envelope of a whole track, the data behind the scrolling
 * waveforms. One point covers `1 / pointsPerSecond` seconds; each band is an
 * unsigned byte (0..255).
 *
 * The arrays are filled front to back by one analysis thread while the UI
 * reads them: readers must only touch indices below [size].
 */
class Waveform(val pointsPerSecond: Double, capacity: Int) {
    val low = ByteArray(capacity)
    val mid = ByteArray(capacity)
    val high = ByteArray(capacity)

    @Volatile
    var size = 0

    /** True once the whole track has been analysed (or loaded from the cache). */
    @Volatile
    var complete = false

    fun write(file: File) {
        val count = size
        file.parentFile?.mkdirs()
        DataOutputStream(file.outputStream().buffered()).use { out ->
            out.writeInt(MAGIC)
            out.writeDouble(pointsPerSecond)
            out.writeInt(count)
            out.write(low, 0, count)
            out.write(mid, 0, count)
            out.write(high, 0, count)
        }
    }

    companion object {
        private const val MAGIC = 0x464C5857 // "FLXW"

        fun read(file: File): Waveform? = try {
            DataInputStream(file.inputStream().buffered()).use { input ->
                if (input.readInt() != MAGIC) return null
                val pointsPerSecond = input.readDouble()
                val count = input.readInt()
                if (count <= 0 || count > 4_000_000 || pointsPerSecond <= 0.0) return null
                Waveform(pointsPerSecond, count).apply {
                    input.readFully(low)
                    input.readFully(mid)
                    input.readFully(high)
                    size = count
                    complete = true
                }
            }
        } catch (_: Exception) {
            null
        }
    }
}

/**
 * Builds a [Waveform] from decoded PCM. The bands are split with two one-pole
 * filters (about 200 Hz and 4 kHz), which is cheap enough to run on every
 * sample of a track and close enough for a display.
 */
class WaveformBuilder(sampleRate: Int, durationSec: Double) {

    private val window = max(1, sampleRate / POINTS_PER_SECOND)
    val waveform = Waveform(
        sampleRate.toDouble() / window,
        // Durations from container metadata can be short; leave headroom.
        (((if (durationSec > 0.0) durationSec else 900.0) + 30.0) * sampleRate / window).toInt()
    )

    private val lowCoef = (1.0 - exp(-2.0 * PI * 200.0 / sampleRate)).toFloat()
    private val highCoef = (1.0 - exp(-2.0 * PI * 4000.0 / sampleRate)).toFloat()
    private var below200 = 0f
    private var below4k = 0f
    private var peakLow = 0f
    private var peakMid = 0f
    private var peakHigh = 0f
    private var count = 0

    /** Adds [frames] interleaved stereo frames. */
    fun add(stereo: FloatArray, frames: Int) {
        val target = waveform
        for (f in 0 until frames) {
            val x = (stereo[f * 2] + stereo[f * 2 + 1]) * 0.5f
            below200 += lowCoef * (x - below200)
            below4k += highCoef * (x - below4k)
            peakLow = max(peakLow, abs(below200))
            peakMid = max(peakMid, abs(below4k - below200))
            peakHigh = max(peakHigh, abs(x - below4k))
            if (++count == window) {
                val index = target.size
                if (index < target.low.size) {
                    target.low[index] = toByte(peakLow * LOW_GAIN)
                    target.mid[index] = toByte(peakMid * MID_GAIN)
                    target.high[index] = toByte(peakHigh * HIGH_GAIN)
                    target.size = index + 1
                }
                peakLow = 0f
                peakMid = 0f
                peakHigh = 0f
                count = 0
            }
        }
    }

    private fun toByte(value: Float): Byte = (value.coerceIn(0f, 1f) * 255f).toInt().toByte()

    companion object {
        const val POINTS_PER_SECOND = 150

        // Highs carry far less energy than bass; lift them so all bands read.
        private const val LOW_GAIN = 1.1f
        private const val MID_GAIN = 1.8f
        private const val HIGH_GAIN = 3.2f
    }
}
