# Testing

## Automated

```bash
./gradlew testDebugUnitTest
./gradlew assembleDebug lintDebug
```

All tests run on the JVM; no device or emulator is needed.

| Test class | Covers |
| --- | --- |
| `Flx4DecoderTest` | The FLX4 address table: every mapped button, knob, jog mode and pad range, running status, 14-bit pairs per deck, relative encoders |
| `DeckTest` | Play, tempo ranges, end of track, CDJ cue, beat and manual loops, halve/double, hot cues, beat jump, slip roll, pad FX, scratch, bend, sync, Smart CFX |
| `FxTest` | Bypass when off, echo timing and tail, trans gating, stability of every effect |
| `WaveformTest` | Resolution, band separation, overflow, cache-file round trip |
| `PcmRingTest` | Interpolation, seek reset, eviction, decoder throttling |
| `DspTest` | Biquads, mixer curves, soft limiter |
| `BpmAnalyzerTest`, `BeatGridTest` | Tempo estimation and beat math |
| `MainActivityTest` (Robolectric) | The real layouts inflate, the activity runs its lifecycle, touch controls and controller events reach the engine |

`MainActivityTest` also writes `app/build/screenshots/main.png`, a rendering of
the mixing screen with two synthetic tracks. Open it after changing a layout.

## What the tests cannot show

Robolectric stubs `AudioTrack`, `MediaCodec` and `MidiManager`. Nothing below
has been executed on real hardware for 0.2.0; please check it on a phone, and
with a DDJ-FLX4 where marked.

Audio

1. A loaded track plays, in time, without dropouts; seeking and loops are clean.
2. Scratching on the waveform and long backward drags recover.
3. Two decks stay in sync over several minutes.
4. Effects sound right and do not clip.

Controller (FLX4)

5. The controller powers up from the phone, or needs its power port.
6. MIDI is detected, and again after unplugging and replugging.
7. Direction of every fader and knob; the tempo fader sign and the browse
   knob direction were changed in 0.2.0 to follow the Mixxx mapping.
8. Jog: touch-scratch, side bend, SHIFT search.
9. LEDs: play, cue, sync, channel cue, loop in/out, pad modes, pads, Beat FX,
   Smart CFX, master cue, level meters.
10. Beat FX channel switch (1 / 2 / 1&2) and level/depth knob.

USB audio

11. With the FLX4 connected, the status line shows its name. Note whether it
    says `master 1-2 + phones 3-4` or `stereo`.
12. In four-channel mode: master on the MASTER outputs, cue on PHONES, mix and
    level knobs working.
13. Unplugging the controller pauses both decks.

App

14. First-run folder picker, and the folder still readable after a reboot.
15. In-app update from one signed release to the next.

Record the phone model, Android build and FLX4 firmware in any report.

## Manual smoke test with adb

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n io.github.timotheegrollier.flx4pocket/.MainActivity
adb logcat -s Flx4Midi Flx4Audio AndroidRuntime
```

To see what Android exposes for the controller's sound card:

```bash
adb shell dumpsys audio | grep -i -A6 usb
adb shell dumpsys media.audio_policy | grep -i -B2 -A12 usb
```
