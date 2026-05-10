# Releasing WatchBridge

One-time setup, then every release after is `git tag vX.Y.Z && git push --tags`.

## One-time: generate the release keystore

The keystore is the project's signing identity. Treat it like a private key — if you lose it, you can never publish updates that install over the existing app (users would have to uninstall first).

```bash
keytool -genkey -v \
  -keystore watchbridge-release.keystore \
  -keyalg RSA -keysize 4096 -validity 10000 \
  -alias watchbridge
```

When prompted:
- **Keystore password:** strong, unique. Save it.
- **Distinguished name:** anything reasonable (`CN=WatchBridge, O=WatchBridge, C=US`).
- **Key password:** same as keystore password (simpler).

**Back up the `.keystore` file** somewhere safe — 1Password attachment, encrypted disk, etc. Don't commit it. The repo's `.gitignore` already blocks `*.keystore`.

## One-time: add four GitHub Secrets

GitHub repo → **Settings → Secrets and variables → Actions → New repository secret**. Add all four:

| Secret name         | Value                                                              |
| ------------------- | ------------------------------------------------------------------ |
| `KEYSTORE_BASE64`   | Output of `base64 -i watchbridge-release.keystore` (one long line) |
| `KEYSTORE_PASSWORD` | The keystore password from `keytool`                               |
| `KEY_ALIAS`         | `watchbridge`                                                      |
| `KEY_PASSWORD`      | The key password (same as keystore password if you reused it)      |

To copy the base64 to your clipboard on macOS:
```bash
base64 -i watchbridge-release.keystore | pbcopy
```

## Cutting a release

```bash
git tag v0.2.0
git push origin v0.2.0
```

The `Release` workflow (`.github/workflows/release.yml`) will:
1. Build a signed release APK (`assembleRelease`).
2. Verify the signature with `apksigner`.
3. Create a GitHub Release named `WatchBridge 0.2.0` with auto-generated notes from commits/PRs since the previous tag.
4. Attach `watchbridge-0.2.0.apk` + `install.sh` + `install.bat`.

Watch the run at **GitHub repo → Actions tab**. Total time ~3 minutes.

## Local release build (sanity check)

To test the release config without pushing a tag:

```bash
export KEYSTORE_PATH=/path/to/watchbridge-release.keystore
export KEYSTORE_PASSWORD=...
export KEY_ALIAS=watchbridge
export KEY_PASSWORD=...
export VERSION_NAME=0.2.0-test
export VERSION_CODE=999

./gradlew :app:assembleRelease

apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk
```

If the certificate fingerprint matches the one CI prints, you're golden.

## Versioning

Tags drive the version. The CI workflow strips the leading `v` for `versionName` and uses `github.run_number` for `versionCode` (monotonically increasing — required for Android update detection).

For local development, `versionName` defaults to `"0.1.0"` and `versionCode` to `1`. Set `VERSION_NAME` / `VERSION_CODE` env vars to override.

## If the keystore is lost

You're stuck with a fork: every existing user must uninstall before updating to anything signed with a new key. That's permanent for the user base. Don't lose it.

If you're publishing to Play Store later, [Play App Signing](https://support.google.com/googleplay/android-developer/answer/9842756) lets Google hold the key — strongly recommended for production apps to avoid this exact disaster.
