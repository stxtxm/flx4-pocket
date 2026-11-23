# Architecture

FLX4 Pocket is an Android-only app with no native code and no runtime
dependency: it uses the platform MIDI, MediaCodec, AudioTrack, SAF and
PackageInstaller APIs directly.

## Data flow

```text
music folder (SAF) ─► Library.scan ─► track list
                                         │ load
                    ┌────────────────────┴───────────────────┐
                    ▼                                        ▼
      AudioSource (streaming decode)            TrackAnalyzer (one full decode)
      PcmRing, a few seconds ahead              Waveform + BpmAnalyzer -> cache
                    │                                        │
touch UI ──┐        ▼                                        ▼
           ├─► MainActivity ─► AudioEngine ── Deck A / Deck B ── waveform, grid
FLX4 MIDI ─┘   (wiring)        │  trim, EQ, filter, pad FX per deck
 Flx4Midi ─► Flx4Decoder       │  faders, crossfader, Beat FX, cue bus, limiter
                               ▼
                    AudioTrack: stereo, split cue or 4 channels
```

## Layers

| Layer | Files | Android-free |
| --- | --- | --- |
| DSP and deck logic | `Deck`, `Fx`, `Dsp`, `MixerMath`, `BeatGrid`, `BpmAnalyzer`, `Waveform`, `PcmRing` | yes |
| Controller protocol | `Flx4Protocol` (`Flx4Decoder`), `MidiMap` | yes |
| Platform I/O | `AudioEngine`, `AudioSource`, `PcmDecoder`, `Flx4Midi`, `Library`, `Update*` | no |
| Screen | `MainActivity`, `DeckPanel`, `WaveformView`, `Controls`, `UpdateUi` | no |

The first two layers hold all the behaviour and are what the JVM tests
exercise. `Deck` reads audio through the small `PcmSource` interface, so tests
drive it with an in-memory source.

## Audio engine

`AudioEngine` owns one `AudioTrack` and one high-priority thread that mixes
256-frame blocks (5.8 ms at 44.1 kHz). Both decks render into the same block,
so there is no second player clock to drift against.

Per block:

1. `Deck.render()` produces each deck post-trim, EQ, filter and pad FX, but
   pre-fader.
2. The **cue bus** is tapped (decks whose CUE button is on), then the channel
   faders and the equal-power crossfader are applied in place, ramped across
   the block so fast moves and cuts do not click.
3. **Beat FX** process deck A, deck B or the summed master. They sit after the
   faders so an echo keeps ringing when a fader closes.
4. The master passes a stereo-linked peak limiter (`Limiter`): the gain drops
   at once to keep peaks under -0.45 dBFS and recovers over about 100 ms. The
   headphones keep the gentler `MixerMath.softClip` curve.
5. Headphones = cue bus (plus the master if MASTER CUE is on), blended with
   the master by the headphones mix knob.

Output layouts:

| Layout | Channels | When |
| --- | --- | --- |
| Stereo | master L/R | default |
| Split cue | master mono / headphones mono | user option on a stereo output |
| Four channels | master on 1-2, headphones on 3-4 | the selected device reports four or more channels |

`AudioEngine` registers an `AudioDeviceCallback`: a USB audio sink that appears
is selected automatically; if the current output disappears, both decks are
paused before falling back to the phone, so the speaker never takes over a
set at full volume. The four-channel track uses a channel *index* mask
(`0xF`), which addresses the interface's outputs directly.

## Decoding and buffering

`AudioSource` runs a MediaCodec decoder on its own thread and writes PCM into
a `PcmRing` at the absolute frame given by each buffer's timestamp. The mixer
reads a fractional cursor with four-point cubic (Catmull-Rom) interpolation,
which keeps the highs that a linear read loses whenever the tempo is off 0 %.
The ring is circular, so evicting old audio costs nothing.

A file decodes far faster than it plays, and the ring evicts its oldest audio
when full. The decoder is therefore **throttled**: it only writes while less
than six seconds are buffered ahead of the last frame the mixer asked for
(`PcmRing.wantsData`). The ring holds 24 seconds, so about 18 seconds behind
the playhead stay available for loops, rolls and backward scratching without
touching the codec.

A seek asks the extractor for the previous sync sample, flushes the codec and
resets the ring to the target frame; decoded audio before the target is
dropped, so there is no silent gap. If the mixer finds a frame missing for
250 ms (for example scratching far backwards), it requests a seek itself.

A second thread (`flx4-fill`) decodes the whole track at full speed into a
`TrackBuffer`: 16-bit stereo in native memory, about 10 MB a minute, for
tracks up to 15 minutes. Reads prefer it, so within seconds of loading every
jump (hot cue, loop, beat jump, needle) finds its audio already decoded, and
the streaming decoder stops. Longer files only stream.

Without keylock, tempo is a resampling read (`sourceRate / 44100 × speed`):
pitch follows speed.

## Deck

- **Transport**: play/pause, CDJ cue (set, preview while held, return).
- **Speed** = tempo fader × sync factor × (1 + nudge + jog bend). While
  scratching, the cursor instead follows the pending jog/finger movement,
  spread over a few blocks.
- **Keylock** (WSOLA): the cursor still moves at that speed, but the audio is
  read in grains at the track's own pitch: a new grain every 70 ms, started
  at the cursor and shifted by up to 12 ms to where its waveform best
  continues the previous grain (normalised cross-correlation, coarse then
  fine), crossfaded over 12 ms. Scratching bypasses it.
- **Jumps** (hot cues, loops, seeks) crossfade from the old position over
  3 ms instead of cutting; with keylock the next grain starts at the target.
- **Sync**: the follower's factor is `masterBpm / ownBpm`, recomputed every
  block so it follows the master's tempo; its cursor is moved by the shortest
  phase error, then a correction of at most ±0.4 % per block keeps the phases
  locked. Taking over with a tempo fader releases sync. `tempoPosition` is
  where a fader must sit for the current speed, sync included: the FLX4 tempo
  fader only takes over there (soft takeover).
- **Loops**: manual in/out (quantized to the grid when Q is on), beat loops,
  halve/double, reloop. A **loop roll** is a second, temporary loop with a
  slip cursor that keeps advancing; releasing the pad jumps to it.
- **Pads**: `padDown(mode, pad, shift)` / `padUp(mode, pad)` implement every
  mode. The touch UI passes the deck's selected mode; the controller passes
  the mode encoded in the pad note.
- **Smart CFX**: the filter becomes resonant and feeds a 3/4-beat echo whose
  level follows the knob.

## Effects

`FxUnit` is one tempo-synced stereo insert with a type, a beat length and a
depth. There are five instances: the Beat FX unit in the engine and, per
deck, one for pad FX and one for the Smart CFX echo. Echo and reverb keep
rendering their tail for a few seconds after switch-off without taking new
input. TRANS is phase-locked to the deck's beat position.

## Waveforms

`TrackAnalyzer` decodes each track once: `WaveformBuilder` reduces it to 150
points per second of three peak bands (split at about 200 Hz and 4 kHz), and
`BpmAnalyzer.Onsets` feeds the tempo estimate after the first minute. The
waveform is handed to the deck immediately and fills in while it is drawn.
Results are cached: grid and hot cues in SharedPreferences, waveforms as
files in the cache directory, keyed by document URI and modification time.

`WaveformView` redraws on every display frame. The mixer reports its position
one audio buffer at a time, so the view runs its own clock at the deck's
current speed and only steers it towards the reported position; that is what
keeps the scroll smooth. Bars are anchored to track time rather than to
screen pixels, so they do not shimmer while scrolling. The horizontal scale is
heard time: two decks at the same BPM show the same beat spacing.

## MIDI

`Flx4Midi` finds a USB MIDI device whose name contains `FLX`, forwards its
bytes to `Flx4Decoder` on the main thread and sends LED / meter messages,
de-duplicated so the periodic refresh only transmits changes. Every 200 ms it
sends the controller's keep-alive (from the Mixxx mapping), which the FLX4
answers with its control positions (see `docs/DDJ-FLX4-MIDI.md`).

`Flx4Decoder` handles running status, real-time bytes, SysEx, 14-bit
controller pairs (kept per channel) and relative encoders, reports absolute
controls only when their position changes, and emits
`Flx4Listener` events: `onButton`, `onKnob`, `onJog`, `onPad`, `onPadMode`,
`onBrowse`. `MainActivity` maps those to the same engine calls as the touch
controls.

## Screen refresh

`MainActivity` is a `Choreographer.FrameCallback`. Every frame it mirrors
control positions and meters; every sixth frame it updates texts and button
states. Views only invalidate when a value changed. Controller LEDs are
refreshed by a 50 ms handler loop instead, so they keep following the app with
the screen off.

## Background playback

While a deck plays and the app is in front, `MainActivity` starts
`PlaybackService`, a `mediaPlayback` foreground service that only holds a
notification: it keeps the process from being frozen or killed when the screen
goes off or another app opens. The engine stays in the activity. The service
stops after ten minutes without playback, or when the activity is destroyed.

## Threads

| Thread | Work |
| --- | --- |
| main | UI, MIDI events, transport calls |
| `flx4-mixer` (urgent audio) | mix loop, AudioTrack writes |
| `flx4-decode`, one per deck | MediaExtractor + MediaCodec + ring writes, until the track buffer is complete |
| `flx4-fill`, one per deck (background priority) | whole-track decode into the `TrackBuffer` |
| worker pool (4) | folder scan, track load, analysis, update check / download |

Deck transport methods and `Deck.render()` are synchronized on the deck; plain
mixer parameters are volatile.

## Updates

`UpdateChecker` reads `update.json` from the latest GitHub release and
compares its `versionCode`. `UpdateUi` drives the whole flow from one button.
`UpdateInstaller` verifies package name, version and signing certificate
before creating a PackageInstaller session; `InstallResultReceiver` forwards
the system confirmation.
