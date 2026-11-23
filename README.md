# FLX4 Pocket

[![CI](https://github.com/stxtxm/flx4-pocket/actions/workflows/ci.yml/badge.svg)](https://github.com/stxtxm/flx4-pocket/actions/workflows/ci.yml)
[![Release](https://img.shields.io/github/v/release/stxtxm/flx4-pocket)](https://github.com/stxtxm/flx4-pocket/releases/latest)

A focused two-deck Android DJ app for the **Pioneer DJ DDJ-FLX4**, using the
controller's real USB MIDI map (the same addresses as rekordbox, djay and the
Mixxx FLX4 mapping). It plays a local music folder, beat-matches two decks,
gives you EQ/filter/trim, hot cues, beat loops and beat jumps, and mirrors the
state on the controller LEDs.

Built for a **Pixel 9a** first, compatible with Android 8.0+ (API 26).

> **Read the hardware truth first.** The DDJ-FLX4 cannot be powered by the
> phone, and stock Android cannot reliably give an app the FLX4's four-channel
> master/cue USB audio interface. This app is therefore a **MIDI controller app
> with audio from the phone**, or from a USB audio device your phone exposes.
> It does not pretend to replace rekordbox's USB audio path. Details and
> sources: [docs/ANDROID-LIMITS.md](docs/ANDROID-LIMITS.md).

## What works in 0.1.0

- Exact DDJ-FLX4 MIDI addresses for transport, tempo, jogs, channel faders,
  crossfader, trim, 3-band EQ, filters, headphone mix, browse/load, cue,
  sync and performance pads.
- Sample-clock two-deck mixer with beat sync:
  - BPM analysis on device, tap tempo, tempo range cycling;
  - beat-phase alignment and continuous ±0.2% drift correction;
  - shared AudioTrack clock, so the two decks never drift as independent players.
- Per-deck trim, 3-band EQ, filter, channel fader and equal-power crossfader.
- Hot cues (8 per deck), beat loops (1/4 to 32 beats), beat jumps, manual loop
  in/out and reloop.
- FLX4 LED feedback: play, cue, sync, channel cue and performance pads.
- Local library through the Android folder picker (no storage permission).
- Best-effort output routing to phone, wired, Bluetooth or USB audio outputs.
- Split-cue output (master left / cue right) for a stereo splitter cable.
- In-app GitHub Releases updater with SHA-256, package, version and signature
  checks before handing the APK to Android's installer.
- Unit tests for DSP, ring buffering, beat math and BPM estimation.

## What is intentionally not here yet

- 4-channel USB audio and cue through the FLX4's own headphone jack (Android
  platform limitation, see below).
- Keylock/time-stretch: changing tempo changes pitch, like a vinyl pitch fader.
- Beat FX, sampler, stems/keyboard modes, pad FX and key shift.
- Streaming services, recording, cloud library, DVS.
- A rekordbox-identical waveform view; the UI shows a beat-phase bar instead.

## Install (first time)

1. Download `FLX4-Pocket-<version>.apk` from
   [Releases](https://github.com/stxtxm/flx4-pocket/releases/latest).
2. Either open it on the phone and allow installation from that source, or:
   ```bash
   adb install -r FLX4-Pocket-0.1.0.apk
   ```
3. Power the DDJ-FLX4 through its **power** USB-C port with a 5 V adapter or
   power bank. Connect the phone to the controller's **device** USB-C port.
4. Open FLX4 Pocket, tap **LIBRARY → FOLDER**, pick a music folder, then tap a
   track. You can also use the FLX4 browse knob and LOAD 1 / LOAD 2.
5. Choose **AUDIO OUT** if your phone exposes a USB/wired output. Otherwise the
   phone speaker or its normal media output is used.
6. For pre-cueing, use a stereo splitter cable: enable **SPLIT CUE** and send
   master to the left channel and cue to the right channel.

The FLX4 power port is separate from the data port by design; the phone's OTG
current is not enough for the controller.

### Do not open the APK on Fedora/GNOME

If double-clicking the APK in GNOME Files starts GNOME Software/PackageKit and
shows an authentication error ("Mise à jour impossible… groupe wheel / mot de
passe root"), that is the Linux package manager trying to install an **Android**
package as if it were an RPM. It is unrelated to FLX4 Pocket and cannot succeed.

Copy the APK to the Pixel and open it there, or install it from this repository
with adb:

```bash
adb install -r FLX4-Pocket-0.1.0.apk
```

## Controller workflow

| FLX4 control | App action |
| --- | --- |
| PLAY / PAUSE | Play/pause that deck |
| CUE | CDJ-style cue: paused sets a cue, playing jumps to cue and stops |
| BEAT SYNC | Match the other deck's BPM and phase |
| SHIFT + BEAT SYNC | Cycle tempo range (6/8/16/25%) |
| Long BEAT SYNC | Make that deck the master |
| Tempo fader | Pitch fader, 14-bit, ± the selected range |
| Jog wheel | Seek/scratch-style nudge |
| SHIFT + jog | Fast seek |
| Channel fader / trim / EQ / filter | Mixer controls |
| Crossfader | Equal-power master crossfade |
| Headphones CUE (channel) | Add that deck to the cue bus |
| Headphone mixing knob | Blend cue/master in split-cue mode |
| LOOP IN / OUT / RELOOP | Manual loop |
| HOT CUE / BEAT LOOP / BEAT JUMP + pads | Pad modes, as labelled |
| SHIFT + hot-cue pad | Delete hot cue |
| Browse knob / press | Select a track / open the library |
| LOAD 1 / LOAD 2 | Load selected track into A / B |
| SHIFT + CUE | Toggle cue quantize |

The complete address table, including the controls that are not implemented yet,
is in [docs/DDJ-FLX4-MIDI.md](docs/DDJ-FLX4-MIDI.md).

## Updates on the phone

Every release publishes `update.json`, the APK and its SHA-256 in GitHub
Releases. The app checks the latest release at most every six hours (or when
you tap **CHECK UPDATES**), verifies the manifest, downloads the APK, checks the
hash, package name, version code and signing certificate, then opens Android's
PackageInstaller.

Android always shows its own install confirmation for a normal app; that cannot
be bypassed on a stock Pixel. The app asks for "install unknown apps" once,
opens the setting, resumes automatically when you return, and then hands over to
the system prompt. The update must be signed with the same key as the installed
build, which is why the release keystore is stored in GitHub Actions secrets.
See [docs/RELEASES.md](docs/RELEASES.md).

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
app/src/main/java/.../AudioEngine.kt   shared-clock two-deck mixer
app/src/main/java/.../Deck.kt          transport, sync, EQ, loops, cues
app/src/main/java/.../AudioSource.kt   MediaCodec decoder + PCM ring
app/src/main/java/.../Flx4Midi.kt      USB MIDI discovery, parsing, LEDs
app/src/main/java/.../MidiMap.kt       exact FLX4 addresses
app/src/main/java/.../BpmAnalyzer.kt   onset autocorrelation
app/src/main/java/.../MainActivity.kt  controller + UI wiring
app/src/main/java/.../UpdateChecker.kt GitHub release manifest
app/src/main/java/.../UpdateInstaller.kt PackageInstaller session
docs/                                  mapping, limits, architecture, releases
.github/workflows/                     CI and release pipeline
```

More: [architecture](docs/ARCHITECTURE.md), [testing](docs/TESTING.md).

## License

No license file has been added yet. All rights reserved by the author until a
license is chosen.
