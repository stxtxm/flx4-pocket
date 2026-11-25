package io.github.timotheegrollier.flx4pocket

import android.content.Context
import android.net.Uri
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log2
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** A key of the western scale: [tonic] is the pitch class, 0 = C. */
data class MusicalKey(val tonic: Int, val minor: Boolean, val strength: Double) {

    /** "Am", "C", "F#m": the name as DJs read it on a track list. */
    val name: String get() = NOTE_NAMES[tonic] + if (minor) "m" else ""

    /** The Camelot wheel code rekordbox shows, e.g. "8A" for A minor, "8B" for C major. */
    val camelot: String get() = (if (minor) CAMELOT_MINOR[tonic] else CAMELOT_MAJOR[tonic]).toString() +
        if (minor) "A" else "B"

    fun encode(): String = "$tonic;$minor;$strength"

    companion object {
        val NOTE_NAMES = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

        /** Camelot numbers by pitch class: C is 8B, A minor is 8A, and so on around the wheel. */
        private val CAMELOT_MAJOR = intArrayOf(8, 3, 10, 5, 12, 7, 2, 9, 4, 11, 6, 1)
        private val CAMELOT_MINOR = intArrayOf(5, 12, 7, 2, 9, 4, 11, 6, 1, 8, 3, 10)

        fun decode(text: String): MusicalKey? {
            val parts = text.split(';')
            if (parts.size != 3) return null
            val tonic = parts[0].toIntOrNull()?.takeIf { it in 0..11 } ?: return null
            val minor = parts[1].toBooleanStrictOrNull() ?: return null
            val strength = parts[2].toDoubleOrNull() ?: return null
            return MusicalKey(tonic, minor, strength)
        }
    }
}

/**
 * Finds the key of a track from its pitch content: a chroma profile of the
 * whole track, matched against the Krumhansl-Kessler key profiles.
 */
object MusicalKeys {

    /** Result of one analysis; null from [analyze] when it was stopped before the end. */
    class Analysis(val key: MusicalKey?)

    /**
     * Decodes [uri] and estimates its key. Blocking; run it on a worker thread.
     * [stop] is polled between blocks; a stopped analysis returns null.
     */
    fun analyze(context: Context, uri: Uri, stop: () -> Boolean): Analysis? {
        var chroma: ChromaAccumulator? = null
        var stopped = false
        val decoded = PcmDecoder.decode(context, uri) { stereo, frames, sampleRate, _ ->
            val accumulator = chroma ?: ChromaAccumulator(sampleRate).also { chroma = it }
            accumulator.add(stereo, frames)
            if (stop()) stopped = true
            !stopped
        }
        if (stopped) return null
        val profile = chroma?.profile ?: return Analysis(null)
        return Analysis(if (decoded) estimate(profile) else null)
    }

    private val MAJOR = doubleArrayOf(6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88)
    private val MINOR = doubleArrayOf(6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17)

    /** The key whose profile correlates best with [chroma]; null for silence. */
    fun estimate(chroma: DoubleArray): MusicalKey? {
        if (chroma.all { it <= 0.0 }) return null
        var best: MusicalKey? = null
        for (minor in booleanArrayOf(false, true)) {
            val profile = if (minor) MINOR else MAJOR
            for (tonic in 0 until 12) {
                val r = correlation(chroma) { pc -> profile[Math.floorMod(pc - tonic, 12)] }
                if (best == null || r > best.strength) best = MusicalKey(tonic, minor, r)
            }
        }
        return best
    }

    private fun correlation(x: DoubleArray, y: (Int) -> Double): Double {
        val mx = x.average()
        val my = (0 until 12).sumOf { y(it) } / 12.0
        var sxy = 0.0
        var sxx = 0.0
        var syy = 0.0
        for (i in 0 until 12) {
            val dx = x[i] - mx
            val dy = y(i) - my
            sxy += dx * dy
            sxx += dx * dx
            syy += dy * dy
        }
        return if (sxx == 0.0 || syy == 0.0) 0.0 else sxy / sqrt(sxx * syy)
    }
}

/**
 * Sums the spectrum of consecutive blocks into twelve pitch classes, from 55 Hz
 * (A1) to 2 kHz. Bass and melody both count, so a key shows even without a bassline.
 */
class ChromaAccumulator(private val sampleRate: Int) {

    val profile = DoubleArray(12)

    private val block = DoubleArray(SIZE)
    private var filled = 0
    private val re = DoubleArray(SIZE)
    private val im = DoubleArray(SIZE)
    private val window = DoubleArray(SIZE) { 0.5 - 0.5 * cos(2 * PI * it / SIZE) }
    private val twiddleRe = DoubleArray(SIZE / 2) { cos(-2 * PI * it / SIZE) }
    private val twiddleIm = DoubleArray(SIZE / 2) { sin(-2 * PI * it / SIZE) }

    /** The pitch class of each FFT bin, -1 outside the analysed range. */
    private val binClass = IntArray(SIZE / 2) { bin ->
        val hz = bin * sampleRate.toDouble() / SIZE
        if (hz < MIN_HZ || hz > MAX_HZ) -1
        else Math.floorMod((69 + 12 * log2(hz / 440.0)).roundToInt(), 12)
    }

    fun add(stereo: FloatArray, frames: Int) {
        for (f in 0 until frames) {
            block[filled++] = (stereo[f * 2] + stereo[f * 2 + 1]) * 0.5
            if (filled == SIZE) {
                transform()
                filled = 0
            }
        }
    }

    private fun transform() {
        for (i in 0 until SIZE) {
            re[i] = block[i] * window[i]
            im[i] = 0.0
        }
        fft(re, im)
        for (bin in 1 until SIZE / 2) {
            val pc = binClass[bin]
            if (pc >= 0) profile[pc] += sqrt(re[bin] * re[bin] + im[bin] * im[bin])
        }
    }

    /** In-place radix-2 transform of a power-of-two length. */
    private fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                re[i] = re[j].also { re[j] = re[i] }
                im[i] = im[j].also { im[j] = im[i] }
            }
        }
        var len = 2
        while (len <= n) {
            val stride = n / len
            for (start in 0 until n step len) {
                for (k in 0 until len / 2) {
                    val wr = twiddleRe[k * stride]
                    val wi = twiddleIm[k * stride]
                    val a = start + k
                    val b = a + len / 2
                    val tr = re[b] * wr - im[b] * wi
                    val ti = re[b] * wi + im[b] * wr
                    re[b] = re[a] - tr
                    im[b] = im[a] - ti
                    re[a] += tr
                    im[a] += ti
                }
            }
            len = len shl 1
        }
    }

    private companion object {
        const val SIZE = 8192
        const val MIN_HZ = 55.0
        const val MAX_HZ = 2000.0
    }
}
