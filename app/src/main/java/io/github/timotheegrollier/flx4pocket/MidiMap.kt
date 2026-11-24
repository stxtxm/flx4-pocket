package io.github.timotheegrollier.flx4pocket

/**
 * DDJ-FLX4 MIDI addresses.
 *
 * Source of truth: Pioneer DJ's "DDJ-FLX4 MIDI message list" and the Mixxx
 * Pioneer-DDJ-FLX4 mapping. See docs/DDJ-FLX4-MIDI.md for the full table.
 *
 * Channels are zero-based here: MIDI channel 1 is 0, channel 7 is 6, etc.
 */
object MidiMap {

    const val CH_DECK_A = 0
    const val CH_DECK_B = 1
    const val CH_FX = 4
    const val CH_FX_2 = 5
    const val CH_MIXER = 6
    const val CH_PAD_A = 7
    const val CH_PAD_SHIFT_A = 8
    const val CH_PAD_B = 9
    const val CH_PAD_SHIFT_B = 10
    const val CH_LOADED = 15

    // Transport and deck notes (channel 0 / 1).
    const val NOTE_PLAY = 0x0B
    const val NOTE_CUE = 0x0C
    const val NOTE_TO_START = 0x48 // SHIFT + CUE
    const val NOTE_SHIFT = 0x3F
    const val NOTE_SYNC = 0x58
    const val NOTE_SYNC_LONG = 0x5C
    const val NOTE_TEMPO_RANGE = 0x60 // SHIFT + BEAT SYNC
    const val NOTE_LOOP_IN = 0x10
    const val NOTE_LOOP_OUT = 0x11
    const val NOTE_FOUR_BEAT_EXIT = 0x4D // 4 BEAT/EXIT
    const val NOTE_ACTIVE_LOOP = 0x50 // SHIFT + 4 BEAT/EXIT
    const val NOTE_CUE_LOOP_CALL_PREV = 0x51 // CUE/LOOP CALL ◁
    const val NOTE_CUE_LOOP_CALL_NEXT = 0x53 // CUE/LOOP CALL ▷
    const val NOTE_MEMORY_DELETE = 0x3E // SHIFT + CUE/LOOP CALL ◁
    const val NOTE_MEMORY_SAVE = 0x3D // SHIFT + CUE/LOOP CALL ▷
    const val NOTE_CUE_CHANNEL = 0x54
    const val NOTE_TAP = 0x68 // SHIFT + headphones CUE
    const val NOTE_JOG_TOUCH = 0x36
    const val NOTE_JOG_SHIFT_TOUCH = 0x67

    // Pad mode buttons (channel 0 / 1).
    const val NOTE_HOT_CUE_MODE = 0x1B
    const val NOTE_PAD_FX1_MODE = 0x1E
    const val NOTE_BEAT_JUMP_MODE = 0x20
    const val NOTE_SAMPLER_MODE = 0x22
    const val NOTE_KEYBOARD_MODE = 0x69
    const val NOTE_PAD_FX2_MODE = 0x6B
    const val NOTE_BEAT_LOOP_MODE = 0x6D
    const val NOTE_KEY_SHIFT_MODE = 0x6F

    /**
     * Pad notes on the pad channels are `mode base + pad index (0..7)`, so the
     * high nibble of a pad note identifies its mode on its own.
     */
    const val PAD_BASE_HOT_CUE = 0x00
    const val PAD_BASE_FX1 = 0x10
    const val PAD_BASE_BEAT_JUMP = 0x20
    const val PAD_BASE_SAMPLER = 0x30
    const val PAD_BASE_KEYBOARD = 0x40
    const val PAD_BASE_FX2 = 0x50
    const val PAD_BASE_BEAT_LOOP = 0x60
    const val PAD_BASE_KEY_SHIFT = 0x70

    // Mixer and browser notes (channel 6).
    const val NOTE_SMART_CFX = 0x00
    const val NOTE_SMART_FADER = 0x01
    const val NOTE_BROWSE_PRESS = 0x41
    const val NOTE_BROWSE_SHIFT_PRESS = 0x42
    const val NOTE_LOAD_A = 0x46
    const val NOTE_LOAD_B = 0x47
    const val NOTE_SHIFT_LOAD_A = 0x68
    const val NOTE_SHIFT_LOAD_B = 0x7A
    const val NOTE_MASTER_CUE = 0x63

    // Beat FX notes (channel 4; ON/OFF and CH2 select also arrive on channel 5).
    const val NOTE_FX_CH1 = 0x10
    const val NOTE_FX_CH2 = 0x11
    const val NOTE_FX_CLEAR = 0x43 // SHIFT + ON/OFF
    const val NOTE_FX_ON = 0x47
    const val NOTE_FX_BEAT_LEFT = 0x4A
    const val NOTE_FX_BEAT_RIGHT = 0x4B
    const val NOTE_FX_SELECT = 0x63
    const val NOTE_FX_SELECT_SHIFT = 0x64

    // Control changes (deck channels 0 / 1). MSB at n, LSB at n + 0x20.
    const val CC_TEMPO = 0x00
    const val CC_LEVEL_METER = 0x02 // output only
    const val CC_TRIM = 0x04
    const val CC_EQ_HIGH = 0x07
    const val CC_EQ_MID = 0x0B
    const val CC_EQ_LOW = 0x0F
    const val CC_VOLUME = 0x13
    const val CC_JOG_SIDE = 0x21
    const val CC_JOG_VINYL = 0x22
    const val CC_JOG_BEND = 0x23
    const val CC_JOG_SHIFT = 0x29

    // Control changes (mixer channel 6).
    const val CC_MASTER_LEVEL = 0x08
    const val CC_HEADPHONE_MIX = 0x0C
    const val CC_HEADPHONE_LEVEL = 0x0D
    const val CC_FILTER_A = 0x17
    const val CC_FILTER_B = 0x18
    const val CC_CROSSFADER = 0x1F
    const val CC_BROWSE = 0x40
    const val CC_BROWSE_SHIFT = 0x64

    // Control changes (Beat FX channel 4).
    const val CC_FX_DEPTH = 0x02

    const val LSB_OFFSET = 0x20
}
