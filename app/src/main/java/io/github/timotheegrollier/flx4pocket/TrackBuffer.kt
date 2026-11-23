package io.github.timotheegrollier.flx4pocket

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/**
 * A whole track as 16-bit stereo PCM, filled from its start by a background
 * decoder a few seconds after loading. Once a frame is in, a jump to it (hot
 * cue, loop, beat jump, needle) plays at once: no codec seek, no gap.
 *
 * Native memory (about 10 MB a minute), so long tracks do not hit the Java
 * heap limit. One writer thread, any number of readers. Android-free.
 */
class TrackBuffer(val sampleRate: Int, private val capacityFrames: Int) {

    private val pcm = ByteBuffer.allocateDirect(capacityFrames * 4).order(ByteOrder.nativeOrder()).asShortBuffer()

    /** Frames decoded so far, from frame 0; published after their samples. */
    @Volatile
    var frames = 0
        private set

    /** Set once the decoder reached the end of the file. */
    @Volatile
    var complete = false

    /** Appends interleaved stereo; false when it did not all fit. */
    fun append(stereo: FloatArray, count: Int): Boolean {
        val n = minOf(count, capacityFrames - frames)
        val first = frames * 2
        for (i in 0 until n * 2) {
            pcm.put(first + i, (stereo[i] * 32768f).roundToInt().coerceIn(-32768, 32767).toShort())
        }
        frames += n
        return n == count
    }

    fun has(frame: Double): Boolean = frame >= 0.0 && frame + 1.0 < frames

    /** Same interpolation as [PcmRing.sample]; false until [frame] is decoded. */
    fun sample(frame: Double, out: FloatArray): Boolean {
        val available = frames
        if (!(frame >= 0.0 && frame + 1.0 < available)) return false
        val index = frame.toInt()
        val t = (frame - index).toFloat()
        val at = index * 2
        val before = if (index > 0) at - 2 else at
        val after = if (index + 2 < available) at + 4 else at + 2
        for (c in 0..1) out[c] = cubic(get(before + c), get(at + c), get(at + 2 + c), get(after + c), t)
        return true
    }

    private fun get(i: Int): Float = pcm.get(i) / 32768f
}
