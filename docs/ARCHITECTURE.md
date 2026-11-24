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
                               │ master output, sample for sample
                               ▼
                    Recorder ─► 24-bit WAV in Music/FLX4 Pocket
```

## Layers

| Layer | Files | Android-free |
| --- | --- | --- |
| DSP and deck logic | `Deck`, `Fx`, `Dsp`, `MixerMath`, `BeatGrid`, `BpmAnalyzer`, `Waveform`, `PcmRing`, `Recorder` | yes |
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

**Output rate.** Android gives a stream its low-latency ("fast") path only
when it runs at the rate the output is mixed at; a 44.1 kHz stream on a
48 kHz output goes through the normal mixer and a second buffer, some 60 ms
later. So the engine has no fixed rate: `openTrack` tries the rates the
output may run at, the likeliest first, and keeps the first one for which
`AudioTrack.getPerformanceMode()` reports the fast path (read after `play()`,
when the stream is on its output). Left alone, Android mixes a USB output at
the highest rate the interface offers, 48 kHz on the FLX4; from Android 14
the engine first asks for a 44.1 kHz mixer there
(`setPreferredMixerAttributes`), so that 44.1 kHz tracks reach the controller
without being resampled at all. The decks, the effects and the limiter are
told the rate (`Deck.setOutputRate`) while the output is stopped. The status
line shows the rate, `fast` when the path was granted, and the delay Android
reports.

Per block:

0. A master deck that stops hands the sync master role to the deck still
   playing. The follower renders first, while the master still stands where
   the block starts.
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
6. While REC is on, the master output of the block (after the master level and
   the limiter: what is sent out) is copied into a pooled block for the
   `Recorder`.

A fault in the mix loop stops the sound, not the app: the status line says so
and AUDIO restarts the output.

## Recording

`Recorder` is Android-free. The mixer thread only fills pooled blocks
(`take` / `put` never wait and never allocate); a writer thread converts them
to 24-bit PCM and writes them with positional `FileChannel` writes. The WAV
header is rewritten every five seconds, so a recording is still valid if the
app is killed; a file that would pass the 4 GB WAV limit is completed and the
next part begins. A write error ends the recording and is shown. A WAV has one
rate: if the output changes to another rate, the recording is completed and
the screen says why. `MainActivity` supplies the files: from Android 10 a
MediaStore audio entry under `Music/FLX4 Pocket` (no permission), before that
the app's own music folder.

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
reads a fractional cursor through the band-limited interpolation described
below. The ring is circular, so evicting old audio costs nothing.

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
`TrackBuffer`: 16-bit stereo, about 10 MB a minute, for tracks up to 15
minutes. Android keeps direct buffers on the Java heap, so the manifest asks
for a large heap (`largeHeap`): two 15-minute tracks need about 320 MB. If a
buffer still does not fit, that deck only streams. Reads prefer it, so within
seconds of loading every jump (hot cue, loop, beat jump, needle) finds its
audio already decoded, and the streaming decoder stops. Longer files only
stream.

Without keylock, tempo is a resampling read (`sourceRate / outputRate ×
speed`): pitch follows speed. Between two frames the read is band-limited
(`Sinc`: sixteen frames weighed by a Kaiser-windowed sinc, 128 phases,
interpolated between them), flat within 0.1 dB to 17 kHz; on a whole frame it
returns the sample itself, so a track at 0 % on an output at its rate, read
from its start, is played sample for sample (a jump, a loop or a sync
correction lands between frames). Keylock grains start on whole frames for
the same reason.

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
- **Sync**, as rekordbox plays BEAT SYNC: the follower's factor is
  `masterBpm / ownBpm`, folded to the nearer of half, same and double speed
  (87 against 174 BPM is one tempo) and recomputed every block so it follows
  the master's tempo. Whenever the follower starts or jumps (PLAY, hot cue,
  memory cue, search), its next block lands on the master's beat, crossfaded
  like any jump; a paused deck stays on its cue point. In between, a
  correction of at most ±0.4 % holds the beats together. A jog nudge, a bend
  button or a scratch is kept: the offset it leaves becomes the target. SYNC
  off folds the factor into the deck's own tempo, so nothing jumps, and
  `tempoPosition` is where a fader must sit for that speed: the FLX4 tempo
  fader, ignored while synced, takes over there (soft takeover).
- **Loops**: manual in/out (quantized to the grid when Q is on), beat loops,
  halve/double, reloop. `loopBeats()` gives the length in beats, exact for
  the pad sizes so the pads and the screen can show it. A jump past the loop
  end leaves the loop. A **loop roll** is a second, temporary loop with a
  slip cursor that keeps advancing; releasing the pad jumps to it.
- **Scratch**: an alpha-beta filter follows the platter's speed and takes up
  part of the remaining travel each block, so the sound does not trail a
  steady hand while the jog's coarse ticks stay smoothed.
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
`BpmAnalyzer.Onsets` collects the energy the beat grid is estimated from: a
first grid after one minute, so a deck can sync at once, then the final one
from the whole track (up to ten minutes). The waveform is handed to the deck
immediately and fills in while it is drawn. Results are cached: grid and hot
cues in SharedPreferences, waveforms as files in the cache directory, keyed by
document URI and modification time. Grids from before 0.6 only label the
library until their track is loaded and analysed again.

## Beat grid

Sync, quantize, loops and the drawn grid all rest on one tempo and the time
of one beat. A grid that is 0.05 BPM off is 115 ms off after five minutes, so
`BpmAnalyzer` works for thousandths of a BPM:

1. Onset envelope: the rise of the log energy per 5.8 ms frame, minus its
   local mean.
2. Tempo: autocorrelation up to four beats of the slowest tempo. A lag scores
   with its 2, 3 and 4 multiples (a beat also repeats after a bar), each value
   summed with its neighbours since a beat is rarely a whole number of
   frames; the shortest lag wins unless a longer one is 10 % better.
3. Exact period: the same autocorrelation peak is followed to 2, 4, 8 ...
   beats, up to half the stretch, with a parabolic fit: a fraction of a frame
   at a lag of two minutes is a thousandth of a BPM.
4. One octave: tempos read from 89.5 to 179 BPM, so 174 is never 87 (and 85
   reads 170; sync treats half and double as the same tempo).
5. Anchor: the envelope folded onto one beat; the centre of mass of its three
   busiest frames is the beat, finer than one frame.

Known limit: a breakbeat whose off-beat kicks outweigh its snares can anchor
half a beat off. TAP (three taps or more) fixes that without touching the
tempo: see `Deck.tapGrid`.

## Waveform drawing

`WaveformView` redraws on every display frame only while its picture moves
(playing, scratching, a jog cueing, an analysis filling in); a still deck is
redrawn about fifteen times a second, enough to pick up a new cue, loop or grid, and
the overviews always are. The mixer reports its position one audio buffer at
a time, so the view runs its own clock at the deck's current speed and only
steers it towards the reported position, less the output delay Android
reports: the playhead shows what is heard, not what is being mixed. Bars are
anchored to track time rather than to screen pixels, so they do not shimmer
while scrolling. The horizontal scale is heard time: two decks at the same BPM
show the same beat spacing.

## Screen

One layout, inflated once. The activity owns the audio engine, so the
manifest declares every configuration change as handled: a rotation, the dark
theme switching or a keyboard being plugged in never recreates it.
`MainActivity.applyOrientation` restacks the same views when the phone is
held upright (`fullSensor`): the three panels become a column, the library
puts its folders above its tracks, and the control bar and the library header
take two rows. Folded panels and the waveform height are remembered per
orientation. From Android 12 `fitDisplayShape` pads the screen away from the
camera cutout and, at both ends of its long sides, from the rounded corners.
Upright, the version button moves to the status row, where an update's
longer label fits. Two changes still restart the screen, and so stop the
music: a wallpaper or system colour change from Android 12 (it cannot be
declared as handled), and a display size or font size change only applies
after a restart.

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

While a deck plays or REC is on and the app is in front, `MainActivity` starts
`PlaybackService`, a `mediaPlayback` foreground service that only holds a
notification: it keeps the process from being frozen or killed when the screen
goes off or another app opens. The engine stays in the activity. The service
stops after ten minutes without playback or recording, or when the activity is
destroyed.

## Threads

| Thread | Work |
| --- | --- |
| main | UI, MIDI events, transport calls |
| `flx4-mixer` (urgent audio) | mix loop, AudioTrack writes |
| `flx4-decode`, one per deck | MediaExtractor + MediaCodec + ring writes, until the track buffer is complete |
| `flx4-fill`, one per deck (background priority) | whole-track decode into the `TrackBuffer` |
| `flx4-recorder`, while REC is on | PCM conversion and file writes |
| worker pool (4) | folder scan, track load, analysis, update check / download |

Deck transport methods and `Deck.render()` are synchronized on the deck; plain
mixer parameters are volatile.

## Updates

`UpdateChecker` reads `update.json` from the latest GitHub release and
compares its `versionCode`. `UpdateUi` drives the whole flow from one button.
`UpdateInstaller` verifies package name, version and signing certificate
before creating a PackageInstaller session; `InstallResultReceiver` forwards
the system confirmation.
