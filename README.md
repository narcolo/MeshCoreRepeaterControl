# MeshCore Discovery

An Android app that discovers nearby MeshCore mesh network nodes via a Bluetooth LE companion radio and shares results over a `#discovery` channel.

## How It Works

```
Android App  →  BLE  →  Companion Radio  →  LoRa  →  MeshCore Nodes
                              ↓
                     Discovery responses
```

The app connects to a MeshCore companion device (any device with a `MeshCore-` or `Whisper-` BLE name prefix), sends a zero-hop node discovery request over LoRa, and listens for 20 seconds. Discovered nodes are displayed with signal quality (SNR), node type, name (resolved from contacts), coordinates, and distance from your location.

Results are automatically formatted as compact CSV messages and sent to a `#discovery` channel. If the channel doesn't exist, the app creates it with a SHA-256-derived PSK.

## Features

- **BLE Scanning** — discover and connect to MeshCore companion devices
- **Node Discovery** — find nearby repeaters, clients, room servers, and sensors via LoRa
- **GPS Integration** — refreshes location before each discovery run; computes distance to nodes with known coordinates
- **Auto-Discover** — configurable repeat interval (1/3/5/10/15/30/60 minutes) with foreground service to keep running with screen off
- **Reliable Send** — messages sent with 3-second timeout, up to 3 retries, and deferred delivery on failure
- **Discovery Stats** — tracks successful/failed discovery runs per session and overall

## Usage

1. Launch the app and grant Bluetooth + Location permissions
2. Tap **Connect** to scan for companion devices
3. Select your companion from the list
4. Tap **Discover Repeaters** for a single discovery run, or **Auto-Discover** for recurring runs at a chosen interval
5. Results appear as a list showing node name, type, SNR, coordinates, and distance
6. Results are automatically sent to the `#discovery` channel

## CSV Message Format

Each message sent to `#discovery` (max 140 bytes):

```
lat,lon,timestamp|PKPK:snrIn/rLat,rLon|PKPK:snrIn/0,0|...
```

- `lat,lon` — your GPS coordinates (4 decimal places)
- `timestamp` — Unix epoch seconds
- `PKPK` — first 2 bytes of node public key (4 hex chars)
- `snrIn` — inbound SNR (1 decimal place)
- `rLat,rLon` — node coordinates if known, otherwise `0,0`

If results exceed 140 bytes, they are split across multiple messages with the same header.

## Requirements

- Android 7.0 (API 24) or higher
- Bluetooth LE hardware
- A MeshCore companion radio device

## Build

```bash
./gradlew assembleDebug
```

Install on a connected device:

```bash
./gradlew installDebug
```

## Permissions

| Permission | Purpose |
|---|---|
| `BLUETOOTH_SCAN` | Discover BLE devices (Android 12+) |
| `BLUETOOTH_CONNECT` | Connect to companion (Android 12+) |
| `ACCESS_FINE_LOCATION` | GPS for discovery coordinates |
| `FOREGROUND_SERVICE` | Keep auto-discover running with screen off |
| `POST_NOTIFICATIONS` | Show auto-discover status notification (Android 13+) |

## References

- [MeshCore](https://github.com/ripplebiz/MeshCore)
- [Companion Radio Protocol](https://github.com/ripplebiz/MeshCore/wiki/Companion-Radio-Protocol)

## License

MIT License
