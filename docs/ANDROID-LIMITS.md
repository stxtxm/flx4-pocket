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

A powered USB-C hub can also work if it provides enough power, but the simplest
reliable setup is the dedicated power port. A USB-A charger with a USB-A to
USB-C cable is fine for the power port if the controller accepts that cable.

Source: <https://www.pioneerdj.com/product/dj-controllers/ddj-flx4>

## 2. USB audio into the FLX4 is not a reliable Android feature

The FLX4 contains a 4-channel USB audio interface: master stereo and headphone
stereo. iOS apps such as rekordbox/djay can use it. On Android, exposing a
multi-channel USB audio device to an app is OEM- and OS-dependent; Pioneer's own
FLX4 page marks USB audio playback for iPhone/iPad and explicitly says it is not
available on Android devices. Some phones and third-party apps can route a
stereo USB output, but independent master/cue channels are widely reported as
missing.

Consequences for this app:

- The audio engine always renders on the phone.
- **AUDIO OUT** lists `AudioManager` sink devices and tries
  `AudioTrack.setPreferredDevice()`. If your phone exposes the FLX4 (or another
  USB DAC) as a stereo sink, you can select it. It is best-effort, not a
  guaranteed driver.
- Even when a USB sink appears, Android typically receives only the stereo
  master; the FLX4's own headphone cue path is not fed by a four-channel stream.
- The stable pre-cue solution on Android is a **stereo split cable**: enable
  **SPLIT CUE**, master goes to the left channel and the cue bus to the right.
  This is the same family of workaround rekordbox for Android uses with split
  output. Audio becomes mono on each side.

Sources:

- <https://www.pioneerdj.com/product/dj-controllers/ddj-flx4> (USB audio section,
  "Not available on Android devices")
- <https://rekordbox.com/en/support/faq/connecting-to-dj-units-android/>
- <https://community.algoriddim.com/t/how-to-pre-cue-in-djay-for-android/35402>
  (Algoriddim: independent cue output is not supported on many Android devices;
  newer Pioneer controllers have a mono switch as a workaround)
- <https://support.alphatheta.com/en-US/articles/26320093306521> (FLX4 split
  output guidance)

The app does not claim 4-channel USB audio. If a future Android release exposes a
stable multichannel UAC path, the engine can be extended with a second output
pair, but that is outside the current platform contract.

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
- Beat sync locks to the analysed grid and corrects drift by up to 0.2%; BPM
  analysis can be wrong on rubato, live drums or very sparse intros. Use TAP or
  the tempo fader when the grid is wrong.
- The shared audio clock removes inter-player drift, but MIDI + Android audio
  latency is still typically tens of milliseconds. This is a controller app, not
  a hard real-time CDJ.
- MediaCodec supports local files the platform can decode (MP3, AAC/M4A, FLAC,
  WAV, OGG/Opus, ...). DRM-protected or unsupported codecs fail with a clear
  message.
