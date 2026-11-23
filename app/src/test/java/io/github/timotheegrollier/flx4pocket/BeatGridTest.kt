package io.github.timotheegrollier.flx4pocket

import org.junit.Assert.assertEquals
import org.junit.Test

class BeatGridTest {

    @Test
    fun `phase follows the beat grid`() {
        val grid = BeatGrid(bpm = 120.0, anchorSec = 0.0)
        assertEquals(0.0, grid.phase(0.0), 1e-9)
        assertEquals(0.5, grid.phase(0.25), 1e-9)
        assertEquals(0.0, grid.phase(0.5), 1e-9)
        assertEquals(4, grid.beatIndex(2.0))
        assertEquals(0.5, grid.secondsPerBeat(), 1e-9)
    }

    @Test
    fun `phase is relative to the anchor`() {
        val grid = BeatGrid(bpm = 128.0, anchorSec = 1.25)
        assertEquals(0.0, grid.phase(1.25), 1e-9)
        assertEquals(0.5, grid.phase(1.25 + 60.0 / 128.0 / 2.0), 1e-9)
    }

    @Test
    fun `phase error picks the shortest signed path`() {
        assertEquals(0.2, BeatGrid.phaseError(0.7, 0.5), 1e-9)
        assertEquals(-0.2, BeatGrid.phaseError(0.5, 0.7), 1e-9)
        assertEquals(-0.3, BeatGrid.phaseError(0.1, 0.4), 1e-9)
        assertEquals(0.3, BeatGrid.phaseError(0.4, 0.1), 1e-9)
        assertEquals(0.0, BeatGrid.phaseError(0.0, 1.0), 1e-9)
    }

    @Test
    fun `invalid grid is safe`() {
        val grid = BeatGrid(bpm = 0.0)
        assertEquals(0.0, grid.phase(12.0), 1e-9)
        assertEquals(0.0, grid.secondsPerBeat(), 1e-9)
        assertEquals(false, grid.valid)
    }
}
