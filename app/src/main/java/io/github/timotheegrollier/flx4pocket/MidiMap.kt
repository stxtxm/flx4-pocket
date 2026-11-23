package io.github.timotheegrollier.flx4pocket

/**
 * DDJ-FLX4 MIDI addresses.
 *
 * Source of truth: Pioneer DJ's "DDJ-FLX4 MIDI message list" and the Mixxx
 * Pioneer-DDJ-FLX4 mapping, which is the most widely exercised FLX4 map. The
 * app follows those addresses exactly; see docs/DDJ-FLX4-MIDI.md for the full
 * table and for features that are not implemented yet (Beat FX, stems, sampler).
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

    // Transport and deck notes (channel 0 / 1).
    const val NOTE_PLAY = 0x0B
    const val NOTE_CUE = 0x0C
    const val NOTE_SHIFT = 0x3F
    const val NOTE_SYNC = 0x58
    const val NOTE_SYNC_LONG = 0x5C
    const val NOTE_TEMPO_RANGE = 0x60
    const val NOTE_LOOP_IN = 0x10
    const val NOTE_LOOP_OUT = 0x11
    const val NOTE_RELOOP = 0x4D
    const val NOTE_CUE_CHANNEL = 0x54
    const val NOTE_QUANTIZE = 0x68
    const val NOTE_JOG_TOUCH = 0x36
    const val NOTE_JOG_SHIFT_TOUCH = 0x67

    // Pad mode buttons (channel 0 / 1).
    const val NOTE_HOT_CUE_MODE = 0x1B
    const val NOTE_BEAT_LOOP_MODE = 0x6D
    const val NOTE_BEAT_JUMP_MODE = 0x20
    const val NOTE_KEYBOARD_MODE = 0x69
    const val NOTE_PAD_FX1_MODE = 0x1E
    const val NOTE_PAD_FX2_MODE = 0x6B
    const val NOTE_SAMPLER_MODE = 0x22
    const val NOTE_KEY_SHIFT_MODE = 0x6F

    // Mixer and browser notes (channel 6).
    const val NOTE_BROWSE_PRESS = 0x41
    const val NOTE_BROWSE_SHIFT_PRESS = 0x42
    const val NOTE_LOAD_A = 0x46
    const val NOTE_LOAD_B = 0x47

    // Control changes (deck channels 0 / 1).
    const val CC_TEMPO_MSB = 0x00
    const val CC_TEMPO_LSB = 0x20
    const val CC_TRIM_MSB = 0x04
    const val CC_TRIM_LSB = 0x24
    const val CC_EQ_HIGH_MSB = 0x07
    const val CC_EQ_HIGH_LSB = 0x27
    const val CC_EQ_MID_MSB = 0x0B
    const val CC_EQ_MID_LSB = 0x2B
    const val CC_EQ_LOW_MSB = 0x0F
    const val CC_EQ_LOW_LSB = 0x2F
    const val CC_VOLUME_MSB = 0x13
    const val CC_VOLUME_LSB = 0x33
    const val CC_JOG_SIDE = 0x21
    const val CC_JOG_VINYL = 0x22
    const val CC_JOG_BEND = 0x23
    const val CC_JOG_SHIFT = 0x29

    // Control changes (mixer channel 6).
    const val CC_HEADPHONE_MIX_MSB = 0x0C
    const val CC_HEADPHONE_MIX_LSB = 0x2C
    const val CC_FILTER_A_MSB = 0x17
    const val CC_FILTER_A_LSB = 0x37
    const val CC_FILTER_B_MSB = 0x18
    const val CC_FILTER_B_LSB = 0x38
    const val CC_CROSSFADER_MSB = 0x1F
    const val CC_CROSSFADER_LSB = 0x3F
    const val CC_BROWSE = 0x40
}
