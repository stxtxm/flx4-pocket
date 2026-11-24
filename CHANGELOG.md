# Changelog

## 0.6.1

SYNC leaves the start to the DJ, the level meters read as on the FLX4, and the
FLX4's headphones work on phones that only send it two channels.

### Changed

- SYNC no longer moves a deck by itself. A synced deck that is started, cued
  or jumped (PLAY, CUE, hot cue, memory cue, search, beat jump) plays from
  where it was put; sync keeps the master's tempo and holds that place against
  the beat, also when TAP or the analysis changes either deck's grid. Only
  SYNC pressed while both decks play lands it on the master's beat. (0.6.0
  moved a synced deck onto the master's beat at every start and jump.)
- Channel level meters, on screen and on the FLX4, read before the channel
  fader and the crossfader, as the FLX4's own indicators do: a deck closed on
  the crossfader still shows its level while it is cued. They read both
  channels (only the left was read).
- Meters in decibels, falling at 24 dB a second (it was about 160: between
  two updates of the FLX4's LEDs a peak had already dropped 8 dB, so TRIM had
  to be pushed). On the FLX4 the five LEDs light from -24, -12, -6, -3 and
  0 dBFS, after Pioneer's MIDI message list: a track at TRIM centre lights
  orange on its loudest parts, as the manual asks. On screen, 3 dB a segment,
  amber from -6 dBFS, red at 0.
- The master meter reads before the limiter: red shows it at work.
- **Headphones over 2 channels** (AUDIO, formerly Split cue) now takes over a
  four-channel output and reopens it at once (it was ignored on the FLX4).
  With the FLX4's rear Android MONO/STEREO switch on MONO, master and
  headphones come out each in mono, over the two channels that phones such as
  the Pixel 9 family deliver; they leave the four-channel headphones silent
  (a known Android bug). It starts unticked: the Split cue box of earlier
  versions, which did nothing on the FLX4 and may have been left ticked, is
  not carried over, or it would now send the cue to a speaker.

### Fixed

- Meters falling towards zero went through denormal floats, slow on some
  processors.

## 0.6.0

Sync and loops as rekordbox plays them, a beat grid that holds for a whole
track, less delay between the FLX4 and the sound, the set recorded to WAV, and
a bigger screen that also works upright.

### Added

- **REC** (bar under the waveforms) records the mix to a 24-bit WAV file in
  `Music/FLX4 Pocket`: the master output as it is sent out, at the output's
  own rate, with no lossy step. The button shows the elapsed time; the file
  stays valid if the app is killed, and continues in a second file past 4 GB.
- **Upright view.** Turn the phone and the same screen restacks, without
  restarting anything: the panels in a column, each the full width, the
  status line on its own row, the library with its folders above its tracks.
  Each orientation remembers its own folded panels and waveform height;
  upright, the mixer starts folded. Either way up, also on its side.
- The loop length shows on the 4 BEAT / EXIT button (4, then the size and
  EXIT while a loop plays), so halving and doubling are no longer blind.
- The status line ends with the output's rate, `fast` when Android granted its
  low-latency path, and the delay it reports.

### Changed

- **Bigger.** Larger text and taller buttons throughout; each deck's tempo is
  a solid block in the deck's colour at the top left of its waveform, with the
  deck letter (they used to sit small at the top right, in the screen's
  corner); the remaining time and the MASTER mark have a dark backing; the
  track list has a tempo column.
- **Nothing under the corners.** From Android 12 the screen reads the shape of
  the display and keeps clear of the camera and, along its long sides, of the
  rounded corners.
- **Beat grid.** The analysis finds the tempo to a few thousandths of a BPM
  (it was 0.01 to 0.05 off, which is up to 115 ms after five minutes), from
  the whole track instead of its first twelve seconds, so a long intro no
  longer spoils it, and 174 BPM is no longer read as 87. Tempos read between
  89.5 and 179 BPM. A first grid shows after a minute of the track is decoded,
  the final one a few seconds later. Tracks analysed by an earlier version are
  analysed again the first time they are loaded.
- **Beat sync, as in rekordbox.** A synced deck lands on the master's beat
  when it starts and after a hot cue, a memory cue or a search (it used to be
  aligned only at the moment SYNC was pressed, then crept back over up to a
  minute); a paused deck stays on its cue point. A jog nudge on a synced deck
  is kept. SYNC off, or SYNC pressed on the other deck, keeps the tempo the
  deck plays at (it jumped back to the fader's), widening the tempo range if
  the fader could not reach it, as rekordbox switches to WIDE. 87 against 174 BPM syncs at
  the same speed instead of doubling it. A master deck that stops hands the
  role to the deck still playing, so the deck on air never follows a track
  being loaded, cued or started under it. SYNC on the deck on air while the
  other is stopped is refused. A synced deck ignores the FLX4 tempo fader, as
  Pioneer players do.
- **Less delay.** The output is opened at its own rate, which is what lets
  Android give it its low-latency path; on Android 14 and later the FLX4's
  sound card is asked to run at 44.1 kHz, so tracks reach it without being
  resampled. Expected with the FLX4: 20 to 35 ms instead of 70 to 100 ms (see
  `docs/ANDROID-LIMITS.md`). While scratching, the sound no longer trails a
  steady hand by 10 ms. The waveforms show what is heard, not what is being
  mixed.
- **Sound.** Audio read between two samples (another speed, an output at
  another rate, after a jump or in a loop) goes through a windowed sinc: flat
  to 17 kHz, where the cubic read lost 1.7 dB at 15 kHz. With KEY, on an
  output at the track's rate, the samples are played as they are.
- With quantize, a hot cue played on a running deck lands as far into the beat
  as the deck was, so the rhythm goes on (rekordbox's quantize).
- CUE/LOOP CALL ◄ ► with no loop playing jump to the memory cue and wait
  there, as in rekordbox (the deck used to play on).
- BEAT JUMP during a loop moves the loop with the deck (rekordbox's loop
  move).
- SHIFT + headphones CUE on the FLX4 taps the tempo, as in rekordbox (it
  toggled quantize, which stays on the Q button). TAP keeps the analysed tempo
  when the taps agree with it, or with its half or double, and only moves the
  grid half a beat if the taps fall between its beats.
- The app no longer restarts, stopping the music, when the dark theme
  switches, the font size changes or a keyboard is plugged in.
- Waveforms are redrawn at full rate only while they move; a still deck costs
  a fraction of what it did.

### Fixed

- In BEAT LOOP pad mode the pad of the current loop length almost never lit,
  on screen and on the FLX4 (the length was compared exactly).
- A 4 BEAT loop asked for at the very start of a track could be up to half a
  beat short.
- A hot cue or a search past the end of a playing loop fell back into the
  loop: it now leaves it.
- A deck synced to deck A ran 5.8 ms ahead when it was deck B.
- Tapping the tempo with taps in the same millisecond could hang the app.
- With KEY, a jump to the very start of a track (SHIFT + CUE, a hot cue at
  0:00) could leave the deck silent until pause and play.
- CUE, IN or a new hot cue set before the first beat of a track got a time
  before 0:00: CUE stopped previewing and OUT was ignored. They now land on
  the first beat.
- A fault in the audio thread stopped the app: it now stops the sound and says
  so; AUDIO restarts the output.

### Documentation

- `docs/ANDROID-LIMITS.md`: powering the FLX4 from the phone's USB-C port,
  what the delay is made of, the limits of the beat grid, the recording.
- `docs/TESTING.md`: the hardware checklist for all of the above.

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
