# Testing

## Automated

```bash
./gradlew testDebugUnitTest
```

Covered without an Android device:

- `Biquad`: transparency at 0 dB, low-pass DC gain and 10 kHz attenuation,
  high-pass DC rejection.
- `MixerMath`: equal-power crossfader, unity EQ/trim, fader curve.
- `BeatGrid`: phase, anchor-relative phase, wrapped phase error, invalid grid.
- `PcmRing`: interpolation, seek reset, eviction of old audio.
- `BpmAnalyzer`: synthetic 120 BPM and 128 BPM click tracks (beat-aligned
  anchor) and silence rejection.

## Build check

```bash
./gradlew assembleDebug
./gradlew assembleRelease   # requires the signing environment variables
```

## Hardware checks still required

These cannot be executed in this repository's CI and must be verified with a real
DDJ-FLX4 and a Pixel 9a:

1. USB MIDI enumeration and reconnect after unplugging.
2. Every fader/knob direction and 14-bit resolution, especially the tempo fader
   sign and the jog direction.
3. LED feedback for play, cue, sync, channel cue and pads.
4. Tempo range cycling with SHIFT + BEAT SYNC.
5. Audio output selection: phone speaker, wired output, and (if the phone
   exposes it) the FLX4 USB stereo sink.
6. Split-cue with a stereo splitter cable.
7. Latency feel and sync stability over a long mix.
8. First-run SAF folder permission and persisted access after a reboot.
9. In-app update from one signed release to the next, including the Android
   unknown-source permission and installer confirmation.
10. Behaviour when the phone is unplugged from the controller during playback.

Record the firmware version and phone build in any bug report.

## Manual smoke test with adb

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n io.github.timotheegrollier.flx4pocket/.MainActivity
adb logcat -s Flx4Midi Flx4Audio AndroidRuntime
```

No emulator or device was attached to the environment where 0.1.1 was built, so
these hardware checks are explicitly pending.
