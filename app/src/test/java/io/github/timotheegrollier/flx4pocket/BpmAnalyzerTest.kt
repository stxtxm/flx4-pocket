package io.github.timotheegrollier.flx4pocket

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class BpmAnalyzerTest {

    @Test
    fun `detects a 120 bpm click track`() {
        val fps = 100.0
        val seconds = 60.0
        val energy = FloatArray((seconds * fps).toInt()) { 0.0001f }
        var beat = 0.0
        while (beat < seconds) {
            val index = (beat * fps).toInt()
            if (index in energy.indices) energy[index] = 1.0f
            beat += 0.5
        }
        val estimate = BpmAnalyzer.estimate(energy, fps)
        assertNotNull(estimate)
        assertEquals(120.0, estimate!!.bpm, 1.0)
        assertOnBeat(estimate.anchorSec, expected = 0.0, bpm = 120.0)
    }

    @Test
    fun `detects a 128 bpm click track with an offset`() {
        val fps = 172.0
        val seconds = 45.0
        val period = 60.0 / 128.0
        val offset = 0.37
        val energy = FloatArray((seconds * fps).toInt()) { 0.0001f }
        var time = offset
        while (time < seconds) {
            val index = (time * fps).toInt()
            if (index in energy.indices) energy[index] = 1.0f
            time += period
        }
        val estimate = BpmAnalyzer.estimate(energy, fps)
        assertNotNull(estimate)
        assertEquals(128.0, estimate!!.bpm, 1.5)
        assertOnBeat(estimate.anchorSec, expected = offset, bpm = 128.0)
    }

    /**
     * A beat anchor is only meaningful modulo one beat: any beat of the track is
     * a valid grid origin. This asserts beat alignment rather than a specific
     * beat index, which is what sync actually consumes.
     */
    private fun assertOnBeat(anchor: Double, expected: Double, bpm: Double) {
        val period = 60.0 / bpm
        var delta = (anchor - expected) % period
        if (delta < 0.0) delta += period
        delta = minOf(delta, period - delta)
        assertTrue(
            "anchor $anchor should sit on a beat near $expected (period $period)",
            abs(delta) < 0.03
        )
    }

    @Test
    fun `rejects silence`() {
        val estimate = BpmAnalyzer.estimate(FloatArray(1000), 100.0)
        assertEquals(null, estimate)
    }
}
