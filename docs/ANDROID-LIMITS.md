# Android and DDJ-FLX4 limits

This document exists so nobody buys or plugs in hardware expecting something the
platform cannot deliver. The app is deliberately honest about the following.

## 1. The FLX4 is not powered by the phone

The DDJ-FLX4 has two USB-C ports: one for power and one for the device
connection. Pioneer's product page for smartphone use says to connect a USB
power adapter or mobile battery to the controller for power, then connect the
phone to the controller's device USB-C port. The controller's current draw is
not something a phone's OTG port should be expected to supply.

For a Pixel 9a setup:

```text
5 V USB-C power adapter ──► FLX4 power port
Pixel 9a ────────────────► FLX4 device port (USB-C data)
FLX4 master RCA / headphones ──► speakers / headphones
```

The app cannot influence this: if the controller's lights do not come on, or
it disconnects when the jog LEDs light up, it is not getting enough current.
A powered USB-C hub can also work if it provides enough power, but the simplest
reliable setup is the dedicated power port. A USB-A charger with a USB-A to
USB-C cable is fine for the power port if the controller accepts that cable.

Source: <https://www.pioneerdj.com/product/dj-controllers/ddj-flx4>

## 2. USB audio through the FLX4 depends on the phone

The FLX4 contains a class-compliant four-channel USB audio interface: outputs
1-2 feed MASTER, outputs 3-4 feed PHONES. Pioneer's own page lists USB audio
playback for iPhone/iPad and says it is not available on Android devices with
its apps, and Algoriddim reports that independent cue output is missing on
many Android devices. Whether a given phone exposes all four channels to apps
is decided by its USB audio HAL, not by this app.

What the app does:

- When a USB audio output appears it is selected automatically.
- If Android reports four or more channels for it, the app opens a
  four-channel stream (channel index mask) with master on 1-2 and headphones
  on 3-4. The status line then reads `master 1-2 + phones 3-4`.
- If the device reports stereo only, or refuses the four-channel stream, the
  app plays the master in stereo. The status line reads `stereo`. On the
  FLX4 that normally means sound on MASTER and nothing on PHONES.
- **Split cue** (AUDIO menu) is the fallback for any stereo output: master on
  the left, headphones on the right, through a splitter cable.

**This path is untested on hardware.** The code follows the documented Android
APIs, but nobody has yet confirmed on a Pixel 9a which of the three cases
applies. `docs/TESTING.md` lists the adb commands that show what the phone
exposes.

Sources:

- <https://www.pioneerdj.com/product/dj-controllers/ddj-flx4>
- <https://community.algoriddim.com/t/how-to-pre-cue-in-djay-for-android/35402>
- <https://developer.android.com/reference/android/media/AudioFormat.Builder#setChannelIndexMask(int)>

## 3. The installer confirmation cannot be removed

The in-app updater downloads and verifies the APK, then creates a
`PackageInstaller` session. Android requires the user to confirm installation of
an app update from a non-system app. A normal APK cannot bypass this. The app:

1. checks `canRequestPackageInstalls()`;
2. opens the "install unknown apps" screen for its own package if needed;
3. resumes automatically when you come back;
4. hands the verified APK to PackageInstaller, which shows the system prompt.

On a Pixel 9a this is a one-time permission and one confirmation per update.
Any claim of "silent install" without device-owner/MDM privileges would be false.

## 4. Update signing

Android only replaces an installed app if the new APK is signed with the same
certificate. The repository's release workflow reads a stable keystore from
GitHub Actions secrets. If a release is built with a different key, Android
returns `INSTALL_FAILED_UPDATE_INCOMPATIBLE`, and the app shows the clearer
message produced by its own signature pre-check. Never publish a release from a
different key under the same application id.

## 5. Audio quality and timing

- Tempo changes are implemented by resampling. There is no keylock yet, so
  moving the pitch fader changes pitch as well as speed.
- Beat sync locks to the analysed grid and corrects drift by up to 0.4%; BPM
  analysis can be wrong on rubato, live drums or very sparse intros. Use TAP or
  the tempo fader when the grid is wrong.
- The shared audio clock removes inter-player drift, but MIDI + Android audio
  latency is still typically tens of milliseconds. This is a controller app, not
  a hard real-time CDJ.
- MediaCodec supports local files the platform can decode (MP3, AAC/M4A, FLAC,
  WAV, OGG/Opus, ...). DRM-protected or unsupported codecs fail with a clear
  message.
