package io.github.timotheegrollier.flx4pocket

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/**
 * A whole track as 16-bit stereo PCM, filled from its start by a background
 * decoder a few seconds after loading. Once a frame is in, a jump to it (hot
 * cue, loop, beat jump, needle) plays at once: no codec seek, no gap.
 *
 * About 10 MB a minute. A direct buffer, but on Android those live on the Java
 * heap: the manifest asks for a large heap, so two long tracks fit. One writer
 * thread, one reader thread (the mixer). Android-free.
 */
class TrackBuffer(val sampleRate: Int, private val capacityFrames: Int) {

    private val pcm = ByteBuffer.allocateDirect(capacityFrames * 4).order(ByteOrder.nativeOrder()).asShortBuffer()

    private val weights = FloatArray(Sinc.TAPS)

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

    /** Same interpolation as [PcmRing.sample]; false until [frame] is decoded. One reader thread at a time. */
    fun sample(frame: Double, out: FloatArray): Boolean {
        val available = frames
        if (!(frame >= 0.0 && frame + 1.0 < available)) return false
        val index = frame.toInt()
        val t = (frame - index).toFloat()
        if (t == 0f) { // a whole frame: the sample itself
            out[0] = get(index * 2)
            out[1] = get(index * 2 + 1)
            return true
        }
        Sinc.weigh(t, weights)
        var left = 0f
        var right = 0f
        for (k in 0 until Sinc.TAPS) {
            val at = (index + k - Sinc.BEFORE).coerceIn(0, available - 1) * 2
            left += get(at) * weights[k]
            right += get(at + 1) * weights[k]
        }
        out[0] = left
        out[1] = right
        return true
    }

    private fun get(i: Int): Float = pcm.get(i) / 32768f
}
