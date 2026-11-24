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
        assertEquals(120.0, estimate!!.bpm, 0.01)
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
        assertEquals(128.0, estimate!!.bpm, 0.01)
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
            abs(delta) < 0.008 // an energy frame is 5.8 ms, and the click generators floor to a frame
        )
    }

    /** Energy frames of a click on every beat, as [BpmAnalyzer.Onsets] would hand them over. */
    private fun clicks(fps: Double, seconds: Double, bpm: Double, offset: Double): FloatArray {
        val energy = FloatArray((seconds * fps).toInt()) { 0.0001f }
        var time = offset
        while (time < seconds) {
            val index = (time * fps).toInt()
            if (index in energy.indices) energy[index] = 1.0f
            time += 60.0 / bpm
        }
        return energy
    }

    @Test
    fun `a non-integer tempo is found to a hundredth of a BPM`() {
        val fps = 44100.0 / 256
        val estimate = BpmAnalyzer.estimate(clicks(fps, 300.0, 127.93, 0.21), fps)!!
        assertEquals(127.93, estimate.bpm, 0.005)
        assertOnBeat(estimate.anchorSec, expected = 0.21, bpm = 127.93)
    }

    @Test
    fun `174 bpm does not come out as 87`() {
        val fps = 44100.0 / 256
        val estimate = BpmAnalyzer.estimate(clicks(fps, 240.0, 174.0, 0.1), fps)!!
        assertEquals(174.0, estimate.bpm, 0.01)
    }

    @Test
    fun `a silent intro does not spoil the grid`() {
        val fps = 44100.0 / 256
        val estimate = BpmAnalyzer.estimate(clicks(fps, 200.0, 128.0, 20.3), fps)!!
        assertEquals(128.0, estimate.bpm, 0.01)
        assertOnBeat(estimate.anchorSec, expected = 20.3, bpm = 128.0)
    }

    /** The real path, PCM in and grid out: a kick on every beat and a hat between, for five minutes. */
    @Test
    fun `the grid of a track still sits on its kicks after five minutes`() {
        val rate = 44100
        val bpm = 126.0
        val first = 0.137
        val period = rate * 60.0 / bpm
        val noise = java.util.Random(1)
        val onsets = BpmAnalyzer.Onsets(rate)
        val block = FloatArray(rate * 2)
        for (second in 0 until 300) {
            for (f in 0 until rate) {
                val sinceBeat = ((second.toLong() * rate + f - first * rate) % period + period) % period
                val kick = Math.sin(2 * Math.PI * 55.0 * sinceBeat / rate) * Math.exp(-sinceBeat / rate * 30.0)
                val sinceHat = (sinceBeat + period / 2) % period
                val hat = (noise.nextDouble() - 0.5) * 0.4 * Math.exp(-sinceHat / rate * 90.0)
                val v = (kick * 0.8 + hat).toFloat()
                block[f * 2] = v
                block[f * 2 + 1] = v
            }
            onsets.add(block, rate)
        }
        val grid = onsets.estimate()!!
        assertEquals(bpm, grid.bpm, 0.005)

        val beat = 60.0 / bpm
        val late = first + Math.round((295.0 - first) / beat) * beat // a kick near the end of the track
        val gridBeat = 60.0 / grid.bpm
        val nearest = grid.anchorSec + Math.round((late - grid.anchorSec) / gridBeat) * gridBeat
        assertEquals("grid against kick at ${late}s", late, nearest, 0.010)
    }

    @Test
    fun `rejects silence`() {
        val estimate = BpmAnalyzer.estimate(FloatArray(1000), 100.0)
        assertEquals(null, estimate)
    }
}
