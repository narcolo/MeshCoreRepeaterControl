package com.example.meshcorerepeatercontrol.bluetooth;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class BleManager {
    private static final String TAG = "BleManager";

    // MeshCore BLE Service UUID - needs to be discovered/confirmed
    // This is a placeholder - will need to be updated with actual MeshCore UUID
    private static final UUID MESHCORE_SERVICE_UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E");
    private static final UUID MESHCORE_TX_CHAR_UUID = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E");
    private static final UUID MESHCORE_RX_CHAR_UUID = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E");
    private static final UUID CLIENT_CHARACTERISTIC_CONFIG = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private final Context context;
    private final BluetoothAdapter bluetoothAdapter;
    private BluetoothLeScanner bleScanner;
    private BluetoothGatt bluetoothGatt;
    private BluetoothGattCharacteristic txCharacteristic;
    private BluetoothGattCharacteristic rxCharacteristic;

    private ScanCallback scanCallback;
    private BleConnectionCallback connectionCallback;
    private BleDataCallback dataCallback;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private static final long SCAN_PERIOD = 10000; // 10 seconds

    public interface BleScanCallback {
        void onDeviceFound(BleDevice device);
        void onScanComplete();
    }

    public interface BleConnectionCallback {
        void onConnected();
        void onDisconnected();
        void onConnectionFailed(String error);
    }

    public interface BleDataCallback {
        void onDataReceived(byte[] data);
        void onDataSent();
    }

    public BleManager(Context context) {
        this.context = context.getApplicationContext();
        BluetoothManager bluetoothManager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        this.bluetoothAdapter = bluetoothManager.getAdapter();
        if (bluetoothAdapter != null) {
            this.bleScanner = bluetoothAdapter.getBluetoothLeScanner();
        }
    }

    public boolean isBluetoothEnabled() {
        return bluetoothAdapter != null && bluetoothAdapter.isEnabled();
    }

    public void startScan(BleScanCallback callback) {
        if (!isBluetoothEnabled() || bleScanner == null) {
            callback.onScanComplete();
            return;
        }

        scanCallback = new ScanCallback() {
            @Override
            public void onScanResult(int callbackType, ScanResult result) {
                BluetoothDevice device = result.getDevice();
                String name = device.getName();
                if (name != null && (name.startsWith("MeshCore-") || name.startsWith("Whisper-"))) {
                    callback.onDeviceFound(new BleDevice(device, result.getRssi()));
                }
            }

            @Override
            public void onScanFailed(int errorCode) {
                Log.e(TAG, "BLE scan failed with error: " + errorCode);
                callback.onScanComplete();
            }
        };

        bleScanner.startScan(scanCallback);

        handler.postDelayed(() -> {
            stopScan();
            callback.onScanComplete();
        }, SCAN_PERIOD);
    }

    public void stopScan() {
        if (bleScanner != null && scanCallback != null) {
            bleScanner.stopScan(scanCallback);
            scanCallback = null;
        }
    }

    private boolean connectionEstablished = false;
    private static final long CONNECTION_TIMEOUT_MS = 10000;
    private Runnable connectionTimeoutRunnable;

    public void connect(BleDevice device, BleConnectionCallback callback) {
        this.connectionCallback = callback;
        this.connectionEstablished = false;

        // Connection timeout
        connectionTimeoutRunnable = () -> {
            if (!connectionEstablished && connectionCallback != null) {
                Log.w(TAG, "Connection timeout");
                if (bluetoothGatt != null) {
                    bluetoothGatt.disconnect();
                    bluetoothGatt.close();
                    bluetoothGatt = null;
                }
                connectionCallback.onConnectionFailed("Connection timed out");
                connectionCallback = null;
            }
        };
        handler.postDelayed(connectionTimeoutRunnable, CONNECTION_TIMEOUT_MS);

        BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
            @Override
            public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
                if (status != BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_DISCONNECTED) {
                    Log.e(TAG, "GATT connection failed, status=" + status);
                    handler.removeCallbacks(connectionTimeoutRunnable);
                    gatt.close();
                    handler.post(() -> {
                        if (connectionCallback != null) {
                            if (!connectionEstablished) {
                                connectionCallback.onConnectionFailed("Connection failed (GATT error " + status + ")");
                                connectionCallback = null;
                            } else {
                                connectionCallback.onDisconnected();
                            }
                        }
                    });
                    return;
                }

                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    Log.i(TAG, "Connected to GATT server.");
                    bluetoothGatt = gatt;
                    // Request larger MTU for MeshCore frames (matches Dart client)
                    gatt.requestMtu(185);
                    handler.postDelayed(() -> gatt.discoverServices(), 600);
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    Log.i(TAG, "Disconnected from GATT server.");
                    handler.removeCallbacks(connectionTimeoutRunnable);
                    handler.post(() -> {
                        if (connectionCallback != null) {
                            connectionCallback.onDisconnected();
                        }
                    });
                }
            }

            @Override
            public void onServicesDiscovered(BluetoothGatt gatt, int status) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    Log.i(TAG, "Services discovered");

                    // Log all available services and characteristics for debugging
                    Log.i(TAG, "=== Available BLE Services ===");
                    for (BluetoothGattService service : gatt.getServices()) {
                        Log.i(TAG, "Service UUID: " + service.getUuid());
                        for (BluetoothGattCharacteristic characteristic : service.getCharacteristics()) {
                            Log.i(TAG, "  - Characteristic UUID: " + characteristic.getUuid());
                            Log.i(TAG, "    Properties: " + characteristic.getProperties());
                        }
                    }
                    Log.i(TAG, "=============================");

                    BluetoothGattService service = gatt.getService(MESHCORE_SERVICE_UUID);
                    if (service != null) {
                        txCharacteristic = service.getCharacteristic(MESHCORE_TX_CHAR_UUID);
                        rxCharacteristic = service.getCharacteristic(MESHCORE_RX_CHAR_UUID);

                        if (rxCharacteristic != null) {
                            Log.i(TAG, "Enabling notifications for RX characteristic");
                            gatt.setCharacteristicNotification(rxCharacteristic, true);
                            BluetoothGattDescriptor descriptor = rxCharacteristic.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG);
                            if (descriptor != null) {
                                descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                                boolean writeResult = gatt.writeDescriptor(descriptor);
                                Log.i(TAG, "Descriptor write initiated: " + writeResult);
                                // onConnected() will be called in onDescriptorWrite callback
                            } else {
                                Log.e(TAG, "CCCD descriptor not found");
                                handler.post(() -> {
                                    if (connectionCallback != null) {
                                        connectionCallback.onConnectionFailed("Notification descriptor not found");
                                    }
                                });
                            }
                        } else {
                            Log.e(TAG, "RX characteristic not found");
                            handler.post(() -> {
                                if (connectionCallback != null) {
                                    connectionCallback.onConnectionFailed("RX characteristic not found");
                                }
                            });
                        }
                    } else {
                        handler.post(() -> {
                            if (connectionCallback != null) {
                                connectionCallback.onConnectionFailed("MeshCore service not found. Check Logcat for available UUIDs.");
                            }
                        });
                    }
                } else {
                    Log.e(TAG, "Service discovery failed: " + status);
                    handler.post(() -> {
                        if (connectionCallback != null) {
                            connectionCallback.onConnectionFailed("Service discovery failed");
                        }
                    });
                }
            }

            @Override
            public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
                if (CLIENT_CHARACTERISTIC_CONFIG.equals(descriptor.getUuid())) {
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        Log.i(TAG, "Notifications enabled successfully");
                        connectionEstablished = true;
                        handler.removeCallbacks(connectionTimeoutRunnable);
                        handler.post(() -> {
                            if (connectionCallback != null) {
                                connectionCallback.onConnected();
                            }
                        });
                    } else {
                        Log.e(TAG, "Failed to enable notifications: " + status);
                        handler.post(() -> {
                            if (connectionCallback != null) {
                                connectionCallback.onConnectionFailed("Failed to enable notifications");
                            }
                        });
                    }
                }
            }

            @Override
            public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
                if (MESHCORE_RX_CHAR_UUID.equals(characteristic.getUuid())) {
                    byte[] data = characteristic.getValue();
                    Log.i(TAG, "Data received from companion (" + data.length + " bytes): " +
                          bytesToHex(data));
                    handler.post(() -> {
                        if (dataCallback != null) {
                            dataCallback.onDataReceived(data);
                        }
                    });
                } else {
                    Log.w(TAG, "Notification from unexpected characteristic: " + characteristic.getUuid());
                }
            }

            @Override
            public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    handler.post(() -> {
                        if (dataCallback != null) {
                            dataCallback.onDataSent();
                        }
                    });
                }
            }
        };

        device.getDevice().connectGatt(context, false, gattCallback);
    }

    public void disconnect() {
        if (connectionTimeoutRunnable != null) {
            handler.removeCallbacks(connectionTimeoutRunnable);
        }
        if (bluetoothGatt != null) {
            bluetoothGatt.disconnect();
            bluetoothGatt.close();
            bluetoothGatt = null;
        }
    }

    public void sendData(byte[] data, BleDataCallback callback) {
        // Don't overwrite the persistent dataCallback! Just notify the write callback
        if (bluetoothGatt != null && txCharacteristic != null) {
            txCharacteristic.setValue(data);
            bluetoothGatt.writeCharacteristic(txCharacteristic);
            // The write confirmation will be handled by onCharacteristicWrite
            // which already calls dataCallback.onDataSent()
        }
    }

    public void setDataCallback(BleDataCallback callback) {
        this.dataCallback = callback;
    }

    public boolean isConnected() {
        return bluetoothGatt != null && txCharacteristic != null && rxCharacteristic != null;
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02X", b));
        }
        return sb.toString();
    }
}
