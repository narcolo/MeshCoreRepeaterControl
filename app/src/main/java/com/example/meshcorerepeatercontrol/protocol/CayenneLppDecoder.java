package com.example.meshcorerepeatercontrol.protocol;

import android.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

public class CayenneLppDecoder {
    private static final String TAG = "CayenneLppDecoder";

    // Cayenne LPP Data Types
    public static final byte LPP_DIGITAL_INPUT = 0;
    public static final byte LPP_DIGITAL_OUTPUT = 1;
    public static final byte LPP_ANALOG_INPUT = 2;
    public static final byte LPP_ANALOG_OUTPUT = 3;
    public static final byte LPP_LUMINOSITY = 101;
    public static final byte LPP_PRESENCE = 102;
    public static final byte LPP_TEMPERATURE = 103;
    public static final byte LPP_HUMIDITY = 104;
    public static final byte LPP_ACCELEROMETER = 113;
    public static final byte LPP_BAROMETER = 115;
    public static final byte LPP_VOLTAGE = 116;
    public static final byte LPP_CURRENT = 117;
    public static final byte LPP_FREQUENCY = 118;
    public static final byte LPP_PERCENTAGE = 120;
    public static final byte LPP_ALTITUDE = 121;
    public static final byte LPP_CONCENTRATION = 125;
    public static final byte LPP_POWER = (byte) 128;
    public static final byte LPP_DISTANCE = (byte) 130;
    public static final byte LPP_ENERGY = (byte) 131;
    public static final byte LPP_DIRECTION = (byte) 132;
    public static final byte LPP_GYROMETER = (byte) 134;
    public static final byte LPP_GPS = (byte) 136;

    // Custom types for MeshCore
    public static final byte LPP_RSSI = (byte) 140;
    public static final byte LPP_SNR = (byte) 141;
    public static final byte LPP_NOISE_FLOOR = (byte) 142;

    public static class LppValue {
        public int channel;
        public byte type;
        public String name;
        public Object value;
        public String unit;

        public LppValue(int channel, byte type, String name, Object value, String unit) {
            this.channel = channel;
            this.type = type;
            this.name = name;
            this.value = value;
            this.unit = unit;
        }

        @Override
        public String toString() {
            return String.format("Ch%d: %s = %s %s", channel, name, value, unit);
        }
    }

    public static List<LppValue> decode(byte[] data) {
        List<LppValue> values = new ArrayList<>();
        int offset = 0;

        while (offset < data.length) {
            if (offset + 2 > data.length) {
                Log.w(TAG, "Incomplete LPP frame at offset " + offset);
                break;
            }

            int channel = data[offset] & 0xFF;
            byte type = data[offset + 1];
            offset += 2;

            LppValue value = decodeValue(channel, type, data, offset);
            if (value != null) {
                values.add(value);
                offset += getTypeSize(type);
            } else {
                Log.w(TAG, "Unknown LPP type: " + type + " at offset " + (offset - 2));
                break;
            }
        }

        return values;
    }

    private static LppValue decodeValue(int channel, byte type, byte[] data, int offset) {
        try {
            switch (type) {
                case LPP_DIGITAL_INPUT:
                case LPP_DIGITAL_OUTPUT:
                    return new LppValue(channel, type, "Digital", data[offset] & 0xFF, "");

                case LPP_ANALOG_INPUT:
                case LPP_ANALOG_OUTPUT:
                    return new LppValue(channel, type, "Analog", decodeInt16(data, offset) / 100.0, "");

                case LPP_LUMINOSITY:
                    return new LppValue(channel, type, "Luminosity", decodeUint16(data, offset), "lux");

                case LPP_PRESENCE:
                    return new LppValue(channel, type, "Presence", data[offset] & 0xFF, "");

                case LPP_TEMPERATURE:
                    return new LppValue(channel, type, "Temperature", decodeInt16(data, offset) / 10.0, "°C");

                case LPP_HUMIDITY:
                    return new LppValue(channel, type, "Humidity", (data[offset] & 0xFF) / 2.0, "%");

                case LPP_BAROMETER:
                    return new LppValue(channel, type, "Pressure", decodeUint16(data, offset) / 10.0, "hPa");

                case LPP_VOLTAGE:
                    return new LppValue(channel, type, "Voltage", decodeUint16(data, offset) / 100.0, "V");

                case LPP_CURRENT:
                    return new LppValue(channel, type, "Current", decodeUint16(data, offset) / 1000.0, "A");

                case LPP_FREQUENCY:
                    return new LppValue(channel, type, "Frequency", decodeUint32(data, offset), "Hz");

                case LPP_PERCENTAGE:
                    return new LppValue(channel, type, "Percentage", data[offset] & 0xFF, "%");

                case LPP_ALTITUDE:
                    return new LppValue(channel, type, "Altitude", decodeInt16(data, offset), "m");

                case LPP_POWER:
                    return new LppValue(channel, type, "Power", decodeUint16(data, offset), "W");

                case LPP_DISTANCE:
                    return new LppValue(channel, type, "Distance", decodeUint32(data, offset) / 1000.0, "m");

                case LPP_ACCELEROMETER:
                    double x = decodeInt16(data, offset) / 1000.0;
                    double y = decodeInt16(data, offset + 2) / 1000.0;
                    double z = decodeInt16(data, offset + 4) / 1000.0;
                    return new LppValue(channel, type, "Accelerometer",
                            String.format("x:%.3f y:%.3f z:%.3f", x, y, z), "g");

                case LPP_GYROMETER:
                    double gx = decodeInt16(data, offset) / 100.0;
                    double gy = decodeInt16(data, offset + 2) / 100.0;
                    double gz = decodeInt16(data, offset + 4) / 100.0;
                    return new LppValue(channel, type, "Gyrometer",
                            String.format("x:%.2f y:%.2f z:%.2f", gx, gy, gz), "°/s");

                case LPP_GPS:
                    double lat = decodeInt24(data, offset) / 10000.0;
                    double lon = decodeInt24(data, offset + 3) / 10000.0;
                    double alt = decodeInt24(data, offset + 6) / 100.0;
                    return new LppValue(channel, type, "GPS",
                            String.format("%.4f,%.4f (%.1fm)", lat, lon, alt), "");

                // Custom MeshCore types
                case LPP_RSSI:
                    return new LppValue(channel, type, "RSSI", (byte) data[offset], "dBm");

                case LPP_SNR:
                    return new LppValue(channel, type, "SNR", decodeInt16(data, offset) / 10.0, "dB");

                case LPP_NOISE_FLOOR:
                    return new LppValue(channel, type, "Noise Floor", (byte) data[offset], "dBm");

                default:
                    return null;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error decoding LPP value: " + e.getMessage());
            return null;
        }
    }

    private static int getTypeSize(byte type) {
        switch (type) {
            case LPP_DIGITAL_INPUT:
            case LPP_DIGITAL_OUTPUT:
            case LPP_PRESENCE:
            case LPP_HUMIDITY:
            case LPP_PERCENTAGE:
            case LPP_RSSI:
            case LPP_NOISE_FLOOR:
                return 1;

            case LPP_ANALOG_INPUT:
            case LPP_ANALOG_OUTPUT:
            case LPP_LUMINOSITY:
            case LPP_TEMPERATURE:
            case LPP_BAROMETER:
            case LPP_VOLTAGE:
            case LPP_CURRENT:
            case LPP_ALTITUDE:
            case LPP_POWER:
            case LPP_SNR:
                return 2;

            case LPP_FREQUENCY:
            case LPP_DISTANCE:
                return 4;

            case LPP_ACCELEROMETER:
            case LPP_GYROMETER:
                return 6;

            case LPP_GPS:
                return 9;

            default:
                return 0;
        }
    }

    private static int decodeInt16(byte[] data, int offset) {
        ByteBuffer buffer = ByteBuffer.wrap(data, offset, 2);
        buffer.order(ByteOrder.BIG_ENDIAN);
        return buffer.getShort();
    }

    private static int decodeUint16(byte[] data, int offset) {
        ByteBuffer buffer = ByteBuffer.wrap(data, offset, 2);
        buffer.order(ByteOrder.BIG_ENDIAN);
        return buffer.getShort() & 0xFFFF;
    }

    private static int decodeInt24(byte[] data, int offset) {
        int value = ((data[offset] & 0xFF) << 16) |
                    ((data[offset + 1] & 0xFF) << 8) |
                    (data[offset + 2] & 0xFF);
        // Sign extend if negative
        if ((value & 0x800000) != 0) {
            value |= 0xFF000000;
        }
        return value;
    }

    private static long decodeUint32(byte[] data, int offset) {
        ByteBuffer buffer = ByteBuffer.wrap(data, offset, 4);
        buffer.order(ByteOrder.BIG_ENDIAN);
        return buffer.getInt() & 0xFFFFFFFFL;
    }
}
