# MeshCore Repeater Control

An Android application for monitoring MeshCore repeater telemetry via Bluetooth LE companions.

## Features

- **Bluetooth LE Scanning**: Discover and connect to MeshCore companion devices
- **Repeater Management**: Add and manage multiple repeaters with secure password storage
- **Real-time Telemetry**: Monitor repeater metrics including:
  - Battery percentage and voltage
  - Temperature
  - Signal quality (RSSI, SNR, Noise Floor)
  - Additional sensor data via Cayenne LPP format
- **Secure Storage**: Encrypted local storage for repeater passwords using Android EncryptedSharedPreferences

## Architecture

```
Android App → Bluetooth LE → Companion Radio → LoRa → MeshCore Repeaters
```

### Key Components

1. **BLE Manager** (`bluetooth/`)
   - Handles Bluetooth scanning and connection
   - Manages GATT characteristics for data exchange

2. **MeshCore Protocol** (`protocol/`)
   - Implements MeshCore Companion Radio Protocol
   - Command encoding/decoding
   - Telemetry request handling

3. **Cayenne LPP Decoder** (`protocol/CayenneLppDecoder.java`)
   - Decodes sensor data from telemetry responses
   - Supports standard LPP types plus custom MeshCore types

4. **Secure Storage** (`storage/SecureStorage.java`)
   - AES-256 encrypted password storage
   - Repeater configuration management

5. **UI Components**
   - `MainActivity`: BLE scanning and companion connection
   - `TelemetryActivity`: Real-time telemetry dashboard

## Usage

### 1. Connect to Companion

1. Launch the app
2. Grant Bluetooth permissions
3. Tap "Scan for Companions"
4. Select your MeshCore companion device from the list

### 2. Add Repeater

1. After connection, tap "Add New Repeater"
2. Enter:
   - **Repeater Name**: Friendly name for identification
   - **Public Key Prefix**: First 12 hex characters of repeater's public key (e.g., `A1B2C3D4E5F6`)
   - **Password**: Repeater authentication password
3. Tap "Add"

### 3. View Telemetry

- The telemetry dashboard will open automatically
- Tap "Refresh Telemetry" to request updated data
- Data updates in real-time when received

## MeshCore Protocol Implementation

The app implements the MeshCore Companion Radio Protocol for BLE communication:

- **Frame Format**: `[Command Code:1 byte][Data:n bytes]`
- **Commands Used**:
  - `CMD_APP_START (0x01)`: Initial handshake
  - `CMD_SEND_TELEMETRY_REQ (0x27)`: Request telemetry from repeater
- **Responses**:
  - `PUSH_TELEMETRY_RESPONSE (0x8B)`: Telemetry data in Cayenne LPP format

## Telemetry Data Format

Telemetry responses contain Cayenne Low Power Payload (LPP) encoded sensor data:

- Each sensor: `[Channel:1byte][Type:1byte][Data:n bytes]`
- Temperature: 2 bytes, 0.1°C resolution
- Battery/Voltage: 2 bytes, 0.01V resolution
- RSSI, SNR, Noise Floor: Custom MeshCore types

## Requirements

- Android 7.0 (API 24) or higher
- Bluetooth LE support
- Bluetooth permissions (granted at runtime)

## Build

```bash
./gradlew assembleDebug
```

## Permissions

- `BLUETOOTH_SCAN` (Android 12+)
- `BLUETOOTH_CONNECT` (Android 12+)
- `ACCESS_FINE_LOCATION` (Android 11 and below)

## References

- [MeshCore GitHub](https://github.com/meshcore-dev/MeshCore)
- [Companion Radio Protocol](https://github.com/meshcore-dev/MeshCore/wiki/Companion-Radio-Protocol)
- [Cayenne LPP Specification](https://docs.mydevices.com/docs/lorawan/cayenne-lpp)

## License

MIT License
