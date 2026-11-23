# DDJ-FLX4 MIDI mapping

This app uses the DDJ-FLX4's native USB MIDI map. The addresses below are
cross-checked against Pioneer DJ's **DDJ-FLX4 MIDI message list** and the
**Mixxx Pioneer-DDJ-FLX4** mapping/script, the most widely exercised public FLX4
map. They are the same addresses rekordbox and djay use for the controller's MIDI
surface.

Channels are shown as MIDI channels 1-based (the app stores them 0-based).
Buttons are Note On with velocity `0x7F` for press and `0x00` for release.
Knobs and faders are 14-bit where the hardware sends MSB + LSB: value =
`(MSB << 7) | LSB`.

Status bytes:

- Deck 1 buttons `0x90`, Deck 1 CC `0xB0`
- Deck 2 buttons `0x91`, Deck 2 CC `0xB1`
- Mixer and browser buttons `0x96`, mixer CC `0xB6`
- Deck 1 pads `0x97`, shift layer `0x98`
- Deck 2 pads `0x99`, shift layer `0x9A`
- Beat FX `0x94` / `0x95`

## Transport and deck controls

| Control | Message | Address | App behaviour |
| --- | --- | --- | --- |
| PLAY/PAUSE | Note | ch 1/2, `0x0B` | Play or pause the deck |
| CUE | Note | ch 1/2, `0x0C` | Paused: set cue. Playing: jump to cue and stop |
| SHIFT | Note | ch 1/2, `0x3F` | Shift state (used by pads and quantize) |
| BEAT SYNC | Note | ch 1/2, `0x58` | Match BPM and beat phase to the other deck |
| BEAT SYNC long press | Note | ch 1/2, `0x5C` | Set this deck as sync master |
| BEAT SYNC + SHIFT | Note | ch 1/2, `0x60` | Cycle tempo range 6/8/16/25% |
| LOOP IN | Note | ch 1/2, `0x10` | Set manual loop in |
| LOOP OUT | Note | ch 1/2, `0x11` | Set manual loop out |
| RELOOP/EXIT | Note | ch 1/2, `0x4D` | Toggle reloop |
| Headphones CUE (channel) | Note | ch 1/2, `0x54` | Toggle deck in the cue bus |
| SHIFT + CUE | Note | ch 1/2, `0x68` | Toggle cue quantize |
| Jog touch | Note | ch 1/2, `0x36` | Observed, no action (see limitations) |
| SHIFT + jog touch | Note | ch 1/2, `0x67` | Observed, no action |
| TEMPO fader | CC | ch 1/2, `0x00` MSB / `0x20` LSB | 14-bit pitch fader, centre 8192 |
| TRIM | CC | ch 1/2, `0x04` / `0x24` | Input trim |
| EQ HI | CC | ch 1/2, `0x07` / `0x27` | High shelf, ±24 dB |
| EQ MID | CC | ch 1/2, `0x0B` / `0x2B` | Mid peaking, ±24 dB |
| EQ LOW | CC | ch 1/2, `0x0F` / `0x2F` | Low shelf, ±24 dB |
| Channel fader | CC | ch 1/2, `0x13` / `0x33` | Channel volume |
| Jog side / vinyl on / vinyl off | CC | ch 1/2, `0x21` / `0x22` / `0x23` | Relative turn (± around 64), seek |
| SHIFT + jog | CC | ch 1/2, `0x29` | Relative turn, fast seek |

## Mixer

| Control | Message | Address | App behaviour |
| --- | --- | --- | --- |
| Crossfader | CC | ch 7, `0x1F` / `0x3F` | Equal-power A/B crossfade |
| Headphone mixing | CC | ch 7, `0x0C` / `0x2C` | Cue/master blend in split-cue |
| FILTER CH1 | CC | ch 7, `0x17` / `0x37` | Deck A filter, LPF left / HPF right |
| FILTER CH2 | CC | ch 7, `0x18` / `0x38` | Deck B filter |
| MASTER LEVEL | analog | — | Not a MIDI control; use the phone UI |
| HEADPHONES LEVEL | analog | — | Not a MIDI control; use split-cue level |

## Browser and load

| Control | Message | Address | App behaviour |
| --- | --- | --- | --- |
| BROWSE rotate | CC | ch 7, `0x40` | Relative selection |
| BROWSE press | Note | ch 7, `0x41` | Open/close library |
| SHIFT + BROWSE press | Note | ch 7, `0x42` | Same as BROWSE press |
| LOAD Deck 1 | Note | ch 7, `0x46` | Load selected track into A |
| LOAD Deck 2 | Note | ch 7, `0x47` | Load selected track into B |

## Performance pads

Pad modes are selected with the physical mode buttons, and the app keeps the
same mode:

| Mode | Button address | Pads | App behaviour |
| --- | --- | --- | --- |
| HOT CUE | ch 1/2, `0x1B` | ch 8/10, notes `0x00`–`0x07` | Trigger/set hot cue; SHIFT pad deletes |
| BEAT LOOP | ch 1/2, `0x6D` | ch 8/10, notes `0x60`–`0x67` | Loop 1/4, 1/2, 1, 2, 4, 8, 16, 32 beats |
| BEAT JUMP | ch 1/2, `0x20` | ch 8/10, notes `0x20`–`0x27` | Jump −1, +1, −2, +2, −4, +4, −8, +8 beats |
| SAMPLER | ch 1/2, `0x22` | ch 8/10, notes `0x30`–`0x37` | Not implemented (notes ignored) |
| KEYBOARD/STEMS | ch 1/2, `0x69` | ch 8/10, notes `0x40`–`0x47` | Not implemented (notes ignored) |
| PAD FX 1 / 2 | ch 1/2, `0x1E` / `0x6B` | — | Not implemented |
| KEY SHIFT | ch 1/2, `0x6F` | ch 8/10, notes `0x70`–`0x77` | Not implemented |

Note that the pad mode button addresses are on the deck channels, while the pad
notes arrive on the pad channels (7/9) and the SHIFT pad layer (8/10). The app
uses the mode it last saw so the same physical pad does the right thing.

## LEDs (MIDI out)

LEDs are Note On messages on the deck channel or pad channel, velocity `0x7F`
for on and `0x00` for off.

| LED | Address |
| --- | --- |
| PLAY | ch 1/2, note `0x0B` |
| CUE | ch 1/2, note `0x0C` |
| BEAT SYNC | ch 1/2, note `0x58` |
| Headphones CUE (channel) | ch 1/2, note `0x54` |
| Hot-cue pads | ch 8/10, notes `0x00`–`0x07` |
| Beat-loop pads | ch 8/10, notes `0x60`–`0x67` |

The app caches LED state and only sends changes, so the 120 ms UI refresh does
not flood the MIDI port. Pad-mode button LEDs and Beat FX LEDs are not driven
yet.

## Not mapped yet

These addresses are part of the official map, and the app deliberately leaves
them unused because the corresponding audio features do not exist:

- Beat FX section: FX select (`0x63`/`0x64`), Beat left/right (`0x4A`/`0x4B`),
  channel select (`0x10`/`0x11`), level/depth (`0xB4` `0x02`), on/off
  (`0x94`/`0x95` note `0x47`).
- Sampler, keyboard/stems, pad FX and key-shift pads.
- CUE/LOOP CALL left/right (`0x51`/`0x53`) and their SHIFT variants.
- SHIFT + LOOP IN/OUT loop-point adjustment (`0x4C`/`0x4E`).
- Beat-jump size adjustment with SHIFT + pads (`0x98`/`0x9A` notes `0x26`/`0x27`).
- VU meter output (`0x02`).

## Sources

- Pioneer DJ DDJ-FLX4 product and instruction manual:
  <https://www.pioneerdj.com/product/dj-controllers/ddj-flx4>
- Pioneer DDJ-FLX4 MIDI message list (mirror used for cross-checking):
  <https://github.com/magtomm/pajoniiir/blob/master/docs/reference/DDJ-FLX4_MIDI_message_List.md>
- Mixxx DDJ-FLX4 mapping:
  <https://github.com/mixxxdj/mixxx/blob/main/res/controllers/Pioneer-DDJ-FLX4.midi.xml>
- Mixxx DDJ-FLX4 script:
  <https://github.com/mixxxdj/mixxx/blob/main/res/controllers/Pioneer-DDJ-FLX4-script.js>

If firmware ever changes an address, use a MIDI monitor on the device and
update both `MidiMap.kt` and this table together.
