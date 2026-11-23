package io.github.timotheegrollier.flx4pocket

import kotlin.math.ln
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * BPM estimation based on an onset-energy autocorrelation.
 *
 * It is intentionally dependency-free and Android-free. When the estimate has
 * a low confidence the UI marks the BPM with "~", and the grid can always be
 * corrected by tapping the tempo.
 */
object BpmAnalyzer {

    data class Result(
        val bpm: Double,
        val anchorSec: Double,
        val confidence: Double,
        val durationSec: Double
    )

    data class Estimate(val bpm: Double, val anchorSec: Double, val confidence: Double)

    /** Collects the short-time energy of a PCM stream, the input of [estimate]. */
    class Onsets(private val sampleRate: Int) {
        private val hop = max(64, sampleRate / 172)
        private var energies = FloatArray(16_384)
        private var size = 0
        private var sum = 0.0
        private var count = 0

        val seconds: Double get() = size.toDouble() * hop / sampleRate

        fun add(stereo: FloatArray, frames: Int) {
            for (f in 0 until frames) {
                val mono = (stereo[f * 2] + stereo[f * 2 + 1]) * 0.5
                sum += mono * mono
                if (++count == hop) {
                    if (size == energies.size) energies = energies.copyOf(size * 2)
                    energies[size++] = (sum / hop).toFloat()
                    sum = 0.0
                    count = 0
                }
            }
        }

        /** Null when there is less than eight seconds of audio or no clear pulse. */
        fun estimate(): Estimate? {
            if (seconds < 8.0) return null
            return estimate(energies.copyOf(size), sampleRate.toDouble() / hop)
        }
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
}
