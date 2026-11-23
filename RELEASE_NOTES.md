FLX4 Pocket 0.1.2

Standalone first install — includes the in-app updater fix.

- Fixed in-app updates on Pixel 9a / Android 15: the PackageInstaller result
  status is now delivered through a mutable `PendingIntent`, so the app no
  longer reports "No update found" when the update actually installed.
- Play works standalone: the PLAY button gives feedback when no track is
  loaded, and audio errors are shown in the status line.
- Real-time horizontal spectra for both decks, like the famous DJ apps.
- Bigger touch buttons; the version label shows the installed build.
- In-app updater: checks every 30 minutes while the app is open, and the
  update button always shows the latest version.
- Unit tests for the FFT/spectrum DSP.

This is the version to install if you tried 0.1.1 on a Pixel 9a and the
update never went through.
