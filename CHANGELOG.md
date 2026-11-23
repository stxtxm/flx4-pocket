# Changelog

## 0.5.0

A library you can fill and browse from the phone and from the FLX4.

### Added

- **+ FOLDER** adds a music folder with its subfolders, as many as you like
  (it used to replace the one folder); **+ TRACKS** adds single audio files,
  several at once, shown under "Added tracks".
- A folder column next to the track list: every folder as a tree with its
  track count; a folder shows its tracks and those of its subfolders, and the
  search works inside it.
- Long-press a top folder or an added track to take it out of the library,
  after a confirmation; the files stay on the phone.
- With nothing added yet, the track list explains how to add music.
- Playlists: + PLAYLIST creates one, a long-press on a track adds it (or
  removes it from the playlist shown); they keep their order, show in the
  folder column and are browsed like folders. Long-press deletes one.
- BPM ↑ sorts the tracks by tempo (unanalysed ones last).
- Memory cues, mapped as in rekordbox: SHIFT + CUE/LOOP CALL ► saves the cue
  point or the loop playing, CUE/LOOP CALL ◄ ► call them in track order (a
  memory loop plays), SHIFT + ◄ deletes the one called. Red marks on the
  waveform and the overview; saved with the track. On screen, ½ / 2× call
  them when no loop plays.
- Instant doubles: LOAD pressed twice loads the other deck's track at the
  same place, speed and keylock, playing if it plays, and realigns once the
  audio is decoded.
- SHIFT + LOAD 1 opens the library sorted by BPM on the master deck's tempo;
  SHIFT + LOAD 2 opens and closes it (rekordbox for Android).
- Drag the bar under the waveforms to resize them against the panels; kept.

### Changed

- FLX4 browser as in rekordbox: BROWSE press moves the cursor between the
  folders and the tracks, BROWSE turn moves in the one that has it; SHIFT +
  BROWSE press opens and closes the library (BROWSE press used to).
- The folder from 0.4 and earlier becomes the first folder of the library.
- SHIFT + CUE/LOOP CALL ◄ ► no longer jump 16 beats: they delete and save
  memory cues, as in rekordbox. Beat jumps stay on the BEAT JUMP pads.

### Fixed

- With KEY, a loop kept playing past its end for up to 70 ms at each seam (a
  grain ran on) and clicked where a grain read across the end; the seam now
  starts a new grain at the loop start, crossfaded.

## 0.4.0

Waveforms first, loops that stay on the beat, and a shorter delay between a
move and the sound.

### Added

- DECK A, MIXER and DECK B, under the waveforms, fold their panel away; the
  others widen, and with all three folded the waveforms fill the screen. The
  library still opens where the panels were. The choice is remembered.
- Loop point adjust, as in rekordbox: IN or OUT during a loop selects that
  point (its LED and on-screen button blink) and the jog, or a drag on the
  waveform, moves it until the button is pressed again.
- The status line shows the output latency Android reports.

### Changed

- The bar above the waveforms is gone: its status, master meter, LIBRARY,
  AUDIO and version buttons moved to the bar under the waveforms, and the
  track information sits on the waveform without the dark band that hid it.
- FLX4 loop section mapped as in rekordbox: 4 BEAT/EXIT starts a 4-beat loop
  or exits the loop (it used to re-enter the last loop); SHIFT + 4 BEAT/EXIT
  turns the last loop back on (it used to exit and pause); holding IN no
  longer starts a 4-beat loop. The on-screen LOOP button became 4 BEAT / EXIT.
- Loops never leave the beat: OUT pressed a little late wraps back to the
  same place in the beat instead of the loop start; a 4-beat or beat loop
  pressed just before a beat starts on that beat; loops under a beat snap to
  the nearest step; halving under the playhead wraps in phase; turning a loop
  back on no longer jumps to its start.
- Latency: the audio buffer starts at two blocks (12 ms) and grows only after
  an underrun; it used to stay at twice Android's minimum. PLAY, IN, OUT,
  4 BEAT, halve / double, TAP and FX ON act when the finger lands, not when it
  lifts.
- The mixer shares the width with the decks instead of a fixed 300 dp, and
  the waveforms keep a third of the height on any screen.

### Fixed

- The loop seam clicked; it now crossfades over 3 ms, like jumps. A jump made
  at a loop end crossfades with what follows the loop instead of with itself.

### Documentation

- The FLX4 can run from the phone's USB-C port (bus power); its power port is
  optional (`docs/ANDROID-LIMITS.md`).

## 0.3.0

Faster loading, keylock, gap-free hot cues, background playback, a
better-behaved DDJ-FLX4 and a cleaner sound.

### Added

- KEY (keylock) per deck: the tempo changes, the pitch does not. WSOLA
  time-stretch read straight from the track at the playhead, so cues and hot
  cues stay instant. Scratching stays vinyl.
- The whole track is decoded into memory in the background (tracks up to
  15 min, about 10 MB a minute): within seconds of loading, hot cues, loops
  and jumps anywhere play without a gap. Jumps crossfade over 3 ms instead of
  clicking.
- Playback goes on with the screen off or another app in front (foreground
  service with a notification, stopped after ten idle minutes). Controller
  LEDs keep updating meanwhile.
- Load lock: a playing deck refuses a new track.
- FLX4 tempo fader soft takeover: after a sync, a touch change or a
  reconnection it only drives the tempo once it reaches the playing speed; an
  arrow next to the pitch shows which way to move it.
- Tap an empty deck, or press its PLAY, to open the library for that deck
  (the folder picker on first use); a tap on a track then loads it there.
- The library opens under the waveforms, which stay in sight with their
  remaining time. While it is open a finger on a waveform cannot grab a
  playing track.
- FLX4: the keep-alive from the Mixxx mapping is sent every 200 ms; the
  controller answers it with its fader and knob positions, so the app starts
  where the hardware is. Repeated positions are ignored, so they never undo a
  touch-screen change.
- FLX4: LOAD with the library closed opens it for that deck; LOAD then loads
  and closes it. The first browse click only opens the library. The jog ring
  plays its load animation on every load.

### Changed

- Master limiter instead of a clipping curve: loud overlaps are turned down,
  not distorted. Channel faders and the crossfader are ramped (no zipper
  noise or clicks on cuts).
- Pitched or non-44.1 kHz audio uses cubic interpolation (clearer highs).
- EQ range -26 / +6 dB, like a Pioneer mixer, instead of +/-24 dB.
- Notification sounds no longer duck the master.

### Fixed

- A synced deck now follows the master's tempo when it changes; before, it
  kept the tempo it had when SYNC was pressed and drifted.
- Once full, each deck's buffer shifted all 24 seconds of audio on every
  decoder write (about 300 MB/s) while the mixer waited; the buffer is now
  circular.

### Not verified

Still no phone or controller where this was built. The keep-alive and its
answer come from the Mixxx mapping; see `docs/TESTING.md`.

## 0.2.0

A rewrite of the screen and of most of the audio path.

### Fixed

- **No sound in 0.1.x.** The audio engine reset its own output during
  construction, so the mixer thread exited at once; and the track decoder ran
  unthrottled, overwriting the audio that was about to be played. Both are
  fixed, the second with a regression test.
- The FLX4 tempo fader was inverted and the browse knob scrolled backwards.
- 14-bit controls of deck 1 and deck 2 shared their LSB/MSB memory.
- Background update checks no longer show "No update found" toasts.

### Added

- Scrolling three-band waveforms with beat grid, loop, cue and hot-cue
  markers; drag to scratch, pinch to zoom; track overview with needle search.
- Touch mixer: knobs, faders, level meters, crossfader, pitch bend, CDJ cue.
- Pad modes Hot Cue, Beat Loop, Beat Jump, Pad FX 1 and Pad FX 2; slip loop
  roll; loop halve / double; hot cues saved per track.
- Beat FX (echo, low-cut echo, reverb, flanger, phaser, trans) on a deck or
  the master, Smart CFX, master limiter.
- FLX4: jog scratch / bend / search, Beat FX section, Smart CFX, master cue,
  master and headphone level, loop call buttons, pad FX pads, more LEDs and
  the channel level meters.
- USB audio: automatic routing to the controller, four-channel output
  (master 1-2, headphones 3-4) when Android exposes it.
- Library search and per-row load buttons; the app no longer requires USB
  host or MIDI support to install.
- Non-blocking in-app updater on a single button.
- Tests: MIDI address table, effects, waveform, deck behaviour, decoder
  throttling, and a Robolectric smoke test of the real screen.

### Removed

- The 32-band spectrum bars (replaced by the waveforms).

### Not verified

No phone or controller was available for this build. Controller power from
the phone and four-channel USB audio depend on the device; see
`docs/ANDROID-LIMITS.md` and the checklist in `docs/TESTING.md`.

## 0.1.2 — 2026-11-23

Standalone-first install: the in-app updater works on Pixel 9a again, play
works standalone, real-time spectra for both decks, bigger touch buttons,
in-app update checks every 30 minutes while the app is open.

- `PackageInstaller` result status is delivered through a mutable
  `PendingIntent` on Android 12+; the app used to report "No update found"
  on a Pixel 9a when the update had actually downloaded fine.
- PLAY button gives feedback when no track is loaded, and audio errors are
  shown in the status line.
- New `Spectrum` FFT/band-mapping DSP with unit tests; `Deck` publishes its
  real-time spectrum; `BeatBarView` shows it with a beat pulse.
- Bigger touch buttons and the version label now shows the installed build.
- `UpdateChecker.lastError` distinguishes "GitHub unreachable" from "No
  update found".
- Auto-update checks every 30 minutes while the app is open.

## 0.1.1 — 2026-11-23

In-app updater fix. This is the recommended first install.

- The `PackageInstaller` status `PendingIntent` is mutable on Android 12+, so
  the system can deliver `EXTRA_STATUS` and the install confirmation flow
  completes on a Pixel 9a.

## 0.1.0 — 2026-11-23

First public release.

- Two-deck Android mixer for the Pioneer DDJ-FLX4 using its exact USB MIDI map.
- Shared-clock audio engine with per-deck trim, 3-band EQ, filter, channel fader,
  equal-power crossfader and pre-fader cue bus.
- Beat sync with phase alignment and continuous drift correction.
- On-device BPM analysis, tap tempo, tempo-range cycling and a beat-phase bar.
- 8 hot cues per deck, beat loops (1/4–32 beats), beat jumps, manual loops and
  reloop.
- FLX4 LED feedback for transport, sync, channel cue and pads.
- SAF music library, no storage permission.
- Best-effort audio output selection (phone, wired, Bluetooth, USB).
- Split-cue mode (master left / cue right) for stereo splitter cables.
- In-app GitHub Releases updater with SHA-256, package, version and signature
  verification, plus Android's required confirmation.
- CI (tests + debug build) and a signed release workflow publishing the APK,
  checksum and `update.json`.
- Unit tests for DSP, ring buffer, beat math and BPM estimation.

Known limitations: Android does not provide a reliable FLX4 four-channel USB
audio path, the controller needs its own power supply, and keylock is not
implemented yet.
