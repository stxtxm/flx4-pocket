# DDJ-FLX4 MIDI mapping

The app uses the DDJ-FLX4's native USB MIDI map. Addresses come from Pioneer
DJ's **DDJ-FLX4 MIDI message list** and the **Mixxx Pioneer-DDJ-FLX4** mapping;
the Smart CFX, master cue, master / headphone level and Pad FX pad addresses,
which Mixxx does not use, come from the official list as documented (with
hardware captures) by the pajoniiir project. `Flx4DecoderTest` asserts every
row marked "yes" below.

Channels are shown 1-based (the code stores them 0-based). Buttons are Note On
with velocity `0x7F` for press and `0x00` for release. Faders and knobs are
14-bit: MSB on controller `n`, LSB on `n + 0x20`, value = `(MSB << 7) | LSB`.

Status bytes: deck 1 `0x90`/`0xB0`, deck 2 `0x91`/`0xB1`, Beat FX
`0x94`/`0x95`/`0xB4`, mixer and browser `0x96`/`0xB6`, deck 1 pads `0x97`
(SHIFT layer `0x98`), deck 2 pads `0x99` (SHIFT layer `0x9A`).

## Deck (channel 1 / 2)

| Control | Type | Address | App behaviour | Mapped |
| --- | --- | --- | --- | --- |
| PLAY/PAUSE | Note | `0x0B` | Play / pause; empty deck: open the library for it | yes |
| SHIFT + PLAY/PAUSE | Note | `0x0E` | — | no |
| CUE | Note | `0x0C` | CDJ cue: set / preview while held / return and stop | yes |
| SHIFT + CUE | Note | `0x48` | Jump to track start | yes |
| SHIFT | Note | `0x3F` | Modifier only: shifted controls send their own notes | yes |
| BEAT SYNC | Note | `0x58` | Sync tempo and phase to the other deck, or release | yes |
| BEAT SYNC long press | Note | `0x5C` | Make this deck the sync master | yes |
| SHIFT + BEAT SYNC | Note | `0x60` | Cycle tempo range ±6 / ±10 / ±16 % / WIDE | yes |
| IN / 4 BEAT | Note | `0x10` | Loop in; held for 0.5 s: 4-beat loop from that point | yes |
| OUT | Note | `0x11` | Loop out, loop starts | yes |
| RELOOP/EXIT | Note | `0x4D` | Exit the loop / re-enter the last one | yes |
| SHIFT + RELOOP/EXIT | Note | `0x50` | Exit the loop and pause | yes |
| SHIFT + IN / OUT | Note | `0x4C` / `0x4E` | Loop point adjust with the jog | no |
| CUE/LOOP CALL ◄ / ► | Note | `0x51` / `0x53` | Halve / double the active loop | yes |
| SHIFT + CUE/LOOP CALL ◄ / ► | Note | `0x3E` / `0x3D` | Jump 16 beats back / forward | yes |
| Headphones CUE | Note | `0x54` | Deck in the headphone cue bus | yes |
| SHIFT + headphones CUE | Note | `0x68` | Toggle quantize | yes |
| Jog touch (also with SHIFT) | Note | `0x36` (`0x67`) | Scratch mode while touched | yes |
| Jog platter, touched | CC | `0x22` | Scratch: relative, `0x40` = still, 720 ticks per turn | yes |
| Jog side / vinyl off | CC | `0x21` / `0x23` | Pitch bend; fine positioning when paused | yes |
| SHIFT + jog | CC | `0x29` | Search, 0.2 s per tick | yes |
| TEMPO | CC | `0x00` + `0x20` | Pitch fader, with soft takeover (it drives the tempo once it reaches the deck's playing speed). Value 0 is the "+" end (fastest) | yes |
| TRIM | CC | `0x04` + `0x24` | Trim, unity at centre | yes |
| EQ HI / MID / LOW | CC | `0x07` / `0x0B` / `0x0F` (+ `0x20`) | ±24 dB shelf / peak / shelf | yes |
| Channel fader | CC | `0x13` + `0x33` | Channel volume | yes |

## Pad modes (channel 1 / 2) and pads (channel 8 / 10, SHIFT layer 9 / 11)

Pad notes are `mode base + pad (0..7)`: the high nibble of a pad note is its
mode. The app decodes pads from the note itself, so they act correctly even
if a mode-button message was missed.

| Mode | Mode button | Pad notes | App behaviour | Mapped |
| --- | --- | --- | --- | --- |
| HOT CUE | `0x1B` | `0x00`–`0x07` | Set / jump; SHIFT + pad deletes | yes |
| PAD FX 1 | `0x1E` | `0x10`–`0x17` | Hold: echo 1/4, 1/2, 3/4, 1; slip roll 1/8, 1/4, 1/2, 1 | yes |
| BEAT JUMP | `0x20` | `0x20`–`0x27` | −1, +1, −2, +2, −4, +4, −8, +8 beats | yes |
| SAMPLER | `0x22` | `0x30`–`0x37` | Not supported (message shown) | decoded |
| KEYBOARD (SHIFT + HOT CUE) | `0x69` | `0x40`–`0x47` | Not supported | decoded |
| PAD FX 2 (SHIFT + PAD FX 1) | `0x6B` | `0x50`–`0x57` | Hold: low-cut echo 1/2, 1; reverb; flanger; phaser; trans 1/8, 1/4, 1/2 | yes |
| BEAT LOOP (SHIFT + BEAT JUMP) | `0x6D` | `0x60`–`0x67` | 1/4, 1/2, 1, 2, 4, 8, 16, 32 beats; same pad again exits | yes |
| KEY SHIFT (SHIFT + SAMPLER) | `0x6F` | `0x70`–`0x77` | Not supported | decoded |

## Mixer and browser (channel 7)

| Control | Type | Address | App behaviour | Mapped |
| --- | --- | --- | --- | --- |
| Crossfader | CC | `0x1F` + `0x3F` | Equal-power crossfade | yes |
| CFX (filter) CH1 / CH2 | CC | `0x17` / `0x18` (+ `0x20`) | Low-pass left, high-pass right | yes |
| SMART CFX | Note | `0x00` | Resonant filter + synced echo on both CFX knobs | yes |
| SMART FADER | Note | `0x01` | Not supported (message shown) | decoded |
| MASTER LEVEL | CC | `0x08` + `0x28` | Master gain | yes |
| HEADPHONES MIXING | CC | `0x0C` + `0x2C` | Cue ↔ master blend in the headphones | yes |
| HEADPHONES LEVEL | CC | `0x0D` + `0x2D` | Headphones gain | yes |
| MASTER CUE | Note | `0x63` | Master into the cue bus | yes |
| BROWSE turn | CC | `0x40` | Move the library selection; `0x01` = +1, `0x7F` = −1. With the library closed, the first click only opens it | yes |
| SHIFT + BROWSE turn | CC | `0x64` | Move ten tracks at a time | yes |
| BROWSE press (also with SHIFT) | Note | `0x41` (`0x42`) | Open / close the library | yes |
| LOAD 1 / 2 | Note | `0x46` / `0x47` | Load the selected track and close the library; with the library closed, open it for that deck instead | yes |

The headphone controls act on the app's headphone bus, which is only audible
on a four-channel USB output or in split-cue mode.

## Beat FX (channel 5; ON/OFF and CH2 select also on channel 6)

| Control | Type | Address | App behaviour | Mapped |
| --- | --- | --- | --- | --- |
| BEAT FX SELECT | Note | `0x63` | Next effect | yes |
| SHIFT + BEAT FX SELECT | Note | `0x64` | Previous effect | yes |
| BEAT ◄ / ► | Note | `0x4A` / `0x4B` | Effect time 1/8 … 8 beats | yes |
| CH SELECT 1 / 2 | Note | ch 5 `0x10` / ch 6 `0x11` | Value = switch state; both on = master | yes |
| LEVEL/DEPTH | CC | ch 5 `0x02` | Effect depth (7-bit) | yes |
| ON/OFF | Note | `0x47` | Toggle the effect | yes |
| SHIFT + ON/OFF | Note | `0x43` | Effect off | yes |

Effects in selection order: ECHO, LC ECHO, REVERB, FLANGER, PHASER, TRANS.

## Output: LEDs and meters

Note On to the same address as the control, `0x7F` on and `0x00` off.

| LED | Address | Lit when |
| --- | --- | --- |
| PLAY | ch 1/2 `0x0B` | Deck playing |
| CUE | ch 1/2 `0x0C` | Track loaded and paused |
| BEAT SYNC | ch 1/2 `0x58` | Sync engaged |
| Headphones CUE | ch 1/2 `0x54` | Deck in the cue bus |
| IN / OUT | ch 1/2 `0x10` / `0x11` | Loop-in set / loop active |
| Pad mode buttons | ch 1/2 `0x1B` `0x1E` `0x20` `0x6B` `0x6D` | Mode selected |
| Hot-cue pads | pad ch `0x00`–`0x07` | Cue stored |
| Pad FX pads | pad ch `0x10`–`0x17`, `0x50`–`0x57` | Pad held |
| Beat-jump pads | pad ch `0x20`–`0x27` | Mode selected |
| Beat-loop pads | pad ch `0x60`–`0x67` | That loop length is active |
| BEAT FX ON/OFF | ch 5 `0x47` | Blinks on `0x7F` (effect on), steady on `0x00` |
| SMART CFX | ch 7 `0x00` | Smart CFX on |
| MASTER CUE | ch 7 `0x63` | Master cue on |
| Channel level meter | CC ch 1/2 `0x02` | Post-fader level, 0–127 |
| Jog "track loaded" | ch 16 note `0x00` / `0x01` | A track is loaded; `0x00` then `0x7F` on each load replays the jog animation |

Pad LEDs are also written to the SHIFT layer so they stay lit while SHIFT is
held. Sent values are cached and only changes are transmitted.

## Keep-alive and control positions

Every 200 ms while connected the app sends
`F0 00 40 05 00 00 04 05 00 50 02 F7`, the keep-alive the Mixxx mapping sends
at the same rate (captured from DJ software with Wireshark). Mixxx also relies
on the controller answering it with the position of its faders and knobs, so
the app's mixer matches the hardware from the moment it is plugged in. `Flx4Decoder` reports a control only
when its position changes: a repeated answer can never undo a change made on
the touch screen. The decoder is reset on each connection, so the first answer
always applies. Not yet verified on hardware.

## Changed in 0.2.0

- **Tempo fader direction** now follows Mixxx (`rate = 1 − value / 8192`).
  0.1.x had it the other way round.
- **Browse knob** is decoded as a two's-complement step (`0x01` / `0x7F`)
  instead of an offset from 64, which scrolled backwards.
- 14-bit halves are stored per channel; before, deck 1 and deck 2 shared them.

## Sources

- Pioneer DJ DDJ-FLX4 product page and manual:
  <https://www.pioneerdj.com/product/dj-controllers/ddj-flx4>
- DDJ-FLX4 MIDI message list and capture notes:
  <https://github.com/magtomm/pajoniiir/blob/master/docs/DDJ_FLX4_MIDI_MAP.md>
- Mixxx mapping and script:
  <https://github.com/mixxxdj/mixxx/blob/main/res/controllers/Pioneer-DDJ-FLX4.midi.xml>,
  <https://github.com/mixxxdj/mixxx/blob/main/res/controllers/Pioneer-DDJ-FLX4-script.js>

If a firmware changes an address, update `MidiMap.kt`, `Flx4DecoderTest.kt`
and this table together.
