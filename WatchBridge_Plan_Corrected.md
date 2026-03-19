# WatchBridge — Corrected Project Plan

### Open-Source BLE Notification Bridge for Galaxy Watch 4 Classic ↔ iPhone

### Version: 2.0 (Corrected Architecture)
### Last Updated: March 19, 2026

---

## 1. Problem Statement

Samsung dropped iOS support starting with Galaxy Watch 4 (Wear OS 3+). The official Galaxy Wearable iOS app only supports older Tizen-based watches. This leaves Galaxy Watch 4 Classic owners with iPhones completely cut off — no notifications, no call alerts, nothing.

The only existing solution (Merge app) costs $4–6/month with known stability issues. There is no free, open-source alternative.

---

## 2. Project Goal

Build a **single Wear OS app** that runs on the Galaxy Watch 4 Classic and connects to an iPhone over BLE to receive notifications using Apple's ANCS (Apple Notification Center Service) protocol.

**No iOS app required.** ANCS is built into iOS and exposed to any bonded BLE device automatically.

---

## 3. Architecture — CORRECTED

### 3.1 Critical Correction: BLE Role Architecture

**IMPORTANT:** The original plan incorrectly specified the watch should advertise as a BLE peripheral. This is **incorrect** for ANCS. The correct architecture is:

| Component | BLE Role | GATT Role | Function |
|-----------|----------|-----------|----------|
| iPhone | Central | **GATT Server** | Exposes ANCS service; notification provider |
| Watch | Peripheral | **GATT Client** | Scans, connects, consumes ANCS notifications |

**Why this matters:**

- ANCS is a **GATT Client service** on the consuming device (watch)
- The iPhone exposes ANCS as a **GATT Service** that any bonded BLE device can discover and connect to
- The watch must **scan for and connect to** the iPhone as a GATT client
- The watch does NOT advertise ANCS — the iPhone is the server

### 3.2 Corrected Architecture Diagram

```
┌─────────────────────────────────────────────────────────────────────────────────────┐
│                                    iPhone (iOS)                                      │
│                                                                                      │
│  ┌────────────────────────────────────────────────────────────────────────────────┐ │
│  │                         ANCS Service (GATT Server)                              │ │
│  │  Service UUID: 7905F431-B5CE-4E99-A40F-4B1E122D00D0                           │ │
│  │                                                                                │ │
│  │  ┌──────────────────────────────────────────────────────────────────────────┐  │ │
│  │  │ Notification Source (Notifiable)                                        │  │ │
│  │  │ UUID: 9FBF120D-6301-42D9-8C58-25E699A21DBD                             │  │ │
│  │  │ Direction: iPhone → Watch (events)                                       │  │ │
│  │  └──────────────────────────────────────────────────────────────────────────┘  │ │
│  │                                                                                │ │
│  │  ┌──────────────────────────────────────────────────────────────────────────┐  │ │
│  │  │ Control Point (Write with Response)                                    │  │ │
│  │  │ UUID: 69D1D8F3-45E1-49A8-9821-9BBDFDAAD9D9                             │  │ │
│  │  │ Direction: Watch → iPhone (commands)                                    │  │ │
│  │  └──────────────────────────────────────────────────────────────────────────┘  │ │
│  │                                                                                │ │
│  │  ┌──────────────────────────────────────────────────────────────────────────┐  │ │
│  │  │ Data Source (Notifiable)                                                │  │ │
│  │  │ UUID: 22EAC6E9-24D6-4BB5-BE44-B36ACE7C7BFB                             │  │ │
│  │  │ Direction: iPhone → Watch (attribute data)                              │  │ │
│  │  └──────────────────────────────────────────────────────────────────────────┘  │ │
│  │                                                                                │ │
│  │  ENCRYPTION: All characteristics require authorization & encryption          │ │
│  └────────────────────────────────────────────────────────────────────────────────┘ │
│                                                                                      │
│  iPhone acts as BLE Central (initiates connection)                                  │
│  iPhone acts as GATT Server (provides ANCS service)                                 │
│                                                                                      │
└─────────────────────────────────────────────────────────────────────────────────────┘
                                        │
                                        │ BLE Connection (Encrypted)
                                        │ Bonding Required
                                        │
                                        ▼
┌─────────────────────────────────────────────────────────────────────────────────────┐
│                               Galaxy Watch 4 Classic (Wear OS)                         │
│                                                                                        │
│  ┌────────────────────────────────────────────────────────────────────────────────┐  │
│  │                              WatchBridge App                                    │  │
│  │                                                                                │  │
│  │  ┌──────────────────────────────────────────────────────────────────────────┐  │  │
│  │  │                         BLE Manager (GATT Client)                        │  │  │
│  │  │                                                                          │  │  │
│  │  │  1. Scan for iPhones                                                    │  │  │
│  │  │  2. Connect as GATT Client                                               │  │  │
│  │  │  3. Discover ANCS service                                                │  │  │
│  │  │  4. Access encrypted characteristic → triggers iOS bonding              │  │  │
│  │  │  5. Subscribe to Notification Source                                     │  │  │
│  │  │  6. Write to Control Point for details/actions                           │  │  │
│  │  │  7. Receive Data Source notifications                                     │  │  │
│  │  │                                                                          │  │  │
│  │  └──────────────────────────────────────────────────────────────────────────┘  │  │
│  │                                    │                                          │  │
│  │                                    ▼                                          │  │
│  │  ┌──────────────────────────────────────────────────────────────────────────┐  │  │
│  │  │                          ANCS Parser                                    │  │  │
│  │  │                                                                          │  │  │
│  │  │  • Notification Source Parser (8-byte events)                           │  │  │
│  │  │  • Data Source Parser (attribute reassembly)                            │  │  │
│  │  │  • Control Point Command Builder                                         │  │  │
│  │  │  • Session State Manager                                                 │  │  │
│  │  │                                                                          │  │  │
│  │  └──────────────────────────────────────────────────────────────────────────┘  │  │
│  │                                    │                                          │  │
│  │                                    ▼                                          │  │
│  │  ┌──────────────────────────────────────────────────────────────────────────┐  │  │
│  │  │                      Notification Renderer                               │  │  │
│  │  │                                                                          │  │  │
│  │  │  • Wear OS native notifications                                         │  │  │
│  │  │  • Call handling UI (accept/reject)                                     │  │  │
│  │  │  • Action synchronization                                               │  │  │
│  │  │                                                                          │  │  │
│  │  └──────────────────────────────────────────────────────────────────────────┘  │  │
│  │                                                                                │  │
│  └────────────────────────────────────────────────────────────────────────────────┘  │
│                                                                                      │
│  Watch acts as BLE Peripheral (accepts connection from iPhone Central)              │
│  Watch acts as GATT Client (scans for and connects to iPhone)                      │
│                                                                                      │
└─────────────────────────────────────────────────────────────────────────────────────┘
```

---

## 4. Complete ANCS Connection Sequence

This section details the exact sequence required to establish an ANCS connection.

### 4.1 Step-by-Step Connection Flow

```
┌──────────────────────────────────────────────────────────────────────────────────────┐
│                              PHASE 1: Discovery & Connection                         │
├──────────────────────────────────────────────────────────────────────────────────────┤
│                                                                                      │
│  Step 1.1: User-Initiated Scan                                                      │
│  ─────────────────────────────────                                                   │
│  • User opens WatchBridge app and taps "Connect to iPhone"                          │
│  • App starts BLE scanning for nearby devices                                       │
│  • iPhones appear in Bluetooth settings as available devices                         │
│  • User selects their iPhone from the scan results                                  │
│                                                                                      │
│  Step 1.2: Initial Connection (Unencrypted)                                         │
│  ─────────────────────────────────────────────                                       │
│  • Watch initiates BLE connection to iPhone                                         │
│  • Watch acts as GATT Client (central role)                                        │
│  • Connection established but NOT encrypted yet                                    │
│  • iPhone sees "WatchBridge" in Bluetooth devices                                   │
│                                                                                      │
│  Step 1.3: Service Discovery                                                        │
│  ──────────────────────────────                                                      │
│  • Watch discovers all GATT services on iPhone                                      │
│  • Watch searches for ANCS Service UUID: 7905F431-B5CE-4E99-A40F-4B1E122D00D0     │
│  • Watch discovers three characteristics:                                           │
│    - Notification Source (Subscribe)                                                │
│    - Control Point (Write)                                                          │
│    - Data Source (Subscribe)                                                        │
│                                                                                      │
│  Step 1.4: Trigger iOS Bonding (CRITICAL STEP)                                     │
│  ──────────────────────────────────────────────                                      │
│  • Watch attempts to SUBSCRIBE to Notification Source characteristic                │
│  • Notification Source requires ENCRYPTION                                          │
│  • iOS detects need for encryption → triggers pairing dialog on iPhone             │
│  • User confirms pairing on iPhone                                                  │
│  • LE Secure Connection established                                                  │
│  • Bonding keys stored on both devices                                             │
│                                                                                      │
│  ⚠️ CRITICAL: iOS bonding only triggers when accessing encrypted characteristics.   │
│     Simply connecting does NOT trigger bonding.                                      │
│                                                                                      │
└──────────────────────────────────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────────────────────────────────┐
│                           PHASE 2: ANCS Session Initialization                        │
├──────────────────────────────────────────────────────────────────────────────────────┤
│                                                                                      │
│  Step 2.1: Subscribe to Notification Source                                          │
│  ─────────────────────────────────────────────                                       │
│  • Write subscription descriptor to Notification Source CCCD                        │
│  • Start receiving 8-byte notification events                                       │
│  • iOS may send pre-existing notifications (EventFlags bit 2 = 1)                  │
│                                                                                      │
│  Step 2.2: Request Pre-existing Notifications                                        │
│  ────────────────────────────────────────────────                                   │
│  • For each category with CategoryCount > 0:                                        │
│    - Query iOS for notification details via Control Point                           │
│    - Build local notification cache                                                 │
│  • ANCS sends full notification list at session start                              │
│                                                                                      │
│  Step 2.3: Request App Attributes                                                    │
│  ───────────────────────────────────                                                 │
│  • For each unique app identifier received:                                         │
│    - Query app display name via Control Point                                       │
│    - Cache mapping: BundleID → DisplayName                                          │
│  • Reduces future queries                                                           │
│                                                                                      │
└──────────────────────────────────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────────────────────────────────┐
│                              PHASE 3: Active Session                                  │
├──────────────────────────────────────────────────────────────────────────────────────┤
│                                                                                      │
│  Step 3.1: Process Notification Events                                                │
│  ──────────────────────────────────────                                              │
│  • Receive 8-byte events from Notification Source                                   │
│  • EventID: 0=Added, 1=Modified, 2=Removed                                          │
│  • Extract: CategoryID, CategoryCount, NotificationUID                               │
│                                                                                      │
│  Step 3.2: Fetch Notification Details (On-Demand)                                    │
│  ─────────────────────────────────────────────                                        │
│  • When user taps notification:                                                      │
│    - Write GetNotificationAttributes to Control Point                               │
│    - Request: Title, Subtitle, Message, Date                                        │
│    - Receive fragmented response on Data Source                                      │
│    - Reassemble and display                                                          │
│                                                                                      │
│  Step 3.3: Handle User Actions                                                       │
│  ─────────────────────────────────                                                   │
│  • User accepts/rejects/dismisses:                                                  │
│    - Write PerformNotificationAction to Control Point                               │
│    - ActionID: 0=Positive, 1=Negative                                               │
│    - iOS executes action and sends removal event                                    │
│                                                                                      │
│  Step 3.4: Handle Session Events                                                     │
│  ───────────────────────────────────                                                 │
│  • On brief disconnect (< 5 sec): Attempt seamless reconnect                        │
│  • On long disconnect: Clear UID cache, rebuild from scratch                         │
│  • On GATT Service Changed: Re-discover ANCS service                                │
│                                                                                      │
└──────────────────────────────────────────────────────────────────────────────────────┘
```

### 4.2 iOS Bonding Sequence Detail

```
┌──────────────────────────────────────────────────────────────────────────────────────┐
│                            iOS Bonding — Detailed Sequence                            │
├──────────────────────────────────────────────────────────────────────────────────────┤
│                                                                                      │
│  The following sequence is REQUIRED for iOS to establish bonding:                    │
│                                                                                      │
│  1. Watch connects to iPhone (unencrypted)                                          │
│  2. Watch discovers ANCS service                                                    │
│  3. Watch discovers characteristics                                                │
│  4. Watch sets characteristic configuration (CCC descriptor)                       │
│     → This REQUIRES encryption on ANCS characteristics                              │
│  5. iOS detects encryption requirement                                              │
│  6. iOS initiates LE Secure Connections pairing                                    │
│  7. iPhone displays pairing dialog to user                                          │
│  8. User approves pairing                                                           │
│  9. LTK (Long Term Key) exchanged and stored                                        │
│ 10. Connection now encrypted                                                        │
│ 11. Bond successfully established                                                    │
│ 12. Future connections: Watch connects → iOS detects bond → auto-encrypts           │
│                                                                                      │
│  ⚠️ WITHOUT ACCESSING ENCRYPTED CHARACTERISTICS, iOS WILL NOT PAIR!                │
│                                                                                      │
└──────────────────────────────────────────────────────────────────────────────────────┘
```

---

## 5. What ANCS Gives Us (and What It Doesn't)

### 5.1 What We CAN Build

| Feature | ANCS Support | Details |
|---------|-------------|---------|
| Incoming call alerts | Full | Category: IncomingCall. Accept/Reject via positive/negative actions |
| SMS/iMessage notifications | Full | App identifier + title + message body |
| WhatsApp/Telegram/etc. | Full | All app notifications forwarded |
| Email notifications | Full | Sender + subject + preview |
| Calendar reminders | Full | Category: Schedule |
| Missed call alerts | Full | Category: MissedCall |
| Notification dismissal | Full | Dismiss on watch → dismisses on iPhone |
| Active call hang-up | Undocumented | iOS 13+ exposes "Active Call" as hidden 12th category |

### 5.2 What We CANNOT Build (iOS Limitations)

| Feature | Why Not |
|---------|---------|
| Reply to messages | ANCS is read-only for message content; no text input channel |
| Make outgoing calls | No API to trigger calls on iOS from BLE |
| Health data sync | Requires HealthKit access (needs an iOS app) |
| App installation/management | Requires Galaxy Wearable companion app |
| Siri integration | Apple-exclusive |
| Watch face sync | Requires Samsung's proprietary infrastructure |

### 5.3 iOS Version Compatibility Notes

| iOS Version | ActiveCall (Cat.12) | Notes |
|-------------|---------------------|-------|
| iOS 13-15 | Available | undocumented, behavior varies |
| iOS 16-17 | Available | May have restrictions |
| iOS 18+ | Unknown | Test required; Apple may change |

**Recommendation:** Implement ActiveCall as a best-effort feature with fallback to no hang-up capability. Do not market this as guaranteed functionality.

---

## 6. Core Components — CORRECTED

### 6.1 BLE Scanner & Connection Manager

**Function:** Scan for iPhones and establish GATT client connection.

**Components:**

```kotlin
// Key responsibilities:
// 1. BLE Scanning for nearby iPhones
// 2. Connect as GATT Client (NOT peripheral)
// 3. Service discovery for ANCS UUID
// 4. Trigger bonding via encrypted characteristic access
// 5. Maintain connection lifecycle
// 6. Handle reconnection with exponential backoff
```

**Critical Implementation Notes:**

- Use `BluetoothLeScanner` to scan for BLE devices
- Filter for devices that advertise ANCS capability
- Connect using `BluetoothGattCallback` as CLIENT
- Handle bonding state changes
- Store bond information for automatic reconnection

### 6.2 ANCS GATT Client

**Function:** Interface with iPhone's ANCS GATT service.

**Components:**

```kotlin
// Key responsibilities:
// 1. Discover ANCS service and characteristics
// 2. Configure characteristic notifications (triggers bonding!)
// 3. Subscribe to Notification Source
// 4. Write Control Point commands
// 5. Receive Data Source responses
// 6. Handle MTU negotiation
```

**Critical Implementation Notes:**

- Subscribe to Notification Source BEFORE bonding is complete (triggers bonding)
- Handle fragmented Data Source responses
- Implement proper timeout handling
- Support connection priority changes

### 6.3 Bond Manager

**Function:** Handle iOS bonding requirements.

**Components:**

```kotlin
// Key responsibilities:
// 1. Monitor bonding state
// 2. Trigger bonding when accessing encrypted characteristics
// 3. Store and retrieve bonding keys
// 4. Handle bond loss and re-pairing
// 5. Support LE Secure Connections
```

**Critical Implementation Notes:**

- iOS requires accessing encrypted characteristics to trigger pairing
- "Just Works" pairing is typically used (no PIN required)
- Bond information persists across reboots
- Handle case where iPhone "forgets" the bond

### 6.4 Notification Renderer

**Function:** Convert ANCS notifications to Wear OS native notifications.

**Components:**

```kotlin
// Key responsibilities:
// 1. Create Wear OS notifications with NotificationCompat
// 2. Map ANCS categories to icons and channels
// 3. Display actionable notifications (accept/reject)
/// 4. Handle notification dismissal
// 5. Support notification grouping by app
```

**Critical Implementation Notes:**

- Use Wear OS notification APIs
- Support inline actions for call handling
- Implement notification priority management

---

## 7. ANCS Protocol Deep Dive — Reference

### 7.1 Notification Source Event Format (8 bytes)

```
Byte 0: EventID
  0 = Notification Added
  1 = Notification Modified
  2 = Notification Removed

Byte 1: EventFlags (bitmask)
  Bit 0 = Silent
  Bit 1 = Important
  Bit 2 = Pre-existing (sent at session start)
  Bit 3 = Positive Action exists
  Bit 4 = Negative Action exists

Byte 2: CategoryID
  0  = Other
  1  = IncomingCall
  2  = MissedCall
  3  = Voicemail
  4  = Social
  5  = Schedule
  6  = Email
  7  = News
  8  = HealthAndFitness
  9  = BusinessAndFinance
  10 = Location
  11 = Entertainment
  12 = ActiveCall (undocumented, iOS 13+)

Byte 3: CategoryCount
Bytes 4-7: NotificationUID (uint32, little-endian)
```

### 7.2 GetNotificationAttributes Command

```
Byte 0: CommandID = 0
Bytes 1-4: NotificationUID
Bytes 5+: AttributeIDs with 2-byte length prefix
  0 = AppIdentifier
  1 = Title (+ length)
  2 = Subtitle (+ length)
  3 = Message (+ length)
  4 = MessageSize
  5 = Date
  6 = PositiveActionLabel
  7 = NegativeActionLabel
```

### 7.3 PerformNotificationAction Command

```
Byte 0: CommandID = 2
Bytes 1-4: NotificationUID
Byte 5: ActionID
  0 = Positive (Accept)
  1 = Negative (Reject/Dismiss)
```

---

## 8. Technical Risks & Mitigations — CORRECTED

| Risk | Impact | Mitigation |
|------|--------|------------|
| **iOS bonding not triggering** | No connection possible | Ensure encrypted characteristic access; document pairing steps clearly |
| **Wear OS foreground service killed** | Connection drops | Design for graceful degradation; notify user; implement auto-restart |
| **ANCS session loss on disconnect** | Stale UIDs | Clear cache on disconnect; rebuild from Notification Source |
| **BLE MTU limitations** | Fragmented data | Request larger MTU; implement reassembly buffer |
| **Battery drain** | Watch dies mid-day | Minimize scan time; batch operations; efficient intervals |
| **iOS Bluetooth restrictions** | Background disconnects | ANCS bonded devices get priority; test multiple iOS versions |
| **Galaxy Watch 4 quirks** | API differences | Test on Wear OS 3.5 and 4.0; use compat libraries |
| **ActiveCall undocumented** | Inconsistent behavior | Implement as best-effort; fallback gracefully |
| **iOS version differences** | Compatibility issues | Test across iOS 15-18; document known issues |

---

## 9. Tech Stack

| Component | Technology | Why |
|-----------|------------|-----|
| Language | Kotlin | First-class Wear OS support, coroutines for async BLE |
| UI Framework | Jetpack Compose for Wear OS | Modern, declarative UI for watch |
| BLE Library | Nordic Android BLE Library (`no.nordicsemi.android:ble-ktx`) | Battle-tested, coroutine support, GATT server + client support |
| Build System | Gradle (Kotlin DSL) | Standard Android/Wear OS tooling |
| Min SDK | API 30 (Android 11) | Galaxy Watch 4 ships with Wear OS 3 / Android 11 |
| Target SDK | API 34 | Latest stable |
| Deployment | ADB sideload | No Play Store account needed initially |

---

## 10. Development Phases — CORRECTED TIMELINE

### Phase 1: BLE Foundation (Week 1-2)

**Goal:** Watch discovers and connects to iPhone, ANCS bonding established.

- [ ] Set up Wear OS project with Compose UI
- [ ] Implement BLE scanning for iPhones
- [ ] Implement GATT client connection (NOT peripheral advertising!)
- [ ] Discover ANCS service and characteristics
- [ ] **CRITICAL:** Implement encrypted characteristic access to trigger iOS bonding
- [ ] Handle bonding flow and store bond information
- [ ] Subscribe to Notification Source characteristic
- [ ] Verify 8-byte events are received
- [ ] Implement MTU negotiation
- [ ] **Deliverable:** Watch pairs with iPhone, logs raw notification events to screen

**Testing:**
- Pair with iPhone running iOS 15, 16, 17, 18
- Verify bonding triggers correctly
- Test reconnection after disconnect

### Phase 2: Notification Parsing & Display (Week 2-3)

**Goal:** Real notifications appear on watch with readable content.

- [ ] Implement Control Point write commands (GetNotificationAttributes)
- [ ] Implement Data Source response parser with fragment reassembly
- [ ] Build ANCS attribute request pipeline (queue + rate limiting)
- [ ] Map AppIdentifier → human-readable app names (cache)
- [ ] Render notifications using Wear OS NotificationCompat
- [ ] Map ANCS categories to appropriate icons and channels
- [ ] Handle notification removal (dismiss sync)
- [ ] **Deliverable:** Real iPhone notifications appear on watch

**Testing:**
- Test with 10+ different apps (Messages, WhatsApp, Gmail, Slack, etc.)
- Verify attribute parsing for long messages
- Test notification grouping

### Phase 3: Actions & Call Handling (Week 3-4)

**Goal:** Accept/reject calls, dismiss notifications from watch.

- [ ] Implement PerformNotificationAction commands
- [ ] Incoming call UI with accept/reject buttons
- [ ] Active call detection (Category 12) with hang-up support
- [ ] Notification dismiss action (syncs back to iPhone)
- [ ] Positive/negative action labels displayed dynamically
- [ ] **Deliverable:** Full call management + notification actions from watch

**Testing:**
- Incoming call → accept from watch → verify call connects
- Incoming call → reject from watch → verify call rejected
- Active call → hang up from watch (best-effort)
- Notification dismiss from watch → verify dismissed on iPhone

### Phase 4: Reliability & Background Service (Week 4-5)

**Goal:** Rock-solid connection that survives real-world usage.

- [ ] Foreground service for persistent BLE connection
- [ ] Handle Wear OS battery optimization (no user toggle available!)
- [ ] Implement connection state machine with graceful degradation
- [ ] Auto-reconnect with exponential backoff
- [ ] Handle bond loss / re-pairing scenarios
- [ ] Monitor GATT Service Changed characteristic
- [ ] Session management (clear stale UIDs on reconnect)
- [ ] Battery usage optimization
- [ ] **Deliverable:** Connection survives sleep, distance, and daily use

**Testing:**
- Walk out of BLE range → return → verify auto-reconnect
- iPhone reboot → verify reconnection
- Watch reboot → verify service restarts
- Leave connected overnight → verify morning connection
- Battery drain measurement: target < 5% additional drain per day

### Phase 5: Polish & UX (Week 5-6)

**Goal:** Clean, usable app ready for daily driving.

- [ ] Settings screen (notification filtering, vibration patterns)
- [ ] Connection status UI
- [ ] Onboarding flow with pairing instructions
- [ ] App icon and branding
- [ ] Notification grouping
- [ ] DND/Theater mode respect
- [ ] Error handling and user diagnostics
- [ ] Documentation (README, setup guide)
- [ ] **Deliverable:** Polished app ready for sideloading

---

## 11. Project Structure

```
watchbridge/
├── app/
│   ├── build.gradle.kts
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/watchbridge/
│       │   ├── WatchBridgeApp.kt              # Application class
│       │   ├── MainActivity.kt                 # Main Compose activity
│       │   │
│       │   ├── ble/
│       │   │   ├── BleScanner.kt               # BLE scanning for iPhones (GATT Client)
│       │   │   ├── BleConnectionManager.kt     # Connection lifecycle + reconnect
│       │   │   ├── AncsGattClient.kt           # ANCS service discovery + subscriptions
│       │   │   └── BondManager.kt              # Pairing and bond storage
│       │   │
│       │   ├── ancs/
│       │   │   ├── AncsConstants.kt            # UUIDs, category IDs, command IDs
│       │   │   ├── AncsNotificationEvent.kt    # Notification Source parser (8-byte)
│       │   │   ├── AncsAttributeParser.kt      # Data Source response parser + reassembly
│       │   │   ├── AncsActionHandler.kt        # Control Point command writer
│       │   │   └── AncsSessionManager.kt       # UID tracking, session lifecycle
│       │   │
│       │   ├── notification/
│       │   │   ├── NotificationRenderer.kt     # ANCS → Wear OS notification mapping
│       │   │   ├── NotificationChannels.kt     # Channel setup per ANCS category
│       │   │   ├── CallNotificationHandler.kt  # Special handling for calls
│       │   │   └── AppNameResolver.kt          # Bundle ID → display name cache
│       │   │
│       │   ├── service/
│       │   │   └── WatchBridgeService.kt       # Foreground service + state machine
│       │   │
│       │   └── ui/
│       │       ├── screens/
│       │       │   ├── HomeScreen.kt           # Connection status + notifications
│       │       │   ├── PairingScreen.kt        # Onboarding / pairing instructions
│       │       │   └── SettingsScreen.kt       # Filters, vibration, etc.
│       │       ├── components/
│       │       │   └── ConnectionStatusCard.kt
│       │       └── theme/
│       │           └── WatchBridgeTheme.kt
│       │
│       └── res/
│           ├── drawable/                       # Category icons, app icon
│           ├── values/strings.xml
│           └── xml/
│               └── watch_face_config.xml
│
├── build.gradle.kts                            # Root build file
├── settings.gradle.kts
├── gradle.properties
└── README.md
```

---

## 12. Testing Strategy

### Phase 1-2 Testing

- Unit tests for ANCS protocol parsers
- Integration test: scan → connect → pair → verify events
- Test with 10+ different app notifications

### Phase 3 Testing

- Incoming call → accept/reject from watch
- Active call → hang up (best-effort)
- Notification dismiss → verify on iPhone

### Phase 4 Testing

- Walk out of range → return → reconnect
- Device reboots → reconnection
- Overnight connection stability
- Battery drain < 5% per day

### Phase 5 Testing

- Category filtering
- DND mode
- High notification volume (50+)
- Real-world usage over 1 week

---

## 13. Reference Implementations

### 13.1 InfiniTime (PineTime Firmware)

An open-source smartwatch firmware with working ANCS client implementation.

**Key Learnings:**

- iOS bonding only triggers on encrypted characteristic access
- MTU negotiation is critical for attribute fetching
- Fragmentation handling for long messages
- Session management edge cases

**Reference:** https://github.com/InfiniTimeOrg/InfiniTime (Issue #920 documents iOS bonding)

### 13.2 Nordic Semiconductor ANCS

Reference implementation for ANCS client on embedded platforms.

**Reference:** https://docs.nordicsemi.com/bundle/ncs-2.9.2/page/nrf/libraries/bluetooth/services/ancs_client.html

---

## 14. Installation Guide (For End Users)

```bash
# 1. Enable Developer Options on Galaxy Watch 4 Classic
#    Settings → About Watch → Software → tap "Software version" 5 times

# 2. Enable ADB Debugging
#    Settings → Developer Options → ADB Debugging → ON
#    Settings → Developer Options → Debug over Wi-Fi → ON (note the IP:port)

# 3. Connect via ADB from your computer
adb connect <watch-ip>:<port>

# 4. Install WatchBridge
adb install watchbridge.apk

# 5. Open WatchBridge on the watch
#    - Follow on-screen instructions
#    - Grant Bluetooth permissions when prompted

# 6. On iPhone:
#    - Go to Settings → Bluetooth
#    - Find "WatchBridge" in device list
#    - Tap to pair
#    - Accept pairing request on iPhone when prompted
```

---

## 15. Future Roadmap (Post-MVP)

| Feature | Effort | Value |
|---------|--------|-------|
| Media control (AMS) | Medium | Control iPhone music playback |
| Battery level sharing | Low | Show iPhone battery on watch |
| Notification reply (voice) | High | Experimental; may need iOS app |
| Companion iOS app | High | Unlock health sync, richer control |
| Play Store listing | Medium | Easier installation |
| Support other Wear OS watches | Low | Minimal changes required |
| Connection status tile | Low | Quick glance at status |

---

## 16. Open Source Strategy

- **License:** MIT (maximum adoption)
- **Repository:** GitHub
- **Name:** WatchBridge
- **Tagline:** "Free, open-source notification bridge for Wear OS watches and iPhones"
- **Target audience:** Anyone with a Wear OS watch and an iPhone

---

## 17. Success Metrics

| Metric | Target |
|--------|--------|
| Notification delivery reliability | > 98% |
| Notification latency | < 2 seconds |
| Call accept/reject success rate | > 99% |
| Auto-reconnect success rate | > 95% within 30 seconds |
| Daily battery impact | < 5% additional drain |
| Time to pair (first setup) | < 5 minutes |
| Crash-free sessions | > 99.5% |

---

## Appendix A: Key Corrections Summary

This document corrects the following issues from the original plan:

| Issue | Original Plan | Corrected Plan |
|-------|--------------|----------------|
| BLE Role | Watch advertises as peripheral | Watch scans as GATT client |
| GATT Role | Watch is GATT Server | Watch is GATT Client |
| Bonding | Mentioned but not detailed | Complete bonding sequence documented |
| Connection Flow | Missing steps | Full 3-phase connection sequence |
| iOS Bonding Trigger | Not specified | Triggers on encrypted characteristic access |
| Foreground Service | Assumed reliable | Acknowledged as risk with mitigation |
| Timeline | 4 weeks | 6 weeks (realistic) |
| Reference Implementation | Not mentioned | InfiniTime referenced |

---

*This corrected plan addresses all critical architectural and technical gaps identified in the review. The fundamental change is the correction of BLE role architecture: the watch must act as a GATT client (scanning and connecting to iPhone), not as a GATT peripheral (advertising and waiting for connections).*
