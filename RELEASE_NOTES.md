FLX4 Pocket 0.7.0

- Track keys: each analysed track shows its key in Camelot notation (8A, 11B) beside its tempo, and on the deck next to its title. It is an estimate from the track's pitch: check it against rekordbox or your ear. The analysis runs in the background and pauses while a deck plays.
- Track tags: the list shows the title, artist, album and codec of each file; the format line (sample rate, channels, bitrate) is read from the file. SEARCH matches artist, album and genre too.
- SYNC follows the other deck, playing or stopped, so the pair can be armed in either start order. The SYNC light comes on at once on the synced deck, and on the master while the other follows it. A long press still makes that deck the master by hand.
- Pressing SYNC on the deck on air leaves its SYNC light on and the pair in time.
- Audio latency: the output buffer no longer starts oversized, and the app falls back when a forced 44.1 kHz USB mixer does not get Android's fast path. Check the status line for `fast` and the reported ms.
