package io.github.timotheegrollier.flx4pocket

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Beat grid estimation from onset energies: the tempo to a thousandth of a BPM,
 * so a grid holds for a whole track, and the time of one beat.
 *
 * The tempo comes from the autocorrelation of the onset envelope; its strongest
 * lags are folded into one octave (70 to 180 BPM, where 174 is not 87 and 80
 * stays 80) and a comb search over the whole stretch says which one really runs
 * through the track. The beat is then followed over many bars, where a fraction
 * of a frame is a hundredth of a BPM. The phase is measured on the kick band
 * alone, so the grid sits on the kick drum and not on an offbeat hat.
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
        private val kickAlpha = 1.0 - exp(-2.0 * Math.PI * KICK_HZ / sampleRate)
        private var energies = FloatArray(16_384)
        private var kicks = FloatArray(16_384)
        private var size = 0
        private var sum = 0.0
        private var kickSum = 0.0
        private var kick = 0.0
        private var count = 0

        val seconds: Double get() = size.toDouble() * hop / sampleRate

        fun add(stereo: FloatArray, frames: Int) {
            for (f in 0 until frames) {
                val mono = (stereo[f * 2] + stereo[f * 2 + 1]) * 0.5
                // One-pole low pass: the kick and the bass, which mark the beat.
                kick += kickAlpha * (mono - kick)
                sum += mono * mono
                kickSum += kick * kick
                if (++count == hop) {
                    if (size == energies.size) {
                        energies = energies.copyOf(size * 2)
                        kicks = kicks.copyOf(size * 2)
                    }
                    energies[size] = (sum / hop).toFloat()
                    kicks[size] = (kickSum / hop).toFloat()
                    size++
                    sum = 0.0
                    kickSum = 0.0
                    count = 0
                }
            }
        }

        /** Null when there is less than eight seconds of audio or no clear pulse. */
        fun estimate(): Estimate? {
            if (seconds < 8.0) return null
            return estimate(energies.copyOf(size), kicks.copyOf(size), sampleRate.toDouble() / hop)
        }
    }

    /**
     * Returns the tempo and the time of one beat in the analysed window.
     * Exposed for unit tests.
     */
    fun estimate(rawEnergy: FloatArray, fps: Double): Estimate? = estimate(rawEnergy, null, fps)

    private fun estimate(rawEnergy: FloatArray, kickEnergy: FloatArray?, fps: Double): Estimate? {
        if (fps <= 0.0 || rawEnergy.size < 64) return null
        val n = rawEnergy.size
        val env = envelope(rawEnergy, fps)
        val kickEnv = if (kickEnergy != null && kickEnergy.size == n) envelope(kickEnergy, fps) else env

        val minLag = max(2, (fps * 60.0 / MAX_BPM).roundToInt())
        val maxLag = min(n / 2, (fps * 60.0 / (MIN_BPM * 0.85)).roundToInt())
        if (maxLag <= minLag + 2) return null

        // Autocorrelation up to four beats of the slowest tempo.
        val top = min(n / 2, maxLag * 4 + 3)
        val acf = DoubleArray(top + 2)
        for (lag in minLag - 1..top) acf[lag] = correlation(env, lag)

        // A beat also repeats after 2, 3 and 4 beats (a bar). Each value counts
        // with its neighbours, since a beat rarely lasts a whole number of frames.
        val scores = DoubleArray(maxLag + 1)
        var bestScore = 0.0
        var total = 0.0
        for (lag in minLag..maxLag) {
            var score = 0.0
            for (m in 1..4) {
                var peak = 0.0
                for (k in lag * m - m / 2..min(top, lag * m + m / 2)) {
                    peak = max(peak, acf[k - 1] + acf[k] + acf[k + 1])
                }
                score += peak
            }
            scores[lag] = score
            total += score
            if (score > bestScore * 1.1) bestScore = score
        }
        if (bestScore <= 0.0) return null
        val confidence = bestScore * (maxLag - minLag + 1) / total

        // Not just the shortest lag: a syncopated bar can make a multiple look
        // stronger than the beat, and the octave may be either side of it. Every
        // candidate is folded into one octave, then the comb search says which
        // period really explains the onsets over the whole stretch.
        val folded = ArrayList<Double>()
        val used = BooleanArray(maxLag + 1)
        while (folded.size < CANDIDATES) {
            var pick = -1
            for (lag in minLag..maxLag) {
                if (!used[lag] && (pick < 0 || scores[lag] > scores[pick])) pick = lag
            }
            if (pick < 0) break
            val from = max(minLag, (pick * 0.85).toInt())
            val to = min(maxLag, (pick * 1.15).toInt() + 1)
            for (k in from..to) used[k] = true
            val rough = peak(env, pick)
            if (rough < 4.0) continue
            var bpm = 60.0 * fps / rough
            while (bpm < MIN_BPM) bpm *= 2.0
            while (bpm >= MAX_BPM) bpm /= 2.0
            if (folded.none { abs(it / bpm - 1.0) < 0.008 }) folded.add(bpm)
        }

        var bestPeriod = 0.0
        var bestComb = -1.0
        for (bpm in folded) {
            val fitted = fitPeriod(env, fps, 60.0 * fps / bpm)
            if (!(fitted >= 4.0)) continue
            val fittedBpm = 60.0 * fps / fitted
            if (fittedBpm < MIN_BPM * 0.98 || fittedBpm > MAX_BPM * 1.02) continue
            val score = combScore(env, fitted)
            if (score > bestComb) {
                bestComb = score
                bestPeriod = fitted
            }
        }
        if (bestPeriod <= 0.0) return null
        // The comb picks the octave; a few autocorrelation peaks over many beats then
        // place the period to a hundredth of a BPM, which one beat cannot tell.
        val period = refine(env, bestPeriod)

        val anchor = anchor(kickEnv, period)
        return Estimate(
            bpm = 60.0 * fps / period,
            anchorSec = anchor / fps,
            confidence = if (confidence.isFinite()) confidence else 0.0
        )
    }

    /** The final analysis keeps the octave the preview published, unless the two are not the same tempo. */
    fun sameOctave(bpm: Double, reference: Double): Double {
        if (reference <= 0.0 || bpm <= 0.0) return bpm
        return listOf(bpm / 2.0, bpm, bpm * 2.0).firstOrNull { abs(it / reference - 1.0) < 0.04 } ?: bpm
    }

    /** Onset energy: the rise of the short-time energy, minus its slow average. */
    private fun envelope(rawEnergy: FloatArray, fps: Double): DoubleArray {
        val n = rawEnergy.size
        val onset = DoubleArray(n)
        for (i in 1 until n) {
            onset[i] = max(0.0, ln(1.0 + rawEnergy[i]) - ln(1.0 + rawEnergy[i - 1]))
        }
        // Remove the slow envelope so the autocorrelation sees transients rather
        // than the overall loudness of the track.
        val window = max(4, (fps * 0.35).roundToInt())
        val prefix = DoubleArray(n + 1)
        for (i in 0 until n) prefix[i + 1] = prefix[i] + onset[i]
        val env = DoubleArray(n)
        for (i in 0 until n) {
            val from = max(0, i - window)
            val to = min(n, i + window + 1)
            val localMean = (prefix[to] - prefix[from]) / (to - from)
            env[i] = max(0.0, onset[i] - localMean)
        }
        return env
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

    /** Wide, then fine: the comb peaks only when the period holds over the whole stretch. */
    private fun fitPeriod(env: DoubleArray, fps: Double, p0: Double): Double {
        var period = searchPeriod(env, p0, 0.035, 40)
        period = searchPeriod(env, period, 0.0018, 24)
        return period
    }

    private fun searchPeriod(env: DoubleArray, center: Double, reach: Double, steps: Int): Double {
        var best = center
        var bestScore = -1.0
        for (j in 0..steps) {
            val period = center * (1.0 + reach * (2.0 * j / steps - 1.0))
            val score = combScore(env, period)
            if (score > bestScore) {
                bestScore = score
                best = period
            }
        }
        return best
    }

    private fun combScore(env: DoubleArray, period: Double): Double {
        if (!(period > 1.0)) return 0.0
        val weight = DoubleArray(COMB_BINS)
        for (i in env.indices) {
            val phase = i - floor(i / period) * period
            weight[min((phase / period * COMB_BINS).toInt(), COMB_BINS - 1)] += env[i]
        }
        var best = -1.0
        for (bin in 0 until COMB_BINS) {
            val around = weight[(bin + COMB_BINS - 1) % COMB_BINS] + weight[bin] + weight[(bin + 1) % COMB_BINS]
            if (around > best) best = around
        }
        return best
    }

    /**
     * Follows the beat to 2, 4, 8 ... beats with a parabolic fit on the autocorrelation: a
     * fraction of a frame at a lag of minutes is a hundredth of a BPM, and the fit is
     * rejected if it moves the period by more than 1 %.
     */
    private fun refine(env: DoubleArray, period: Double): Double {
        var refined = period
        var beats = 2
        while (true) {
            val lag = (beats * refined).roundToInt()
            if (lag + 2 >= env.size) break
            val step = peak(env, lag) / beats
            // A wild peak belongs to something else: keep the last good period.
            if (step > 0.0 && abs(step / refined - 1.0) < 0.005) refined = step
            beats *= 2
        }
        return refined
    }

    /** Folds the whole stretch onto one beat: the centre of mass of its three busiest frames. */
    private fun anchor(env: DoubleArray, period: Double): Double {
        val bins = ceil(period).toInt()
        if (bins <= 0) return 0.0
        val weight = DoubleArray(bins)
        val moment = DoubleArray(bins)
        for (i in env.indices) {
            val phase = i - floor(i / period) * period
            val bin = min(phase.toInt(), bins - 1)
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
        if (mass <= 0.0) return 0.0
        var a = sum / mass
        a -= floor(a / period) * period
        return a
    }

    /** Below this the analysis reads the next octave up (50 BPM is 100, not a very slow pulse). */
    private const val MIN_BPM = 70.0

    /** Above this it reads the next octave down. One octave keeps 174 out of 87. */
    private const val MAX_BPM = 180.0

    private const val CANDIDATES = 4
    private const val COMB_BINS = 64
    private const val KICK_HZ = 120.0
}