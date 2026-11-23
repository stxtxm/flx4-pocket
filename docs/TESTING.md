# Testing

## Automated

```bash
./gradlew testDebugUnitTest
./gradlew assembleDebug lintDebug
```

All tests run on the JVM; no device or emulator is needed.

| Test class | Covers |
| --- | --- |
| `Flx4DecoderTest` | The FLX4 address table: every mapped button, knob, jog mode and pad range, running status, 14-bit pairs per deck, relative encoders, repeated positions reported once |
| `DeckTest` | Play, tempo ranges, end of track, CDJ cue, beat and manual loops, halve/double, hot cues, beat jump, slip roll, pad FX, scratch, bend, sync (also when the master's tempo moves), keylock pitch and tempo, click-free jumps, Smart CFX |
| `TrackBufferTest` | Whole-track buffer: decoded frames read back, undecoded ones wait, capacity |
| `FxTest` | Bypass when off, echo timing and tail, trans gating, stability of every effect |
| `WaveformTest` | Resolution, band separation, overflow, cache-file round trip |
| `PcmRingTest` | Cubic interpolation accuracy, seek reset, eviction over many capacities, decoder throttling |
| `DspTest` | Biquads, mixer curves, EQ range, master limiter, soft clip |
| `BpmAnalyzerTest`, `BeatGridTest` | Tempo estimation and beat math |
| `MainActivityTest` (Robolectric) | The real layouts inflate, the activity runs its lifecycle, touch controls and controller events reach the engine, an empty deck opens the library for itself, waveforms cannot grab a track while the library is open, tempo fader soft takeover, load lock, KEY |

`MainActivityTest` also writes `app/build/screenshots/main.png`, a rendering of
the mixing screen with two synthetic tracks. Open it after changing a layout.

## What the tests cannot show

Robolectric stubs `AudioTrack`, `MediaCodec` and `MidiManager`. Nothing below
has been executed on real hardware for 0.3.0; please check it on a phone, and
with a DDJ-FLX4 where marked.

Audio

1. A loaded track plays, in time, without dropouts; seeking and loops are clean.
2. Scratching on the waveform and long backward drags recover.
3. Two decks stay in sync over several minutes.
4. Effects sound right and do not clip.
5. KEY on at ±8 %: same pitch, no obvious warble or doubled kicks.
6. A few seconds after loading, hot cues anywhere in the track jump without a
   gap or a click.
7. Screen off (power button) and another app in front: the mix keeps playing
   for several minutes; the notification shows while it does.

Controller (FLX4)

8. The controller powers up from the phone, or needs its power port.
9. MIDI is detected, and again after unplugging and replugging.
10. Direction of every fader and knob; the tempo fader sign and the browse
   knob direction were changed in 0.2.0 to follow the Mixxx mapping.
11. Jog: touch-scratch, side bend, SHIFT search.
12. LEDs: play, cue, sync, channel cue, loop in/out, pad modes, pads, Beat FX,
   Smart CFX, master cue, level meters.
13. Beat FX channel switch (1 / 2 / 1&2) and level/depth knob.
14. On connection, the on-screen faders, knobs and crossfader jump to the
    hardware positions (keep-alive answer), and a touch-screen change is not
    undone a moment later.
15. Left alone for 15 minutes, the controller's LEDs still follow the app.
16. After SYNC or a touch-screen tempo change, moving the FLX4 tempo fader does
    not make the tempo jump: the arrow next to the pitch shows which way to
    go, and it takes over at the playing speed.
17. LOAD with the library closed opens it; LOAD again loads, the jog ring
    plays its load animation and the library closes.

USB audio

18. With the FLX4 connected, the status line shows its name. Note whether it
    says `master 1-2 + phones 3-4` or `stereo`.
19. In four-channel mode: master on the MASTER outputs, cue on PHONES, mix and
    level knobs working.
20. Unplugging the controller pauses both decks.
21. A notification sound does not lower the master.

App

22. First-run folder picker, and the folder still readable after a reboot.
23. Tapping an empty deck opens the library for it (the folder picker on first
    use); tapping a track loads it there.
24. Loading onto a playing deck is refused with a message.
25. In-app update from one signed release to the next.

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
