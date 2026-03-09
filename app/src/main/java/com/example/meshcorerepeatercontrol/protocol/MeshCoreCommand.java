package com.example.meshcorerepeatercontrol.protocol;

public class MeshCoreCommand {
    // Request Commands
    public static final byte CMD_APP_START = 0x01;
    public static final byte CMD_SEND_TXT_MSG = 0x02;
    public static final byte CMD_SEND_CHANNEL_TXT_MSG = 0x03;
    public static final byte CMD_GET_CONTACTS = 0x04;
    public static final byte CMD_SEND_SELF_ADVERT = 0x07;  // Broadcast self advertisement
    public static final byte CMD_ADD_UPDATE_CONTACT = 0x09;
    public static final byte CMD_RESET_PATH = 0x0D;
    public static final byte CMD_SET_ADVERT_LATLON = 0x0E;  // Set GPS coordinates
    public static final byte CMD_DEVICE_QUERY = 0x16;
    public static final byte CMD_SEND_LOGIN = 0x1A;  // Login to repeater/room server
    public static final byte CMD_SEND_STATUSREQ = 0x1B;  // Request status from repeater
    public static final byte CMD_SEND_TELEMETRY_REQ = 0x27;
    public static final byte CMD_SEND_BINARY_REQ = 0x32;  // Binary request (status, etc)
    public static final byte CMD_GET_CHANNEL = 0x1F;   // Get channel configuration
    public static final byte CMD_SET_CHANNEL = 0x20;   // Create/set channel
    public static final byte CMD_SEND_CONTROL_DATA = 0x37;  // Send control data (v8+ discovery)
    public static final byte CMD_GET_STATS = 0x38;

    // Binary request types (for CMD_SEND_BINARY_REQ)
    public static final byte BINARY_REQ_TYPE_STATUS = 0x01;

    // Control data types (for CMD_SEND_CONTROL_DATA)
    public static final byte CONTROL_DISCOVER_REQ = (byte) 0x80;
    public static final byte CONTROL_DISCOVER_RESP = (byte) 0x90;

    // Response Codes
    public static final byte RESP_OK = 0x00;
    public static final byte RESP_ERR = 0x01;
    public static final byte RESP_CONTACTS_START = 0x02;
    public static final byte RESP_CONTACT = 0x03;
    public static final byte RESP_END_OF_CONTACTS = 0x04;
    public static final byte RESP_SELF_INFO = 0x05;
    public static final byte RESP_SENT = 0x06;
    public static final byte RESP_CONTACT_MSG_RECV = 0x07;
    public static final byte RESP_CHANNEL_MSG_RECV = 0x08;
    public static final byte RESP_CURR_TIME = 0x09;
    public static final byte RESP_EXPORT_CONTACT = 0x0B;
    public static final byte RESP_BATT_AND_STORAGE = 0x0C;
    public static final byte RESP_DEVICE_INFO = 0x0D;
    public static final byte RESP_CHANNEL_INFO = 0x12;  // Channel info response
    public static final byte RESP_STATS = 0x18;  // Stats response

    // Push Notification Codes
    public static final byte PUSH_ADVERT = (byte) 0x80;
    public static final byte PUSH_PATH_UPDATED = (byte) 0x81;
    public static final byte PUSH_SEND_CONFIRMED = (byte) 0x82;
    public static final byte PUSH_MSG_WAITING = (byte) 0x83;
    public static final byte PUSH_RAW_DATA = (byte) 0x84;
    public static final byte PUSH_LOGIN_SUCCESS = (byte) 0x85;
    public static final byte PUSH_LOGIN_FAILED = (byte) 0x86;
    public static final byte PUSH_STATUS_RESPONSE = (byte) 0x87;  // Status response from repeater
    public static final byte PUSH_TRACE_DATA = (byte) 0x89;
    public static final byte PUSH_NEW_ADVERT = (byte) 0x8A;  // New node advertisement
    public static final byte PUSH_TELEMETRY_RESPONSE = (byte) 0x8B;
    public static final byte PUSH_BINARY_RESPONSE = (byte) 0x8C;
    public static final byte PUSH_CONTROL_DATA = (byte) 0x8E;  // Control data response (discovery)

    // Stats Types (for CMD_GET_STATS)
    public static final byte STATS_TYPE_CORE = 0x00;    // Battery, uptime, error flags, queue length
    public static final byte STATS_TYPE_RADIO = 0x01;   // Noise floor, RSSI, SNR, airtime
    public static final byte STATS_TYPE_PACKETS = 0x02; // Packet counts
}
