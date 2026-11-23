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
| `DeckTest` | Play, tempo ranges, end of track, CDJ cue, beat and manual loops, halve/double, loops that keep the beat (late OUT, early 4 BEAT, 1/4-beat loops, halving under the playhead), click-free loop seams (also with KEY, which must restart at the loop start at once), loop point adjust with the jog, memory cues, instant doubles, hot cues, beat jump, slip roll, pad FX, scratch, bend, sync (also when the master's tempo moves), keylock pitch and tempo, click-free jumps, Smart CFX |
| `TrackBufferTest` | Whole-track buffer: decoded frames read back, undecoded ones wait, capacity |
| `FxTest` | Bypass when off, echo timing and tail, trans gating, stability of every effect |
| `LibraryTest` | Folder column order (parents before children, lookalike names apart), counts, which tracks a folder holds |
| `WaveformTest` | Resolution, band separation, overflow, cache-file round trip |
| `PcmRingTest` | Cubic interpolation accuracy, seek reset, eviction over many capacities, decoder throttling |
| `DspTest` | Biquads, mixer curves, EQ range, master limiter, soft clip |
| `BpmAnalyzerTest`, `BeatGridTest` | Tempo estimation and beat math |
| `MainActivityTest` (Robolectric) | The real layouts inflate, the activity runs its lifecycle, touch controls and controller events reach the engine, an empty deck opens the library for itself, waveforms cannot grab a track while the library is open, panels fold away (the library still opens, the choice is remembered), tempo fader soft takeover, load lock, KEY, the controller browsing folders then tracks, playlists (order, kept across launches), BPM sort, resizing the waveforms by dragging |

`MainActivityTest` also writes `app/build/screenshots/main.png` (the mixing
screen with two synthetic tracks) and `waveforms.png` (every panel folded).
Open them after changing a layout.

## What the tests cannot show

Robolectric stubs `AudioTrack`, `MediaCodec` and `MidiManager`. 0.3.0 was used
on a Pixel 9a with a DDJ-FLX4; nothing below has been checked on hardware for
0.4.0 yet. Please check it on a phone, and with a DDJ-FLX4 where marked.

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
8. Latency: the status line shows a figure in ms a second after start (note
   it, with the output); a fader cut, PLAY or a pad is heard at once, and the
   sound does not crackle.

Controller (FLX4)

9. The controller runs from the phone alone, without a power supply; note
   whether it cuts out when its LEDs light up or with loud headphones.
10. MIDI is detected, and again after unplugging and replugging.
11. Direction of every fader and knob; the tempo fader sign and the browse
   knob direction were changed in 0.2.0 to follow the Mixxx mapping.
12. Jog: touch-scratch, side bend, SHIFT search.
13. LEDs: play, cue, sync, channel cue, loop in/out, pad modes, pads, Beat FX,
   Smart CFX, master cue, level meters.
14. Beat FX channel switch (1 / 2 / 1&2) and level/depth knob.
15. On connection, the on-screen faders, knobs and crossfader jump to the
    hardware positions (keep-alive answer), and a touch-screen change is not
    undone a moment later.
16. Left alone for 15 minutes, the controller's LEDs still follow the app.
17. After SYNC or a touch-screen tempo change, moving the FLX4 tempo fader does
    not make the tempo jump: the arrow next to the pitch shows which way to
    go, and it takes over at the playing speed.
18. LOAD with the library closed opens it; LOAD again loads, the jog ring
    plays its load animation and the library closes.
19. Loops, against the other deck playing in sync: IN then OUT (also a little
    late), 4 BEAT/EXIT (also a little early), beat-loop pads, CUE/LOOP CALL
    ◄ ► to halve and double. The seam is clean and the deck never drifts off
    the beat. IN or OUT during a loop blinks and the jog moves that point;
    pressed again it stops. SHIFT + 4 BEAT/EXIT turns the last loop back on.

USB audio

20. With the FLX4 connected, the status line shows its name. Note whether it
    says `master 1-2 + phones 3-4` or `stereo`.
21. In four-channel mode: master on the MASTER outputs, cue on PHONES, mix and
    level knobs working.
22. Unplugging the controller pauses both decks.
23. A notification sound does not lower the master.

App

24. First-run folder picker, and the folders still readable after a reboot.
    + FOLDER twice and + TRACKS (several files): everything shows, in the
    folder column as a tree with counts; long-press removes a top folder or an
    added track after a confirmation. With the FLX4: BROWSE press moves
    between folders and tracks, BROWSE turn moves in the one with the cursor,
    SHIFT + BROWSE press closes the library. BPM ↑ sorts by tempo; SHIFT +
    LOAD 1 opens it on the master's tempo. A playlist made with + PLAYLIST and
    long-presses keeps its order after a restart.
29. Memory cues: SHIFT + CUE/LOOP CALL ► saves the cue point and a playing
    loop; ◄ ► call them (a red mark on the waveform each), SHIFT + ◄ deletes
    the one called; they come back with the track.
30. LOAD twice on a deck: the other deck's track plays along at the same
    place and speed, and stays together after a second.
31. Dragging the bar under the waveforms resizes them, and the size is kept.
25. Tapping an empty deck opens the library for it (the folder picker on first
    use); tapping a track loads it there.
26. Loading onto a playing deck is refused with a message.
27. DECK A, MIXER and DECK B fold their panels; with all three folded the
    waveforms fill the screen, the library still opens, and the choice is kept
    after a restart.
28. In-app update from one signed release to the next.

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
