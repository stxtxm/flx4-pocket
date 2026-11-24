package io.github.timotheegrollier.flx4pocket

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * Records the master output to 24-bit WAV files while the mixer runs: the
 * samples sent to the output, at the output's own rate, with no lossy step.
 *
 * The mixer thread only fills a pooled block ([take] and [put] never wait and
 * never allocate); a writer thread turns the blocks into PCM. The header is
 * rewritten every few seconds, so a recording is still a valid WAV if the app
 * is killed. A file that reaches the 4 GB WAV limit (four to four and a half hours
 * at 48 kHz) is closed and the next part begins. Android-free.
 */
class Recorder(
    val sampleRate: Int,
    blockFrames: Int,
    private val maxFileBytes: Long = WAV_LIMIT,
    private val newFile: (part: Int) -> FileChannel
) {
    private val free = ArrayBlockingQueue<FloatArray>(POOL)
    private val filled = ArrayBlockingQueue<FloatArray>(POOL)

    @Volatile
    private var running = true

    /** Frames written so far, all parts together. */
    @Volatile
    var frames = 0L
        private set

    /** Blocks lost because the storage could not keep up: each is a gap in the recording. */
    @Volatile
    var dropped = 0
        private set

    /** Why the recording ended by itself (storage full, file error), or null. */
    @Volatile
    var error: String? = null
        private set

    private val thread: Thread

    init {
        repeat(POOL) { free.add(FloatArray(blockFrames * 2)) }
        thread = Thread(::writeLoop, "flx4-recorder").apply { start() }
    }

    /** An interleaved stereo block for the mixer to fill; null (and counted) when the writer is behind, or has ended. */
    fun take(): FloatArray? {
        if (!running) return null
        return free.poll() ?: null.also { dropped++ }
    }

    fun put(block: FloatArray) {
        filled.offer(block)
    }

    /** Writes what is still queued, completes the header and closes the file. [reason]: why, when not asked for. */
    fun stop(reason: String? = null) {
        if (error == null) error = reason
        running = false
        thread.join(5000)
    }

    private fun writeLoop() {
        var part = 0
        var file: FileChannel? = null
        var dataBytes = 0L
        var headerAt = 0L
        val pcm = ByteBuffer.allocate(free.peek()!!.size * 3).order(ByteOrder.LITTLE_ENDIAN)
        try {
            while (true) {
                val block = filled.poll(100, TimeUnit.MILLISECONDS)
                if (block == null) {
                    if (!running) break
                    continue
                }
                if (file == null || dataBytes + block.size * 3 > maxFileBytes - HEADER_BYTES) {
                    file?.let { finish(it, dataBytes) }
                    file = newFile(++part)
                    dataBytes = 0L
                    headerAt = 0L
                    header(file, 0L)
                }
                pcm.clear()
                for (x in block) {
                    val v = (x.coerceIn(-1f, 1f) * 8388607f).roundToInt()
                    pcm.put(v.toByte()).put((v shr 8).toByte()).put((v shr 16).toByte())
                }
                pcm.flip()
                var position = HEADER_BYTES + dataBytes
                while (pcm.hasRemaining()) position += file.write(pcm, position)
                dataBytes += block.size * 3
                frames += block.size / 2
                free.offer(block)
                if (dataBytes - headerAt >= sampleRate * 6L * HEADER_SECONDS) {
                    header(file, dataBytes)
                    headerAt = dataBytes
                }
            }
        } catch (e: Exception) {
            error = e.message ?: e.javaClass.simpleName
        } finally {
            running = false
            file?.let { runCatching { finish(it, dataBytes) } }
        }
    }

    private fun finish(file: FileChannel, dataBytes: Long) {
        header(file, dataBytes)
        file.close()
    }

    /** The 44-byte header of a stereo 24-bit PCM WAV holding [dataBytes] of audio. */
    private fun header(file: FileChannel, dataBytes: Long) {
        val h = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()).putInt((36 + dataBytes).toInt()).put("WAVE".toByteArray())
        h.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(2)
        h.putInt(sampleRate).putInt(sampleRate * 6).putShort(6).putShort(24)
        h.put("data".toByteArray()).putInt(dataBytes.toInt())
        h.flip()
        var position = 0L
        while (h.hasRemaining()) position += file.write(h, position)
    }

    companion object {
        const val HEADER_BYTES = 44

        /** WAV sizes are 32-bit: a file stays under 4 GB. */
        const val WAV_LIMIT = 0xFFFF_FFF0L

        /** Blocks in flight between the mixer and the writer: about five seconds of storage stall. */
        private const val POOL = 1024
        private const val HEADER_SECONDS = 5
    }
}
