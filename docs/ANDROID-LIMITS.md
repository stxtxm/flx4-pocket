# Android and DDJ-FLX4 limits

This document exists so nobody buys or plugs in hardware expecting something the
platform cannot deliver. The app is deliberately honest about the following.

## 1. Powering the FLX4 from the phone

Pioneer documents single-cable bus power (5 V, 500 mA) for a PC/Mac only. For
a phone or tablet its manual says to power the controller through its
**power** port: a USB adapter or power bank that delivers 9 V 3 A (24 V
maximum), a certified cable, no power-saving mode. If that supply is too weak,
IN and OUT flash on both decks and do not respond.

A phone can also power the FLX4 through the **device** port, but not
dependably. On a Pixel 9a a direct USB-C to USB-C cable lights it only some of
the time; through a USB hub it has lit every time (whether that hub had its
own power supply was not noted). A USB-C to USB-A adapter with a USB-A to
USB-C cable should behave like the hub, but has not been tried. A USB-A port
always carries 5 V; a USB-C port switches it on only after both ends have
agreed which one supplies power. Why that agreement sometimes fails between
this phone and the FLX4 is not established.

```text
Pixel 9a ──► USB hub (or C-to-A adapter) ──► FLX4 device port   (hub: lit every time; adapter: untried)
Pixel 9a ──────────── USB-C to USB-C ──────► FLX4 device port   (sometimes dark)
9 V 3 A adapter or power bank ─────────────► FLX4 power port    (Pioneer's setup)
```

If it stays dark on a direct cable: unlock the phone, use the cable supplied
with the FLX4, unplug for a few seconds, turn the plug over, or use the hub.
Pioneer also rules out bus power with headphones under 32 Ω or a mono plug in
the headphones output. The app cannot help: Android gives apps no control over
the port's power role, and a controller without power does not exist for
them. Powering the controller drains the phone's battery faster.

Sources: DDJ-FLX4 Instruction Manual, "Connections", "Troubleshooting" and
"Specifications"
(<https://www.pioneerdj.com/en/support/documents/controller/ddj-flx4/>);
<https://rekordbox.com/en/support/faq/connecting-to-dj-units-android>;
Android's public `UsbManager` ("only host mode is supported in the public
API", <https://developer.android.com/reference/android/hardware/usb/UsbManager>).

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

- **Latency.** Android gives a stream its low-latency ("fast") path only when
  the stream runs at the rate the output is mixed at; otherwise it goes
  through the normal mixer and a second buffer, some 60 ms later. Left alone,
  Android mixes a USB output at the highest rate the interface offers, 48 kHz
  on the FLX4. So the app opens its stream at the output's own rate, and from
  Android 14 first asks for the controller's output to be mixed at 44.1 kHz,
  the rate of the music. The status line ends with the result: the rate,
  `fast` when the path was granted, and the delay Android reports. Expected
  with the FLX4: `44.1 kHz fast` and 20 to 35 ms (70 to 100 ms without
  `fast`). These figures come from reading Android's source, not from a
  measurement on a Pixel 9a: if yours differ, they are what to report.
- What remains is outside an app without native code: Android's fast mixer,
  the USB link and the controller's converter (10 to 18 ms), the app's own two
  blocks of buffer (it grows by a block after an underrun, so it settles on
  the smallest the output takes without glitches), and a Java thread that
  cannot get real-time priority. Bluetooth adds 150 ms or more and is not
  usable to mix. This is a controller app, not a hard real-time CDJ.
- The waveforms are drawn that reported delay behind the mixer, so the beat
  under the playhead is the beat being heard.
- **Sound.** A track at 0 % on an output at its own rate, read from its
  start, is played sample for sample. Between two samples (another speed, an
  output at another rate, and after a jump, a loop or a sync correction) it
  is read through a 16-tap windowed sinc: flat within 0.1 dB to 17 kHz, 0.3 dB down at
  18 kHz (the four-point cubic it replaces lost 1.7 dB at 15 kHz). With KEY
  the deck time-stretches (WSOLA): clean within the usual ±10 %, with audible
  grain artifacts at large changes. Scratching is always vinyl.
- Playback goes on with the screen off or another app in front through a
  foreground service started while the app is in front (Android refuses it
  from the background). On Android 13+ its notification only shows if
  notifications are allowed for the app; the service runs either way. It stops
  after ten minutes without playback or recording.
- **Beat grid.** The analysis assumes one tempo for the whole track and reads
  it between 89.5 and 179 BPM: an 85 BPM track shows 170. On such a track 4
  BEAT, BEAT JUMP and quantize count half beats, and sync with a track read at
  its own tempo can lock half a beat off; three taps on TAP at its real tempo
  halve the grid. It has been validated on synthesised rhythms, not
  on a music collection. It will be wrong on rubato, live drums or tempo
  changes, and a breakbeat whose off-beat kicks dominate can get a grid half a
  beat off: three taps on TAP move it onto the beat without changing the
  tempo. Beat sync locks to these grids and corrects drift by up to 0.4 %.
- **Recording.** REC writes the master output, after the master level and the
  limiter: the samples that are sent out, as 24-bit WAV at the output's rate,
  in `Music/FLX4 Pocket` (before Android 10, the app's own Music folder). Its
  level therefore follows MASTER LEVEL. About 950 MB an hour at 44.1 kHz;
  past 4 GB (four and a half hours at 44.1 kHz) it goes on in a second file. If the output changes to another rate mid-recording (the
  controller unplugged), the recording is completed and the screen says so:
  press REC again.
- **Do not change the wallpaper during a set.** From Android 12 a wallpaper or
  system colour change can restart the screen of apps, whatever they declare,
  and the mixer lives in this app's screen: the music stops (a recording is
  completed first). Rotating the phone, the dark theme switching
  or a keyboard being plugged in do not stop it.
- MediaCodec supports local files the platform can decode (MP3, AAC/M4A, FLAC,
  WAV, OGG/Opus, ...). DRM-protected or unsupported codecs fail with a clear
  message.
