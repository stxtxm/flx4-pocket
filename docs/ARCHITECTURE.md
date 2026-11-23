# Architecture

FLX4 Pocket is an Android-only, dependency-light app. It uses the platform MIDI,
MediaCodec, AudioTrack, SAF and PackageInstaller APIs; there is no native engine
and no third-party runtime library in the APK.

## Data flow

```text
SAF folder ──► Library.scan ──► track list ──► AudioSource (MediaCodec)
                                                  │  absolute-frame PCM ring
DDJ-FLX4 ──USB MIDI──► Flx4Midi ──► MainActivity   │
                                      │            ▼
                                      └──────► AudioEngine
                                                Deck A + Deck B
                                                trim/EQ/filter
                                                fader/crossfader/cue
                                                AudioTrack 44.1 kHz stereo
```

## Audio engine

`AudioEngine` owns one `AudioTrack` and a high-priority thread. Each block is
256 frames (about 5.8 ms at 44.1 kHz). Both decks are rendered into the same
block, so there is no second player clock to drift against.

`Deck.render()` returns post-trim, post-EQ and post-filter audio, but pre-channel
fader and pre-crossfader. The engine builds two buses:

- **master** = channel fader × crossfader for each deck;
- **cue** = headphone-cue-enabled decks, independent of channel fader and
  crossfader (standard pre-fader cue).

In normal mode both output channels carry the master bus. In split-cue mode:

```text
left  = master
right = cue × (1 − headphone mix) + master × headphone mix
```

so a stereo splitter cable gives master on one side and headphones on the other.

## Decoding and buffering

`AudioSource` runs a MediaCodec decoder on its own thread. Decoded PCM is written
to a `PcmRing` at the absolute frame implied by the buffer presentation time.
The mixer reads a fractional cursor and interpolates. Absolute addressing means:

- a seek can reset the ring and discard stale audio deterministically;
- loop wraps within the eight-second window are sample-accurate;
- an underrun produces silence without advancing the deck cursor, so the audio
  clock and the track position stay consistent.

Seeking restarts the codec and asks the extractor to seek to the closest sync
sample. The mixer waits until the ring contains the requested frame.

Tempo is implemented by reading the ring with a step of
`sourceRate / 44100 × speed`. This changes pitch with speed; keylock is a future
extension.

## Sync

Each deck has a `BeatGrid`: BPM plus the time of one beat. The anchor is only
meaningful modulo a beat, and sync uses beat phase, not a specific downbeat.

On SYNC:

1. the other deck becomes the sync master;
2. the slave's speed multiplier is set to `masterBpm / slaveBpm`;
3. the slave cursor is nudged by the shortest signed phase error (at most half a
   beat);
4. during playback, a small correction of up to ±0.2% is applied each block to
   keep the phases locked.

`BeatGrid.phaseError()` wraps the error into `[-0.5, 0.5)` so the correction
never takes the long way around. If the tempo fader is moved, sync releases, as
on a CDJ.

BPM analysis (`BpmAnalyzer`) computes onset strength from a 60-second mono
decode, autocorrelates it over 55–200 BPM, folds the result into 70–180 BPM and
chooses a phase whose beat positions best match the onset envelope. Results are
cached by document URI + last-modified time. TAP TEMPO overrides the estimate.

## MIDI

`Flx4Midi` enumerates USB MIDI devices through `MidiManager`, auto-opens a device
whose name contains `FLX4`, parses running-status MIDI, combines 14-bit
controller pairs, and dispatches semantic callbacks on the main thread.

`MidiMap` contains the exact official addresses. Pad notes are interpreted using
the last pad-mode button, because the same pad channel/notes are reused by
HOT CUE, BEAT LOOP, BEAT JUMP and the other modes. LED writes are de-duplicated
so a 120 ms UI refresh does not spam the controller.

MainActivity is the single wiring layer: MIDI events and touch events update the
volatile deck parameters; the mixer thread reads them without locks. Transport
mutations (`load`, `seek`, `play`, loops) are synchronized on the deck so the
mixer never sees a half-applied seek.

## Library

`Library` walks a SAF document tree with `DocumentsContract`, filtering audio
MIME types and common extensions. No storage permission is requested. Metadata
is read when a track is loaded; BPM is cached in SharedPreferences.

## Updates

`UpdateChecker` fetches the latest GitHub release, downloads the small
`update.json` asset, and compares its `versionCode` with the installed package.
The APK is downloaded only after user action and is checked against the manifest
SHA-256. `UpdateInstaller` then verifies:

- the file is an APK for this package;
- its version code is newer;
- its signing certificate matches the installed app.

Only then does it create a PackageInstaller session. `InstallResultReceiver`
forwards `STATUS_PENDING_USER_ACTION` to the system confirmation UI and reports
success or failure. The same signing key across releases is mandatory.

## Threading summary

| Thread | Work |
| --- | --- |
| main | UI, MIDI callbacks, transport mutations |
| `flx4-mixer` (urgent audio) | 256-frame mix loop, AudioTrack writes |
| one decoder thread per deck | MediaExtractor + MediaCodec + ring writes |
| worker pool (3) | folder scan, track load, BPM analysis, update download |

## Fail-safe choices

- No native libraries and no network permission beyond GitHub release access.
- No background service; the activity keeps the screen on while mixing.
- Decoder errors become a visible deck error, never a crash.
- Update network errors are reported, not retried in a loop.
- Wrong-signature updates are rejected before the installer prompt.
