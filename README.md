# WatchBridge

[![Latest Release](https://img.shields.io/github/v/release/rajtiwariee/watchbridge?label=Download&color=brightgreen)](https://github.com/rajtiwariee/watchbridge/releases/latest)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

**Free, open-source notification bridge for Wear OS watches and iPhones.**

WatchBridge is a single Wear OS app that runs on your **Galaxy Watch 4 / 5 / 6 (Wear OS 3+)** and connects to an iPhone over BLE to receive notifications, call alerts, and more using Apple's ANCS (Apple Notification Center Service) protocol.

**No iOS app required.** ANCS is built into iOS and exposed to any bonded BLE device automatically.

---

## Features

- **Incoming call alerts:** Full support. Accept/Reject calls directly from your watch.
- **SMS / iMessage:** Full support. See sender, title, and message body.
- **App notifications (WhatsApp, Telegram, Slack, etc.):** All apps forwarding to the iOS Notification Center are supported.
- **Email:** Sender, subject, and a preview.
- **Calendar reminders & Missed calls:** Full support.
- **Notification dismissal:** Dismissing on the watch dismisses on the iPhone too.
- **Active call hang-up:** Best-effort (relies on undocumented iOS features).

*Note: Due to iOS limitations, replying to messages, initiating outgoing calls, and syncing health data are not supported.*

---

## Quick Install (Recommended)

> Works on macOS, Linux, and Windows. Takes 2–3 minutes.

1. **Download** the latest release from [Releases](https://github.com/rajtiwariee/watchbridge/releases/latest):
   - `watchbridge-X.Y.Z.apk`
   - `install.sh` (macOS / Linux) **or** `install.bat` (Windows)
   
   Put both files in the **same folder**.

2. **Enable Developer Options on the watch:**
   - Settings → About watch → Software → tap **Software version** 7 times.

3. **Enable Wireless Debugging on the watch:**
   - Settings → Developer options → **Wireless debugging** ON.

4. **Run the installer** from the folder where you saved the files:
   - **macOS / Linux:** `chmod +x install.sh && ./install.sh`
   - **Windows:** double-click `install.bat`
   
   The script will prompt you for the pairing code and IP shown on the watch. Done.

5. **Pair with iPhone:**
   - Open WatchBridge on the watch and grant Bluetooth permissions.
   - On the iPhone, go to **Settings → Bluetooth** and tap **WatchBridge** when it appears.

> Need ADB? The script will tell you if it's missing. On Mac: `brew install --cask android-platform-tools`. On Windows/Linux: [download platform-tools](https://developer.android.com/tools/releases/platform-tools).

---

## Manual Install (Advanced)

If you prefer to run ADB commands yourself:

1. Enable Developer Options + Wireless Debugging on the watch (steps 2–3 above).
2. On the watch, tap **Pair new device** — note the IP:port and 6-digit code.
3. From your computer:
   ```bash
   adb pair <pair-ip:port> <code>
   adb connect <conn-ip:port>          # the IP shown on the main Wireless debugging page
   adb install -r watchbridge-X.Y.Z.apk
   ```
4. Open the app on the watch and pair via iPhone Bluetooth (step 5 above).

---

## Troubleshooting

**`adb: command not found`** — Install Android platform-tools (see the link in Quick Install).

**Wireless debugging keeps disconnecting** — The watch usually drops the ADB session after the screen sleeps. Re-run `adb connect <ip:port>` if you need to install again. The installed app keeps working regardless.

**iPhone won't show "WatchBridge" in Bluetooth** — Open the WatchBridge app on the watch first and make sure it shows "Advertising / Waiting for iPhone". Toggle iPhone Bluetooth off/on. If WatchBridge had been paired before, on the iPhone tap the (i) icon next to it → **Forget This Device**, then re-pair.

**Notifications stopped after watch reboot** — Open the WatchBridge app once after a reboot to restart its background service. Wear OS aggressively kills background services on boot.

**Pairing code expired** — The code on the "Pair new device" screen rotates. If `adb pair` fails, re-open that screen on the watch to get a fresh code.

**App won't update / "signatures don't match"** — You probably installed a debug build (signed with your developer key) and are now trying to install the official release (signed with the project key). Uninstall first: `adb uninstall com.watchbridge`, then install the release.

---

## Technical Architecture

The **iPhone acts as the BLE Central + GATT Server**, exposing the ANCS service. The **watch acts as a BLE Peripheral + GATT Client**, scans for iPhones, connects, and subscribes to ANCS characteristics.

**Connection sequence:**
1. **Discovery & Connection:** Watch scans for an iPhone, connects, and discovers the ANCS service.
2. **Bonding:** Watch subscribes to the notification source. Because that characteristic requires encryption, iOS automatically triggers secure pairing.
3. **Session:** Watch monitors 8-byte notification events, fetches content progressively, maps Apple's categories to Wear OS notifications, and sends control instructions (e.g. "dismiss", "accept call") back to the iPhone.

---

## For Developers

### Tech Stack
- **Language:** Kotlin
- **UI:** Jetpack Compose for Wear OS
- **BLE:** Nordic Android BLE Library (`no.nordicsemi.android:ble-ktx`)
- **Min SDK:** 30 (Wear OS 3) · **Target SDK:** 34

### Project Structure
- `app/src/main/java/com/watchbridge/ble/` — BLE scanning, GATT client, ANCS interactions, bonding.
- `app/src/main/java/com/watchbridge/ancs/` — Parsing 8-byte ANCS events, fragment assembly, UID management.
- `app/src/main/java/com/watchbridge/notification/` — Mapping ANCS models to native `NotificationCompat`.
- `app/src/main/java/com/watchbridge/service/` — Foreground service for connection continuity.
- `app/src/main/java/com/watchbridge/ui/` — Compose screens (Home, Pairing, Settings, popups, calls).

### Local debug build
```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Cutting a release

Releases are **fully automatic**. Just commit and push to `master`:

```bash
git add -A && git commit -m "feat: my change"
git push origin master
```

If the push touches `app/**`, `gradle/**`, `build.gradle.kts`, or the workflow itself, the `Release` GitHub Action will:
1. Compute the next patch version (e.g. `v0.1.0` → `v0.1.1`) from the latest tag.
2. Push the new tag.
3. Build a signed APK (`watchbridge-0.1.1.apk`).
4. Create a GitHub Release with auto-generated notes + the APK + install scripts attached.

Pushes that only change docs / README / `scripts/` / CI for non-app reasons are skipped (no release noise).

**Want to bump minor or major manually?** Tag it yourself before the next push:
```bash
git tag v0.2.0
git push origin v0.2.0
```
The next code push will then auto-bump to `v0.2.1`, `v0.2.2`, etc.

**One-time setup before the first release** — see [`docs/RELEASING.md`](docs/RELEASING.md) for keystore generation and the four GitHub Secrets the workflow needs.

---

## Known Issues / Risks
- **Background kills:** Wear OS aggressively manages background services. The foreground service tries its best to stay alive.
- **iOS version variances:** The "Active Call" category (12) is undocumented by Apple, so call hang-up behavior may vary across iOS 15–18.
- **Bond drops:** Rarely, the iPhone may forget the bonding keys. Workaround: "Forget Device" on the iPhone and re-pair.

---

## License

MIT — see [LICENSE](LICENSE).
