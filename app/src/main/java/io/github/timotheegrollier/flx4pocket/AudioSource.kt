package io.github.timotheegrollier.flx4pocket

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToLong

/**
 * Streams a local audio file into a [PcmRing].
 *
 * MediaCodec decoding runs on its own thread. The mixer never touches the
 * extractor or codec: it only reads the ring buffer, which keeps seeking,
 * loop wraps and underruns from blocking the audio callback.
 */
class AudioSource(context: Context, uri: Uri) {

    private val extractor = MediaExtractor()
    private var trackIndex = -1
    private var mime: String? = null
    private var format: MediaFormat? = null

    @Volatile
    var sampleRate: Int = 44100
        private set

    @Volatile
    var durationSec: Double = 0.0
        private set

    @Volatile
    var error: String? = null
        private set

    @Volatile
    var endOfStream: Boolean = false
        private set

    @Volatile
    private var ring: PcmRing? = null

    private val seekRequestUs = AtomicLong(NO_SEEK)
    @Volatile
    private var running = true
    private var thread: Thread? = null

    private var decoderRate = 44100
    private var decoderChannels = 2
    private var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT
    private var expectedPtsUs = 0L
    private var conversion = FloatArray(0)

    init {
        extractor.setDataSource(context.applicationContext, uri, null)
        for (i in 0 until extractor.trackCount) {
            val candidate = extractor.getTrackFormat(i)
            val candidateMime = candidate.getString(MediaFormat.KEY_MIME) ?: continue
            if (candidateMime.startsWith("audio/")) {
                trackIndex = i
                mime = candidateMime
                format = candidate
                extractor.selectTrack(i)
                break
            }
        }
        require(trackIndex >= 0) { "No audio track in $uri" }
        val trackFormat = requireNotNull(format)
        decoderRate = trackFormat.intOr(MediaFormat.KEY_SAMPLE_RATE, 44100)
        decoderChannels = trackFormat.intOr(MediaFormat.KEY_CHANNEL_COUNT, 2)
        sampleRate = decoderRate
        durationSec = trackFormat.longOr(MediaFormat.KEY_DURATION, 0L) / 1_000_000.0
        ring = PcmRing(decoderRate, 8)
    }

    fun start() {
        if (thread != null || !running) return
        thread = Thread(::decodeLoop, "flx4-decode-${hashCode()}").apply {
            priority = Thread.NORM_PRIORITY
            start()
        }
    }

    fun seek(seconds: Double) {
        if (!running) return
        seekRequestUs.set((seconds.coerceAtLeast(0.0) * 1_000_000.0).toLong())
    }

    fun sampleFrame(frame: Double, out: FloatArray): Boolean = ring?.sample(frame, out) ?: false

    fun hasFrame(frame: Double): Boolean = ring?.hasFrame(frame) ?: false

    fun close() {
        running = false
        val worker = thread
        if (worker != null) {
            try {
                worker.join(800)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            if (worker.isAlive) worker.interrupt()
        } else {
            extractor.release()
        }
        ring = null
    }

    private fun decodeLoop() {
        var codec: MediaCodec? = null
        try {
            val audioFormat = requireNotNull(format)
            codec = MediaCodec.createDecoderByType(requireNotNull(mime))
            codec.configure(audioFormat, null, null, 0)
            codec.start()
            val info = MediaCodec.BufferInfo()
            var inputDone = false

            while (running) {
                val requestedSeek = seekRequestUs.getAndSet(NO_SEEK)
                if (requestedSeek != NO_SEEK) {
                    extractor.seekTo(requestedSeek, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                    try {
                        codec.stop()
                    } catch (_: Exception) {
                    }
                    codec.configure(audioFormat, null, null, 0)
                    codec.start()
                    ring?.reset(secondsToFrame(requestedSeek / 1_000_000.0, decoderRate))
                    expectedPtsUs = requestedSeek
                    inputDone = false
                    endOfStream = false
                }

                if (!inputDone) {
                    val inputIndex = codec.dequeueInputBuffer(10_000)
                    if (inputIndex >= 0) {
                        val input = codec.getInputBuffer(inputIndex)
                        val size = if (input != null) extractor.readSampleData(input, 0) else -1
                        if (size < 0) {
                            codec.queueInputBuffer(
                                inputIndex,
                                0,
                                0,
                                0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(
                                inputIndex,
                                0,
                                size,
                                extractor.sampleTime,
                                0
                            )
                            extractor.advance()
                        }
                    }
                }

                val outputIndex = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        applyOutputFormat(codec.outputFormat)
                    }

                    outputIndex >= 0 -> {
                        if (info.size > 0) readOutput(codec, outputIndex, info)
                        codec.releaseOutputBuffer(outputIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            endOfStream = true
                        }
                    }
                }
            }
        } catch (e: Exception) {
            error = e.message ?: e.javaClass.simpleName
        } finally {
            try {
                codec?.stop()
            } catch (_: Exception) {
            }
            try {
                codec?.release()
            } catch (_: Exception) {
            }
            extractor.release()
        }
    }

    private fun applyOutputFormat(outputFormat: MediaFormat) {
        decoderRate = outputFormat.intOr(MediaFormat.KEY_SAMPLE_RATE, decoderRate)
        decoderChannels = outputFormat.intOr(MediaFormat.KEY_CHANNEL_COUNT, decoderChannels)
        if (outputFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
            pcmEncoding = outputFormat.intOr(
                MediaFormat.KEY_PCM_ENCODING,
                AudioFormat.ENCODING_PCM_16BIT
            )
        }
        if (sampleRate != decoderRate) {
            sampleRate = decoderRate
            ring = PcmRing(decoderRate, 8)
        }
    }

    private fun readOutput(codec: MediaCodec, outputIndex: Int, info: MediaCodec.BufferInfo) {
        val buffer = codec.getOutputBuffer(outputIndex) ?: return
        buffer.position(info.offset)
        buffer.limit(info.offset + info.size)
        val bytesPerSample = if (pcmEncoding == AudioFormat.ENCODING_PCM_FLOAT) 4 else 2
        val frameCount = info.size / (bytesPerSample * decoderChannels.coerceAtLeast(1))
        if (frameCount <= 0) return

        val needed = frameCount * 2
        if (conversion.size < needed) conversion = FloatArray(needed)

        if (pcmEncoding == AudioFormat.ENCODING_PCM_FLOAT) {
            val floats = buffer.order(ByteOrder.nativeOrder()).asFloatBuffer()
            for (f in 0 until frameCount) {
                val base = f * decoderChannels
                val left = floats.get(base)
                val right = if (decoderChannels > 1) floats.get(base + 1) else left
                conversion[f * 2] = left
                conversion[f * 2 + 1] = right
            }
        } else {
            val shorts = buffer.order(ByteOrder.nativeOrder()).asShortBuffer()
            for (f in 0 until frameCount) {
                val base = f * decoderChannels
                val left = shorts.get(base) / 32768.0f
                val right = if (decoderChannels > 1) shorts.get(base + 1) / 32768.0f else left
                conversion[f * 2] = left
                conversion[f * 2 + 1] = right
            }
        }

        var ptsUs = info.presentationTimeUs
        if (ptsUs <= 0L) ptsUs = expectedPtsUs
        val startFrame = secondsToFrame(ptsUs / 1_000_000.0, decoderRate)
        expectedPtsUs = ptsUs + (frameCount * 1_000_000.0 / decoderRate).toLong()
        ring?.write(startFrame, conversion, frameCount)
    }

    private fun secondsToFrame(seconds: Double, rate: Int): Long =
        (seconds * rate).roundToLong()

    companion object {
        private const val NO_SEEK = -1L
    }
}

private fun MediaFormat.intOr(key: String, fallback: Int): Int =
    if (containsKey(key)) getInteger(key) else fallback

private fun MediaFormat.longOr(key: String, fallback: Long): Long =
    if (containsKey(key)) getLong(key) else fallback
