FLX4 Pocket 0.7.1

- Less delay with the FLX4: if Android refuses its low-latency path to the four-channel stream (Pixel 9 family), the app plays stereo on the fast path, some 60 ms sooner. Check the status line: it should read `stereo` and `fast`. For the headphones, set the FLX4's rear switch on MONO and tick "Headphones over 2 channels" in AUDIO.
- The output buffer shrinks back 10 s after a glitch instead of staying larger for the rest of the set.
- Controller moves reach the decks without waiting for the screen, with less jitter.
- Jog nudges act sooner and stop when the hand stops.
- Holding CUE then pressing PLAY keeps the deck playing, as on CDJs.
- A synced deck nudged off the beat comes back within a second.
- With KEY on, hot cues, jumps and loops land exactly on the beat.
- The screen and the pad LEDs follow the controller at once.
