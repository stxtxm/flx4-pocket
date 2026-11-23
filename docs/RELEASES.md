# Releases and in-app updates

## Versioning

`versionCode` must increase for every release; `versionName` is the human version.
Both live in `app/build.gradle`:

```groovy
versionCode 2
versionName "0.1.1"
```

The release tag is `v<versionName>` (for example `v0.1.1`).

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
   git tag v0.1.1
   git push origin v0.1.1
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
  "versionCode": 2,
  "versionName": "0.1.1",
  "apk": "FLX4-Pocket-0.1.1.apk",
  "sha256": "<hex>",
  "notes": "Release notes shown inside the app"
}
```

The app only offers an update when `versionCode` is greater than the installed
package. It verifies the APK SHA-256, package name, version and signing
certificate before opening Android's installer.

## In-app update flow on a Pixel 9a

1. The first release is installed manually (browser or `adb install`).
2. On launch the app checks for a newer release at most every six hours; the
   **CHECK UPDATES** button forces a check.
3. If an update exists, the button shows the version and a dialog shows the
   release notes.
4. **Download & install** downloads the APK with progress. Android may ask once
   to allow "install unknown apps" for FLX4 Pocket; the app opens that setting
   and resumes automatically when you return.
5. Android shows its own confirmation. This is a platform requirement and cannot
   be bypassed by a normal app.
6. On success the downloaded file is deleted.

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
