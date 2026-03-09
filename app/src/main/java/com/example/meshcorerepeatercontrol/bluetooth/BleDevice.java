package com.example.meshcorerepeatercontrol.bluetooth;

import android.bluetooth.BluetoothDevice;

public class BleDevice {
    private final BluetoothDevice device;
    private final int rssi;
    private final String name;
    private final String address;

    public BleDevice(BluetoothDevice device, int rssi) {
        this.device = device;
        this.rssi = rssi;
        this.name = device.getName() != null ? device.getName() : "Unknown Device";
        this.address = device.getAddress();
    }

    public BluetoothDevice getDevice() {
        return device;
    }

    public int getRssi() {
        return rssi;
    }

    public String getName() {
        return name;
    }

    public String getAddress() {
        return address;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        BleDevice bleDevice = (BleDevice) obj;
        return address.equals(bleDevice.address);
    }

    @Override
    public int hashCode() {
        return address.hashCode();
    }
}
