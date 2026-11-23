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
