# Releases and in-app updates

## Versioning

`versionCode` must increase for every release; `versionName` is the human version.
Both live in `app/build.gradle`:

```groovy
versionCode 4
versionName "0.2.0"
```

The release tag is `v<versionName>` (for example `v0.2.0`).

## Signing key

The key is **not** in the repository. Generate it once:

```bash
./scripts/setup-signing.sh
```

The script creates `keystore/release.jks` and `keystore/credentials.txt`
(both gitignored), prints the base64 value, and shows the `gh secret set`
commands. Back up both files somewhere safe: losing the key means installed
users cannot receive in-app updates without uninstalling and losing app data.

Required GitHub Actions secrets:

| Secret | Value |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | `base64 -w0 keystore/release.jks` |
| `ANDROID_STORE_PASSWORD` | store password |
| `ANDROID_KEY_ALIAS` | `flx4-pocket` |
| `ANDROID_KEY_PASSWORD` | key password |

Without the secrets, `.github/workflows/release.yml` falls back to a debug APK.
That fallback is useful for testing the workflow but is **not** a valid update
channel for an already installed release build, because the debug key differs.

## Cutting a release

1. Bump `versionCode` and `versionName` in `app/build.gradle`.
2. Update `CHANGELOG.md` and `RELEASE_NOTES.md`.
3. Run:
   ```bash
   ./gradlew testDebugUnitTest assembleRelease
   ```
4. Commit, push, then create and push the tag:
   ```bash
   git tag v0.2.0
   git push origin v0.2.0
   ```
5. The **Release** workflow:
   - builds the unit tests;
   - signs `release` with the four secrets;
   - renames the APK to `FLX4-Pocket-<version>.apk`;
   - writes `FLX4-Pocket-<version>.apk.sha256` and `update.json`;
   - creates (or updates) the GitHub release and uploads all three assets.

`update.json` looks like:

```json
{
  "versionCode": 4,
  "versionName": "0.2.0",
  "apk": "FLX4-Pocket-0.2.0.apk",
  "sha256": "<hex>",
  "notes": "Release notes shown inside the app"
}
```

The app only offers an update when `versionCode` is greater than the installed
package. It verifies the APK SHA-256, package name, version and signing
certificate before opening Android's installer.

## In-app update flow

Everything happens on the version button in the top bar (`UpdateUi`):

| Button shows | Meaning | Tap |
| --- | --- | --- |
| `v0.2.0` | Up to date, or not checked yet | Check now |
| `CHECKING…` | Reading the latest release | — |
| `UPDATE 0.2.1` (amber) | A newer `versionCode` is published | Release notes, then **Download** |
| `↓ 42%` | Downloading in the background | — |
| `INSTALL 0.2.1` (green) | Downloaded and SHA-256 verified | Install |

1. The first release is installed manually (browser or `adb install`).
2. The app checks a few seconds after launch and then every hour. Background
   checks never show a dialog or a toast.
3. The download does not block the screen, so a mix can continue.
4. Nothing is installed until INSTALL is tapped; if a deck is playing the app
   asks first, because installing restarts it.
5. Android may ask once to allow "install unknown apps" for FLX4 Pocket. The
   app opens that setting and continues the installation when you return.
6. Android shows its own confirmation. This is a platform requirement.

## Why the key is critical

PackageInstaller refuses to replace an installed app with a differently signed
APK. The app pre-checks this and shows:

> The update is signed with a different key. Install the release APK from the
> same channel instead.

If the key is lost, the only recovery is to publish a new application id or ask
users to uninstall. Keep the keystore and passwords backed up.

## CI

- `.github/workflows/ci.yml` runs on pushes and pull requests:
  unit tests + debug APK.
- `.github/workflows/release.yml` runs on `v*` tags or manually:
  tests + signed release + GitHub release assets.

Both use JDK 17 and Android SDK 34. The release workflow requests
`contents: write` to create the release with `GITHUB_TOKEN`.
