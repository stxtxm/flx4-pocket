# Changelog

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
