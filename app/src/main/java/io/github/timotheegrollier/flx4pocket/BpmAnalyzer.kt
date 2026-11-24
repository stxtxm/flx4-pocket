package io.github.timotheegrollier.flx4pocket

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Beat grid estimation from an onset-energy autocorrelation: the tempo to a
 * few thousandths of a BPM, so a grid holds for a whole track, and the time
 * of one beat.
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

        // Autocorrelation up to four beats of the slowest tempo.
        val top = minOf(n / 2, maxLag * 4 + 3)
        val acf = DoubleArray(top + 2)
        for (lag in minLag - 1..top) acf[lag] = correlation(env, lag)

        // A beat also repeats after 2, 3 and 4 beats (a bar). Each value counts
        // with its neighbours, since a beat rarely lasts a whole number of
        // frames. The shortest lag wins unless a longer one is clearly better,
        // which settles ties between a beat and its multiples.
        var bestLag = minLag
        var bestScore = 0.0
        var total = 0.0
        for (lag in minLag..maxLag) {
            var score = 0.0
            for (m in 1..4) {
                var peak = 0.0
                for (k in lag * m - m / 2..minOf(top, lag * m + m / 2)) {
                    peak = max(peak, acf[k - 1] + acf[k] + acf[k + 1])
                }
                score += peak
            }
            total += score
            if (score > bestScore * 1.1) {
                bestScore = score
                bestLag = lag
            }
        }
        if (bestScore <= 0.0) return null
        val confidence = bestScore * (maxLag - minLag + 1) / total

        // An integer lag is far too coarse for a grid that must hold for a whole
        // track. The same peak comes back after 2, 4, 8 ... beats: followed up
        // to half the stretch, a fraction of a frame there is a thousandth of a BPM.
        var period = peak(env, bestLag)
        var beats = 1
        while (true) {
            val next = minOf(beats * 2, (n / 2 / period).toInt())
            if (next <= beats) break
            beats = next
            period = peak(env, (period * beats).roundToInt()) / beats
        }
        if (!(period >= 4.0)) return null

        // One octave, so that a tempo never has two readings (87 and 174).
        var bpm = 60.0 * fps / period
        while (bpm < MIN_BPM) {
            bpm *= 2.0
            period /= 2.0
        }
        while (bpm >= MIN_BPM * 2.0) {
            bpm /= 2.0
            period *= 2.0
        }

        // Fold the whole stretch onto one beat: the anchor is the centre of
        // mass of its three busiest frames, which is finer than one frame.
        val bins = ceil(period).toInt()
        val weight = DoubleArray(bins)
        val moment = DoubleArray(bins)
        for (i in 0 until n) {
            val phase = i - floor(i / period) * period
            val bin = minOf(phase.toInt(), bins - 1)
            weight[bin] += env[i]
            moment[bin] += env[i] * phase
        }
        var best = 0
        var heaviest = -1.0
        for (bin in 0 until bins) {
            val w = weight[(bin + bins - 1) % bins] + weight[bin] + weight[(bin + 1) % bins]
            if (w > heaviest) {
                heaviest = w
                best = bin
            }
        }
        var mass = 0.0
        var sum = 0.0
        for (d in -1..1) {
            val bin = (best + d + bins) % bins
            // A neighbour across the end of the beat belongs to the next or previous one.
            val turn = if (best + d < 0) -period else if (best + d >= bins) period else 0.0
            mass += weight[bin]
            sum += moment[bin] + turn * weight[bin]
        }
        if (mass <= 0.0) return null
        var anchor = sum / mass
        anchor -= floor(anchor / period) * period
        return Estimate(
            bpm = bpm,
            anchorSec = anchor / fps,
            confidence = if (confidence.isFinite()) confidence else 0.0
        )
    }

    /** Mean product of the envelope with itself [lag] frames later. */
    private fun correlation(env: DoubleArray, lag: Int): Double {
        if (lag <= 0 || lag >= env.size) return 0.0
        var sum = 0.0
        for (i in lag until env.size) sum += env[i] * env[i - lag]
        return sum / (env.size - lag)
    }

    /** Position, finer than one frame, of the autocorrelation peak within two lags of [center]. */
    private fun peak(env: DoubleArray, center: Int): Double {
        var best = center
        var top = -1.0
        for (lag in center - 2..center + 2) {
            val value = correlation(env, lag)
            if (value > top) {
                top = value
                best = lag
            }
        }
        val before = correlation(env, best - 1)
        val after = correlation(env, best + 1)
        val curve = before - 2.0 * top + after
        return if (curve < 0.0) best + (0.5 * (before - after) / curve).coerceIn(-0.5, 0.5) else best.toDouble()
    }

    /** Tempos are read in one octave from here: house and techno as they are, 174 not 87, and 85 as 170. */
    private const val MIN_BPM = 89.5
}
