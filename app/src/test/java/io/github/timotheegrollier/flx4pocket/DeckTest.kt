package io.github.timotheegrollier.flx4pocket

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.math.abs

class DeckTest {

    /** Thirty seconds of a ramp that is always "decoded". */
    private class FakeSource(override val durationSec: Double = 30.0) : PcmSource {
        override val sampleRate = 44100
        var seeks = 0
        override fun sampleFrame(frame: Double, out: FloatArray): Boolean {
            if (frame < 0 || frame > durationSec * sampleRate) return false
            out[0] = 0.5f
            out[1] = 0.5f
            return true
        }

        override fun hasFrame(frame: Double) = true
        override fun seek(seconds: Double) {
            seeks++
        }

        override fun close() {}
    }

    private val deck = Deck(0)
    private val l = FloatArray(256)
    private val r = FloatArray(256)

    @Before
    fun load() {
        deck.load(FakeSource(), "Title", "Artist", 0.0)
        deck.setGrid(120.0, 0.0, 2.0) // a beat every 0.5 s
    }

    /** Lets [seconds] of audio go by, as the mixer thread would. */
    private fun play(seconds: Double, target: Deck = deck, master: Deck? = null): Float {
        var peak = 0f
        repeat((seconds * Deck.SAMPLE_RATE / 256).toInt()) {
            target.render(256, l, r, master)
            peak = maxOf(peak, l.maxOf { abs(it) })
        }
        return peak
    }

    @Test
    fun `a paused deck is silent and does not move`() {
        assertEquals(0f, play(0.5), 0f)
        assertEquals(0.0, deck.positionSec, 0.0)
    }

    @Test
    fun `playing advances at the tempo fader speed`() {
        deck.togglePlay()
        assertTrue(play(1.0) > 0.4f)
        assertEquals(1.0, deck.positionSec, 0.01)

        deck.setTempoNormalized(1.0) // +10 %
        play(1.0)
        assertEquals(2.1, deck.positionSec, 0.02)
        assertEquals(132.0, deck.effectiveBpm(), 1e-6)
    }

    @Test
    fun `tempo range cycles and keeps the fader position`() {
        deck.setTempoNormalized(-1.0)
        assertEquals(0.90, deck.tempoRate, 1e-9)
        deck.cycleTempoRange()
        assertEquals(0.84, deck.tempoRate, 1e-9)
        assertEquals(-1.0, deck.tempoPosition, 1e-9)
        repeat(3) { deck.cycleTempoRange() }
        assertEquals(0.10, deck.tempoRange, 1e-9)
    }

    @Test
    fun `playback stops at the end of the track`() {
        deck.seekTo(29.8)
        deck.togglePlay()
        play(1.0)
        assertFalse(deck.playing)
        assertEquals(30.0, deck.positionSec, 0.01)
        deck.togglePlay()
        assertEquals("play at the end restarts the track", 0.0, deck.positionSec, 0.01)
    }

    @Test
    fun `cue button behaves like a CDJ`() {
        deck.seekTo(4.0)
        deck.cueDown() // paused away from the cue: set it
        deck.cueUp()
        assertEquals(4.0, deck.cuePointSec, 1e-9)

        deck.cueDown() // paused on the cue: preview while held
        assertTrue(deck.playing)
        play(0.5)
        deck.cueUp()
        assertFalse(deck.playing)
        assertEquals(4.0, deck.positionSec, 1e-9)

        deck.togglePlay()
        play(1.0)
        deck.cueDown() // playing: back to the cue and stop
        assertFalse(deck.playing)
        assertEquals(4.0, deck.positionSec, 1e-9)
    }

    @Test
    fun `PLAY while CUE is held keeps the deck playing`() {
        deck.cueDown() // paused on the cue point (0 s): preview
        play(0.5)
        deck.togglePlay()
        deck.cueUp()
        assertTrue(deck.playing)
        play(0.5)
        assertEquals(1.0, deck.positionSec, 0.01)
        deck.togglePlay() // a second press stops it as usual
        assertFalse(deck.playing)
    }

    @Test
    fun `beat loop wraps and the same pad exits it`() {
        deck.seekTo(2.1)
        deck.togglePlay()
        deck.padDown(Deck.PAD_BEAT_LOOP, 3) // 2 beats = 1 s, from the beat at 2.0
        assertEquals(2.0, deck.loopInSec, 1e-9)
        assertEquals(3.0, deck.loopOutSec, 1e-9)
        assertEquals(2.0, deck.loopBeats(), 1e-9)
        play(2.6)
        assertTrue(deck.positionSec in 2.0..3.0)

        deck.padDown(Deck.PAD_BEAT_LOOP, 3)
        assertFalse(deck.loopActive)
        play(1.0)
        assertTrue(deck.positionSec > 3.0)
    }

    @Test
    fun `loop can be halved doubled and re-entered`() {
        deck.seekTo(4.0)
        deck.toggleBeatLoop(4.0)
        deck.scaleLoop(0.5)
        assertEquals(2.0, deck.loopBeats(), 1e-9)
        deck.scaleLoop(2.0)
        deck.scaleLoop(2.0)
        assertEquals(8.0, deck.loopBeats(), 1e-9)

        deck.toggleLoop()
        assertFalse(deck.loopActive)
        deck.seekTo(20.3)
        deck.toggleLoop() // active loop again: back inside it, at the same place in the beat
        assertTrue(deck.loopActive)
        assertEquals(4.3, deck.positionSec, 1e-9)
    }

    @Test
    fun `manual loop is quantized to the grid`() {
        deck.seekTo(1.04)
        deck.setLoopIn()
        deck.seekTo(2.97)
        deck.setLoopOut()
        assertEquals(1.0, deck.loopInSec, 1e-9)
        assertEquals(3.0, deck.loopOutSec, 1e-9)
        assertTrue(deck.loopActive)

        deck.exitLoop()
        deck.quantize = false
        deck.seekTo(5.04)
        deck.fourBeatLoopOrExit()
        assertEquals(5.04, deck.loopInSec, 1e-9)
        assertEquals(7.04, deck.loopOutSec, 1e-9)
        deck.fourBeatLoopOrExit() // EXIT
        assertFalse(deck.loopActive)
    }

    @Test
    fun `a loop never throws the deck off the beat`() {
        deck.seekTo(1.0)
        deck.setLoopIn()
        deck.seekTo(2.97) // OUT a little early: the loop closes on the beat at 3.0, no jump
        deck.setLoopOut()
        assertEquals(2.97, deck.positionSec, 1e-9)

        deck.exitLoop()
        deck.seekTo(3.03) // OUT a little late: back inside, as far into the beat
        deck.setLoopOut()
        assertEquals(1.03, deck.positionSec, 1e-9)

        deck.exitLoop()
        deck.seekTo(5.96) // 4 BEAT just before a beat starts on that beat
        deck.fourBeatLoopOrExit()
        assertEquals(6.0, deck.loopInSec, 1e-9)
        assertEquals(5.96, deck.positionSec, 1e-9)

        deck.exitLoop()
        deck.seekTo(10.4) // a 1/4-beat loop starts on the nearest quarter beat, around the playhead
        deck.toggleBeatLoop(0.25)
        assertEquals(10.375, deck.loopInSec, 1e-9)
        assertEquals(10.4, deck.positionSec, 1e-9)

        deck.exitLoop()
        deck.seekTo(12.0)
        deck.toggleBeatLoop(4.0)
        deck.seekTo(13.3)
        deck.scaleLoop(0.5) // halved under the playhead: wraps in phase
        assertEquals(12.3, deck.positionSec, 1e-9)
    }

    @Test
    fun `a beat loop reports its size exactly on any grid, and the screen can show it`() {
        deck.setGrid(127.3, 0.11, 2.0)
        deck.seekTo(21.0) // (out - in) / beat is 4.000000000000003 here: no pad would light
        deck.toggleBeatLoop(4.0)
        assertEquals(4.0, deck.loopBeats(), 0.0)
        deck.scaleLoop(0.5)
        assertEquals(2.0, deck.loopBeats(), 0.0)
        assertEquals("2", beatsLabel(deck.loopBeats()))
        assertEquals(listOf("1/4", "1/2", "1", "32"), listOf(0.25, 0.5, 1.0, 32.0).map(::beatsLabel))
        assertEquals("a manual loop of any length", "3.3", beatsLabel(3.27))
    }

    @Test
    fun `a loop asked for before the first beat keeps its length`() {
        deck.setGrid(128.0, 0.40, 2.0) // the first beat of the grid falls after half a beat
        deck.fourBeatLoopOrExit() // at 0:00, to loop the intro
        assertTrue(deck.loopInSec >= 0.0)
        assertEquals(4.0, deck.loopBeats(), 0.0)
    }

    @Test
    fun `a jump past a loop leaves it`() {
        deck.seekTo(20.0)
        deck.padDown(Deck.PAD_HOT_CUE, 0) // a hot cue later in the track
        deck.seekTo(4.0)
        deck.togglePlay()
        deck.toggleBeatLoop(4.0) // 4.0 to 6.0
        deck.seekTo(5.0)
        assertTrue("inside the loop: it goes on", deck.loopActive)
        deck.padDown(Deck.PAD_HOT_CUE, 0)
        assertFalse(deck.loopActive)
        play(0.5)
        assertEquals("it plays on from the hot cue instead of falling back into the loop", 20.5, deck.positionSec, 0.26)
    }

    @Test
    fun `beat jump during a loop moves the loop, as in rekordbox`() {
        deck.seekTo(4.0)
        deck.togglePlay()
        deck.toggleBeatLoop(4.0) // 4.0 to 6.0
        deck.seekTo(5.0)
        deck.padDown(Deck.PAD_BEAT_JUMP, 5) // +4 beats
        assertTrue(deck.loopActive)
        assertEquals(6.0, deck.loopInSec, 1e-9)
        assertEquals(8.0, deck.loopOutSec, 1e-9)
        assertEquals(7.0, deck.positionSec, 1e-9)
        deck.padDown(Deck.PAD_BEAT_JUMP, 6) // -8 beats
        assertEquals(2.0, deck.loopInSec, 1e-9)
        assertEquals(3.0, deck.positionSec, 1e-9)
        deck.padDown(Deck.PAD_BEAT_JUMP, 6) // -8 beats again: the loop stops at the start of the track
        assertEquals(0.0, deck.loopInSec, 1e-9)
        assertEquals(2.0, deck.loopOutSec, 1e-9)
        assertEquals(1.0, deck.positionSec, 1e-9)
    }

    @Test
    fun `calling a memory cue waits on it, as in rekordbox`() {
        deck.seekTo(8.0)
        deck.cueDown()
        deck.cueUp()
        deck.saveMemoryCue()
        deck.seekTo(1.0)
        deck.togglePlay()
        deck.cueLoopCall(1)
        assertFalse(deck.playing)
        assertEquals(8.0, deck.positionSec, 1e-9)
    }

    @Test
    fun `a loop seam crossfades instead of clicking`() {
        deck.load(SineSource(441.0), "Sine", "", 0.0)
        deck.quantize = false
        deck.seekTo(10.0)
        deck.setLoopIn()
        deck.seekTo(11.0 + 25.0 / 44100) // out of phase with the loop start: a hard cut would step by ~0.5
        deck.togglePlay()
        deck.render(256, l, r, null)
        val previous = l[255]
        deck.setLoopOut() // the loop closes here, so the deck jumps back to 10.0, then loops once more
        val worst = worstStep(1.5, previous)
        assertTrue("still looping", deck.positionSec in 10.0..11.01)
        assertTrue("largest step $worst", worst < 0.05f)
    }

    /** Largest sample-to-sample step over [seconds] of playback, starting from [previous]. */
    private fun worstStep(seconds: Double, previous: Float): Float {
        var last = previous
        var worst = 0f
        repeat((seconds * 44100 / 256).toInt()) {
            deck.render(256, l, r, null)
            for (x in l) {
                worst = maxOf(worst, abs(x - last))
                last = x
            }
        }
        return worst
    }

    @Test
    fun `with KEY the loop seam crossfades too`() {
        deck.load(SineSource(441.0), "Sine", "", 0.0)
        deck.quantize = false
        deck.keyLock = true
        deck.seekTo(10.0)
        deck.setLoopIn()
        deck.seekTo(11.0 + 25.0 / 44100)
        deck.togglePlay()
        repeat(40) { deck.render(256, l, r, null) } // past the first grains
        val previous = l[255]
        deck.setLoopOut()
        val worst = worstStep(2.5, previous)
        assertTrue("still looping", deck.positionSec in 10.0..11.3)
        assertTrue("largest step $worst", worst < 0.08f)
    }

    /** The sample value is the position in the track: 0 at the start, 1 at the end. */
    private class RampSource : PcmSource {
        override val sampleRate = 44100
        override val durationSec = 30.0
        override fun sampleFrame(frame: Double, out: FloatArray): Boolean {
            if (frame < 0 || frame > durationSec * sampleRate) return false
            out[0] = (frame / (durationSec * sampleRate)).toFloat()
            out[1] = out[0]
            return true
        }

        override fun hasFrame(frame: Double) = true
        override fun seek(seconds: Double) {}
        override fun close() {}
    }

    @Test
    fun `with KEY the loop starts over at the seam, not a grain later`() {
        deck.load(RampSource(), "Ramp", "", 0.0)
        deck.quantize = false
        deck.keyLock = true
        deck.seekTo(10.0)
        deck.setLoopIn()
        deck.seekTo(11.0)
        deck.togglePlay()
        deck.setLoopOut() // loops 10.0 to 11.0 from 10.0
        var previous = deck.positionSec
        repeat(400) {
            deck.render(256, l, r, null)
            if (deck.positionSec < previous) { // wrapped during this block
                repeat(3) { deck.render(256, l, r, null) } // past the 512-frame grain crossfade
                assertEquals("playing the loop start", 10.0 / 30.0, l[255].toDouble(), 0.005)
                return
            }
            previous = deck.positionSec
        }
        throw AssertionError("the loop never wrapped")
    }

    @Test
    fun `memory cues are saved, called in order and deleted`() {
        deck.seekTo(2.0)
        deck.cueDown() // paused away from the cue point: sets it here
        deck.cueUp()
        deck.saveMemoryCue()
        deck.seekTo(5.0)
        deck.cueDown()
        deck.cueUp()
        deck.saveMemoryCue()
        deck.seekTo(8.0)
        deck.toggleBeatLoop(4.0) // 8.0 to 10.0
        deck.saveMemoryCue() // a memory loop
        deck.exitLoop()
        assertEquals(3, deck.memoryCues.size)

        deck.seekTo(0.0)
        deck.cueLoopCall(1)
        assertEquals(2.0, deck.positionSec, 1e-9)
        assertEquals(2.0, deck.cuePointSec, 1e-9)
        deck.cueLoopCall(1)
        deck.cueLoopCall(1) // the loop: set and playing
        assertTrue(deck.loopActive)
        assertEquals(10.0, deck.loopOutSec, 1e-9)
        deck.cueLoopCall(-1) // in a loop, ◁ halves it
        assertEquals(2.0, deck.loopBeats(), 1e-9)

        deck.exitLoop()
        deck.cueLoopCall(-1)
        assertEquals(5.0, deck.positionSec, 1e-9)
        deck.deleteMemoryCue() // the one just called
        assertEquals(listOf(2.0, 8.0), deck.memoryCues.map { it.inSec })
    }

    @Test
    fun `instant doubles play along at the same speed and place`() {
        val other = Deck(1)
        other.load(FakeSource(), "Same", "", 0.0)
        other.cycleTempoRange() // ±16 %
        other.setTempoNormalized(0.5)
        other.keyLock = true
        other.seekTo(12.3)
        other.togglePlay()
        deck.playAlong(other)
        assertEquals(other.tempoRate, deck.tempoRate, 1e-9)
        assertEquals(12.3, deck.positionSec, 1e-9)
        assertTrue(deck.playing)
        assertTrue(deck.keyLock)
    }

    @Test
    fun `IN and OUT during a loop let the jog move its points`() {
        deck.seekTo(4.0)
        deck.toggleBeatLoop(4.0) // 4.0 to 6.0
        deck.setLoopIn()
        assertEquals(Deck.ADJUST_IN, deck.loopAdjust)
        deck.bend(40) // 40 jog ticks later
        assertEquals(4.0 + 40 * Deck.JOG_TICK_SECONDS, deck.loopInSec, 1e-9)
        deck.setLoopOut()
        assertEquals(Deck.ADJUST_OUT, deck.loopAdjust)
        deck.scratchBegin()
        assertFalse("the jog top moves the point, it does not scratch", deck.scratching)
        deck.bend(-40)
        assertEquals(6.0 - 40 * Deck.JOG_TICK_SECONDS, deck.loopOutSec, 1e-9)
        deck.setLoopOut()
        assertEquals(0, deck.loopAdjust)
        deck.bend(10) // a normal pitch bend again
        assertEquals(6.0 - 40 * Deck.JOG_TICK_SECONDS, deck.loopOutSec, 1e-9)

        deck.setLoopIn()
        deck.fourBeatLoopOrExit() // EXIT also ends the adjustment
        assertFalse(deck.loopActive)
        assertEquals(0, deck.loopAdjust)
        deck.setLoopIn() // with no loop playing, IN sets a new loop in point
        assertEquals(4.0, deck.loopInSec, 1e-9)
    }

    @Test
    fun `hot cues are set quantized recalled and deleted`() {
        deck.seekTo(1.26)
        deck.padDown(Deck.PAD_HOT_CUE, 2)
        assertEquals(1.5, deck.hotCues[2]!!, 1e-9)
        deck.seekTo(10.0)
        deck.padDown(Deck.PAD_HOT_CUE, 2)
        assertEquals(1.5, deck.positionSec, 1e-9)
        deck.padDown(Deck.PAD_HOT_CUE, 2, shift = true)
        assertNull(deck.hotCues[2])
    }

    @Test
    fun `beat jump pads move by whole beats`() {
        deck.seekTo(10.0)
        deck.padDown(Deck.PAD_BEAT_JUMP, 5) // +4 beats
        assertEquals(12.0, deck.positionSec, 1e-9)
        deck.padDown(Deck.PAD_BEAT_JUMP, 0) // -1 beat
        assertEquals(11.5, deck.positionSec, 1e-9)
    }

    @Test
    fun `loop roll repeats then resumes where the track would have been`() {
        deck.seekTo(2.0)
        deck.togglePlay()
        deck.padDown(Deck.PAD_FX1, 5) // ROLL 1/4 beat = 0.125 s
        assertEquals(5, deck.heldFxPad)
        play(1.0)
        assertTrue("still inside the roll", deck.positionSec in 2.0..2.126)
        deck.padUp(Deck.PAD_FX1, 5)
        assertEquals(-1, deck.heldFxPad)
        assertEquals(3.0, deck.positionSec, 0.01)
    }

    @Test
    fun `pad fx is held on and released`() {
        deck.padDown(Deck.PAD_FX2, 2)
        assertTrue(deck.padFx.enabled)
        assertEquals(FxType.REVERB, deck.padFx.type)
        deck.padUp(Deck.PAD_FX2, 3) // another pad's release must not stop it
        assertTrue(deck.padFx.enabled)
        deck.padUp(Deck.PAD_FX2, 2)
        assertFalse(deck.padFx.enabled)
    }

    @Test
    fun `scratching a paused deck is audible and moves it both ways`() {
        deck.seekTo(5.0)
        deck.scratchBegin()
        deck.scratchMove(-0.5)
        assertTrue(play(0.3) > 0.4f)
        assertEquals(4.5, deck.positionSec, 0.01)
        deck.scratchMove(1.0)
        play(0.3)
        assertEquals(5.5, deck.positionSec, 0.01)
        deck.scratchEnd()
        assertEquals(0f, play(0.2), 0f)
    }

    @Test
    fun `jog bend nudges a playing deck and steps a paused one`() {
        deck.bend(4)
        assertEquals(4 * Deck.JOG_TICK_SECONDS, deck.positionSec, 1e-9)

        deck.togglePlay()
        play(1.0)
        val before = deck.positionSec
        repeat(20) {
            deck.bend(5)
            play(0.05)
        }
        assertTrue("bending forward runs ahead of normal speed", deck.positionSec - before > 1.02)
    }

    @Test
    fun `nudge buttons change the speed while held`() {
        deck.togglePlay()
        deck.nudge = 0.04
        play(1.0)
        assertEquals(1.04, deck.positionSec, 0.01)
    }

    @Test
    fun `sync matches tempo and beat phase of the master`() {
        val master = Deck(1)
        master.load(FakeSource(), "M", "", 0.0)
        master.setGrid(126.0, 0.1, 2.0)
        master.togglePlay()
        play(1.0, target = master)

        deck.seekTo(3.2)
        deck.togglePlay()
        assertTrue(deck.engageSync(master))
        assertEquals(126.0, deck.effectiveBpm(), 1e-6)
        together(0.01, master) // it lands on the master's beat in its next block
        assertEquals(0.0, BeatGrid.phaseError(master.phase(), deck.phase()), 1e-6)

        together(1.2, master)
        assertEquals(0.0, BeatGrid.phaseError(master.phase(), deck.phase()), 1e-6)

        deck.setTempoNormalized(0.0) // touching the tempo fader releases sync
        assertFalse(deck.syncEnabled)
    }

    /** Both decks for [seconds], as the mixer renders them: the follower first, then its master. */
    private fun together(seconds: Double, master: Deck) = repeat((seconds * Deck.SAMPLE_RATE / 256).toInt().coerceAtLeast(1)) {
        deck.render(256, l, r, master)
        master.render(256, l, r, null)
    }

    private fun master(bpm: Double, anchor: Double = 0.0) = Deck(1).apply {
        load(FakeSource(), "M", "", 0.0)
        setGrid(bpm, anchor, 2.0)
    }

    @Test
    fun `sync off keeps the tempo the deck plays at`() {
        val master = master(126.0)
        assertTrue(deck.engageSync(master))
        deck.releaseSync() // SYNC pressed again, or SYNC pressed on the other deck
        assertFalse(deck.syncEnabled)
        assertEquals("no tempo jump on air", 126.0, deck.effectiveBpm(), 1e-6)
        assertEquals("the fader takes over from there", 0.5, deck.tempoPosition, 1e-6)
    }

    @Test
    fun `PLAY starts a synced deck on the master's beat`() {
        val master = master(126.0, anchor = 0.1)
        master.togglePlay()
        play(1.0, target = master)
        deck.seekTo(4.0)
        assertTrue(deck.engageSync(master)) // pressed on a paused deck: the tempo only, for now
        assertEquals("a paused deck stays on its cue point", 4.0, deck.positionSec, 0.0)

        play(0.33, target = master)
        assertTrue(

            "the test starts it off the master's beat",
            abs(BeatGrid.phaseError(master.phase(), deck.phase())) > 0.05
        )
        deck.togglePlay() // PLAY: no second SYNC press needed
        together(0.01, master)
        assertEquals(

            "PLAY lands on the master's beat, as rekordbox does",
            0.0,
            BeatGrid.phaseError(master.phase(), deck.phase()),
            1e-6
        )
        assertEquals(126.0, deck.effectiveBpm(), 1e-6)
        together(10.0, master)
        assertEquals("it stays on the beat", 0.0, BeatGrid.phaseError(master.phase(), deck.phase()), 1e-3)

        deck.seekTo(17.3) // needle search, hot cue, memory cue: it lands on the beat again
        together(0.01, master)
        assertEquals(
            "a seek on a synced deck lands on the master's beat",
            0.0,
            BeatGrid.phaseError(master.phase(), deck.phase()),
            1e-3
        )
        together(5.0, master)
        assertEquals("and stays there", 0.0, BeatGrid.phaseError(master.phase(), deck.phase()), 1e-3)
    }

    @Test
    fun `a synced deck that starts before the master lands on its beat`() {
        val master = master(126.0, anchor = 0.1)
        deck.seekTo(4.0)
        assertTrue(deck.engageSync(master)) // SYNC pressed while both decks are stopped
        deck.togglePlay() // the DJ starts this deck first, on its cue point
        together(0.33, master) // the master is paused: there is no beat to land on yet
        assertTrue(abs(BeatGrid.phaseError(master.phase(), deck.phase())) > 0.05)

        master.togglePlay() // the master comes in: the deck is already on its beat
        together(0.01, master)
        assertEquals(0.0, BeatGrid.phaseError(master.phase(), deck.phase()), 1e-3)
        assertEquals(126.0, deck.effectiveBpm(), 1e-6)
        together(10.0, master)
        assertEquals("it stays on the beat", 0.0, BeatGrid.phaseError(master.phase(), deck.phase()), 1e-3)
    }

    @Test
    fun `a new grid on either deck lands the synced deck on the beat again`() {
        val master = master(126.0)
        master.togglePlay()
        deck.togglePlay()
        assertTrue(deck.engageSync(master))
        together(1.0, master) // on the master's beat
        deck.setGrid(120.0, 0.25, 2.0) // TAP moved this deck's grid half a beat
        master.setGrid(126.0, 0.1, 2.0) // and the analysis refined the master's
        together(0.05, master)
        assertEquals(
            "the deck lands on the master's beat again",
            0.0,
            BeatGrid.phaseError(master.phase(), deck.phase()),
            1e-3
        )
        together(20.0, master)
        assertEquals("and holds it", 0.0, BeatGrid.phaseError(master.phase(), deck.phase()), 1e-3)
        assertEquals(126.0, deck.effectiveBpm(), 1e-6)
    }

    @Test
    fun `a nudge on a synced deck is taken back onto the beat`() {
        val master = master(126.0)
        master.togglePlay()
        deck.togglePlay()
        assertTrue(deck.engageSync(master))
        together(0.5, master)
        repeat(4) {
            deck.bend(5) // the DJ pushes the platter: the grid was a little off
            together(0.05, master)
        }
        assertTrue(
            "the push moves the deck while the hand is on it",
            abs(BeatGrid.phaseError(master.phase(), deck.phase())) > 0.01
        )
        together(0.5, master) // released: as rekordbox, sync takes the beat back
        assertEquals("the beat is back", 0.0, BeatGrid.phaseError(master.phase(), deck.phase()), 1e-3)
        together(8.0, master)
        assertEquals("and stays there", 0.0, BeatGrid.phaseError(master.phase(), deck.phase()), 1e-3)
        assertEquals(126.0, deck.effectiveBpm(), 1e-6)
    }

    @Test
    fun `sync off beyond the fader's range widens the range, so the fader can take over`() {
        deck.setGrid(140.0, 0.0, 2.0)
        assertTrue(deck.engageSync(master(100.0)))
        deck.releaseSync()
        assertEquals("the tempo on air is kept", 100.0, deck.effectiveBpm(), 1e-6)
        assertEquals("WIDE, as rekordbox switches to", 0.50, deck.tempoRange, 1e-9)
        assertTrue("a fader can reach it", abs(deck.tempoPosition) <= 1.0)
        repeat(4) {
            deck.cycleTempoRange()
            assertTrue("never backwards", deck.tempoRate > 0.0)
        }
    }

    @Test
    fun `SYNC right after a jog push still lands on the beat`() {
        val master = master(126.0, anchor = 0.1)
        master.togglePlay()
        deck.togglePlay()
        together(1.0, master)
        deck.bend(3) // the DJ pushes the platter, then presses SYNC at once
        together(0.06, master)
        assertTrue(deck.engageSync(master))
        together(0.01, master)
        assertEquals(0.0, BeatGrid.phaseError(master.phase(), deck.phase()), 1e-6)
    }

    @Test
    fun `a synced deck keeps its octave when the master's fader moves`() {
        deck.setGrid(90.0, 0.0, 2.0)
        val master = master(127.0) // 1.41 times faster: synced at the same octave
        master.togglePlay()
        deck.togglePlay()
        assertTrue(deck.engageSync(master))
        together(0.1, master)
        assertEquals(127.0, deck.effectiveBpm(), 1e-6)
        master.setTempoNormalized(0.12) // +1.2 %: the ratio passes 1.42
        together(0.1, master)
        assertEquals("it follows, without dropping to half speed", 127.0 * 1.012, deck.effectiveBpm(), 1e-6)
    }

    @Test
    fun `halving a short loop on a synced deck stays inside it`() {
        val master = master(120.0)
        master.togglePlay()
        deck.togglePlay()
        assertTrue(deck.engageSync(master))
        together(1.0, master)
        deck.toggleBeatLoop(1.0)
        val start = deck.loopInSec
        while (deck.positionSec < start + 0.35) together(0.001, master) // 0.7 into the loop
        deck.cueLoopCall(-1) // halve: the deck is past the new end and wraps, in phase
        repeat(20) {
            together(0.001, master)
            assertTrue("${deck.positionSec} left the loop at $start", deck.positionSec >= start - 1e-6 && deck.positionSec <= start + 0.25 + 1e-6)
        }
    }

    @Test
    fun `with quantize a hot cue at the very start keeps the beat`() {
        deck.setGrid(128.0, 0.35, 2.0) // the first beat 0.35 s in
        deck.quantize = false
        deck.padDown(Deck.PAD_HOT_CUE, 0) // a hot cue at 0:00
        deck.quantize = true
        deck.togglePlay()
        deck.seekTo(0.35 + 20.1 * 60.0 / 128.0) // a tenth into a beat: earlier in its beat than the cue
        deck.padDown(Deck.PAD_HOT_CUE, 0)
        assertEquals("as far into the beat as before", 0.1, deck.phase(), 1e-6)
        assertTrue(deck.positionSec < 0.5)
    }

    @Test
    fun `cue and loop points set in a lead-in land on the first beat`() {
        deck.setGrid(128.0, 0.35, 2.0)
        deck.seekTo(0.05)
        deck.cueDown()
        deck.cueUp()
        assertEquals(0.35, deck.cuePointSec, 1e-9)
        deck.seekTo(0.05)
        deck.setLoopIn()
        assertEquals(0.35, deck.loopInSec, 1e-9)
    }

    @Test
    fun `an 80 BPM deck syncs to a 160 BPM master`() {
        deck.setGrid(80.0, 0.0, 2.0)
        val master = master(160.0, anchor = 0.05)
        master.togglePlay()
        deck.seekTo(1.13) // off the beat, as a DJ drops it
        deck.togglePlay()
        assertTrue(deck.engageSync(master))
        together(0.01, master)
        assertEquals("80 BPM is half of 160: it keeps its own speed", 80.0, deck.effectiveBpm(), 1e-6)
        assertEquals(
            "every beat of the slow deck falls on a beat of the master",
            0.0,
            BeatGrid.phaseError(master.phase(), deck.phase() * 2),
            1e-6
        )
        together(8.0, master)
        assertEquals(0.0, BeatGrid.phaseError(master.phase(), deck.phase() * 2), 1e-3)
    }

    @Test
    fun `half and double tempo sync without changing speed`() {
        deck.setGrid(87.0, 0.0, 2.0)
        val master = master(174.0)
        master.togglePlay()
        deck.togglePlay()
        deck.seekTo(3.37) // off the master's beat when SYNC is pressed
        assertTrue(deck.engageSync(master))
        assertEquals("87 BPM is 174 BPM: not twice the speed", 87.0, deck.effectiveBpm(), 1e-6)
        together(1.0, master)
        assertEquals(87.0, deck.effectiveBpm(), 1e-3)
        assertEquals("every beat of the slower deck falls on a beat of the master", 0.0, BeatGrid.phaseError(master.phase(), deck.phase() * 2), 1e-6)

        deck.setGrid(174.0, 0.0, 2.0)
        val slow = master(87.0)
        slow.togglePlay()
        assertTrue(deck.engageSync(slow))
        assertEquals(174.0, deck.effectiveBpm(), 1e-6)
    }

    @Test
    fun `with quantize a hot cue jumps without leaving the beat`() {
        deck.seekTo(2.0)
        deck.padDown(Deck.PAD_HOT_CUE, 0) // a hot cue on the beat at 2.0
        deck.seekTo(10.0)
        deck.togglePlay()
        play(0.15) // 0.3 beat into a beat
        val into = deck.phase()
        deck.padDown(Deck.PAD_HOT_CUE, 0)
        assertEquals("as far into the beat as before the jump", into, deck.phase(), 1e-9)
        assertEquals(2.0 + into * 0.5, deck.positionSec, 1e-9)

        deck.quantize = false
        deck.padDown(Deck.PAD_HOT_CUE, 0)
        assertEquals("without quantize: exactly on the cue", 2.0, deck.positionSec, 1e-9)
    }

    @Test
    fun `a synced deck follows the master's tempo changes`() {
        val master = Deck(1)
        master.load(FakeSource(), "M", "", 0.0)
        master.setGrid(120.0, 0.0, 2.0)
        master.togglePlay()
        deck.togglePlay()
        assertTrue(deck.engageSync(master))
        master.setTempoNormalized(0.5) // the master speeds up by 5 %
        repeat(20) {
            master.render(256, l, r, null)
            deck.render(256, l, r, master)
        }
        assertEquals(126.0, deck.effectiveBpm(), 0.01)
        assertEquals("a fader takes over at the synced speed", 0.5, deck.tempoPosition, 1e-6)
    }

    /** A 0.5 amplitude sine, always decoded. */
    private class SineSource(private val hz: Double) : PcmSource {
        override val sampleRate = 44100
        override val durationSec = 30.0
        override fun sampleFrame(frame: Double, out: FloatArray): Boolean {
            if (frame < 0 || frame > durationSec * sampleRate) return false
            val v = (0.5 * Math.sin(2 * Math.PI * hz * frame / sampleRate)).toFloat()
            out[0] = v
            out[1] = v
            return true
        }

        override fun hasFrame(frame: Double) = true
        override fun seek(seconds: Double) {}
        override fun close() {}
    }

    /** Pitch heard over [blocks] blocks, from rising zero crossings. */
    private fun heardHz(blocks: Int): Double {
        var crossings = 0
        var previous = 0f
        repeat(blocks) {
            deck.render(256, l, r, null)
            for (x in l) {
                if (previous < 0f && x >= 0f) crossings++
                previous = x
            }
        }
        return crossings / (blocks * 256.0 / Deck.SAMPLE_RATE)
    }

    @Test
    fun `at the output's own rate the deck keeps its time and its pitch`() {
        deck.load(SineSource(441.0), "Sine", "", 0.0) // a 44.1 kHz track
        deck.setOutputRate(48000) // on an output that runs at 48 kHz
        deck.togglePlay()
        var crossings = 0
        var previous = 0f
        repeat(375) { // two seconds of output
            deck.render(256, l, r, null)
            for (x in l) {
                if (previous < 0f && x >= 0f) crossings++
                previous = x
            }
        }
        assertEquals(2.0, deck.positionSec, 1e-6)
        assertEquals(441.0, crossings / 2.0, 1.0)
    }

    @Test
    fun `with KEY a jump to the very start does not freeze the deck`() {
        deck.load(object : PcmSource { // music-like: a tone in noise, so grains have a best place to start
            override val sampleRate = 44100
            override val durationSec = 30.0
            override fun sampleFrame(frame: Double, out: FloatArray): Boolean {
                if (frame < 0 || frame > durationSec * sampleRate) return false
                val n = Math.floor(frame).toLong()
                val noise = ((n * 1103515245L + 12345L) and 0x7fffffffL) / 2147483647.0 - 0.5
                val v = (0.4 * Math.sin(2 * Math.PI * 441.0 * frame / sampleRate) + 0.4 * noise).toFloat()
                out[0] = v
                out[1] = v
                return true
            }
            override fun hasFrame(frame: Double) = true
            override fun seek(seconds: Double) {}
            override fun close() {}
        }, "Noise", "", 0.0)
        deck.keyLock = true
        deck.togglePlay()
        repeat(40) { attempt ->
            play(0.1 + attempt * 0.003) // land in a different place of the grain each time
            deck.seekTo(0.0) // SHIFT + CUE, or a hot cue at 0:00
            val peak = play(0.2)
            assertTrue("attempt $attempt: still playing from the start", deck.positionSec in 0.15..0.25)
            assertTrue("attempt $attempt: audible", peak > 0.3f)
        }
    }

    @Test
    fun `keylock changes the tempo but not the pitch`() {
        deck.load(SineSource(441.0), "Sine", "", 0.0)
        deck.togglePlay()
        deck.setTempoNormalized(0.8) // +8 %
        assertEquals("vinyl: the pitch follows the tempo", 476.3, heardHz(172), 4.0)

        deck.keyLock = true
        heardHz(20) // first grains
        val before = deck.positionSec
        assertEquals("keylock: the pitch stays", 441.0, heardHz(172), 4.0)
        assertEquals("and the tempo is still +8 %", 1.08, (deck.positionSec - before) / (172 * 256.0 / 44100), 0.002)
    }

    @Test
    fun `a jump crossfades instead of clicking`() {
        deck.load(SineSource(441.0), "Sine", "", 0.0)
        deck.togglePlay()
        deck.render(256, l, r, null)
        var previous = l[255]
        deck.seekTo(10.0 + 0.25 / 441) // where the sine is at its peak: a cut would step by ~0.7
        deck.render(256, l, r, null)
        var worst = 0f
        for (x in l) {
            worst = maxOf(worst, abs(x - previous))
            previous = x
        }
        assertTrue("largest step $worst", worst < 0.05f)
    }

    @Test
    fun `tapping the beat corrects a grid without losing its precision`() {
        deck.setGrid(127.93, 0.1, 8.0)
        val beat = 60.0 / 127.93
        deck.seekTo(0.1 + 20.5 * beat) // the DJ hears beats where the grid has its off-beats
        deck.tapGrid(127.0) // a finger is a BPM off: the analysed tempo is kept
        assertEquals(127.93, deck.grid.bpm, 0.0)
        assertEquals("the grid moved half a beat, onto the taps", 0.0, BeatGrid.phaseError(deck.phase(), 0.0), 1e-9)
        deck.tapGrid(128.5) // on the grid already: nothing moves
        assertEquals(0.0, BeatGrid.phaseError(deck.phase(), 0.0), 1e-9)

        deck.tapGrid(64.2) // the track is felt at half the tempo shown
        assertEquals(127.93 / 2, deck.grid.bpm, 1e-9)

        deck.setGrid(128.0, 0.0, 8.0)
        deck.seekTo(7.3)
        deck.tapGrid(100.0) // nothing like the analysis: the taps are the grid
        assertEquals(100.0, deck.grid.bpm, 0.0)
        assertEquals(7.3, deck.grid.anchorSec, 0.0)
    }

    @Test
    fun `sync is refused without a grid`() {
        val master = Deck(1)
        master.load(FakeSource(), "M", "", 0.0)
        assertFalse(deck.engageSync(master))
    }

    @Test
    fun `loading a new track resets the deck`() {
        deck.seekTo(8.0)
        deck.padDown(Deck.PAD_HOT_CUE, 0)
        deck.toggleBeatLoop(4.0)
        deck.togglePlay()
        deck.load(FakeSource(12.0), "Next", "", 0.0)
        assertFalse(deck.playing)
        assertFalse(deck.loopActive)
        assertFalse(deck.grid.valid)
        assertNull(deck.hotCues[0])
        assertEquals(0.0, deck.positionSec, 0.0)
        assertEquals(12.0, deck.durationSec, 0.0)
        assertEquals("Next", deck.title)
        assertNotNull(deck.source)
    }

    @Test
    fun `filter and eq at centre leave the level unchanged`() {
        deck.togglePlay()
        assertEquals(0.5f, play(0.5), 1e-4f)
        deck.trim = 0f
        assertEquals(0f, play(0.1), 0f)
    }

    @Test
    fun `smart cfx adds an echo that outlives the filter`() {
        deck.smartCfx = true
        deck.filter = 0.1f // low-pass side: the test signal is DC
        deck.togglePlay()
        play(1.0)
        deck.pause()
        deck.filter = 0.5f
        assertTrue("echo tail after pausing", play(0.5) > 0.001f)
    }
}
