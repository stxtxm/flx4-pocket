package io.github.timotheegrollier.flx4pocket

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Offline BPM estimation based on an onset-energy autocorrelation.
 *
 * It is intentionally dependency-free: the app ships no native analysis
 * library. When the estimate has a low confidence the UI asks the user to tap
 * the tempo instead, and the grid can always be corrected by tapping.
 */
object BpmAnalyzer {

    data class Result(
        val bpm: Double,
        val anchorSec: Double,
        val confidence: Double,
        val durationSec: Double
    )

    data class Estimate(val bpm: Double, val anchorSec: Double, val confidence: Double)

    fun analyze(context: Context, uri: Uri, maxSeconds: Double = 60.0): Result? {
        val decoded = decodeEnergy(context, uri, maxSeconds) ?: return null
        if (decoded.energies.size < decoded.fps * 8.0) return null
        val estimate = estimate(decoded.energies, decoded.fps) ?: return null
        return Result(
            bpm = estimate.bpm,
            anchorSec = estimate.anchorSec,
            confidence = estimate.confidence,
            durationSec = decoded.durationSec
        )
    }

    /**
     * Returns the tempo and the time of one beat in the analysed window.
     * Exposed for unit tests.
     */
    fun estimate(rawEnergy: FloatArray, fps: Double): Estimate? {
        if (fps <= 0.0 || rawEnergy.size < 64) return null
        val n = rawEnergy.size
        val onset = FloatArray(n)
        for (i in 1 until n) {
            onset[i] = max(
                0.0,
                ln(1.0 + rawEnergy[i].toDouble()) - ln(1.0 + rawEnergy[i - 1].toDouble())
            ).toFloat()
        }

        // Remove the slow envelope so the autocorrelation sees transients rather
        // than the overall loudness of the track.
        val window = max(4, (fps * 0.35).roundToInt())
        val prefix = DoubleArray(n + 1)
        for (i in 0 until n) prefix[i + 1] = prefix[i] + onset[i]
        val env = DoubleArray(n)
        for (i in 0 until n) {
            val from = max(0, i - window)
            val to = minOf(n, i + window + 1)
            val localMean = (prefix[to] - prefix[from]) / (to - from)
            env[i] = max(0.0, onset[i] - localMean)
        }

        val minLag = max(2, (fps * 60.0 / 200.0).roundToInt())
        val maxLag = minOf(n / 2, (fps * 60.0 / 55.0).roundToInt())
        if (maxLag <= minLag + 2) return null

        val scores = DoubleArray(maxLag + 1)
        var total = 0.0
        var counted = 0
        for (lag in minLag..maxLag) {
            var sum = 0.0
            var count = 0
            var i = lag
            while (i < n) {
                sum += env[i] * env[i - lag]
                count++
                i++
            }
            scores[lag] = if (count > 0) sum / count else 0.0
            total += scores[lag]
            counted++
        }
        if (counted == 0) return null
        val mean = total / counted
        if (mean <= 0.0) return null

        var bestLag = minLag
        var bestScore = -1.0
        for (lag in minLag..maxLag) {
            var score = scores[lag]
            if (lag * 2 <= maxLag) score += scores[lag * 2] * 0.5
            if (lag * 3 <= maxLag) score += scores[lag * 3] * 0.25
            if (score > bestScore) {
                bestScore = score
                bestLag = lag
            }
        }
        if (bestScore <= 0.0) return null

        var bpm = 60.0 * fps / bestLag
        while (bpm < 70.0) bpm *= 2.0
        while (bpm > 180.0) bpm /= 2.0
        if (!bpm.isFinite()) return null

        // Integer autocorrelation bins are not precise enough for a grid that
        // must stay aligned for minutes. Refine both tempo and phase against the
        // first 12 seconds of onsets, where a small BPM error cannot accumulate.
        val refined = refineGrid(env, fps, n, bpm)
        val confidence = bestScore / mean
        return Estimate(
            bpm = refined.first,
            anchorSec = refined.second,
            confidence = if (confidence.isFinite()) confidence else 0.0
        )
    }

    private fun refineGrid(
        env: DoubleArray,
        fps: Double,
        n: Int,
        initialBpm: Double
    ): Pair<Double, Double> {
        val minBpm = (initialBpm - 4.0).coerceAtLeast(50.0)
        val maxBpm = (initialBpm + 4.0).coerceAtMost(200.0)
        val horizon = minOf(n, (fps * 12.0).toInt())
        var bestBpm = initialBpm
        var bestPhase = 0.0
        var bestScore = -1.0

        var bpm = minBpm
        while (bpm <= maxBpm) {
            val period = fps * 60.0 / bpm
            if (period >= 2.0) {
                var phase = 0.0
                while (phase < period) {
                    var sum = 0.0
                    var beats = 0
                    var position = phase
                    while (position < horizon) {
                        val index = position.roundToInt()
                        if (index in 0 until n) sum += env[index]
                        position += period
                        beats++
                    }
                    if (beats > 0) {
                        val score = sum / beats
                        if (score > bestScore + 1e-12) {
                            bestScore = score
                            bestBpm = bpm
                            bestPhase = phase
                        }
                    }
                    phase += 1.0
                }
            }
            bpm += 0.02
        }
        return bestBpm to bestPhase / fps
    }

    private data class Decoded(
        val energies: FloatArray,
        val fps: Double,
        val durationSec: Double
    )

    private fun decodeEnergy(context: Context, uri: Uri, maxSeconds: Double): Decoded? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context.applicationContext, uri, null)
            var trackIndex = -1
            var format: MediaFormat? = null
            var mime: String? = null
            for (i in 0 until extractor.trackCount) {
                val candidate = extractor.getTrackFormat(i)
                val candidateMime = candidate.getString(MediaFormat.KEY_MIME) ?: continue
                if (candidateMime.startsWith("audio/")) {
                    trackIndex = i
                    format = candidate
                    mime = candidateMime
                    break
                }
            }
            if (trackIndex < 0) return null
            extractor.selectTrack(trackIndex)
            val durationSec = format!!.longOr(MediaFormat.KEY_DURATION, 0L) / 1_000_000.0
            codec = MediaCodec.createDecoderByType(mime!!)
            codec.configure(format, null, null, 0)
            codec.start()

            var rate = format.intOr(MediaFormat.KEY_SAMPLE_RATE, 44100)
            var channels = format.intOr(MediaFormat.KEY_CHANNEL_COUNT, 2)
            var encoding = AudioFormat.ENCODING_PCM_16BIT
            var hop = max(64, rate / 172)
            val energies = FloatBuilder()
            var accumulated = 0.0
            var accumulatedFrames = 0
            var decodedFrames = 0L
            var inputDone = false
            var outputDone = false
            val info = MediaCodec.BufferInfo()
            val maxFrames = (maxSeconds * rate).toLong()

            while (!outputDone) {
                if (!inputDone) {
                    val inputIndex = codec.dequeueInputBuffer(10_000)
                    if (inputIndex >= 0) {
                        val input = codec.getInputBuffer(inputIndex)
                        val size = if (input != null) extractor.readSampleData(input, 0) else -1
                        if (size < 0) {
                            codec.queueInputBuffer(
                                inputIndex, 0, 0, 0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outputIndex = codec.dequeueOutputBuffer(info, 10_000)
                if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val outputFormat = codec.outputFormat
                    rate = outputFormat.intOr(MediaFormat.KEY_SAMPLE_RATE, rate)
                    channels = outputFormat.intOr(MediaFormat.KEY_CHANNEL_COUNT, channels)
                    if (outputFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                        encoding = outputFormat.intOr(
                            MediaFormat.KEY_PCM_ENCODING,
                            AudioFormat.ENCODING_PCM_16BIT
                        )
                    }
                    hop = max(64, rate / 172)
                    continue
                }
                if (outputIndex < 0) continue

                val buffer = codec.getOutputBuffer(outputIndex)
                if (buffer != null && info.size > 0) {
                    buffer.position(info.offset)
                    buffer.limit(info.offset + info.size)
                    val bytesPerSample =
                        if (encoding == AudioFormat.ENCODING_PCM_FLOAT) 4 else 2
                    val frameCount = info.size / (bytesPerSample * channels.coerceAtLeast(1))
                    if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
                        val floats = buffer.order(ByteOrder.nativeOrder()).asFloatBuffer()
                        for (f in 0 until frameCount) {
                            val base = f * channels
                            val left = floats.get(base)
                            val right = if (channels > 1) floats.get(base + 1) else left
                            val mono = (left + right) * 0.5
                            accumulated += mono * mono
                            accumulatedFrames++
                            if (accumulatedFrames >= hop) {
                                energies.add((accumulated / accumulatedFrames).toFloat())
                                accumulated = 0.0
                                accumulatedFrames = 0
                            }
                        }
                    } else {
                        val shorts = buffer.order(ByteOrder.nativeOrder()).asShortBuffer()
                        for (f in 0 until frameCount) {
                            val base = f * channels
                            val left = shorts.get(base) / 32768.0
                            val right = if (channels > 1) shorts.get(base + 1) / 32768.0 else left
                            val mono = (left + right) * 0.5
                            accumulated += mono * mono
                            accumulatedFrames++
                            if (accumulatedFrames >= hop) {
                                energies.add((accumulated / accumulatedFrames).toFloat())
                                accumulated = 0.0
                                accumulatedFrames = 0
                            }
                        }
                    }
                    decodedFrames += frameCount
                }
                codec.releaseOutputBuffer(outputIndex, false)
                if (decodedFrames >= maxFrames) break
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
            }

            if (energies.size < 32) return null
            return Decoded(energies.toArray(), rate.toDouble() / hop, durationSec)
        } catch (e: Exception) {
            return null
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

    private class FloatBuilder {
        private var data = FloatArray(1024)
        var size = 0
            private set

        fun add(value: Float) {
            if (size == data.size) data = data.copyOf(data.size * 2)
            data[size++] = value
        }

        fun toArray(): FloatArray = data.copyOf(size)
    }
}

private fun MediaFormat.intOr(key: String, fallback: Int): Int =
    if (containsKey(key)) getInteger(key) else fallback

private fun MediaFormat.longOr(key: String, fallback: Long): Long =
    if (containsKey(key)) getLong(key) else fallback
