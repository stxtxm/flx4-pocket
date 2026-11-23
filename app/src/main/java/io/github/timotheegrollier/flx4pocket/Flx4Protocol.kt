package io.github.timotheegrollier.flx4pocket

/** Buttons of the DDJ-FLX4. Global ones are reported with deck `-1`. */
enum class Flx4Button {
    PLAY, CUE, TO_START, SHIFT, SYNC, MASTER, TEMPO_RANGE,
    LOOP_IN, LOOP_OUT, RELOOP, RELOOP_STOP, LOOP_HALVE, LOOP_DOUBLE,
    JUMP_BACK, JUMP_FORWARD, PFL, QUANTIZE, JOG_TOUCH, LOAD,

    /** Beat FX channel selector: `deck` is the channel, `down` its state. */
    FX_CHANNEL,

    // Global.
    BROWSE_PRESS, SMART_CFX, SMART_FADER, MASTER_CUE,
    FX_SELECT, FX_SELECT_PREV, FX_BEAT_LEFT, FX_BEAT_RIGHT, FX_ON, FX_CLEAR
}

/** Absolute controls, delivered as 0..1. Global ones use deck `-1`. */
enum class Flx4Knob {
    TEMPO, TRIM, EQ_HIGH, EQ_MID, EQ_LOW, VOLUME, FILTER,
    CROSSFADER, HEADPHONE_MIX, HEADPHONE_LEVEL, MASTER_LEVEL, FX_DEPTH
}

enum class Flx4Jog { SCRATCH, BEND, SEARCH }

interface Flx4Listener {
    fun onMidiStatus(name: String?) {}
    fun onButton(deck: Int, button: Flx4Button, down: Boolean) {}
    fun onKnob(deck: Int, knob: Flx4Knob, value: Float) {}
    fun onJog(deck: Int, jog: Flx4Jog, delta: Int) {}
    fun onPadMode(deck: Int, mode: Int) {}
    fun onPad(deck: Int, mode: Int, pad: Int, shift: Boolean, down: Boolean) {}
    fun onBrowse(delta: Int, shift: Boolean) {}
}

/**
 * Turns the FLX4's raw MIDI byte stream into [Flx4Listener] calls.
 *
 * Android-free so the official address table is unit tested on the JVM; the
 * USB transport lives in [Flx4Midi]. Not thread-safe: feed it from one thread.
 */
class Flx4Decoder(private val listener: Flx4Listener) {

    // 14-bit controller halves, keyed by channel and MSB controller number.
    private val msb = IntArray(16 * 32) { -1 }
    private val lsb = IntArray(16 * 32)

    private var status = 0
    private var data1 = -1
    private var inSysex = false

    fun feed(data: ByteArray, offset: Int = 0, count: Int = data.size) {
        val end = (offset + count).coerceAtMost(data.size)
        for (i in offset until end) {
            val byte = data[i].toInt() and 0xFF
            when {
                byte >= 0xF8 -> Unit // real-time bytes may interleave anywhere
                byte == 0xF0 -> inSysex = true
                byte == 0xF7 -> {
                    inSysex = false
                    status = 0
                }
                byte and 0x80 != 0 -> if (!inSysex) {
                    status = byte
                    data1 = -1
                }
                inSysex || status == 0 -> Unit
                data1 < 0 -> data1 = byte
                else -> {
                    message(status, data1, byte)
                    data1 = -1 // running status: keep the status byte
                }
            }
        }
    }

    private fun message(status: Int, d1: Int, d2: Int) {
        val channel = status and 0x0F
        when (status and 0xF0) {
            0x90 -> note(channel, d1, d2 > 0)
            0x80 -> note(channel, d1, false)
            0xB0 -> control(channel, d1, d2)
        }
    }

    private fun note(channel: Int, note: Int, down: Boolean) {
        when (channel) {
            MidiMap.CH_DECK_A, MidiMap.CH_DECK_B -> deckNote(channel, note, down)
            MidiMap.CH_MIXER -> when (note) {
                MidiMap.NOTE_SMART_CFX -> listener.onButton(-1, Flx4Button.SMART_CFX, down)
                MidiMap.NOTE_SMART_FADER -> listener.onButton(-1, Flx4Button.SMART_FADER, down)
                MidiMap.NOTE_MASTER_CUE -> listener.onButton(-1, Flx4Button.MASTER_CUE, down)
                MidiMap.NOTE_BROWSE_PRESS, MidiMap.NOTE_BROWSE_SHIFT_PRESS ->
                    listener.onButton(-1, Flx4Button.BROWSE_PRESS, down)
                MidiMap.NOTE_LOAD_A -> listener.onButton(0, Flx4Button.LOAD, down)
                MidiMap.NOTE_LOAD_B -> listener.onButton(1, Flx4Button.LOAD, down)
            }
            MidiMap.CH_FX, MidiMap.CH_FX_2 -> when (note) {
                MidiMap.NOTE_FX_CH1 -> listener.onButton(0, Flx4Button.FX_CHANNEL, down)
                MidiMap.NOTE_FX_CH2 -> listener.onButton(1, Flx4Button.FX_CHANNEL, down)
                MidiMap.NOTE_FX_ON -> listener.onButton(-1, Flx4Button.FX_ON, down)
                MidiMap.NOTE_FX_CLEAR -> listener.onButton(-1, Flx4Button.FX_CLEAR, down)
                MidiMap.NOTE_FX_BEAT_LEFT -> listener.onButton(-1, Flx4Button.FX_BEAT_LEFT, down)
                MidiMap.NOTE_FX_BEAT_RIGHT -> listener.onButton(-1, Flx4Button.FX_BEAT_RIGHT, down)
                MidiMap.NOTE_FX_SELECT -> listener.onButton(-1, Flx4Button.FX_SELECT, down)
                MidiMap.NOTE_FX_SELECT_SHIFT ->
                    listener.onButton(-1, Flx4Button.FX_SELECT_PREV, down)
            }
            MidiMap.CH_PAD_A -> pad(0, note, false, down)
            MidiMap.CH_PAD_SHIFT_A -> pad(0, note, true, down)
            MidiMap.CH_PAD_B -> pad(1, note, false, down)
            MidiMap.CH_PAD_SHIFT_B -> pad(1, note, true, down)
        }
    }

    private fun deckNote(deck: Int, note: Int, down: Boolean) {
        val button = when (note) {
            MidiMap.NOTE_PLAY -> Flx4Button.PLAY
            MidiMap.NOTE_CUE -> Flx4Button.CUE
            MidiMap.NOTE_TO_START -> Flx4Button.TO_START
            MidiMap.NOTE_SHIFT -> Flx4Button.SHIFT
            MidiMap.NOTE_SYNC -> Flx4Button.SYNC
            MidiMap.NOTE_SYNC_LONG -> Flx4Button.MASTER
            MidiMap.NOTE_TEMPO_RANGE -> Flx4Button.TEMPO_RANGE
            MidiMap.NOTE_LOOP_IN -> Flx4Button.LOOP_IN
            MidiMap.NOTE_LOOP_OUT -> Flx4Button.LOOP_OUT
            MidiMap.NOTE_RELOOP -> Flx4Button.RELOOP
            MidiMap.NOTE_RELOOP_STOP -> Flx4Button.RELOOP_STOP
            MidiMap.NOTE_LOOP_HALVE -> Flx4Button.LOOP_HALVE
            MidiMap.NOTE_LOOP_DOUBLE -> Flx4Button.LOOP_DOUBLE
            MidiMap.NOTE_JUMP_BACK -> Flx4Button.JUMP_BACK
            MidiMap.NOTE_JUMP_FORWARD -> Flx4Button.JUMP_FORWARD
            MidiMap.NOTE_CUE_CHANNEL -> Flx4Button.PFL
            MidiMap.NOTE_QUANTIZE -> Flx4Button.QUANTIZE
            MidiMap.NOTE_JOG_TOUCH, MidiMap.NOTE_JOG_SHIFT_TOUCH -> Flx4Button.JOG_TOUCH
            else -> {
                val mode = when (note) {
                    MidiMap.NOTE_HOT_CUE_MODE -> Deck.PAD_HOT_CUE
                    MidiMap.NOTE_PAD_FX1_MODE -> Deck.PAD_FX1
                    MidiMap.NOTE_BEAT_JUMP_MODE -> Deck.PAD_BEAT_JUMP
                    MidiMap.NOTE_SAMPLER_MODE -> Deck.PAD_SAMPLER
                    MidiMap.NOTE_KEYBOARD_MODE -> Deck.PAD_KEYBOARD
                    MidiMap.NOTE_PAD_FX2_MODE -> Deck.PAD_FX2
                    MidiMap.NOTE_BEAT_LOOP_MODE -> Deck.PAD_BEAT_LOOP
                    MidiMap.NOTE_KEY_SHIFT_MODE -> Deck.PAD_KEY_SHIFT
                    else -> return
                }
                if (down) listener.onPadMode(deck, mode)
                return
            }
        }
        listener.onButton(deck, button, down)
    }

    /** The pad mode is the note's high nibble, so a missed mode button cannot desync it. */
    private fun pad(deck: Int, note: Int, shift: Boolean, down: Boolean) {
        if (note and 0x0F > 7) return
        listener.onPad(deck, note shr 4, note and 0x07, shift, down)
    }

    private fun control(channel: Int, cc: Int, value: Int) {
        when (channel) {
            MidiMap.CH_DECK_A, MidiMap.CH_DECK_B -> when (cc) {
                MidiMap.CC_JOG_VINYL -> jog(channel, Flx4Jog.SCRATCH, value)
                MidiMap.CC_JOG_SIDE, MidiMap.CC_JOG_BEND -> jog(channel, Flx4Jog.BEND, value)
                MidiMap.CC_JOG_SHIFT -> jog(channel, Flx4Jog.SEARCH, value)
                else -> {
                    val knob = when (cc and 0x1F) {
                        MidiMap.CC_TEMPO -> Flx4Knob.TEMPO
                        MidiMap.CC_TRIM -> Flx4Knob.TRIM
                        MidiMap.CC_EQ_HIGH -> Flx4Knob.EQ_HIGH
                        MidiMap.CC_EQ_MID -> Flx4Knob.EQ_MID
                        MidiMap.CC_EQ_LOW -> Flx4Knob.EQ_LOW
                        MidiMap.CC_VOLUME -> Flx4Knob.VOLUME
                        else -> return
                    }
                    knob14(channel, cc, value)?.let { listener.onKnob(channel, knob, it) }
                }
            }

            MidiMap.CH_MIXER -> when (cc) {
                MidiMap.CC_BROWSE -> relative(value).let {
                    if (it != 0) listener.onBrowse(it, false)
                }
                MidiMap.CC_BROWSE_SHIFT -> relative(value).let {
                    if (it != 0) listener.onBrowse(it, true)
                }
                else -> {
                    val (deck, knob) = when (cc and 0x1F) {
                        MidiMap.CC_MASTER_LEVEL -> -1 to Flx4Knob.MASTER_LEVEL
                        MidiMap.CC_HEADPHONE_MIX -> -1 to Flx4Knob.HEADPHONE_MIX
                        MidiMap.CC_HEADPHONE_LEVEL -> -1 to Flx4Knob.HEADPHONE_LEVEL
                        MidiMap.CC_FILTER_A -> 0 to Flx4Knob.FILTER
                        MidiMap.CC_FILTER_B -> 1 to Flx4Knob.FILTER
                        MidiMap.CC_CROSSFADER -> -1 to Flx4Knob.CROSSFADER
                        else -> return
                    }
                    knob14(channel, cc, value)?.let { listener.onKnob(deck, knob, it) }
                }
            }

            MidiMap.CH_FX, MidiMap.CH_FX_2 ->
                if (cc == MidiMap.CC_FX_DEPTH) {
                    listener.onKnob(-1, Flx4Knob.FX_DEPTH, value / 127f)
                }
        }
    }

    private fun jog(deck: Int, kind: Flx4Jog, value: Int) {
        val delta = value - 64 // 0x40 is "no movement", clockwise counts up
        if (delta != 0) listener.onJog(deck, kind, delta)
    }

    /** Browse encoder: two's complement steps, 0x01 = +1 and 0x7F = -1. */
    private fun relative(value: Int): Int = if (value >= 64) value - 128 else value

    /**
     * Combines MSB (cc 0..31) and LSB (cc + 0x20) into 0..1. Returns null until
     * the MSB has been seen, so a stray LSB cannot make a control jump.
     */
    private fun knob14(channel: Int, cc: Int, value: Int): Float? {
        if (cc >= 0x40) return null
        val key = channel * 32 + (cc and 0x1F)
        if (cc >= MidiMap.LSB_OFFSET) lsb[key] = value else msb[key] = value
        if (msb[key] < 0) return null
        return ((msb[key] shl 7) or lsb[key]) / 16383f
    }
}
