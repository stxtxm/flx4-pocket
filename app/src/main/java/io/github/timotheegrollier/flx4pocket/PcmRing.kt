package io.github.timotheegrollier.flx4pocket

/**
 * A stereo float ring buffer addressed by absolute source frame numbers.
 *
 * Decoders write at the absolute frame implied by the buffer timestamp, while
 * the mixer reads a fractional cursor. Keeping absolute frame numbers makes
 * seeks and loop wraps deterministic: if the requested audio is no longer in
 * the window, [sample] returns false and the deck can ask the decoder to seek.
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
        var start = startFrame
        var count = frames
        var sourceFrame = 0

        if (start < baseFrame) {
            val skip = (baseFrame - start).toInt()
            if (skip >= count) return
            sourceFrame += skip
            count -= skip
            start += skip
        }

        val endFrame = start + count
        if (endFrame > baseFrame + capacityFrames) {
            val drop = (endFrame - (baseFrame + capacityFrames)).toInt()
            if (drop >= availableFrames) {
                baseFrame = endFrame - capacityFrames
                availableFrames = 0
            } else {
                System.arraycopy(
                    data,
                    drop * channels,
                    data,
                    0,
                    (availableFrames - drop) * channels
                )
                baseFrame += drop
                availableFrames -= drop
            }
        }

        val relativeStart = (start - baseFrame).toInt()
        val gap = relativeStart - availableFrames
        if (gap > 0) {
            val zeroCount = minOf(gap, capacityFrames - availableFrames)
            java.util.Arrays.fill(
                data,
                availableFrames * channels,
                (availableFrames + zeroCount) * channels,
                0f
            )
            availableFrames += zeroCount
        }

        val destinationFrame = (start - baseFrame).toInt()
        val writable = minOf(count, capacityFrames - destinationFrame)
        if (writable <= 0) return

        for (f in 0 until writable) {
            val destination = (destinationFrame + f) * channels
            val sourceIndex = (sourceFrame + f) * channels
            for (c in 0 until channels) {
                data[destination + c] = source[sourceIndex + c]
            }
        }
        availableFrames = maxOf(availableFrames, destinationFrame + writable)
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
        val index = relative.toInt()
        val fraction = (relative - index).toFloat()
        val i0 = index * channels
        val i1 = (index + 1) * channels
        out[0] = data[i0] + (data[i1] - data[i0]) * fraction
        out[1] = data[i0 + 1] + (data[i1 + 1] - data[i0 + 1]) * fraction
        return true
    }
}
