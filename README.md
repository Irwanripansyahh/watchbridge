# WatchBridge

**Free, open-source notification bridge for Wear OS watches and iPhones**

WatchBridge is a single Wear OS app that runs on your Galaxy Watch 4 Classic (and potentially other Wear OS 3+ devices) and connects to an iPhone over BLE to receive notifications, call alerts, and more using Apple's ANCS (Apple Notification Center Service) protocol.

**No iOS app required.** ANCS is built into iOS and exposed to any bonded BLE device automatically.

---

## Features

- **Incoming call alerts:** Full support. Accept/Reject calls directly from your watch.
- **SMS/iMessage notifications:** Full support. See app identifier, title, and message body.
- **App notifications (WhatsApp, Telegram, Slack, etc.):** Full support. All apps forwarding notifications to the iOS Notification Center are supported.
- **Email notifications:** Full support. See sender, subject, and a preview.
- **Calendar reminders & Missed calls:** Full support.
- **Notification dismissal:** Dismissing a notification on the watch also dismisses it on the iPhone.
- **Active call hang-up:** Best-effort support (relies on undocumented iOS features).

*Note: Due to iOS limitations, responding to messages, initiating outgoing calls, and syncing health data are not supported.*

---

## Installation Guide (For End Users)

1. **Enable Developer Options on Galaxy Watch 4 Classic:**
   - Go to `Settings > About Watch > Software`.
   - Tap `Software version` 5 times rapidly until developer mode is enabled.

2. **Enable ADB Debugging:**
   - Go to `Settings > Developer Options`.
   - Turn **ADB Debugging** `ON`.
   - Turn **Debug over Wi-Fi** `ON`. 
   - Note the `<watch-ip>:<port>` displayed under "Debug over Wi-Fi".

3. **Connect via ADB from your computer:**
   Open a terminal/command prompt and run:
   ```bash
   adb connect <watch-ip>:<port>
   ```

4. **Install WatchBridge:**
   Install the WatchBridge APK to your watch:
   ```bash
   adb install watchbridge.apk
   ```

5. **Initial Setup on the Watch:**
   - Open the WatchBridge app on your watch.
   - Follow the on-screen instructions.
   - Grant Bluetooth permissions when prompted.

6. **Pairing with your iPhone:**
   - On your iPhone, go to `Settings > Bluetooth`.
   - Look for **"WatchBridge"** in the list of available devices.
   - Tap to pair.
   - Accept any pairing/bonding requests that appear on the iPhone screen.

---

## Technical Architecture

WatchBridge operates by having the **iPhone act as the BLE Central and GATT Server**, exposing the ANCS service. The **Watch acts as a BLE Peripheral and GATT Client**, which scans for iPhones, connects, and subscribes to the ANCS characteristics.

### Connection Sequence:

1. **Discovery & Connection:** The watch scans for the iPhone, connects, and discovers the ANCS service.
2. **Bonding Initiation:** The watch attempts to subscribe to the notification source. Because this characteristic requires encryption, iOS automatically triggers the secure pairing/bonding flow.
3. **Session:** Once bonded, the watch monitors 8-byte notification events, fetches content progressively, maps Apple's categories to Wear OS native notifications, and sends control instructions (such as "dismiss notification" or "accept call") back to the iPhone.

---

## For Developers

### Tech Stack
- **Language:** Kotlin
- **UI Framework:** Jetpack Compose for Wear OS
- **BLE Library:** Nordic Android BLE Library (`no.nordicsemi.android:ble-ktx`)
- **Min SDK:** API 30 (Android 11 / Wear OS 3)
- **Target SDK:** API 34

### Project Structure
- `app/src/main/java/com/watchbridge/ble/` - BLE scanning, GATT client connection, ANCS service interactions, and bonding.
- `app/src/main/java/com/watchbridge/ancs/` - Low-level parsing of 8-byte ANCS events, assembling fragmented message chunks, and Session/UID management.
- `app/src/main/java/com/watchbridge/notification/` - Mapping ANCS models to native `NotificationCompat` displays on Wear OS.
- `app/src/main/java/com/watchbridge/service/` - Foreground service handling connection continuity and graceful reconnects.
- `app/src/main/java/com/watchbridge/ui/` - Compose-based screens (Home, Pairing, Settings) for the watch UI.

---

## Known Issues / Risks
- **Background Kills:** Wear OS aggressively manages background services. The foreground service makes its best effort to stay alive to maintain the BLE connection.
- **iOS version variances:** The "Active Call" category (12) is historically undocumented by Apple, meaning call hang-up behaviors might vary between iOS 15, 16, 17, and 18.
- **Bond drops:** In rare scenarios, the iPhone may forget the bonding keys, requiring the user to "Forget Device" and re-pair.

---

## License

This project is licensed under the MIT License. See the [LICENSE](LICENSE) file for details.
