package io.github.timotheegrollier.flx4pocket

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal fun MediaFormat.intOr(key: String, fallback: Int): Int =
    if (containsKey(key)) getInteger(key) else fallback

internal fun MediaFormat.longOr(key: String, fallback: Long): Long =
    if (containsKey(key)) getLong(key) else fallback

/** MediaCodec plumbing shared by the streaming [AudioSource] and offline analysis. */
object PcmDecoder {

    /** PCM layout of a decoder's output, refreshed on INFO_OUTPUT_FORMAT_CHANGED. */
    class Layout(format: MediaFormat) {
        var sampleRate = format.intOr(MediaFormat.KEY_SAMPLE_RATE, 44100)
            private set
        var channels = format.intOr(MediaFormat.KEY_CHANNEL_COUNT, 2)
            private set
        private var float = false

        fun update(output: MediaFormat) {
            sampleRate = output.intOr(MediaFormat.KEY_SAMPLE_RATE, sampleRate)
            channels = output.intOr(MediaFormat.KEY_CHANNEL_COUNT, channels)
            float = output.intOr(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT) ==
                AudioFormat.ENCODING_PCM_FLOAT
        }

        fun frames(bytes: Int): Int = bytes / ((if (float) 4 else 2) * channels.coerceAtLeast(1))

        /** Converts [frames] of decoder output to interleaved stereo floats in [out]. */
        fun toStereo(buffer: ByteBuffer, frames: Int, out: FloatArray) {
            val step = channels.coerceAtLeast(1)
            val right = if (step > 1) 1 else 0
            if (float) {
                val floats = buffer.order(ByteOrder.nativeOrder()).asFloatBuffer()
                for (f in 0 until frames) {
                    out[f * 2] = floats.get(f * step)
                    out[f * 2 + 1] = floats.get(f * step + right)
                }
            } else {
                val shorts = buffer.order(ByteOrder.nativeOrder()).asShortBuffer()
                for (f in 0 until frames) {
                    out[f * 2] = shorts.get(f * step) / 32768f
                    out[f * 2 + 1] = shorts.get(f * step + right) / 32768f
                }
            }
        }
    }

    /** Selects the first audio track of [extractor] and returns its format. */
    fun selectAudioTrack(extractor: MediaExtractor): MediaFormat? {
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            if (format.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                extractor.selectTrack(i)
                return format
            }
        }
        return null
    }

    /** Feeds one input buffer; returns true once the end of the file was queued. */
    fun queueInput(codec: MediaCodec, extractor: MediaExtractor): Boolean {
        val index = codec.dequeueInputBuffer(10_000)
        if (index < 0) return false
        val size = codec.getInputBuffer(index)?.let { extractor.readSampleData(it, 0) } ?: -1
        if (size < 0) {
            codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            return true
        }
        codec.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
        extractor.advance()
        return false
    }

    /**
     * Decodes [uri] from start to end as fast as the codec allows, handing
     * interleaved stereo blocks to [sink] until it returns false.
     * Returns false if the file could not be decoded.
     */
    fun decode(
        context: Context,
        uri: Uri,
        sink: (stereo: FloatArray, frames: Int, sampleRate: Int, durationSec: Double) -> Boolean
    ): Boolean {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context.applicationContext, uri, null)
            val format = selectAudioTrack(extractor) ?: return false
            val durationSec = format.longOr(MediaFormat.KEY_DURATION, 0L) / 1_000_000.0
            val layout = Layout(format)
            codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(format, null, null, 0)
            codec.start()

            val info = MediaCodec.BufferInfo()
            var stereo = FloatArray(0)
            var inputDone = false
            while (true) {
                if (!inputDone) inputDone = queueInput(codec, extractor)
                val index = codec.dequeueOutputBuffer(info, 10_000)
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    layout.update(codec.outputFormat)
                    continue
                }
                if (index < 0) continue
                var wantMore = true
                val buffer = codec.getOutputBuffer(index)
                if (buffer != null && info.size > 0) {
                    buffer.limit(info.offset + info.size)
                    buffer.position(info.offset)
                    val frames = layout.frames(info.size)
                    if (stereo.size < frames * 2) stereo = FloatArray(frames * 2)
                    layout.toStereo(buffer, frames, stereo)
                    wantMore = sink(stereo, frames, layout.sampleRate, durationSec)
                }
                codec.releaseOutputBuffer(index, false)
                if (!wantMore || info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                    return true
                }
            }
        } catch (_: Exception) {
            return false
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            extractor.release()
        }
    }
}
