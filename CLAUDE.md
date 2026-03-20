# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build Commands

```bash
./gradlew assembleDebug          # Build debug APK
./gradlew installDebug           # Build and install on connected device
./gradlew testDebugUnitTest      # Run unit tests
./gradlew connectedDebugAndroidTest  # Run instrumentation tests (requires device)
```

Output APK: `app/build/outputs/apk/debug/app-debug.apk`

## Architecture

Android app that discovers MeshCore mesh network nodes via BLE. Communicates with a MeshCore companion radio over BLE, which relays LoRa discovery requests/responses.

**Communication chain:** `Android App → BLE → Companion Radio → LoRa → MeshCore Nodes`

### State Machine

`MainActivity` drives a single-Activity UI through five states via `setState()`, with each state mapping to a visibility-toggled `View` in `activity_main.xml`:

```
DISCONNECTED → SCANNING → CONNECTED → DISCOVERING → RESULTS
                                ↑                        │
                                └────────────────────────┘ (discover again / auto-discover cycle)
```

### Layer Responsibilities

- **`MainActivity`** — UI state machine, orchestrates the full flow. Owns auto-discover scheduling (Handler.postDelayed), GPS refresh, channel scanning for `#discovery`, CSV message assembly, and reliable send with retry/defer logic. All BLE protocol callbacks are wired here.

- **`BleManager`** — Low-level BLE transport. Scans for devices with `MeshCore-` or `Whisper-` name prefix, manages GATT connection lifecycle, notification subscription, and raw byte read/write. Uses Nordic UART Service UUIDs (6E4000xx). Stores `applicationContext` (not Activity) to survive config changes.

- **`MeshCoreProtocol`** — Encodes commands and decodes responses per the MeshCore Companion Radio Protocol. Stateless after construction (just needs BleManager reference). All response parsing is in `handleResponse()` with a command-code switch. Discovery responses arrive as `PUSH_CONTROL_DATA (0x8E)` containing `CONTROL_DISCOVER_RESP (0x90|nodeType)` payloads.

- **`MeshCoreFrame`** — Wire format: `[command_code:1][data:N]`. Little-endian helpers for int16/32/64.

- **`MeshCoreCommand`** — Protocol constants. Commands are 0x01-0x38, responses 0x00-0x18, push notifications 0x80-0x8E.

- **`AutoDiscoverService`** — Minimal foreground Service (connectedDevice|location type) with WakeLock. Keeps process alive during auto-discover; all logic remains in Activity.

### Key Flows

**Discovery:** `fetchLocation()` → `sendSetAdvertLatLon()` → 300ms delay → `sendNodeDiscoverReq(0xFF)` → 20s countdown collecting `onDiscoverResponse` callbacks → `showResults()` → `sendToDiscoveryChannel()`.

**Channel scan for #discovery:** Iterates channels 0-7 via `sendGetChannel()`, looking for `#discovery` by name. If not found, creates it at the first empty slot with a SHA-256-derived PSK.

**Reliable message send:** CSV messages are queued in `pendingMessages`, sent one at a time with 3s timeout and up to 3 retries. Failed messages go to `deferredMessages` and are prepended to the next cycle's queue.

**Auto-discover:** Configurable interval (1-60 min). Starts foreground service, then cycles: discover → results → send to #discovery → countdown → repeat. Notification text updates with countdown.

### CSV Format (sent to #discovery channel)

```
lat,lon,timestamp|PKPK:snrIn/rLat,rLon|PKPK:snrIn/0,0|...
```
- `PKPK` = first 2 bytes of node pubkey (4 hex chars)
- Messages split at 140-byte boundary, each with same header

## Project Config

- **Language:** Java 8 (no Kotlin)
- **SDK:** compileSdk 34, minSdk 24, targetSdk 34
- **Gradle:** AGP 9.0.1
- **Package:** `com.example.meshcorerepeatercontrol`
- **Dependencies:** AndroidX, Material Components, Play Services Location, RecyclerView, CardView

## BLE Protocol Notes

- MTU requested at 185 bytes (matches Dart client)
- BLE writes must be staggered (~300ms) to avoid overlapping GATT operations
- Connection sequence: connect → requestMtu → 600ms delay → discoverServices → enable notifications → onConnected
- On connect: `sendDeviceQuery()` → 300ms → `sendAppStart()` → 600ms → `sendGetContacts()`
- SNR values are signed bytes divided by 4.0; GPS coordinates are int32 divided by 1e6
