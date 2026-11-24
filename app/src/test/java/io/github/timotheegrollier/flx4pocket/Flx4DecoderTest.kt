package io.github.timotheegrollier.flx4pocket

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Checks the decoder against the official DDJ-FLX4 MIDI message list. No
 * controller is needed: each test feeds the bytes the hardware sends.
 */
class Flx4DecoderTest {

    private val events = ArrayList<String>()
    private val decoder = Flx4Decoder(object : Flx4Listener {
        override fun onButton(deck: Int, button: Flx4Button, down: Boolean) {
            events += "button $deck $button $down"
        }

        override fun onKnob(deck: Int, knob: Flx4Knob, value: Float) {
            events += "knob $deck $knob ${"%.3f".format(java.util.Locale.US, value)}"
        }

        override fun onJog(deck: Int, jog: Flx4Jog, delta: Int) {
            events += "jog $deck $jog $delta"
        }

        override fun onPadMode(deck: Int, mode: Int) {
            events += "mode $deck $mode"
        }

        override fun onPad(deck: Int, mode: Int, pad: Int, shift: Boolean, down: Boolean) {
            events += "pad $deck $mode $pad $shift $down"
        }

        override fun onBrowse(delta: Int, shift: Boolean) {
            events += "browse $delta $shift"
        }
    })

    private fun send(vararg bytes: Int): List<String> {
        events.clear()
        decoder.feed(ByteArray(bytes.size) { bytes[it].toByte() })
        return events.toList()
    }

    @Test
    fun `transport buttons report press and release`() {
        assertEquals(listOf("button 0 PLAY true"), send(0x90, 0x0B, 0x7F))
        assertEquals(listOf("button 0 PLAY false"), send(0x90, 0x0B, 0x00))
        assertEquals(listOf("button 1 CUE true"), send(0x91, 0x0C, 0x7F))
        assertEquals(listOf("button 0 SHIFT true"), send(0x90, 0x3F, 0x7F))
        assertEquals(listOf("button 1 CUE false"), send(0x81, 0x0C, 0x40))
        assertEquals(listOf("button 0 TO_START true"), send(0x90, 0x48, 0x7F))
        assertEquals(listOf("button 1 SYNC true"), send(0x91, 0x58, 0x7F))
        assertEquals(listOf("button 1 MASTER true"), send(0x91, 0x5C, 0x7F))
        assertEquals(listOf("button 0 TEMPO_RANGE true"), send(0x90, 0x60, 0x7F))
        assertEquals(listOf("button 0 PFL true"), send(0x90, 0x54, 0x7F))
        assertEquals(listOf("button 0 TAP true"), send(0x90, 0x68, 0x7F))
    }

    @Test
    fun `running status and interleaved real-time bytes are handled`() {
        assertEquals(
            listOf("button 0 PLAY true", "button 0 PLAY false"),
            send(0x90, 0x0B, 0x7F, 0xF8, 0x0B, 0xFE, 0x00)
        )
    }

    @Test
    fun `loop section`() {
        assertEquals(listOf("button 0 LOOP_IN true"), send(0x90, 0x10, 0x7F))
        assertEquals(listOf("button 0 LOOP_OUT true"), send(0x90, 0x11, 0x7F))
        assertEquals(listOf("button 1 FOUR_BEAT_EXIT true"), send(0x91, 0x4D, 0x7F))
        assertEquals(listOf("button 1 ACTIVE_LOOP true"), send(0x91, 0x50, 0x7F))
        assertEquals(listOf("button 0 CUE_LOOP_CALL_PREV true"), send(0x90, 0x51, 0x7F))
        assertEquals(listOf("button 0 CUE_LOOP_CALL_NEXT true"), send(0x90, 0x53, 0x7F))
        assertEquals(listOf("button 0 MEMORY_DELETE true"), send(0x90, 0x3E, 0x7F))
        assertEquals(listOf("button 0 MEMORY_SAVE true"), send(0x90, 0x3D, 0x7F))
    }

    @Test
    fun `pad notes carry their mode and the shift layer`() {
        assertEquals(listOf("pad 0 ${Deck.PAD_HOT_CUE} 0 false true"), send(0x97, 0x00, 0x7F))
        assertEquals(listOf("pad 0 ${Deck.PAD_HOT_CUE} 7 true true"), send(0x98, 0x07, 0x7F))
        assertEquals(listOf("pad 0 ${Deck.PAD_FX1} 2 false true"), send(0x97, 0x12, 0x7F))
        assertEquals(listOf("pad 0 ${Deck.PAD_FX1} 2 false false"), send(0x97, 0x12, 0x00))
        assertEquals(listOf("pad 1 ${Deck.PAD_BEAT_JUMP} 3 false true"), send(0x99, 0x23, 0x7F))
        assertEquals(listOf("pad 1 ${Deck.PAD_FX2} 5 false true"), send(0x99, 0x55, 0x7F))
        assertEquals(listOf("pad 1 ${Deck.PAD_BEAT_LOOP} 7 true true"), send(0x9A, 0x67, 0x7F))
        assertEquals(listOf("pad 0 ${Deck.PAD_SAMPLER} 1 false true"), send(0x97, 0x31, 0x7F))
    }

    @Test
    fun `pad mode buttons`() {
        assertEquals(listOf("mode 0 ${Deck.PAD_HOT_CUE}"), send(0x90, 0x1B, 0x7F))
        assertEquals(listOf("mode 0 ${Deck.PAD_FX1}"), send(0x90, 0x1E, 0x7F))
        assertEquals(listOf("mode 1 ${Deck.PAD_BEAT_JUMP}"), send(0x91, 0x20, 0x7F))
        assertEquals(listOf("mode 1 ${Deck.PAD_FX2}"), send(0x91, 0x6B, 0x7F))
        assertEquals(listOf("mode 0 ${Deck.PAD_BEAT_LOOP}"), send(0x90, 0x6D, 0x7F))
        assertTrue("releasing a mode button does nothing", send(0x90, 0x6D, 0x00).isEmpty())
    }

    @Test
    fun `faders and knobs are 14-bit and kept apart per deck`() {
        assertEquals(listOf("knob 1 VOLUME 0.992"), send(0xB1, 0x13, 0x7F))
        assertEquals(listOf("knob 1 VOLUME 1.000"), send(0xB1, 0x33, 0x7F))
        // Deck 1 must not inherit deck 2's LSB.
        assertEquals(listOf("knob 0 VOLUME 0.000"), send(0xB0, 0x13, 0x00))
        assertEquals(listOf("knob 0 TEMPO 0.500"), send(0xB0, 0x00, 0x40))
        assertEquals(
            listOf("knob 0 EQ_HIGH 0.500", "knob 0 EQ_HIGH 0.504"),
            send(0xB0, 0x07, 0x40, 0x27, 0x40)
        )
        assertEquals(listOf("knob 0 EQ_MID 0.500"), send(0xB0, 0x0B, 0x40))
        assertEquals(listOf("knob 0 EQ_LOW 0.500"), send(0xB0, 0x0F, 0x40))
    }

    @Test
    fun `a position repeated by the keep-alive answer is reported once`() {
        assertEquals(listOf("knob -1 CROSSFADER 0.500"), send(0xB6, 0x1F, 0x40, 0x3F, 0x00))
        assertTrue(send(0xB6, 0x1F, 0x40, 0x3F, 0x00).isEmpty())
        assertEquals(listOf("knob -1 FX_DEPTH 0.504"), send(0xB4, 0x02, 0x40))
        assertTrue(send(0xB4, 0x02, 0x40).isEmpty())
        assertEquals(listOf("button 0 FX_CHANNEL true"), send(0x94, 0x10, 0x7F))
        assertTrue(send(0x94, 0x10, 0x7F).isEmpty())
        assertEquals(listOf("knob -1 CROSSFADER 0.508"), send(0xB6, 0x1F, 0x41))

        decoder.reset() // reconnection: the next answer applies again
        assertEquals(listOf("knob -1 FX_DEPTH 0.504"), send(0xB4, 0x02, 0x40))
    }

    @Test
    fun `an LSB before any MSB is ignored`() {
        assertTrue(send(0xB0, 0x24, 0x10).isEmpty())
        assertEquals(listOf("knob 0 TRIM 0.501"), send(0xB0, 0x04, 0x40))
    }

    @Test
    fun `mixer section`() {
        assertEquals(listOf("knob -1 CROSSFADER 0.000"), send(0xB6, 0x1F, 0x00))
        assertEquals(listOf("knob 0 FILTER 0.992"), send(0xB6, 0x17, 0x7F))
        assertEquals(listOf("knob 1 FILTER 0.992"), send(0xB6, 0x18, 0x7F))
        assertEquals(listOf("knob -1 HEADPHONE_MIX 0.500"), send(0xB6, 0x0C, 0x40))
        assertEquals(listOf("knob -1 HEADPHONE_LEVEL 0.500"), send(0xB6, 0x0D, 0x40))
        assertEquals(listOf("knob -1 MASTER_LEVEL 0.500"), send(0xB6, 0x08, 0x40))
        assertEquals(listOf("button -1 SMART_CFX true"), send(0x96, 0x00, 0x7F))
        assertEquals(listOf("button -1 SMART_FADER true"), send(0x96, 0x01, 0x7F))
        assertEquals(listOf("button -1 MASTER_CUE true"), send(0x96, 0x63, 0x7F))
    }

    @Test
    fun `browser and load`() {
        assertEquals(listOf("browse 1 false"), send(0xB6, 0x40, 0x01))
        assertEquals(listOf("browse -1 false"), send(0xB6, 0x40, 0x7F))
        assertEquals(listOf("browse 2 true"), send(0xB6, 0x64, 0x02))
        assertEquals(listOf("button -1 BROWSE_PRESS true"), send(0x96, 0x41, 0x7F))
        assertEquals(listOf("button -1 BROWSE_SHIFT_PRESS true"), send(0x96, 0x42, 0x7F))
        assertEquals(listOf("button 0 LOAD true"), send(0x96, 0x46, 0x7F))
        assertEquals(listOf("button 1 LOAD true"), send(0x96, 0x47, 0x7F))
        assertEquals(listOf("button 0 SHIFT_LOAD true"), send(0x96, 0x68, 0x7F))
        assertEquals(listOf("button 1 SHIFT_LOAD true"), send(0x96, 0x7A, 0x7F))
    }

    @Test
    fun `jog wheel`() {
        assertEquals(listOf("button 0 JOG_TOUCH true"), send(0x90, 0x36, 0x7F))
        assertEquals("touched with SHIFT", listOf("button 1 JOG_TOUCH true"), send(0x91, 0x67, 0x7F))
        assertEquals(listOf("jog 0 SCRATCH 1"), send(0xB0, 0x22, 0x41))
        assertEquals(listOf("jog 0 SCRATCH -3"), send(0xB0, 0x22, 0x3D))
        assertEquals(listOf("jog 1 BEND -1"), send(0xB1, 0x21, 0x3F))
        assertEquals(listOf("jog 1 BEND 2"), send(0xB1, 0x23, 0x42))
        assertEquals(listOf("jog 0 SEARCH 5"), send(0xB0, 0x29, 0x45))
        assertTrue("0x40 means no movement", send(0xB0, 0x22, 0x40).isEmpty())
    }

    @Test
    fun `beat fx section`() {
        assertEquals(listOf("button -1 FX_ON true"), send(0x94, 0x47, 0x7F))
        assertEquals(listOf("button -1 FX_ON true"), send(0x95, 0x47, 0x7F))
        assertEquals(listOf("button -1 FX_CLEAR true"), send(0x94, 0x43, 0x7F))
        assertEquals(listOf("button -1 FX_SELECT true"), send(0x94, 0x63, 0x7F))
        assertEquals(listOf("button -1 FX_SELECT_PREV true"), send(0x94, 0x64, 0x7F))
        assertEquals(listOf("button -1 FX_BEAT_LEFT true"), send(0x94, 0x4A, 0x7F))
        assertEquals(listOf("button -1 FX_BEAT_RIGHT true"), send(0x94, 0x4B, 0x7F))
        assertEquals(listOf("button 0 FX_CHANNEL true"), send(0x94, 0x10, 0x7F))
        assertEquals(listOf("button 1 FX_CHANNEL false"), send(0x95, 0x11, 0x00))
        assertEquals(listOf("knob -1 FX_DEPTH 1.000"), send(0xB4, 0x02, 0x7F))
    }

    @Test
    fun `unknown messages and sysex are ignored`() {
        assertTrue(send(0xF0, 0x00, 0x40, 0x05, 0x0B, 0x7F, 0xF7).isEmpty())
        assertTrue(send(0x9C, 0x0B, 0x7F).isEmpty())
        assertTrue(send(0xB0, 0x7B, 0x00).isEmpty())
        assertEquals(listOf("button 0 PLAY true"), send(0x90, 0x0B, 0x7F))
    }
}
