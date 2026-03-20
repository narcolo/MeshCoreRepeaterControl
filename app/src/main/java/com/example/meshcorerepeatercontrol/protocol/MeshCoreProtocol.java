package com.example.meshcorerepeatercontrol.protocol;

import android.util.Log;

import com.example.meshcorerepeatercontrol.bluetooth.BleManager;

import java.util.Arrays;
import java.util.Random;

public class MeshCoreProtocol {
    private static final String TAG = "MeshCoreProtocol";
    private static final byte APP_PROTOCOL_VERSION = 0x03;

    private final BleManager bleManager;
    private ProtocolCallback protocolCallback;

    public interface ProtocolCallback {
        void onAppStartResponse(byte[] selfInfo);
        void onTelemetryResponse(byte[] pubKeyPrefix, byte[] lppData);
        void onStatusResponse(byte[] statusData);  // Old generic status (kept for compatibility)
        void onRepeaterStatusResponse(byte[] statusData);  // Repeater status (0x87)
        void onLoginSuccess();
        void onStatsResponse(byte statsType, byte[] statsData);
        void onContactReceived(String pubKeyHex, String name, double lat, double lon);
        void onContactsEnd();
        void onError(String error);

        // Discovery callbacks
        default void onAdvertReceived(byte[] contactData, int snr) {}
        default void onDiscoverResponse(int nodeType, double snr, double snrIn, int rssi, String pubKeyHex) {}
        default void onChannelInfoReceived(int channelIdx, String name, byte[] psk) {}

        // Message delivery callback
        default void onMessageSent() {}
    }

    public MeshCoreProtocol(BleManager bleManager) {
        this.bleManager = bleManager;
        setupDataCallback();
    }

    public void setProtocolCallback(ProtocolCallback callback) {
        this.protocolCallback = callback;
    }

    private void setupDataCallback() {
        bleManager.setDataCallback(new BleManager.BleDataCallback() {
            @Override
            public void onDataReceived(byte[] data) {
                handleResponse(data);
            }

            @Override
            public void onDataSent() {
                Log.d(TAG, "Data sent successfully");
            }
        });
    }

    private void handleResponse(byte[] frameData) {
        Log.d(TAG, "Raw frame data: " + bytesToHex(frameData));

        MeshCoreFrame frame = MeshCoreFrame.decode(frameData);
        if (frame == null) {
            Log.e(TAG, "Failed to decode frame");
            return;
        }

        byte commandCode = frame.getCommandCode();
        byte[] data = frame.getData();

        Log.d(TAG, "Received frame with code: 0x" + String.format("%02X", commandCode) +
                   ", data length: " + data.length + ", data: " + bytesToHex(data));

        switch (commandCode) {
            case MeshCoreCommand.RESP_SELF_INFO:
                Log.d(TAG, "APP_START response (RESP_SELF_INFO) received");
                if (protocolCallback != null) {
                    protocolCallback.onAppStartResponse(data);
                }
                break;

            case MeshCoreCommand.PUSH_TELEMETRY_RESPONSE:
                Log.d(TAG, "Stats/Telemetry response received (0x8B)");
                handleTelemetryResponse(data);
                break;

            case MeshCoreCommand.PUSH_BINARY_RESPONSE:
                Log.d(TAG, "Binary response received (0x8C), data length: " + data.length);
                // Binary response format may vary - check first byte for type
                if (data.length > 0) {
                    byte responseType = data[0];
                    Log.d(TAG, "Binary response type: 0x" + String.format("%02X", responseType));
                    // If it's status response, treat as status data
                    if (responseType == MeshCoreCommand.BINARY_REQ_TYPE_STATUS) {
                        // Skip first byte (type), rest is status data
                        byte[] statusData = new byte[data.length - 1];
                        System.arraycopy(data, 1, statusData, 0, statusData.length);
                        if (protocolCallback != null) {
                            protocolCallback.onRepeaterStatusResponse(statusData);
                        }
                    }
                }
                break;

            case MeshCoreCommand.PUSH_CONTROL_DATA:
                Log.d(TAG, "Control data received (0x8E), data length: " + data.length);
                handleControlData(data);
                break;

            case MeshCoreCommand.PUSH_STATUS_RESPONSE:
                Log.d(TAG, "Repeater status response received (0x87), data length: " + data.length);
                if (protocolCallback != null) {
                    protocolCallback.onRepeaterStatusResponse(data);
                }
                break;

            case MeshCoreCommand.RESP_ERR:
                Log.e(TAG, "Error response received, error code: " + (data.length > 0 ? String.format("0x%02X", data[0]) : "none"));
                if (protocolCallback != null) {
                    protocolCallback.onError("Command returned error");
                }
                break;

            case MeshCoreCommand.RESP_OK:
                Log.d(TAG, "Command acknowledged (RESP_OK)");
                if (protocolCallback != null) {
                    protocolCallback.onMessageSent();
                }
                break;

            case MeshCoreCommand.RESP_SENT:
                Log.d(TAG, "Message sent successfully (RESP_SENT)");
                if (protocolCallback != null) {
                    protocolCallback.onMessageSent();
                }
                break;

            case MeshCoreCommand.PUSH_LOGIN_SUCCESS:
                Log.d(TAG, "Login successful!");
                if (protocolCallback != null) {
                    protocolCallback.onLoginSuccess();
                }
                break;

            case MeshCoreCommand.PUSH_LOGIN_FAILED:
                Log.e(TAG, "Login failed!");
                if (protocolCallback != null) {
                    protocolCallback.onError("Login failed - check password and pub key");
                }
                break;

            case MeshCoreCommand.PUSH_ADVERT:
            case MeshCoreCommand.PUSH_NEW_ADVERT:
                Log.d(TAG, "Advertisement received (0x" + String.format("%02X", commandCode) +
                           "), data length: " + data.length);
                if (data.length > 1 && protocolCallback != null) {
                    int snr = data[0];  // First byte is SNR
                    byte[] contactData = new byte[data.length - 1];
                    System.arraycopy(data, 1, contactData, 0, contactData.length);
                    protocolCallback.onAdvertReceived(contactData, snr);
                }
                break;

            case MeshCoreCommand.RESP_CHANNEL_INFO:
                Log.d(TAG, "Channel info received, data length: " + data.length);
                if (data.length >= 49 && protocolCallback != null) {  // 1 idx + 32 name + 16 psk
                    int channelIdx = data[0] & 0xFF;
                    byte[] nameBytes = new byte[32];
                    System.arraycopy(data, 1, nameBytes, 0, 32);
                    String channelName = new String(nameBytes, java.nio.charset.StandardCharsets.UTF_8)
                            .replace("\0", "").trim();
                    byte[] psk = new byte[16];
                    System.arraycopy(data, 33, psk, 0, 16);
                    protocolCallback.onChannelInfoReceived(channelIdx, channelName, psk);
                }
                break;

            case MeshCoreCommand.RESP_STATS:
                Log.d(TAG, "Stats response received");
                handleStatsResponse(data);
                break;

            case MeshCoreCommand.RESP_CONTACTS_START:
                Log.d(TAG, "Contacts list starting");
                break;

            case MeshCoreCommand.RESP_CONTACT:
                Log.d(TAG, "Contact received (" + data.length + " bytes)");
                if (data.length >= 131 && protocolCallback != null) {
                    // RESP_CONTACT format (147 bytes, after cmd byte stripped):
                    // [0-31] pubKey (32 bytes)
                    // [32] type
                    // [99-130] name (32 bytes, null-padded)
                    // [135-138] lat (int32 LE, /1e6)
                    // [139-142] lon (int32 LE, /1e6)
                    byte[] contactPubKey = new byte[32];
                    System.arraycopy(data, 0, contactPubKey, 0, 32);
                    String contactPubKeyHex = bytesToHex(contactPubKey);

                    byte[] contactNameBytes = new byte[32];
                    System.arraycopy(data, 99, contactNameBytes, 0, 32);
                    String contactName = new String(contactNameBytes, java.nio.charset.StandardCharsets.UTF_8)
                            .replace("\0", "").trim();

                    double contactLat = 0;
                    double contactLon = 0;
                    if (data.length >= 143) {
                        int latRaw = (data[135] & 0xFF) | ((data[136] & 0xFF) << 8) |
                                     ((data[137] & 0xFF) << 16) | ((data[138] & 0xFF) << 24);
                        int lonRaw = (data[139] & 0xFF) | ((data[140] & 0xFF) << 8) |
                                     ((data[141] & 0xFF) << 16) | ((data[142] & 0xFF) << 24);
                        contactLat = latRaw / 1e6;
                        contactLon = lonRaw / 1e6;
                    }

                    Log.d(TAG, "Contact: pubkey=" + contactPubKeyHex + ", name='" + contactName +
                               "', lat=" + contactLat + ", lon=" + contactLon);
                    protocolCallback.onContactReceived(contactPubKeyHex, contactName, contactLat, contactLon);
                }
                break;

            case MeshCoreCommand.RESP_END_OF_CONTACTS:
                Log.d(TAG, "End of contacts list");
                if (protocolCallback != null) {
                    protocolCallback.onContactsEnd();
                }
                break;

            case (byte) 0xFF:
                // Frame 0xFF might contain embedded telemetry or multi-part messages
                Log.d(TAG, "Received 0xFF frame, checking for embedded telemetry");
                // Check if data starts with 0x8B (PUSH_TELEMETRY_RESPONSE)
                if (data.length > 0 && data[0] == MeshCoreCommand.PUSH_TELEMETRY_RESPONSE) {
                    Log.d(TAG, "Found embedded telemetry response in 0xFF frame");
                    // Skip the first byte (0x8B) and process as telemetry
                    byte[] telemetryData = new byte[data.length - 1];
                    System.arraycopy(data, 1, telemetryData, 0, telemetryData.length);
                    handleTelemetryResponse(telemetryData);
                } else {
                    Log.d(TAG, "0xFF frame data: " + bytesToHex(data));
                }
                break;

            default:
                Log.d(TAG, "Unhandled response code: 0x" + String.format("%02X", commandCode) +
                          " (" + commandCode + " decimal), data length: " + data.length);
                Log.d(TAG, "Data (hex): " + bytesToHex(data));

                // Try to interpret as ASCII if printable
                StringBuilder ascii = new StringBuilder();
                boolean isPrintable = true;
                for (byte b : data) {
                    if (b >= 32 && b < 127) {
                        ascii.append((char) b);
                    } else {
                        isPrintable = false;
                        break;
                    }
                }
                if (isPrintable && ascii.length() > 0) {
                    Log.d(TAG, "Data (ASCII): " + ascii.toString());
                }
                break;
        }
    }

    private void handleStatsResponse(byte[] data) {
        if (data.length < 2) {
            Log.e(TAG, "Stats response too short: " + data.length + " bytes");
            return;
        }

        // RESP_STATS format: [stats_type: 1 byte][stats_data: variable]
        byte statsType = data[0];
        byte[] statsData = new byte[data.length - 1];
        System.arraycopy(data, 1, statsData, 0, statsData.length);

        Log.d(TAG, "Stats response - Type: " + statsType + ", Data: " + bytesToHex(statsData));

        String typeName;
        switch (statsType) {
            case MeshCoreCommand.STATS_TYPE_CORE:
                typeName = "CORE (battery, uptime, errors, queue)";
                break;
            case MeshCoreCommand.STATS_TYPE_RADIO:
                typeName = "RADIO (noise floor, RSSI, SNR, airtime)";
                break;
            case MeshCoreCommand.STATS_TYPE_PACKETS:
                typeName = "PACKETS (sent/received counts)";
                break;
            default:
                typeName = "UNKNOWN";
                break;
        }
        Log.d(TAG, "Stats type: " + typeName);

        if (protocolCallback != null) {
            protocolCallback.onStatsResponse(statsType, statsData);
        }
    }

    private void handleTelemetryResponse(byte[] data) {
        if (data.length < 5) {
            Log.e(TAG, "Telemetry response too short: " + data.length + " bytes");
            return;
        }

        // Log all data as hex for debugging
        Log.d(TAG, "Telemetry/broadcast data (" + data.length + " bytes): " + bytesToHex(data));

        // This is broadcast data, not authenticated stats
        // Format may vary - pass all data
        byte[] pubKeyPrefix = new byte[0];
        byte[] telemetryData = data;

        Log.d(TAG, "Telemetry data (" + telemetryData.length + " bytes): " + bytesToHex(telemetryData));

        if (protocolCallback != null) {
            protocolCallback.onTelemetryResponse(pubKeyPrefix, telemetryData);
        }
    }

    private void handleControlData(byte[] data) {
        // PUSH_CONTROL_DATA (0x8E) format from Python meshcore library:
        // [0] SNR (signed byte, /4.0)
        // [1] RSSI (signed byte)
        // [2] path_len
        // [3+] payload
        if (data.length < 4) {
            Log.e(TAG, "Control data too short: " + data.length);
            return;
        }

        int snrRaw = data[0];
        double snr = (snrRaw > 127 ? snrRaw - 256 : snrRaw) / 4.0;
        int rssiRaw = data[1];
        int rssi = rssiRaw > 127 ? rssiRaw - 256 : rssiRaw;
        int pathLen = data[2] & 0xFF;
        byte[] payload = new byte[data.length - 3];
        System.arraycopy(data, 3, payload, 0, payload.length);

        Log.d(TAG, "Control data: SNR=" + snr + ", RSSI=" + rssi +
                   ", pathLen=" + pathLen + ", payload=" + bytesToHex(payload));

        if (payload.length == 0) return;

        int payloadType = payload[0] & 0xF0;

        // NODE_DISCOVER_RESP: payload[0] = 0x90 | nodeType
        if (payloadType == (MeshCoreCommand.CONTROL_DISCOVER_RESP & 0xFF)) {
            int nodeType = payload[0] & 0x0F;

            if (payload.length < 6) {
                Log.w(TAG, "DISCOVER_RESP too short: " + payload.length);
                return;
            }

            // [1] SNR_in (signed byte, /4.0)
            int snrInRaw = payload[1];
            double snrIn = (snrInRaw > 127 ? snrInRaw - 256 : snrInRaw) / 4.0;

            // [2-5] tag (4 bytes LE)
            int tag = (payload[2] & 0xFF) | ((payload[3] & 0xFF) << 8) |
                      ((payload[4] & 0xFF) << 16) | ((payload[5] & 0xFF) << 24);

            // [6+] public key (8 or 32 bytes)
            byte[] pubKey = new byte[payload.length - 6];
            System.arraycopy(payload, 6, pubKey, 0, pubKey.length);
            String pubKeyHex = bytesToHex(pubKey);

            String[] typeNames = {"NONE", "CLI", "REP", "ROOM", "SENS"};
            String typeName = nodeType < typeNames.length ? typeNames[nodeType] : "t:" + nodeType;

            Log.d(TAG, "DISCOVER_RESP: type=" + typeName + ", SNR_in=" + snrIn +
                       ", SNR=" + snr + ", RSSI=" + rssi + ", tag=" + String.format("%08X", tag) +
                       ", pubkey=" + pubKeyHex);

            if (protocolCallback != null) {
                protocolCallback.onDiscoverResponse(nodeType, snr, snrIn, rssi, pubKeyHex);
            }
        } else {
            Log.d(TAG, "Control payload type: 0x" + String.format("%02X", payload[0] & 0xFF));
        }
    }

    // Command sending methods

    public int sendNodeDiscoverReq(int filter) {
        Log.d(TAG, "Sending NODE_DISCOVER_REQ, filter=0x" + String.format("%02X", filter));

        // From Python meshcore library:
        // send_control_data(ControlType.NODE_DISCOVER_REQ | flags, payload)
        // payload = [filter:1][tag:4LE]
        // BLE frame = [0x37][control_type][payload]

        int tag = new Random().nextInt() & 0x7FFFFFFF; // positive random tag
        if (tag == 0) tag = 1;

        byte controlType = (byte) (MeshCoreCommand.CONTROL_DISCOVER_REQ | 0x01); // 0x81 = DISCOVER_REQ + prefix_only

        byte[] payload = new byte[1 + 1 + 4]; // control_type + filter + tag
        payload[0] = controlType;
        payload[1] = (byte) filter;
        payload[2] = (byte) (tag & 0xFF);
        payload[3] = (byte) ((tag >> 8) & 0xFF);
        payload[4] = (byte) ((tag >> 16) & 0xFF);
        payload[5] = (byte) ((tag >> 24) & 0xFF);

        Log.d(TAG, "NODE_DISCOVER_REQ: controlType=0x" + String.format("%02X", controlType & 0xFF) +
                   ", filter=0x" + String.format("%02X", filter) +
                   ", tag=" + String.format("%08X", tag));

        MeshCoreFrame frame = new MeshCoreFrame(MeshCoreCommand.CMD_SEND_CONTROL_DATA, payload);
        sendFrame(frame);

        return tag;
    }

    public void sendDeviceQuery() {
        Log.d(TAG, "Sending DEVICE_QUERY command");
        // Format: [0x16][protocol_version]
        byte[] payload = new byte[]{APP_PROTOCOL_VERSION};
        MeshCoreFrame frame = new MeshCoreFrame(MeshCoreCommand.CMD_DEVICE_QUERY, payload);
        sendFrame(frame);
    }

    public void sendAppStart() {
        Log.d(TAG, "Sending APP_START command");
        // Format: [0x01][app_ver][reserved x6][app_name...][0x00]
        String appName = "MeshCoreDiscovery";
        byte[] nameBytes = appName.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] payload = new byte[1 + 6 + nameBytes.length + 1];
        payload[0] = 0x01; // app version
        // bytes 1-6 are reserved (already zero)
        System.arraycopy(nameBytes, 0, payload, 7, nameBytes.length);
        // last byte is null terminator (already zero)
        MeshCoreFrame frame = new MeshCoreFrame(MeshCoreCommand.CMD_APP_START, payload);
        sendFrame(frame);
    }

    public void sendSelfAdvert() {
        Log.d(TAG, "Sending SEND_SELF_ADVERT command");
        // Format: [0x07][flood_flag]  0=zero-hop, 1=flood
        byte[] payload = new byte[]{0x00};
        MeshCoreFrame frame = new MeshCoreFrame(MeshCoreCommand.CMD_SEND_SELF_ADVERT, payload);
        sendFrame(frame);
    }

    public void sendTelemetryRequest(byte[] targetPubKey) {
        Log.d(TAG, "Sending TELEMETRY_REQ to target: " + bytesToHex(targetPubKey));

        // CMD_SEND_TELEMETRY_REQ format (after LOGIN): 0x27 0x00 0x00 0x00 [32-byte pubkey]
        // No password needed - authentication was done during LOGIN
        if (targetPubKey.length != 32) {
            Log.e(TAG, "Invalid pub key length: " + targetPubKey.length + " (expected 32)");
            return;
        }

        // Python library: b"\x27\x00\x00\x00" + dst_bytes
        byte[] payload = new byte[3 + 32]; // 3 null bytes + 32-byte pubkey
        payload[0] = 0x00;
        payload[1] = 0x00;
        payload[2] = 0x00;
        System.arraycopy(targetPubKey, 0, payload, 3, 32);

        Log.d(TAG, "TELEMETRY_REQ payload: " + bytesToHex(payload));

        MeshCoreFrame frame = new MeshCoreFrame(MeshCoreCommand.CMD_SEND_TELEMETRY_REQ, payload);
        sendFrame(frame);
    }

    private byte[] hashPassword(String password) {
        Log.d(TAG, "Password length: " + password.length() + " chars");

        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(password.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            // SHA-256 produces 32 bytes, take first 16 for channel key
            byte[] key = new byte[16];
            System.arraycopy(hash, 0, key, 0, 16);
            Log.d(TAG, "Password hashed with SHA-256, key (16 bytes): " + bytesToHex(key));
            return key;
        } catch (java.security.NoSuchAlgorithmException e) {
            Log.e(TAG, "SHA-256 not available", e);
            // Fallback: use password bytes padded/truncated to 16 bytes
            byte[] key = new byte[16];
            byte[] passwordBytes = password.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            int len = Math.min(passwordBytes.length, 16);
            System.arraycopy(passwordBytes, 0, key, 0, len);
            Log.d(TAG, "Password as raw bytes (padded to 16): " + bytesToHex(key));
            return key;
        }
    }

    public void sendLogin(byte[] targetPubKey, String password) {
        Log.d(TAG, "Sending LOGIN command to target: " + bytesToHex(targetPubKey));

        // CMD_SEND_LOGIN format: [pub_key: 32 bytes][password: null-terminated string]
        // Note: Companion firmware expects null terminator despite Python library not sending it
        if (targetPubKey.length != 32) {
            Log.e(TAG, "Invalid pub key length: " + targetPubKey.length + " (expected 32)");
            return;
        }

        // Convert password to bytes with null terminator
        byte[] passwordBytes = password.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Log.d(TAG, "Password length: " + passwordBytes.length + " bytes");

        // Combine pub key and password with null terminator
        byte[] payload = new byte[32 + passwordBytes.length + 1];
        System.arraycopy(targetPubKey, 0, payload, 0, 32);
        System.arraycopy(passwordBytes, 0, payload, 32, passwordBytes.length);
        payload[32 + passwordBytes.length] = 0x00; // Null terminator

        Log.d(TAG, "LOGIN payload length: " + payload.length + " bytes (32 pubkey + " +
                   passwordBytes.length + " password + 1 null)");

        MeshCoreFrame frame = new MeshCoreFrame(MeshCoreCommand.CMD_SEND_LOGIN, payload);
        sendFrame(frame);
    }

    public void sendStatusRequest(byte[] targetPubKey) {
        Log.d(TAG, "Sending STATUS_REQUEST to target: " + bytesToHex(targetPubKey));

        // CMD_SEND_STATUSREQ format: 0x1B [32-byte pubkey]
        // Note: Unlike LOGIN, STATUS_REQUEST does NOT use a null terminator (per Python library)
        if (targetPubKey.length != 32) {
            Log.e(TAG, "Invalid pub key length: " + targetPubKey.length + " (expected 32)");
            return;
        }

        // Just the 32-byte pubkey, no null terminator
        byte[] payload = new byte[32];
        System.arraycopy(targetPubKey, 0, payload, 0, 32);

        Log.d(TAG, "STATUS_REQUEST payload (" + payload.length + " bytes): " + bytesToHex(payload));

        MeshCoreFrame frame = new MeshCoreFrame(MeshCoreCommand.CMD_SEND_STATUSREQ, payload);
        sendFrame(frame);
    }

    public void sendResetPath(byte[] targetPubKey) {
        Log.d(TAG, "Sending RESET_PATH (0x0D) for target: " + bytesToHex(targetPubKey));

        // CMD_RESET_PATH format: 0x0D [32-byte pubkey]
        // This tells the companion to use flood mode for this contact
        if (targetPubKey.length != 32) {
            Log.e(TAG, "Invalid pub key length: " + targetPubKey.length + " (expected 32)");
            return;
        }

        byte[] payload = new byte[32];
        System.arraycopy(targetPubKey, 0, payload, 0, 32);

        Log.d(TAG, "RESET_PATH payload (" + payload.length + " bytes): " + bytesToHex(payload));

        MeshCoreFrame frame = new MeshCoreFrame(MeshCoreCommand.CMD_RESET_PATH, payload);
        sendFrame(frame);
    }

    public void sendBinaryStatusRequest(byte[] targetPubKey) {
        Log.d(TAG, "Sending BINARY_STATUS_REQUEST (0x32) to target: " + bytesToHex(targetPubKey));

        // CMD_SEND_BINARY_REQ format: 0x32 [32-byte pubkey][1-byte request type]
        // For status request: type=0x01 (BINARY_REQ_TYPE_STATUS)
        // Per Python library: data = b"\x32" + dst_bytes + request_type.to_bytes(1, "little")
        if (targetPubKey.length != 32) {
            Log.e(TAG, "Invalid pub key length: " + targetPubKey.length + " (expected 32)");
            return;
        }

        byte[] payload = new byte[33]; // 32 pubkey + 1 request type
        System.arraycopy(targetPubKey, 0, payload, 0, 32); // 32-byte public key
        payload[32] = MeshCoreCommand.BINARY_REQ_TYPE_STATUS; // Request type: 0x01 (STATUS)

        Log.d(TAG, "BINARY_STATUS_REQUEST payload (" + payload.length + " bytes): " + bytesToHex(payload));

        MeshCoreFrame frame = new MeshCoreFrame(MeshCoreCommand.CMD_SEND_BINARY_REQ, payload);
        sendFrame(frame);
    }


    public void sendGetContacts() {
        Log.d(TAG, "Sending GET_CONTACTS command");
        // CMD_GET_CONTACTS has no payload
        MeshCoreFrame frame = new MeshCoreFrame(MeshCoreCommand.CMD_GET_CONTACTS, new byte[0]);
        sendFrame(frame);
    }

    public void sendAddUpdateContact(byte[] pubKey, String name) {
        Log.d(TAG, "Sending ADD_UPDATE_CONTACT: " + name);

        // CMD_ADD_UPDATE_CONTACT format: [pubkey: 32 bytes][name: null-terminated string]
        if (pubKey.length != 32) {
            Log.e(TAG, "Invalid pub key length: " + pubKey.length + " (expected 32)");
            return;
        }

        byte[] nameBytes = name.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] payload = new byte[32 + nameBytes.length + 1]; // +1 for null terminator
        System.arraycopy(pubKey, 0, payload, 0, 32);
        System.arraycopy(nameBytes, 0, payload, 32, nameBytes.length);
        payload[32 + nameBytes.length] = 0x00; // Null terminator

        Log.d(TAG, "ADD_UPDATE_CONTACT payload length: " + payload.length + " bytes");

        MeshCoreFrame frame = new MeshCoreFrame(MeshCoreCommand.CMD_ADD_UPDATE_CONTACT, payload);
        sendFrame(frame);
    }

    public void sendAddUpdateContactWithPath(byte[] pubKey, String name, int pathLen, byte type) {
        Log.d(TAG, "Sending ADD_UPDATE_CONTACT with path: " + name + ", pathLen=" + pathLen + ", type=" + type);

        // Full CMD_ADD_UPDATE_CONTACT format (per official Dart client):
        // [pub_key x32][type][flags][path_len][path x64][name x32][timestamp x4]
        if (pubKey.length != 32) {
            Log.e(TAG, "Invalid pub key length: " + pubKey.length + " (expected 32)");
            return;
        }

        int timestamp = (int)(System.currentTimeMillis() / 1000);
        byte[] payload = new byte[32 + 1 + 1 + 1 + 64 + 32 + 4]; // 135 bytes

        int offset = 0;

        // Public key (32 bytes)
        System.arraycopy(pubKey, 0, payload, offset, 32);
        offset += 32;

        // Type (1 byte): 1=chat, 2=repeater
        payload[offset++] = type;

        // Flags (1 byte)
        payload[offset++] = 0x00;

        // Path length (1 byte): -1 for flood mode
        payload[offset++] = (byte)pathLen;

        // Path data (64 bytes) - zero-padded (empty for flood mode)
        // Already zeroed by array initialization
        offset += 64;

        // Name (32 bytes) - null-padded
        byte[] nameBytes = name.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        int nameLen = Math.min(nameBytes.length, 31); // Leave room for null
        System.arraycopy(nameBytes, 0, payload, offset, nameLen);
        // Rest is already zero-padded
        offset += 32;

        // Timestamp (4 bytes, little-endian)
        payload[offset++] = (byte)(timestamp & 0xFF);
        payload[offset++] = (byte)((timestamp >> 8) & 0xFF);
        payload[offset++] = (byte)((timestamp >> 16) & 0xFF);
        payload[offset++] = (byte)((timestamp >> 24) & 0xFF);

        Log.d(TAG, "ADD_UPDATE_CONTACT_WITH_PATH payload: " + payload.length + " bytes, pathLen=" + pathLen);

        MeshCoreFrame frame = new MeshCoreFrame(MeshCoreCommand.CMD_ADD_UPDATE_CONTACT, payload);
        sendFrame(frame);
    }

    public void sendGetStats(byte statsType) {
        Log.d(TAG, "Sending GET_STATS (type=" + statsType + ")");

        // CMD_GET_STATS format: [stats_type: 1 byte]
        // This is a DEVICE command (to companion), not a message command (to repeater)
        // No authentication data needed - just the stats type
        byte[] payload = new byte[1];
        payload[0] = statsType;

        Log.d(TAG, "GET_STATS payload: " + bytesToHex(payload));

        MeshCoreFrame frame = new MeshCoreFrame(MeshCoreCommand.CMD_GET_STATS, payload);
        sendFrame(frame);
    }

    public void sendSetAdvertLatLon(double lat, double lon) {
        Log.d(TAG, "Sending SET_ADVERT_LATLON: lat=" + lat + ", lon=" + lon);
        int latEncoded = (int)(lat * 1000000);
        int lonEncoded = (int)(lon * 1000000);
        byte[] payload = MeshCoreFrame.concat(
                MeshCoreFrame.encodeInt32(latEncoded),
                MeshCoreFrame.encodeInt32(lonEncoded)
        );
        MeshCoreFrame frame = new MeshCoreFrame(MeshCoreCommand.CMD_SET_ADVERT_LATLON, payload);
        sendFrame(frame);
    }

    public void sendChannelTextMessage(int channelIdx, String text) {
        Log.d(TAG, "Sending channel text message to channel " + channelIdx + ": " + text);
        int timestamp = (int)(System.currentTimeMillis() / 1000);
        byte[] textBytes = text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        // Format: [0x00][idx][timestamp LE x4][text][0x00]
        byte[] payload = new byte[1 + 1 + 4 + textBytes.length + 1];
        payload[0] = 0x00;
        payload[1] = (byte) channelIdx;
        byte[] tsBytes = MeshCoreFrame.encodeInt32(timestamp);
        System.arraycopy(tsBytes, 0, payload, 2, 4);
        System.arraycopy(textBytes, 0, payload, 6, textBytes.length);
        payload[payload.length - 1] = 0x00;
        MeshCoreFrame frame = new MeshCoreFrame(MeshCoreCommand.CMD_SEND_CHANNEL_TXT_MSG, payload);
        sendFrame(frame);
    }

    public void sendGetChannel(int channelIdx) {
        Log.d(TAG, "Sending GET_CHANNEL for index " + channelIdx);
        byte[] payload = new byte[]{(byte) channelIdx};
        MeshCoreFrame frame = new MeshCoreFrame(MeshCoreCommand.CMD_GET_CHANNEL, payload);
        sendFrame(frame);
    }

    public void sendSetChannel(int idx, String name, byte[] psk) {
        Log.d(TAG, "Sending SET_CHANNEL: idx=" + idx + ", name=" + name);
        // Format: [idx][name x32][psk x16]
        byte[] payload = new byte[1 + 32 + 16];
        payload[0] = (byte) idx;
        byte[] nameBytes = name.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        int nameLen = Math.min(nameBytes.length, 31);
        System.arraycopy(nameBytes, 0, payload, 1, nameLen);
        if (psk != null && psk.length >= 16) {
            System.arraycopy(psk, 0, payload, 33, 16);
        }
        MeshCoreFrame frame = new MeshCoreFrame(MeshCoreCommand.CMD_SET_CHANNEL, payload);
        sendFrame(frame);
    }

    private void sendFrame(MeshCoreFrame frame) {
        byte[] encodedFrame = frame.encode();
        Log.d(TAG, "Sending frame: " + bytesToHex(encodedFrame));

        bleManager.sendData(encodedFrame, new BleManager.BleDataCallback() {
            @Override
            public void onDataReceived(byte[] data) {
                // Handled by the main callback
            }

            @Override
            public void onDataSent() {
                Log.d(TAG, "Frame sent successfully");
            }
        });
    }

    public static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02X", b));
        }
        return sb.toString();
    }

    public static byte[] hexToBytes(String hex) {
        int len = hex.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                    + Character.digit(hex.charAt(i + 1), 16));
        }
        return data;
    }
}
