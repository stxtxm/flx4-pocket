package io.github.timotheegrollier.flx4pocket

import kotlin.math.floor

/**
 * A minimal beat grid: a tempo plus the position of one beat (the "anchor").
 *
 * A full downbeat grid is not required for beat sync: matching any beat of two
 * tracks is enough to mix them. Hot cues and the position display use seconds,
 * which avoids tying the grid to a specific sample rate.
 */
data class BeatGrid(val bpm: Double = 0.0, val anchorSec: Double = 0.0) {

    val valid: Boolean get() = bpm.isFinite() && bpm > 30.0 && bpm < 300.0

    fun secondsPerBeat(): Double = if (valid) 60.0 / bpm else 0.0

    /** Phase inside the current beat, in [0, 1). */
    fun phase(positionSec: Double): Double {
        if (!valid) return 0.0
        val beats = (positionSec - anchorSec) * bpm / 60.0
        return beats - floor(beats)
    }

    fun beatIndex(positionSec: Double): Long {
        if (!valid) return 0L
        return floor((positionSec - anchorSec) * bpm / 60.0).toLong()
    }

    companion object {
        /**
         * Shortest signed distance in beats from [slavePhase] to [masterPhase],
         * in [-0.5, 0.5). Positive means the slave must move forward.
         */
        fun phaseError(masterPhase: Double, slavePhase: Double): Double {
            var e = masterPhase - slavePhase
            e -= floor(e + 0.5)
            return e
        }
    }
}
