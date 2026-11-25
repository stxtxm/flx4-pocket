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
| `DeckTest` | Play, tempo ranges, end of track, CDJ cue, beat and manual loops, halve/double, loops that keep the beat (late OUT, early 4 BEAT, 1/4-beat loops, halving under the playhead), the loop length reported exactly on any grid, a loop asked for before the first beat, a jump past a loop leaving it, click-free loop seams (also with KEY, which must restart at the loop start at once), loop point adjust with the jog, memory cues (a call waits on the cue), instant doubles, hot cues (with quantize they keep the place in the beat), beat jump (during a loop it moves the loop), slip roll, pad FX, scratch, bend, sync (the tempo; the beat when SYNC is pressed on a playing deck and when PLAY starts a synced deck; a deck jumped plays from there and keeps that place in the beat, also when either grid changes; a nudge kept, half and double tempo, 80 BPM against 160, SYNC off keeping the tempo, the master's tempo moving), TAP correcting a grid, keylock pitch and tempo, time and pitch at a 48 kHz output, click-free jumps, Smart CFX |
| `BpmAnalyzerTest`, `BeatGridTest` | The beat grid to a hundredth of a BPM: click tracks, a non-integer tempo over five minutes, 174 not read as 87, 80 not read as 160, the final analysis keeping the preview's octave, a silent intro, and a synthesised kick and hat track through the real PCM path whose grid must still sit on its kicks after five minutes; beat math |
| `RecorderTest` | The WAV header and the 24-bit samples read back, a recording continuing in a second file at the size limit, a write error stopping it with its reason |
| `TrackBufferTest` | Whole-track buffer: decoded frames read back, undecoded ones wait, capacity |
| `FxTest` | Bypass when off, echo timing and tail, trans gating, stability of every effect |
| `LibraryTest` | Folder column order (parents before children, lookalike names apart), counts, which tracks a folder holds |
| `WaveformTest` | Resolution, band separation, overflow, cache-file round trip |
| `PcmRingTest` | Interpolation accuracy at 5 and 15 kHz, whole frames read sample for sample, seek reset, eviction over many capacities, decoder throttling |
| `DspTest` | Biquads, mixer curves, EQ range, master limiter, soft clip, meters in decibels, the FLX4 level LED bands (-24 / -12 / -6 / -3 / 0 dBFS) |
| `MainActivityTest` (Robolectric) | The real layouts inflate, the activity runs its lifecycle, touch controls and controller events reach the engine, an empty deck opens the library for itself, waveforms cannot grab a track while the library is open, panels fold away (the library still opens, the choice is remembered), the screen restacking when held upright on the same activity and engine and coming back, the padding for a camera cutout and rounded corners, the tempo column of the track list, REC, a channel meter reading before the fader and the crossfader, the playing deck taking the sync master role, tempo fader soft takeover, load lock, KEY, the controller browsing folders then tracks, playlists (order, kept across launches), BPM sort, resizing the waveforms by dragging |

`MainActivityTest` also writes pictures of the real screen to
`app/build/screenshots/`: `main.png` (the mixing screen with two synthetic
tracks), `waveforms.png` (every panel folded), `library.png`, and held upright
`upright.png`, `upright-mixer.png` and `upright-library.png`. Open them after
changing a layout.

## What the tests cannot show

Robolectric stubs `AudioTrack`, `MediaCodec`, `MidiManager` and the media
store. 0.3.0 was used on a Pixel 9a with a DDJ-FLX4; 0.4.0 to 0.6.1 have not
been checked on hardware by the author. Please check on a phone, and with a
DDJ-FLX4 where marked, and report what differs.

Screen

1. Held on its side, nothing is hidden by the rounded corners or the camera:
   the deck letters and tempos (top left), the remaining times (top right),
   the bottom corners of the panels. Either way up.
2. Held upright (either way up, cable at the top or the bottom), the panels
   stack, the status line has its own row under the buttons, the library has
   its folders above its tracks, and the music does not stop while turning.
   Each orientation remembers its own folded panels and waveform height.
3. The tempos (A and B, top left of each waveform) and the track list's tempo
   column are readable from where you stand.
4. With both decks stopped, the phone stays cooler than with 0.5.0 (nothing is
   redrawn at full rate); a waveform still follows a jog or a finger at once.

Audio

5. A loaded track plays, in time, without dropouts; seeking and loops are clean.
6. Scratching on the waveform and long backward drags recover. With the FLX4,
   the sound follows the platter without trailing it, and is not rougher than
   with 0.5.0.
7. Effects sound right and do not clip.
8. KEY on at ±8 %: same pitch, no obvious warble or doubled kicks.
9. A track pitched ±8 % without KEY keeps its highs (cymbals as bright as at
   0 %).
10. A few seconds after loading, hot cues anywhere in the track jump without a
    gap or a click.
11. Screen off (power button) and another app in front: the mix keeps playing
    for several minutes; the notification shows while it does.
12. **Latency.** Note the end of the status line, with the output in use: the
    rate, the word `fast` (Android's low-latency path) or its absence, and
    the figure in ms. Expected with the FLX4 as the sound card on Android 14
    or later: `44.1 kHz fast` and some 20 to 35 ms, against 70 to 100 ms
    without `fast`. A fader cut, PLAY or a pad is heard at once, and the
    sound does not crackle.

Beat grid and sync (two tracks you know, played for several minutes)

13. The tempo shown is the track's (compare with rekordbox), 174 and 80 BPM
    tracks included; the beat marks sit on the kicks at the start and at the
    end of the track. A track loaded with an earlier version shows its old
    grid for a moment, then the new one. Long-press a track read at the wrong
    octave: Halve / Double the BPM fixes it, tab and playing deck included.
14. SYNC on a paused deck, then PLAY anywhere: it lands on the master's beat
    at once, with no second SYNC press, and stays there. SYNC pressed while
    both play puts it on the beat too. A hot cue or a needle search on the
    synced deck plays from there, without a correction. Two decks put on the
    beat still sound as one after three minutes; so do an 80 BPM track and a
    160 BPM one.
15. A jog nudge on the synced deck stays where you put it. SYNC off, or SYNC
    on the other deck, changes no tempo. With SYNC on, the FLX4 tempo fader
    does nothing; after SYNC off the arrow by the pitch shows where to move it.
16. Load and play a new track on the deck that was the master while the other
    deck is on air: the deck on air does not change speed.
17. TAP (screen, or SHIFT + headphones CUE) three times on the beat of a track
    whose marks sit between the beats: they move onto the beats and the tempo
    shown does not change.

Controller (FLX4)

18. The controller runs from the phone alone, without a power supply: see
    `docs/ANDROID-LIMITS.md`, section 1, and note what your cable does.
19. MIDI is detected, and again after unplugging and replugging.
20. Direction of every fader and knob.
21. Jog: touch-scratch, side bend, SHIFT search.
22. LEDs: play, cue, sync, channel cue, loop in/out, pad modes, pads, Beat FX,
    Smart CFX, master cue, level meters.
23. Beat FX channel switch (1 / 2 / 1&2) and level/depth knob.
24. On connection, the on-screen faders, knobs and crossfader jump to the
    hardware positions (keep-alive answer), and a touch-screen change is not
    undone a moment later.
25. Left alone for 15 minutes, the controller's LEDs still follow the app.
26. LOAD with the library closed opens it; LOAD again loads, the jog ring
    plays its load animation and the library closes.
27. Loops, against the other deck playing in sync: IN then OUT (also a little
    late), 4 BEAT/EXIT (also a little early), CUE/LOOP CALL ◄ ► to halve and
    double. The loop length shows on the screen's 4 BEAT button and, in BEAT
    LOOP pad mode, on the pad of that length. The seam is clean and the deck
    never drifts off the beat. IN or OUT during a loop blinks and the jog
    moves that point; pressed again it stops. SHIFT + 4 BEAT/EXIT turns the
    last loop back on. A hot cue placed after the loop leaves the loop.
28. Memory cues: SHIFT + CUE/LOOP CALL ► saves the cue point and a playing
    loop; ◄ ► jump to them and wait there (a red mark on the waveform each),
    SHIFT + ◄ deletes the one called; they come back with the track.
29. LOAD twice on a deck: the other deck's track plays along at the same
    place and speed, and stays together after a second.

USB audio

30. With the FLX4 connected, the status line shows its name. Note whether it
    says `master 1-2 + phones 3-4`, `stereo` or `split cue`.
31. In four-channel mode: master on the MASTER outputs, cue on PHONES, mix and
    level knobs working. If the headphones stay silent: unplug, set the
    FLX4's rear Android MONO/STEREO switch to MONO, replug, tick Headphones
    over 2 channels in AUDIO. Then deck 2's fader down and its CUE on: deck 2
    is heard in the headphones only, never on the speakers (if it is, untick
    at once and report it). Note whether the FLX4 entry of the AUDIO list says
    "USB, stereo" or "USB, master + headphones" with the switch on MONO.
31b. Channel level meters: a track at TRIM centre lights orange (FLX4) and
    amber (screen) on its loudest parts, red only when pushed; a deck with its
    fader down or closed on the crossfader still shows its level.
32. Unplugging the controller pauses both decks.
33. A notification sound does not lower the master.

Recording

34. REC, mix for a few minutes, REC again: the message says where the file
    is, `Music/FLX4 Pocket` (Files app); it plays in another app and sounds
    like the mix. Its level follows MASTER LEVEL. With the screen off, unplug
    the controller while recording: the recording ends by itself, and the
    notification goes away ten minutes later.
35. REC survives the screen going off and another app in front. If the app is
    closed from the recent apps while recording, the file up to that moment
    still plays.

App

36. First-run folder picker, and the folders still readable after a reboot.
    + FOLDER twice and + TRACKS (several files): everything shows, in the
    folder column as a tree with counts; long-press removes a top folder or an
    added track after a confirmation. With the FLX4: BROWSE press moves
    between folders and tracks, BROWSE turn moves in the one with the cursor,
    SHIFT + BROWSE press closes the library. BPM ↑ sorts by tempo; SHIFT +
    LOAD 1 opens it on the master's tempo. A playlist made with + PLAYLIST and
    long-presses keeps its order after a restart.
37. Dragging the bar under the waveforms resizes them, and the size is kept.
38. Tapping an empty deck opens the library for it (the folder picker on first
    use); tapping a track loads it there.
39. Loading onto a playing deck is refused with a message.
40. DECK A, MIXER and DECK B fold their panels; with all three folded the
    waveforms fill the screen, the library still opens, and the choice is kept
    after a restart.
41. The dark theme switching (or a keyboard being plugged in) while a deck
    plays does not stop the music.
42. In-app update from one signed release to the next.

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

To see why the delay is what it is: the app logs the output it opened
(`Output 44100 Hz, 4 ch, low latency true, ...`), and Android logs a refusal
of its low-latency path (`AUDIO_OUTPUT_FLAG_FAST denied`):

```bash
adb logcat -s Flx4Audio AudioFlinger
adb shell dumpsys media.audio_flinger | grep -i -B2 -A12 usb
```
