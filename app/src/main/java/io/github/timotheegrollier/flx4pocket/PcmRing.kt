package io.github.timotheegrollier.flx4pocket

import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A stereo float ring buffer addressed by absolute source frame numbers.
 *
 * Decoders write at the absolute frame implied by the buffer timestamp, while
 * the mixer reads a fractional cursor. Keeping absolute frame numbers makes
 * seeks and loop wraps deterministic: if the requested audio is no longer in
 * the window, [sample] returns false and the deck can ask the decoder to seek.
 *
 * Storage is circular (frame `f` lives at `f mod capacity`), so evicting old
 * audio moves nothing: a full ring used to shift its whole window on every
 * decoder write while the mixer waited on the lock.
 */
class PcmRing(
    val sampleRate: Int,
    seconds: Int = 8,
    private val channels: Int = 2
) {
    private val capacityFrames = sampleRate * seconds
    private val data = FloatArray(capacityFrames * channels)
    private var baseFrame = 0L
    private var availableFrames = 0
    private var lastRead = 0.0
    private val weights = FloatArray(Sinc.TAPS)

    @Synchronized
    fun reset(startFrame: Long) {
        baseFrame = startFrame
        availableFrames = 0
        lastRead = startFrame.toDouble()
    }

    /**
     * True while less than [aheadFrames] are buffered past the last frame the
     * mixer asked for. The decoder must stop writing when this is false:
     * a file decodes far faster than it plays, and [write] evicts the oldest
     * audio, so an unthrottled decoder would overwrite what is about to play.
     */
    @Synchronized
    fun wantsData(aheadFrames: Int): Boolean =
        baseFrame + availableFrames < lastRead + aheadFrames

    @Synchronized
    fun bufferedFrames(): Long = availableFrames.toLong()

    @Synchronized
    fun hasFrame(frame: Double): Boolean {
        if (!frame.isFinite()) return false
        val relative = frame - baseFrame
        return relative >= 0.0 && relative + 1.0 < availableFrames
    }

    private fun slot(frame: Long): Int = Math.floorMod(frame, capacityFrames.toLong()).toInt() * channels

    /**
     * Writes [frames] interleaved stereo frames starting at [startFrame].
     *
     * If the buffer is full, the oldest audio is discarded. A forward jump
     * (decoder timestamps after a seek) is filled with silence so stale audio
     * can never leak into the new position.
     */
    @Synchronized
    fun write(startFrame: Long, source: FloatArray, frames: Int) {
        if (frames <= 0 || source.size < frames * channels) return
        val end = startFrame + frames
        if (end - baseFrame > capacityFrames) {
            val newBase = end - capacityFrames
            availableFrames = (baseFrame + availableFrames - newBase).coerceIn(0L, capacityFrames.toLong()).toInt()
            baseFrame = newBase
        }
        val first = maxOf(startFrame, baseFrame)
        if (first >= end) return
        var frame = baseFrame + availableFrames
        while (frame < first) {
            val at = slot(frame++)
            for (c in 0 until channels) data[at + c] = 0f
        }
        for (f in first until end) {
            val at = slot(f)
            val from = (f - startFrame).toInt() * channels
            for (c in 0 until channels) data[at + c] = source[from + c]
        }
        availableFrames = maxOf(availableFrames, (end - baseFrame).toInt())
    }

    /**
     * Reads an interpolated stereo frame into [out] (L at 0, R at 1).
     * Returns false when the frame is outside the buffered window.
     */
    @Synchronized
    fun sample(frame: Double, out: FloatArray): Boolean {
        if (!frame.isFinite()) return false
        lastRead = frame
        val relative = frame - baseFrame
        if (relative < 0.0 || relative + 1.0 >= availableFrames) return false
        val index = relative.toLong()
        val t = (relative - index).toFloat()
        if (t == 0f) { // a whole frame: the sample itself
            val at = slot(baseFrame + index)
            out[0] = data[at]
            out[1] = data[at + 1]
            return true
        }
        Sinc.weigh(t, weights)
        var left = 0f
        var right = 0f
        for (k in 0 until Sinc.TAPS) {
            // Edges reuse the end frame.
            val at = slot(baseFrame + (index + k - Sinc.BEFORE).coerceIn(0L, availableFrames - 1L))
            left += data[at] * weights[k]
            right += data[at + 1] * weights[k]
        }
        out[0] = left
        out[1] = right
        return true
    }
}

/**
 * Band-limited interpolation between two frames: sixteen frames weighed by a
 * Kaiser-windowed sinc. A track read at another speed, or at another rate than
 * the output's, keeps its highs: flat within 0.1 dB to 17 kHz with images under
 * -40 dB (measured for 44.1 kHz audio), where a four-point cubic lost 1.7 dB at
 * 15 kHz and left images at -14 dB. On a whole frame the weights are 0 and 1.
 */
internal object Sinc {
    const val TAPS = 16

    /** Frames weighed before the one a read falls after: tap `k` weighs frame `k - BEFORE`. */
    const val BEFORE = TAPS / 2 - 1
    private const val PHASES = 128
    private const val BETA = 7.0

    /** Row `p`: the weights for a read `p / PHASES` of the way to the next frame. */
    private val table = FloatArray((PHASES + 1) * TAPS)

    init {
        for (p in 0..PHASES) {
            var sum = 0.0
            val row = DoubleArray(TAPS) { k ->
                val x = k - BEFORE - p.toDouble() / PHASES // frames from the read point
                val edge = 1.0 - (x / (TAPS / 2)) * (x / (TAPS / 2))
                val window = if (edge > 0.0) bessel(BETA * sqrt(edge)) / bessel(BETA) else 0.0
                (if (x == 0.0) 1.0 else sin(PI * x) / (PI * x)) * window
            }
            for (w in row) sum += w
            for (k in 0 until TAPS) table[p * TAPS + k] = (row[k] / sum).toFloat() // unity gain at every phase
        }
    }

    /** Fills [weights] for a read [t] (0..1) of the way from one frame to the next. */
    fun weigh(t: Float, weights: FloatArray) {
        val phase = t * PHASES
        val row = min(phase.toInt(), PHASES - 1)
        val between = phase - row
        val at = row * TAPS
        for (k in 0 until TAPS) {
            val w = table[at + k]
            weights[k] = w + (table[at + TAPS + k] - w) * between
        }
    }

    /** Modified Bessel function of the first kind, order 0: the Kaiser window is made of it. */
    private fun bessel(x: Double): Double {
        var sum = 1.0
        var term = 1.0
        var k = 1
        while (term > 1e-12 * sum) {
            term *= (x / (2 * k)) * (x / (2 * k))
            sum += term
            k++
        }
        return sum
    }
}
