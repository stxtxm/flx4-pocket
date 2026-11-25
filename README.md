# FLX4 Pocket

[![CI](https://github.com/stxtxm/flx4-pocket/actions/workflows/ci.yml/badge.svg)](https://github.com/stxtxm/flx4-pocket/actions/workflows/ci.yml)
[![Release](https://img.shields.io/github/v/release/stxtxm/flx4-pocket)](https://github.com/stxtxm/flx4-pocket/releases/latest)

A two-deck Android DJ app built around the **Pioneer DJ DDJ-FLX4**. Plug the
controller in and it follows the FLX4's official MIDI map; leave it at home and
the whole mixer is on the touch screen: scrolling waveforms, pads, EQ, filter,
Beat FX and a crossfader.

![FLX4 Pocket mixing screen](docs/screenshot.png)

*Rendered from the real layout by the test suite, with two synthetic tracks.*

Built for a **Pixel 9a** first, compatible with Android 8.0+ (API 26).

> **Status:** 0.3.0 runs on a Pixel 9a with a DDJ-FLX4. The 0.4.0 to 0.7.0
> changes (loops, sync, beat grid, latency, recording, upright screen, keys) are
> covered by JVM tests (DSP, deck logic, the MIDI address table, the real
> screen under Robolectric) but not yet by a hardware check. See
> [docs/TESTING.md](docs/TESTING.md) for what must be checked on a device, and
> [docs/ANDROID-LIMITS.md](docs/ANDROID-LIMITS.md) for what Android itself may
> refuse (bus power, four-channel USB audio, latency).

## What is in 0.7.0

**Mixing from the phone**

- Two stacked, scrolling three-band waveforms (bass / mids / highs) at the top
  of the screen, with the beat grid, loop region, cue point and hot cues. Drag
  a waveform to scratch or position the track, pinch to zoom; both decks share
  the zoom so their beats can be lined up by eye.
- Each deck's tempo in large type, in the deck's colour, at the top left of
  its waveform: the two tempos sit one above the other. The track list has a
  tempo column.
- On its side or upright, either way up: turn the phone and the screen
  restacks without stopping anything (upright, the panels form a column and
  the library puts its folders above its tracks). From Android 12 nothing sits
  under the rounded corners or the camera.
- **DECK A**, **MIXER** and **DECK B** (under the waveforms) fold their panel
  away; the others widen to fill the space, and with all three folded the
  waveforms take the whole screen. Drag the bar under the waveforms (outside
  its buttons, at the ⇕) to share the height differently. Both are
  remembered, for each orientation.
- Track overview per deck with needle search.
- Eight pads per deck in five modes: Hot Cue, Beat Loop, Beat Jump, Pad FX 1
  (echoes and slip loop rolls) and Pad FX 2 (low-cut echo, reverb, flanger,
  phaser, trans).
- CDJ-style CUE (hold to preview), PLAY, SYNC (long press: sync master),
  pitch-bend buttons, tempo fader with ±6 / ±10 / ±16 % / WIDE ranges, TAP.
- KEY (keylock): the tempo changes, the pitch does not (WSOLA time-stretch).
- A beat grid that holds for a whole track: the tempo to a few thousandths of
  a BPM, from the whole track, read in one octave from 70 to 180 BPM (174 is
  not 87, and 80 stays 80). A wrong octave is one long press away: **Halve /
  Double the BPM** in the track menu.
- Beat sync as rekordbox: press SYNC and the deck follows the other deck's
  tempo and stays locked to its grid — at once, whether it is playing or
  stopped, at PLAY, and again after a seek, a hot cue or a refined grid. A
  jog or a scratch bends it while you hold it; the beat comes back when you
  let go. SYNC is lit on both decks while one follows the other, and SYNC off
  keeps the tempo. Half and double tempo sync at the same speed (80 against
  160). Long press SYNC makes that deck the sync master by hand.
- Loops as rekordbox plays them on the FLX4: IN, OUT, 4 BEAT / EXIT, halve /
  double, beat loops of 1/4 to 32 beats. The length shows on the 4 BEAT / EXIT
  button and on the BEAT LOOP pads. With quantize (Q) the points snap to the
  grid; a loop never knocks the deck off the beat (closing it late wraps back
  to the same place in the beat, a loop pressed just before a beat starts on
  it), and the loop seam crossfades instead of clicking. IN or OUT during a
  loop selects that point (it blinks): the jog, or a drag on the waveform,
  moves it until the button is pressed again. KEY keeps the seam seamless too.
  With Q, a hot cue played on a running deck keeps the place in the beat.
- Memory cues, as in rekordbox: SHIFT + CUE/LOOP CALL ► saves the cue point
  (or the loop playing), CUE/LOOP CALL ◄ ► jump to them in order and wait
  there (red marks on the waveform), SHIFT + ◄ deletes the one called. On
  screen, ½ and 2× become ◄ MEM / MEM ► when the deck has memory cues and no
  loop plays.
- Instant doubles: LOAD pressed twice puts the other deck's track on this
  deck, at the same place and speed, playing if it plays.
- Low latency: the output runs at its own rate, which is what gets Android's
  low-latency path, and on Android 14+ the FLX4's sound card is asked to run
  at 44.1 kHz, the rate of the music. PLAY, the loop buttons, TAP and FX ON
  act when the finger lands, and the audio buffer is tuned down to the
  smallest the output takes without glitches. The status line shows the rate,
  `fast` when the low-latency path was granted, and the delay.
- **REC** records the mix to a 24-bit WAV file in `Music/FLX4 Pocket` (before
  Android 10, the app's own Music folder): the master output sample for
  sample, at the output's rate.
- Mixer: trim, three-band EQ (-26 / +6 dB, like a DJM), filter, channel faders
  with level meters read before the fader (3 dB a segment, amber from
  -6 dBFS, red at 0), equal-power crossfader, headphone cue buttons, master
  peak limiter (red on its meter means the limiter is at work). Faders are ramped (no zipper noise); audio read between two
  samples (pitched, at another rate, after a jump) goes through a windowed
  sinc that keeps the highs.
- Beat FX on deck A, deck B or the master: echo, low-cut echo, reverb,
  flanger, phaser, trans, with beat length and depth.
- Smart CFX: the filter knob becomes a resonant sweep with a synced echo.
- Track keys: each track is analysed for its key and shown in Camelot notation
  (`8A`, `11B`) beside its tempo, and on the deck with its title. Once a track
  is analysed the key stays with it; a playing deck pauses the analysis.
  The key is an estimate: check it against rekordbox or your ear.
- Track tags: the list shows the title, artist, album and codec (MP3, AAC,
  FLAC, WAV, AIFF...) of each file, and the format line (sample rate, channels,
  bitrate) is read from the file. SEARCH matches artist, album and genre too.
- Library without storage permission: **+ FOLDER** adds a music folder with
  its subfolders (as many folders as you like), **+ TRACKS** adds single
  files. A folder column shows them as a tree with track counts, next to the
  searchable track list; **BPM ↑** sorts by tempo. Playlists: **+ PLAYLIST**,
  then long-press a track to add it (they keep their order and show in the
  folder column). Long-press a track to take it out of the library (the files
  stay), a top folder to take all its tracks out, a playlist to delete it.
  Hot cues and analysis are remembered per track. Tap an empty deck (or its PLAY) to open the library for that
  deck, then tap a track to load it there. The library opens under the
  waveforms, so the playing deck and its remaining time stay in sight. With
  the FLX4: BROWSE press moves between the folders and the tracks, as in
  rekordbox.
- A deck that is playing refuses a new track (load lock): pause it first.
- A few seconds after loading, the whole track is in memory, so hot cues,
  loops and jumps play at once, with a short crossfade instead of a click.
- The mix keeps playing with the screen off or another app in front; a
  notification shows while it does.

**With the DDJ-FLX4**

- On connection the app sends the controller's keep-alive (from the Mixxx
  mapping), which the FLX4 answers with the position of every fader and knob:
  the mixer starts where the hardware is.
- The official MIDI addresses for transport, jog (scratch / bend / search),
  tempo, mixer, filter, Smart CFX, Beat FX, loop section, browse / load and
  the performance pads, with LED feedback and the channel level meters.
- The controller's sound card is selected automatically when Android exposes
  it. With four output channels the master goes to 1-2 (MASTER out) and the
  headphone bus to 3-4 (PHONES), with CUE buttons, headphones mix and level
  working as on the hardware. On phones that only deliver two (the Pixel 9
  family), the FLX4's rear switch on MONO and **Headphones over 2 channels**
  give master and headphones, each in mono.
- The channel level LEDs show each deck before its fader, as on the hardware
  with rekordbox: TRIM so the loudest parts light orange.

**Updates**

- The version button (bar under the waveforms) checks GitHub Releases in the background,
  downloads with progress on the button while you keep mixing, and installs
  only when you tap INSTALL. Every APK is checked (SHA-256, package, version,
  signing certificate) before Android's installer is opened.

## Not here yet

- Sampler, Keyboard / Stems and Key Shift pad modes, Smart Fader.
- Streaming services, DVS.

## Install (first time)

1. Download `FLX4-Pocket-<version>.apk` from
   [Releases](https://github.com/stxtxm/flx4-pocket/releases/latest).
2. Either open it on the phone and allow installation from that source, or:
   ```bash
   adb install -r FLX4-Pocket-0.7.0.apk
   ```
3. Open FLX4 Pocket and add your music in the library: **+ FOLDER** takes a
   folder with its subfolders (add as many as you like), **+ TRACKS** single
   files. Tap an empty deck, then a track, to load it there; **DECK A** and
   **DECK B** on each track load it on either deck.
4. Optional: connect the phone to the DDJ-FLX4's **device** USB-C port.
   Pioneer's setup for a phone adds a 9 V 3 A USB power adapter or power bank
   on the **power** port. The phone alone can power the controller, but on a
   Pixel 9a a direct USB-C to USB-C cable lights it only some of the time,
   while a USB hub has worked every time (a USB-C to USB-A adapter with a
   USB-A to USB-C cable should too, but is untried; see
   [docs/ANDROID-LIMITS.md](docs/ANDROID-LIMITS.md)). The status line turns
   green and shows which output is in use. Turn the phone's media volume all the way up: Android
   scales the USB output by it. Set the level with the FLX4's MASTER LEVEL
   knob.
5. **AUDIO** lists the outputs and holds the master / headphones levels. If
   the FLX4's headphones stay silent (the Pixel 9 family only sends it two
   channels): unplug the FLX4, set the **Android MONO/STEREO** switch on its
   rear panel to **MONO**, plug it back in, then tick **Headphones over 2
   channels**: master and headphones each in mono. Check once: deck 2's fader
   down and its CUE on, deck 2 must be heard in the headphones only. Never
   tick that box with the switch on STEREO; the app remembers it, so untick it
   before you set the switch back to STEREO (see
   [docs/ANDROID-LIMITS.md](docs/ANDROID-LIMITS.md)).

### Do not open the APK on Fedora/GNOME

If double-clicking the APK in GNOME Files starts GNOME Software/PackageKit and
shows an authentication error ("Mise à jour impossible… groupe wheel / mot de
passe root"), that is the Linux package manager trying to install an **Android**
package as if it were an RPM. It is unrelated to FLX4 Pocket and cannot succeed.

Copy the APK to the Pixel and open it there, or install it from this repository
with adb:

```bash
adb install -r FLX4-Pocket-0.7.0.apk
```

## Controller workflow

| FLX4 control | App action |
| --- | --- |
| PLAY / PAUSE | Play / pause; on an empty deck, open the library for it |
| CUE | CDJ cue: set, preview while held, back to cue when playing |
| SHIFT + CUE | Back to the start of the track |
| BEAT SYNC / long press | Sync this deck to the other deck's tempo and grid, playing or stopped, or release keeping the tempo / make this deck the master |
| SHIFT + BEAT SYNC | Cycle the tempo range |
| Tempo fader | Pitch, 14-bit, across the selected range. Ignored while the deck is synced. Soft takeover: after a sync, a touch change or a reconnection it takes over once it reaches the playing speed; until then an arrow next to the pitch shows which way to move it |
| Jog top (touch) / side | Scratch / pitch bend |
| SHIFT + jog | Fast search |
| TRIM, EQ, channel fader, crossfader | Mixer |
| CFX knob / SMART CFX | Filter / resonant filter + echo |
| Headphones CUE, MASTER CUE, mix, level | Headphone bus |
| MASTER LEVEL | Master gain |
| BEAT FX select, beat ◄ ►, CH select, level/depth, ON/OFF | Beat FX |
| IN / OUT | Loop in / loop out (the loop starts). During a loop: select that point, the jog moves it, press again to finish |
| 4 BEAT/EXIT | 4-beat loop from here / leave the loop |
| SHIFT + 4 BEAT/EXIT | Turn the last loop back on (active loop), in phase |
| CUE/LOOP CALL ◄ ► | During a loop: halve / double it; otherwise jump to the previous / next memory cue and wait there |
| SHIFT + CUE/LOOP CALL ◄ / ► | Delete the memory cue called last / save the loop playing or the cue point as a memory cue |
| HOT CUE, PAD FX1/2, BEAT JUMP, BEAT LOOP + pads | Pad modes |
| SHIFT + hot-cue pad | Delete the hot cue |
| Browse turn | Move in the folders or the tracks (the first turn only opens the library); with SHIFT ten rows at a time |
| Browse press / SHIFT + press | Move between the folders and the tracks, as in rekordbox / open, close the library |
| LOAD 1 / 2 | Load the selected track and close the library; with the library closed, open it for that deck first. Twice: instant doubles |
| SHIFT + LOAD 1 / 2 | Library sorted by BPM on the master's tempo / open, close the library |
| SHIFT + headphones CUE | Tap the tempo, as in rekordbox (quantize is the Q button on screen) |

The complete address table is in [docs/DDJ-FLX4-MIDI.md](docs/DDJ-FLX4-MIDI.md).

## Updates on the phone

Every release publishes `update.json`, the APK and its SHA-256 in GitHub
Releases. The app checks on launch and then hourly; a new version turns the
version button (bar under the waveforms) amber. Tap it to read the notes and start the download, keep
mixing while the button counts up, and tap **INSTALL** when the music can
stop. Android always shows its own confirmation, and the first time it asks
to allow installs from FLX4 Pocket; the installation resumes by itself when
you come back from that setting. Updates must be signed with the same key as
the installed build. See [docs/RELEASES.md](docs/RELEASES.md).

## Build and test

Requirements: JDK 17, Android SDK platform 34 and build-tools 34.0.0.

```bash
./gradlew testDebugUnitTest
./gradlew assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

Release signing (same env names used by CI):

```bash
export ANDROID_KEYSTORE_FILE="$PWD/keystore/release.jks"
export ANDROID_STORE_PASSWORD=...
export ANDROID_KEY_ALIAS=flx4-pocket
export ANDROID_KEY_PASSWORD=...
./gradlew assembleRelease
```

## Project layout

```
app/src/main/java/.../
  MainActivity.kt    screen wiring: touch + MIDI -> engine -> screen + LEDs
  DeckPanel.kt       touch controls of one deck
  WaveformView.kt    scrolling waveform and track overview
  Controls.kt        knob, fader and meter views
  AudioEngine.kt     shared-clock mixer, output routing and rate, Beat FX
  Recorder.kt        REC: the master output to 24-bit WAV files
  Deck.kt            transport, sync, loops, cues, scratch, EQ, pad FX
  Fx.kt              echo, reverb, flanger, phaser, trans
  AudioSource.kt     streaming MediaCodec decoder + PcmRing
  PcmDecoder.kt      MediaCodec helpers, offline decode
  Waveform.kt        three-band waveform data, builder, cache file
  BpmAnalyzer.kt     onset autocorrelation
  Library.kt         folder scan, analysis cache, track analyzer
  MusicalKey.kt      track key: chroma, Krumhansl-Kessler estimate, Camelot code
  TrackInfo.kt       tags and audio format of a file, cached
  Flx4Protocol.kt    MIDI bytes -> semantic events (pure, tested)
  Flx4Midi.kt        USB MIDI transport and LEDs
  MidiMap.kt         FLX4 addresses
  UpdateUi.kt / UpdateChecker.kt / UpdateInstaller.kt   in-app updates
docs/                mapping, limits, architecture, testing, releases
.github/workflows/   CI and release pipeline
```

More: [architecture](docs/ARCHITECTURE.md), [testing](docs/TESTING.md).

## License

No license file has been added yet. All rights reserved by the author until a
license is chosen.
