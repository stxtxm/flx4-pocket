package io.github.timotheegrollier.flx4pocket

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToLong

/** What a [Deck] needs from a track: random access to interpolated PCM. */
interface PcmSource {
    val sampleRate: Int
    val durationSec: Double

    /** Reads the stereo frame at fractional index [frame]; false if it is not buffered. */
    fun sampleFrame(frame: Double, out: FloatArray): Boolean
    fun hasFrame(frame: Double): Boolean

    /** Asks for audio around [seconds]; frames become available asynchronously. */
    fun seek(seconds: Double)
    fun close()
}

/**
 * Streams a local audio file into a [PcmRing].
 *
 * MediaCodec decoding runs on its own thread and stays a few seconds ahead of
 * the mixer's read position. The mixer never touches the extractor or codec:
 * it only reads the ring, so seeks and loop wraps cannot block audio output.
 */
class AudioSource(context: Context, uri: Uri) : PcmSource {

    private val extractor = MediaExtractor()
    private val format: MediaFormat
    private val layout: PcmDecoder.Layout

    @Volatile
    override var sampleRate: Int = 44100
        private set

    override val durationSec: Double

    @Volatile
    var error: String? = null
        private set

    @Volatile
    private var ring: PcmRing

    private val seekRequestUs = AtomicLong(NO_SEEK)

    @Volatile
    private var running = true
    private var thread: Thread? = null
    private var stereo = FloatArray(0)

    init {
        try {
            extractor.setDataSource(context.applicationContext, uri, null)
            format = requireNotNull(PcmDecoder.selectAudioTrack(extractor)) { "No audio track" }
        } catch (e: Exception) {
            extractor.release()
            throw e
        }
        layout = PcmDecoder.Layout(format)
        sampleRate = layout.sampleRate
        durationSec = format.longOr(MediaFormat.KEY_DURATION, 0L) / 1_000_000.0
        ring = PcmRing(sampleRate, RING_SECONDS)
    }

    fun start() {
        if (thread != null || !running) return
        thread = Thread(::decodeLoop, "flx4-decode").apply { start() }
    }

    override fun seek(seconds: Double) {
        seekRequestUs.set((seconds.coerceAtLeast(0.0) * 1_000_000.0).toLong())
    }

    override fun sampleFrame(frame: Double, out: FloatArray): Boolean = ring.sample(frame, out)

    override fun hasFrame(frame: Double): Boolean = ring.hasFrame(frame)

    override fun close() {
        running = false
        val worker = thread
        if (worker == null) {
            extractor.release()
            return
        }
        try {
            worker.join(800)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun decodeLoop() {
        var codec: MediaCodec? = null
        try {
            codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(format, null, null, 0)
            codec.start()
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var nextPtsUs = 0L

            while (running) {
                val seekUs = seekRequestUs.getAndSet(NO_SEEK)
                if (seekUs != NO_SEEK) {
                    // PREVIOUS_SYNC: decoding starts at or before the target, and
                    // the ring drops what precedes it, so there is no silent gap.
                    extractor.seekTo(seekUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                    codec.flush()
                    ring.reset(frameAt(seekUs))
                    nextPtsUs = seekUs
                    inputDone = false
                    outputDone = false
                }
                if (outputDone || !ring.wantsData(sampleRate * AHEAD_SECONDS)) {
                    Thread.sleep(4)
                    continue
                }

                if (!inputDone) inputDone = PcmDecoder.queueInput(codec, extractor)
                val index = codec.dequeueOutputBuffer(info, 10_000)
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    layout.update(codec.outputFormat)
                    if (layout.sampleRate != sampleRate) {
                        ring = PcmRing(layout.sampleRate, RING_SECONDS)
                        sampleRate = layout.sampleRate
                    }
                } else if (index >= 0) {
                    val buffer = codec.getOutputBuffer(index)
                    if (buffer != null && info.size > 0) {
                        buffer.limit(info.offset + info.size)
                        buffer.position(info.offset)
                        val frames = layout.frames(info.size)
                        if (stereo.size < frames * 2) stereo = FloatArray(frames * 2)
                        layout.toStereo(buffer, frames, stereo)
                        // Some decoders report 0 for every buffer after the first.
                        val ptsUs = if (info.presentationTimeUs > 0L) info.presentationTimeUs else nextPtsUs
                        nextPtsUs = ptsUs + frames * 1_000_000L / sampleRate
                        ring.write(frameAt(ptsUs), stereo, frames)
                    }
                    codec.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                }
            }
        } catch (_: InterruptedException) {
        } catch (e: Exception) {
            error = e.message ?: e.javaClass.simpleName
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            extractor.release()
        }
    }

    private fun frameAt(timeUs: Long): Long = (timeUs / 1_000_000.0 * sampleRate).roundToLong()

    companion object {
        private const val NO_SEEK = -1L

        /** Audio kept in memory per deck; what is behind the playhead serves loops and scratching. */
        private const val RING_SECONDS = 24
        private const val AHEAD_SECONDS = 6
    }
}
