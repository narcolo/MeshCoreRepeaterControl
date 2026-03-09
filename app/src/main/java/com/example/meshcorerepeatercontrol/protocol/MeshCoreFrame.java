package com.example.meshcorerepeatercontrol.protocol;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class MeshCoreFrame {
    private final byte commandCode;
    private final byte[] data;

    public MeshCoreFrame(byte commandCode, byte[] data) {
        this.commandCode = commandCode;
        this.data = data != null ? data : new byte[0];
    }

    public byte getCommandCode() {
        return commandCode;
    }

    public byte[] getData() {
        return data;
    }

    public byte[] encode() {
        // For BLE, a frame is simply the command code followed by data
        byte[] frame = new byte[1 + data.length];
        frame[0] = commandCode;
        System.arraycopy(data, 0, frame, 1, data.length);
        return frame;
    }

    public static MeshCoreFrame decode(byte[] frameData) {
        if (frameData == null || frameData.length == 0) {
            return null;
        }

        byte commandCode = frameData[0];
        byte[] data = new byte[frameData.length - 1];
        if (data.length > 0) {
            System.arraycopy(frameData, 1, data, 0, data.length);
        }

        return new MeshCoreFrame(commandCode, data);
    }

    // Helper methods for encoding data types

    public static byte[] encodeInt16(int value) {
        ByteBuffer buffer = ByteBuffer.allocate(2);
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        buffer.putShort((short) value);
        return buffer.array();
    }

    public static byte[] encodeInt32(int value) {
        ByteBuffer buffer = ByteBuffer.allocate(4);
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(value);
        return buffer.array();
    }

    public static int decodeInt16(byte[] data, int offset) {
        ByteBuffer buffer = ByteBuffer.wrap(data, offset, 2);
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        return buffer.getShort() & 0xFFFF;
    }

    public static int decodeInt32(byte[] data, int offset) {
        ByteBuffer buffer = ByteBuffer.wrap(data, offset, 4);
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        return buffer.getInt();
    }

    public static long decodeInt64(byte[] data, int offset) {
        ByteBuffer buffer = ByteBuffer.wrap(data, offset, 8);
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        return buffer.getLong();
    }

    public static byte[] concat(byte[]... arrays) {
        int totalLength = 0;
        for (byte[] array : arrays) {
            totalLength += array.length;
        }

        byte[] result = new byte[totalLength];
        int offset = 0;
        for (byte[] array : arrays) {
            System.arraycopy(array, 0, result, offset, array.length);
            offset += array.length;
        }

        return result;
    }
}
