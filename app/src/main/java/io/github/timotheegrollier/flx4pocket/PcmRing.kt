package io.github.timotheegrollier.flx4pocket

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
        val at = baseFrame + index
        // Four-point cubic: linear interpolation dulls the highs whenever the
        // tempo is off 0 % or the file is not 44.1 kHz. Edges reuse the end frame.
        val before = slot(if (index > 0) at - 1 else at)
        val s0 = slot(at)
        val s1 = slot(at + 1)
        val after = slot(if (index + 2 < availableFrames) at + 2 else at + 1)
        for (c in 0..1) out[c] = cubic(data[before + c], data[s0 + c], data[s1 + c], data[after + c], t)
        return true
    }
}

/** Catmull-Rom between [x0] and [x1] at [t] (0..1); exact on the samples themselves. */
internal fun cubic(xm1: Float, x0: Float, x1: Float, x2: Float, t: Float): Float {
    val c1 = 0.5f * (x1 - xm1)
    val c2 = xm1 - 2.5f * x0 + 2f * x1 - 0.5f * x2
    val c3 = 0.5f * (x2 - xm1) + 1.5f * (x0 - x1)
    return ((c3 * t + c2) * t + c1) * t + x0
}
