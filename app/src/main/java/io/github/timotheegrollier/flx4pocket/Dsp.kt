package io.github.timotheegrollier.flx4pocket

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A direct-form I biquad. Coefficients use the RBJ audio EQ cookbook.
 *
 * The class is deliberately Android-free so the DSP can be unit tested on the
 * JVM. One instance holds the state of one channel, so a stereo path needs two.
 */
class Biquad {

    private var b0 = 1.0
    private var b1 = 0.0
    private var b2 = 0.0
    private var a1 = 0.0
    private var a2 = 0.0
    private var z1 = 0.0
    private var z2 = 0.0

    fun reset() {
        z1 = 0.0
        z2 = 0.0
    }

    fun process(x: Double): Double {
        val y = b0 * x + z1
        z1 = b1 * x - a1 * y + z2
        z2 = b2 * x - a2 * y
        return y
    }

    private fun set(b0: Double, b1: Double, b2: Double, a0: Double, a1: Double, a2: Double) {
        if (a0 == 0.0 || !a0.isFinite()) return
        this.b0 = b0 / a0
        this.b1 = b1 / a0
        this.b2 = b2 / a0
        this.a1 = a1 / a0
        this.a2 = a2 / a0
        if (!this.b0.isFinite() || !this.b1.isFinite() || !this.b2.isFinite() ||
            !this.a1.isFinite() || !this.a2.isFinite()
        ) {
            bypass()
        }
    }

    fun bypass() {
        b0 = 1.0
        b1 = 0.0
        b2 = 0.0
        a1 = 0.0
        a2 = 0.0
    }

    fun setLowShelf(fs: Double, f0: Double, gainDb: Double) {
        if (gainDb == 0.0) {
            bypass()
            return
        }
        val a = 10.0.pow(gainDb / 40.0)
        val w = omega(fs, f0)
        val cosw = cos(w)
        val sinw = sin(w)
        val alpha = sinw / 2.0 * sqrt(2.0)
        val twoSqrtAAlpha = 2.0 * sqrt(a) * alpha
        set(
            a * ((a + 1) - (a - 1) * cosw + twoSqrtAAlpha),
            2 * a * ((a - 1) - (a + 1) * cosw),
            a * ((a + 1) - (a - 1) * cosw - twoSqrtAAlpha),
            (a + 1) + (a - 1) * cosw + twoSqrtAAlpha,
            -2 * ((a - 1) + (a + 1) * cosw),
            (a + 1) + (a - 1) * cosw - twoSqrtAAlpha
        )
    }

    fun setHighShelf(fs: Double, f0: Double, gainDb: Double) {
        if (gainDb == 0.0) {
            bypass()
            return
        }
        val a = 10.0.pow(gainDb / 40.0)
        val w = omega(fs, f0)
        val cosw = cos(w)
        val sinw = sin(w)
        val alpha = sinw / 2.0 * sqrt(2.0)
        val twoSqrtAAlpha = 2.0 * sqrt(a) * alpha
        set(
            a * ((a + 1) + (a - 1) * cosw + twoSqrtAAlpha),
            -2 * a * ((a - 1) + (a + 1) * cosw),
            a * ((a + 1) + (a - 1) * cosw - twoSqrtAAlpha),
            (a + 1) - (a - 1) * cosw + twoSqrtAAlpha,
            2 * ((a - 1) - (a + 1) * cosw),
            (a + 1) - (a - 1) * cosw - twoSqrtAAlpha
        )
    }

    fun setPeaking(fs: Double, f0: Double, gainDb: Double, q: Double = 0.9) {
        if (gainDb == 0.0) {
            bypass()
            return
        }
        val a = 10.0.pow(gainDb / 40.0)
        val w = omega(fs, f0)
        val cosw = cos(w)
        val alpha = sin(w) / (2.0 * q)
        set(
            1 + alpha * a,
            -2 * cosw,
            1 - alpha * a,
            1 + alpha / a,
            -2 * cosw,
            1 - alpha / a
        )
    }

    fun setLowPass(fs: Double, f0: Double, q: Double = 0.9) {
        val w = omega(fs, f0)
        val cosw = cos(w)
        val alpha = sin(w) / (2.0 * q)
        set(
            (1 - cosw) / 2.0,
            1 - cosw,
            (1 - cosw) / 2.0,
            1 + alpha,
            -2 * cosw,
            1 - alpha
        )
    }

    fun setHighPass(fs: Double, f0: Double, q: Double = 0.9) {
        val w = omega(fs, f0)
        val cosw = cos(w)
        val alpha = sin(w) / (2.0 * q)
        set(
            (1 + cosw) / 2.0,
            -(1 + cosw),
            (1 + cosw) / 2.0,
            1 + alpha,
            -2 * cosw,
            1 - alpha
        )
    }

    private fun omega(fs: Double, f0: Double): Double {
        val safeF0 = f0.coerceIn(20.0, fs * 0.45)
        return 2.0 * PI * safeF0 / fs
    }
}

/**
 * Real-time spectrum display helpers. Android-free, so the DSP is unit
 * tested on the JVM.
 */
object Spectrum {

    /**
     * Power spectrum of a real signal via an iterative radix-2 FFT.
     * Returns one magnitude per non-negative frequency bin
     * (`input.size / 2 + 1`).
     */
    fun fft(input: FloatArray): FloatArray {
        val n = input.size
        require(n > 0 && n and (n - 1) == 0) { "FFT input size must be a power of 2" }
        val re = DoubleArray(n)
        val im = DoubleArray(n)
        input.forEachIndexed { i, v -> re[i] = v.toDouble() }

        // Bit-reversal permutation so the butterflies run in place.
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j or bit
            if (i < j) {
                val tr = re[i]
                re[i] = re[j]
                re[j] = tr
                val ti = im[i]
                im[i] = im[j]
                im[j] = ti
            }
        }

        var len = 2
        while (len <= n) {
            val ang = -2.0 * PI / len
            val wr = cos(ang)
            val wi = sin(ang)
            var i = 0
            while (i < n) {
                var cwr = 1.0
                var cwi = 0.0
                for (k in 0 until len / 2) {
                    val ur = re[i + k]
                    val ui = im[i + k]
                    val vr = re[i + k + len / 2] * cwr - im[i + k + len / 2] * cwi
                    val vi = re[i + k + len / 2] * cwi + im[i + k + len / 2] * cwr
                    re[i + k] = ur + vr
                    im[i + k] = ui + vi
                    re[i + k + len / 2] = ur - vr
                    im[i + k + len / 2] = ui - vi
                    val t = cwr * wr - cwi * wi
                    cwi = cwr * wi + cwi * wr
                    cwr = t
                }
                i += len
            }
            len *= 2
        }

        val power = FloatArray(n / 2 + 1)
        for (i in power.indices) {
            power[i] = (re[i] * re[i] + im[i] * im[i]).toFloat()
        }
        return power
    }

    /**
     * Maps the power spectrum onto a fixed number of display bands, each the
     * sum of its bin powers, normalized so the peak band is 1.0.
     */
    fun spectrum(input: FloatArray, bands: Int = 32): FloatArray {
        require(input.size > 0 && input.size and (input.size - 1) == 0) {
            "spectrum input size must be a power of 2"
        }
        val power = fft(input)
        val out = FloatArray(bands)
        val half = power.size - 1
        for (b in 0 until bands) {
            var sum = 0.0
            val lo = b * half / bands
            val hi = if (b == bands - 1) half else ((b + 1) * half) / bands
            for (k in lo until hi) sum += power[k]
            out[b] = (sum * 2.0 / input.size).toFloat()
        }
        val peak = out.max()
        if (peak > 0f) {
            for (i in out.indices) out[i] /= peak
        }
        return out
    }
}
