package com.example.meshcorerepeatercontrol;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.example.meshcorerepeatercontrol.bluetooth.BleDevice;
import com.example.meshcorerepeatercontrol.bluetooth.BleDeviceAdapter;
import com.example.meshcorerepeatercontrol.bluetooth.BleManager;
import com.example.meshcorerepeatercontrol.model.DiscoveredRepeater;
import com.example.meshcorerepeatercontrol.protocol.MeshCoreProtocol;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;
import com.google.android.material.button.MaterialButton;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";
    private static final int REQUEST_ENABLE_BT = 1;
    private static final int REQUEST_PERMISSIONS = 2;
    private static final long DISCOVERY_DURATION_MS = 20000;

    private enum AppState { DISCONNECTED, SCANNING, CONNECTED, DISCOVERING, RESULTS }

    private AppState currentState = AppState.DISCONNECTED;

    // Views - state containers
    private View stateDisconnected;
    private View stateScanning;
    private View stateConnected;
    private View stateDiscovering;
    private View stateResults;

    // BLE
    private BleManager bleManager;
    private BleDeviceAdapter deviceAdapter;
    private String connectedDeviceName;

    // Protocol
    private MeshCoreProtocol protocol;

    // Discovery
    private DiscoveredRepeaterAdapter discoveredAdapter;
    private FusedLocationProviderClient fusedLocationClient;
    private CountDownTimer countDownTimer;
    private double currentLat = 0;
    private double currentLon = 0;
    private boolean hasGps = false;

    // Contact name map: pubkey hex -> name
    private final Map<String, String> contactNames = new HashMap<>();
    // Contact coordinates: pubkey hex -> [lat, lon]
    private final Map<String, double[]> contactCoords = new HashMap<>();

    // Discovery tag for matching responses
    private int discoveryTag = 0;

    // Channel scan state
    private int channelScanIdx = 0;
    private int discoveryChannelIdx = -1;
    private int firstEmptyChannelIdx = -1;
    private boolean channelScanComplete = false;

    // Discovering state views
    private ProgressBar discoveryProgress;
    private TextView countdownText;
    private TextView liveNodeCount;

    // Results state views
    private TextView resultsStatus;

    // Reliable message delivery
    private final List<String> pendingMessages = new ArrayList<>();
    private final List<String> deferredMessages = new ArrayList<>();
    private int sendRetryCount = 0;
    private int currentSendChannelIdx = -1;
    private boolean awaitingSendConfirmation = false;
    private String currentSendMessage = null;
    private int totalSendCount = 0;
    private int sentCount = 0;
    private final Handler sendTimeoutHandler = new Handler(Looper.getMainLooper());
    private final Runnable sendTimeoutRunnable = new Runnable() {
        @Override
        public void run() {
            if (!awaitingSendConfirmation || protocol == null) return;
            sendRetryCount++;
            if (sendRetryCount < 3) {
                Log.w(TAG, "Send timeout, retrying (attempt " + (sendRetryCount + 1) + "/3): " + currentSendMessage);
                resultsStatus.setText(getString(R.string.retrying_message, sendRetryCount + 1));
                protocol.sendChannelTextMessage(currentSendChannelIdx, currentSendMessage);
                sendTimeoutHandler.postDelayed(this, 3000);
            } else {
                Log.w(TAG, "Send failed after 3 attempts, deferring: " + currentSendMessage);
                deferredMessages.add(currentSendMessage);
                awaitingSendConfirmation = false;
                sendTimeoutHandler.postDelayed(() -> sendNextPendingMessage(), 300);
            }
        }
    };

    // Auto-discover
    private boolean autoDiscoverActive = false;
    private int autoDiscoverIntervalMs = 0;
    private Handler autoDiscoverHandler;
    private Runnable autoDiscoverRunnable;
    private CountDownTimer autoDiscoverCountdown;
    private MaterialButton btnAutoDiscover;
    private MaterialButton btnStopAuto;
    private MaterialButton btnDiscoverAgain;
    private TextView autoDiscoverStatusText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        bleManager = new BleManager(this);
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);

        initViews();
        setState(AppState.DISCONNECTED);
        checkPermissions();
    }

    private void initViews() {
        // State containers
        stateDisconnected = findViewById(R.id.state_disconnected);
        stateScanning = findViewById(R.id.state_scanning);
        stateConnected = findViewById(R.id.state_connected);
        stateDiscovering = findViewById(R.id.state_discovering);
        stateResults = findViewById(R.id.state_results);

        // Disconnected state
        MaterialButton btnConnect = findViewById(R.id.btn_connect);
        btnConnect.setOnClickListener(v -> startScan());

        // Scanning state
        RecyclerView devicesRecycler = findViewById(R.id.devices_recycler_view);
        devicesRecycler.setLayoutManager(new LinearLayoutManager(this));
        deviceAdapter = new BleDeviceAdapter();
        deviceAdapter.setOnDeviceClickListener(this::onDeviceSelected);
        devicesRecycler.setAdapter(deviceAdapter);

        MaterialButton btnStopScan = findViewById(R.id.btn_stop_scan);
        btnStopScan.setOnClickListener(v -> {
            bleManager.stopScan();
            setState(AppState.DISCONNECTED);
        });

        // Connected state
        MaterialButton btnDiscover = findViewById(R.id.btn_discover);
        btnDiscover.setOnClickListener(v -> startDiscovery());

        btnAutoDiscover = findViewById(R.id.btn_auto_discover);
        btnAutoDiscover.setOnClickListener(v -> showAutoDiscoverDialog());

        MaterialButton btnDisconnectConnected = findViewById(R.id.btn_disconnect_connected);
        btnDisconnectConnected.setOnClickListener(v -> disconnect());

        // Discovering state
        discoveryProgress = findViewById(R.id.discovery_progress);
        countdownText = findViewById(R.id.countdown_text);
        liveNodeCount = findViewById(R.id.live_node_count);

        // Results state
        RecyclerView resultsRecycler = findViewById(R.id.results_recycler_view);
        resultsRecycler.setLayoutManager(new LinearLayoutManager(this));
        discoveredAdapter = new DiscoveredRepeaterAdapter();
        resultsRecycler.setAdapter(discoveredAdapter);

        resultsStatus = findViewById(R.id.results_status);
        autoDiscoverStatusText = findViewById(R.id.auto_discover_status);

        btnDiscoverAgain = findViewById(R.id.btn_discover_again);
        btnDiscoverAgain.setOnClickListener(v -> startDiscovery());

        btnStopAuto = findViewById(R.id.btn_stop_auto);
        btnStopAuto.setOnClickListener(v -> stopAutoDiscover());

        MaterialButton btnDisconnectResults = findViewById(R.id.btn_disconnect_results);
        btnDisconnectResults.setOnClickListener(v -> disconnect());

        // Auto-discover handler
        autoDiscoverHandler = new Handler(Looper.getMainLooper());
        autoDiscoverRunnable = () -> {
            if (autoDiscoverActive && protocol != null) {
                startDiscovery();
            }
        };
    }

    private void setState(AppState state) {
        currentState = state;
        stateDisconnected.setVisibility(state == AppState.DISCONNECTED ? View.VISIBLE : View.GONE);
        stateScanning.setVisibility(state == AppState.SCANNING ? View.VISIBLE : View.GONE);
        stateConnected.setVisibility(state == AppState.CONNECTED ? View.VISIBLE : View.GONE);
        stateDiscovering.setVisibility(state == AppState.DISCOVERING ? View.VISIBLE : View.GONE);
        stateResults.setVisibility(state == AppState.RESULTS ? View.VISIBLE : View.GONE);
    }

    // --- Permissions ---

    private void checkPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT,
                                     Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION},
                        REQUEST_PERMISSIONS);
                return;
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION},
                        REQUEST_PERMISSIONS);
                return;
            }
        }
        checkBluetoothEnabled();
    }

    private void checkBluetoothEnabled() {
        if (!bleManager.isBluetoothEnabled()) {
            Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_PERMISSIONS) {
            boolean allGranted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }
            if (allGranted) {
                checkBluetoothEnabled();
            } else {
                new AlertDialog.Builder(this)
                        .setTitle("Permissions Required")
                        .setMessage("Bluetooth and location permissions are required for discovery.")
                        .setPositiveButton("OK", (dialog, which) -> checkPermissions())
                        .setNegativeButton("Cancel", null)
                        .show();
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_ENABLE_BT && resultCode != RESULT_OK) {
            Toast.makeText(this, "Bluetooth is required", Toast.LENGTH_SHORT).show();
        }
    }

    // --- BLE Scanning ---

    private void startScan() {
        if (!bleManager.isBluetoothEnabled()) {
            Toast.makeText(this, "Please enable Bluetooth", Toast.LENGTH_SHORT).show();
            checkBluetoothEnabled();
            return;
        }

        deviceAdapter.clear();
        setState(AppState.SCANNING);

        bleManager.startScan(new BleManager.BleScanCallback() {
            @Override
            public void onDeviceFound(BleDevice device) {
                runOnUiThread(() -> deviceAdapter.addDevice(device));
            }

            @Override
            public void onScanComplete() {
                runOnUiThread(() -> {
                    if (currentState == AppState.SCANNING) {
                        setState(AppState.DISCONNECTED);
                    }
                });
            }
        });
    }

    private void onDeviceSelected(BleDevice device) {
        bleManager.stopScan();
        connectedDeviceName = device.getName();
        Toast.makeText(this, getString(R.string.status_connecting), Toast.LENGTH_SHORT).show();

        bleManager.connect(device, new BleManager.BleConnectionCallback() {
            @Override
            public void onConnected() {
                runOnUiThread(() -> {
                    setupProtocol();
                    fetchLocation();

                    // Stagger BLE writes: DEVICE_QUERY then APP_START then GET_CONTACTS
                    // Firmware auto-broadcasts CONTROL DISCOVER_REQ after APP_START
                    contactNames.clear();
                    contactCoords.clear();
                    protocol.sendDeviceQuery();
                    new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                        if (protocol != null) protocol.sendAppStart();
                    }, 300);
                    new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                        if (protocol != null) protocol.sendGetContacts();
                    }, 900);

                    TextView deviceNameView = findViewById(R.id.connected_device_name);
                    deviceNameView.setText(getString(R.string.connected_to, connectedDeviceName));
                    setState(AppState.CONNECTED);
                    Toast.makeText(MainActivity.this, "Connected to " + connectedDeviceName, Toast.LENGTH_SHORT).show();
                });
            }

            @Override
            public void onDisconnected() {
                runOnUiThread(() -> {
                    cleanupProtocol();
                    setState(AppState.DISCONNECTED);
                    Toast.makeText(MainActivity.this, "Disconnected", Toast.LENGTH_SHORT).show();
                });
            }

            @Override
            public void onConnectionFailed(String error) {
                runOnUiThread(() -> {
                    setState(AppState.DISCONNECTED);
                    Toast.makeText(MainActivity.this, "Connection failed: " + error, Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void disconnect() {
        stopAutoDiscover();
        if (countDownTimer != null) {
            countDownTimer.cancel();
            countDownTimer = null;
        }
        // Cancel any pending send operations
        sendTimeoutHandler.removeCallbacks(sendTimeoutRunnable);
        awaitingSendConfirmation = false;
        pendingMessages.clear();
        cleanupProtocol();
        bleManager.disconnect();
        setState(AppState.DISCONNECTED);
    }

    // --- Protocol ---

    private void setupProtocol() {
        protocol = new MeshCoreProtocol(bleManager);
        protocol.setProtocolCallback(new MeshCoreProtocol.ProtocolCallback() {
            @Override
            public void onAppStartResponse(byte[] selfInfo) {
                Log.d(TAG, "APP_START response received");
            }

            @Override
            public void onTelemetryResponse(byte[] pubKeyPrefix, byte[] lppData) {}

            @Override
            public void onStatusResponse(byte[] statusData) {}

            @Override
            public void onRepeaterStatusResponse(byte[] statusData) {}

            @Override
            public void onLoginSuccess() {}

            @Override
            public void onStatsResponse(byte statsType, byte[] statsData) {}

            @Override
            public void onContactReceived(String pubKeyHex, String name, double lat, double lon) {
                String key = pubKeyHex.toUpperCase();
                if (name != null && !name.isEmpty()) {
                    contactNames.put(key, name);
                }
                if (lat != 0 || lon != 0) {
                    contactCoords.put(key, new double[]{lat, lon});
                }
                Log.d(TAG, "Contact stored: " + pubKeyHex.substring(0, Math.min(16, pubKeyHex.length()))
                        + " -> " + name + " (" + lat + ", " + lon + ")");
            }

            @Override
            public void onContactsEnd() {}

            @Override
            public void onError(String error) {
                // During channel scan, RESP_ERR means channel doesn't exist at that index
                if (!channelScanComplete && channelScanIdx >= 0) {
                    handleChannelScanError();
                    return;
                }
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "Error: " + error, Toast.LENGTH_SHORT).show());
            }

            @Override
            public void onDiscoverResponse(int nodeType, double snr, double snrIn, int rssi, String pubKeyHex) {
                Log.d(TAG, "Discover response: type=" + nodeType + ", SNR=" + snr +
                           ", SNR_in=" + snrIn + ", RSSI=" + rssi + ", key=" + pubKeyHex);
                if (currentState != AppState.DISCOVERING) return;

                // Look up name and coordinates from contacts by matching pubkey hex prefix
                String name = null;
                double[] coords = null;
                String upperHex = pubKeyHex.toUpperCase();
                for (Map.Entry<String, String> entry : contactNames.entrySet()) {
                    // Discovery responses may have 8-byte (16 hex chars) prefix pubkeys
                    String contactKey = entry.getKey();
                    if (contactKey.startsWith(upperHex) || upperHex.startsWith(contactKey)) {
                        name = entry.getValue();
                        coords = contactCoords.get(contactKey);
                        break;
                    }
                }
                if (name == null || name.isEmpty()) {
                    name = pubKeyHex.substring(0, Math.min(16, pubKeyHex.length()));
                }

                DiscoveredRepeater repeater = new DiscoveredRepeater(
                        MeshCoreProtocol.hexToBytes(pubKeyHex),
                        name,
                        nodeType,
                        snr,
                        snrIn
                );

                // Store repeater coordinates and compute distance
                if (coords != null && (coords[0] != 0 || coords[1] != 0)) {
                    repeater.setLatLon(coords[0], coords[1]);
                    if (hasGps) {
                        repeater.setDistanceKm(haversineKm(currentLat, currentLon, coords[0], coords[1]));
                    }
                }

                runOnUiThread(() -> {
                    discoveredAdapter.addRepeater(repeater);
                    liveNodeCount.setText(getString(R.string.found_nodes, discoveredAdapter.getItemCount()));
                });
            }

            @Override
            public void onChannelInfoReceived(int channelIdx, String name, byte[] psk) {
                Log.d(TAG, "Channel " + channelIdx + ": name='" + name + "'");
                handleChannelInfoResponse(channelIdx, name, psk);
            }

            @Override
            public void onMessageSent() {
                runOnUiThread(() -> {
                    if (!awaitingSendConfirmation) return;
                    sendTimeoutHandler.removeCallbacks(sendTimeoutRunnable);
                    awaitingSendConfirmation = false;
                    sentCount++;
                    Log.d(TAG, "Message confirmed sent (" + sentCount + "/" + totalSendCount + ")");
                    // Stagger next BLE write by 300ms
                    sendTimeoutHandler.postDelayed(() -> sendNextPendingMessage(), 300);
                });
            }
        });
    }

    private void cleanupProtocol() {
        protocol = null;
    }

    // --- GPS ---

    private void fetchLocation() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        fusedLocationClient.getLastLocation().addOnSuccessListener(this, location -> {
            if (location != null) {
                currentLat = location.getLatitude();
                currentLon = location.getLongitude();
                hasGps = true;
                Log.d(TAG, String.format("GPS: %.6f, %.6f", currentLat, currentLon));
            } else {
                Log.d(TAG, "GPS: No location available");
            }
        }).addOnFailureListener(e -> Log.e(TAG, "Location fetch failed", e));
    }

    // --- Discovery ---

    private void startDiscovery() {
        if (protocol == null) {
            Toast.makeText(this, "Not connected", Toast.LENGTH_SHORT).show();
            return;
        }

        // Refresh GPS before every discovery
        fetchLocation();

        if (!hasGps) {
            Toast.makeText(this, R.string.no_gps, Toast.LENGTH_SHORT).show();
            return;
        }

        discoveredAdapter.clear();
        setState(AppState.DISCOVERING);

        discoveryProgress.setProgress(0);
        countdownText.setText(getString(R.string.sending_zero_hop));
        liveNodeCount.setText("");

        // Set GPS then send CONTROL DISCOVER_REQ (0x37)
        // Stagger BLE writes to avoid overlapping GATT operations
        protocol.sendSetAdvertLatLon(currentLat, currentLon);
        discoveryProgress.postDelayed(() -> {
            if (currentState != AppState.DISCOVERING || protocol == null) return;
            // filter 0xFF = all node types
            discoveryTag = protocol.sendNodeDiscoverReq(0xFF);
            Log.d(TAG, "Discovery started, tag=" + String.format("%08X", discoveryTag));
        }, 300);

        countDownTimer = new CountDownTimer(DISCOVERY_DURATION_MS, 500) {
            @Override
            public void onTick(long millisUntilFinished) {
                int progress = (int) ((DISCOVERY_DURATION_MS - millisUntilFinished) * 100 / DISCOVERY_DURATION_MS);
                discoveryProgress.setProgress(progress);
                countdownText.setText(getString(R.string.listening_countdown, (int)(millisUntilFinished / 1000)));
            }

            @Override
            public void onFinish() {
                discoveryProgress.setProgress(100);
                showResults();
            }
        }.start();
    }

    private void showResults() {
        setState(AppState.RESULTS);

        int count = discoveredAdapter.getItemCount();
        if (count > 0) {
            String status = getString(R.string.found_nodes, count);
            if (!deferredMessages.isEmpty()) {
                status += " (" + getString(R.string.messages_deferred, deferredMessages.size()) + ")";
            }
            resultsStatus.setText(status);
            sendToDiscoveryChannel();
        } else {
            resultsStatus.setText(R.string.no_nodes_found);
        }

        // Update button visibility for auto-discover mode
        if (autoDiscoverActive) {
            btnDiscoverAgain.setVisibility(View.GONE);
            btnStopAuto.setVisibility(View.VISIBLE);
            autoDiscoverStatusText.setVisibility(View.VISIBLE);
            autoDiscoverStatusText.setText(getString(R.string.auto_discovery_active, autoDiscoverIntervalMs / 60000));
            scheduleNextAutoDiscover();
        } else {
            btnDiscoverAgain.setVisibility(View.VISIBLE);
            btnStopAuto.setVisibility(View.GONE);
            autoDiscoverStatusText.setVisibility(View.GONE);
        }
    }

    // --- Send to #discovery channel ---

    private void sendToDiscoveryChannel() {
        if (protocol == null) return;

        resultsStatus.setText(R.string.auto_sending);

        channelScanIdx = 0;
        discoveryChannelIdx = -1;
        firstEmptyChannelIdx = -1;
        channelScanComplete = false;

        protocol.sendGetChannel(0);
    }

    private void handleChannelInfoResponse(int channelIdx, String name, byte[] psk) {
        if (channelScanComplete) return;

        if ("#discovery".equalsIgnoreCase(name)) {
            discoveryChannelIdx = channelIdx;
            channelScanComplete = true;
            runOnUiThread(() -> sendCsvToChannel(discoveryChannelIdx));
            return;
        }

        if (name.isEmpty() && firstEmptyChannelIdx < 0) {
            firstEmptyChannelIdx = channelIdx;
        }

        channelScanIdx = channelIdx + 1;
        if (channelScanIdx < 8) {
            protocol.sendGetChannel(channelScanIdx);
        } else {
            finishChannelScan();
        }
    }

    private void handleChannelScanError() {
        // Firmware returns RESP_ERR when channel index doesn't exist
        // This means we've reached the end of valid channels
        if (channelScanComplete) return;
        finishChannelScan();
    }

    private void finishChannelScan() {
        channelScanComplete = true;
        if (firstEmptyChannelIdx >= 0) {
            runOnUiThread(() -> createAndSendToChannel(firstEmptyChannelIdx));
        } else {
            // No empty slot — create at next index after last scanned
            runOnUiThread(() -> createAndSendToChannel(channelScanIdx));
        }
    }

    private void createAndSendToChannel(int idx) {
        // Derive PSK from channel name using SHA-256, same as Python/Dart clients
        byte[] pskBytes = deriveChannelPsk("#discovery");
        protocol.sendSetChannel(idx, "#discovery", pskBytes);
        resultsStatus.postDelayed(() -> sendCsvToChannel(idx), 500);
    }

    private static byte[] deriveChannelPsk(String channelName) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(channelName.getBytes(StandardCharsets.UTF_8));
            byte[] psk = new byte[16];
            System.arraycopy(hash, 0, psk, 0, 16);
            return psk;
        } catch (java.security.NoSuchAlgorithmException e) {
            return new byte[16]; // fallback
        }
    }

    private void sendCsvToChannel(int channelIdx) {
        List<DiscoveredRepeater> repeaters = discoveredAdapter.getRepeaters();
        if (repeaters.isEmpty()) {
            resultsStatus.setText(R.string.no_nodes_found);
            return;
        }

        // Format: lat,lon,timestamp|PKPK:snrIn/rLat,rLon|PKPK:snrIn/0,0|...
        // PKPK = first 2 bytes of pubkey hex (4 chars)
        // rLat,rLon = repeater coordinates (4 decimal places), 0,0 if unknown
        // Split into multiple 140-byte messages if needed, each with same header
        String header = String.format("%.4f,%.4f,%d", currentLat, currentLon, System.currentTimeMillis() / 1000);

        // Build per-repeater entries
        List<String> entries = new ArrayList<>();
        for (DiscoveredRepeater r : repeaters) {
            byte[] pk = r.getPubKey();
            String pkHex;
            if (pk.length >= 2) {
                pkHex = String.format("%02X%02X", pk[0], pk[1]);
            } else if (pk.length == 1) {
                pkHex = String.format("%02X", pk[0]);
            } else {
                pkHex = "??";
            }
            String coords;
            if (r.getLat() != 0 || r.getLon() != 0) {
                coords = String.format("%.4f,%.4f", r.getLat(), r.getLon());
            } else {
                coords = "0,0";
            }
            entries.add("|" + pkHex + ":" + String.format("%.1f", r.getSnrIn()) + "/" + coords);
        }

        // Pack entries into messages, each <= 140 bytes
        List<String> messages = new ArrayList<>();
        StringBuilder current = new StringBuilder(header);
        for (String entry : entries) {
            if (current.length() + entry.length() > 140) {
                messages.add(current.toString());
                current = new StringBuilder(header);
            }
            current.append(entry);
        }
        messages.add(current.toString());

        // Prepend any deferred messages from previous cycle
        pendingMessages.clear();
        pendingMessages.addAll(deferredMessages);
        deferredMessages.clear();
        pendingMessages.addAll(messages);

        currentSendChannelIdx = channelIdx;
        totalSendCount = pendingMessages.size();
        sentCount = 0;

        sendNextPendingMessage();
    }

    private void sendNextPendingMessage() {
        if (protocol == null) return;

        if (pendingMessages.isEmpty()) {
            // All done
            String status = getString(R.string.sent_to_discovery, currentSendChannelIdx);
            if (totalSendCount > 1) {
                status += " (" + totalSendCount + " messages)";
            }
            if (!deferredMessages.isEmpty()) {
                status += " — " + getString(R.string.messages_deferred, deferredMessages.size());
            }
            resultsStatus.setText(status);
            if (deferredMessages.isEmpty()) {
                Toast.makeText(this, "Discovery results sent!", Toast.LENGTH_SHORT).show();
            }
            return;
        }

        currentSendMessage = pendingMessages.remove(0);
        sendRetryCount = 0;
        awaitingSendConfirmation = true;

        int msgNum = sentCount + 1;
        Log.d(TAG, "Sending to #discovery (channel " + currentSendChannelIdx + ") [" + msgNum + "/" + totalSendCount + "]: " + currentSendMessage);
        resultsStatus.setText(getString(R.string.sending_message, msgNum, totalSendCount));

        protocol.sendChannelTextMessage(currentSendChannelIdx, currentSendMessage);
        sendTimeoutHandler.postDelayed(sendTimeoutRunnable, 3000);
    }

    // --- Auto-Discover ---

    private void showAutoDiscoverDialog() {
        final int[] intervals = {3, 5, 10, 15, 30, 60};
        String[] labels = {"3 minutes", "5 minutes", "10 minutes", "15 minutes", "30 minutes", "60 minutes"};

        Spinner spinner = new Spinner(this);
        spinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, labels));
        spinner.setSelection(1); // default to 5 minutes
        spinner.setPadding(48, 24, 48, 24);

        new AlertDialog.Builder(this)
                .setTitle(R.string.auto_discover_title)
                .setView(spinner)
                .setPositiveButton("Start", (dialog, which) -> {
                    int minutes = intervals[spinner.getSelectedItemPosition()];
                    startAutoDiscover(minutes);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void startAutoDiscover(int minutes) {
        autoDiscoverActive = true;
        autoDiscoverIntervalMs = minutes * 60000;
        startDiscovery();
    }

    private void scheduleNextAutoDiscover() {
        autoDiscoverHandler.removeCallbacks(autoDiscoverRunnable);
        autoDiscoverHandler.postDelayed(autoDiscoverRunnable, autoDiscoverIntervalMs);

        // Visual countdown timer
        if (autoDiscoverCountdown != null) {
            autoDiscoverCountdown.cancel();
        }
        autoDiscoverCountdown = new CountDownTimer(autoDiscoverIntervalMs, 1000) {
            @Override
            public void onTick(long millisUntilFinished) {
                int totalSeconds = (int) (millisUntilFinished / 1000);
                int minutes = totalSeconds / 60;
                int seconds = totalSeconds % 60;
                autoDiscoverStatusText.setText(getString(R.string.next_discovery_in, minutes, seconds));
            }

            @Override
            public void onFinish() {
                autoDiscoverStatusText.setText(getString(R.string.auto_discovery_active, autoDiscoverIntervalMs / 60000));
            }
        }.start();
    }

    private void stopAutoDiscover() {
        autoDiscoverActive = false;
        autoDiscoverHandler.removeCallbacks(autoDiscoverRunnable);
        if (autoDiscoverCountdown != null) {
            autoDiscoverCountdown.cancel();
            autoDiscoverCountdown = null;
        }
        // Restore normal button visibility if in RESULTS state
        if (currentState == AppState.RESULTS) {
            btnDiscoverAgain.setVisibility(View.VISIBLE);
            btnStopAuto.setVisibility(View.GONE);
            autoDiscoverStatusText.setVisibility(View.GONE);
        }
    }

    // --- Distance calculation ---

    private static double haversineKm(double lat1, double lon1, double lat2, double lon2) {
        double R = 6371.0; // Earth radius in km
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                 + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                 * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return R * c;
    }

    // --- Back button ---

    @Override
    public void onBackPressed() {
        switch (currentState) {
            case SCANNING:
                bleManager.stopScan();
                setState(AppState.DISCONNECTED);
                break;
            case RESULTS:
                setState(AppState.CONNECTED);
                break;
            case DISCOVERING:
                // Don't interrupt discovery
                break;
            case CONNECTED:
                disconnect();
                break;
            default:
                super.onBackPressed();
                break;
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopAutoDiscover();
        sendTimeoutHandler.removeCallbacks(sendTimeoutRunnable);
        if (countDownTimer != null) {
            countDownTimer.cancel();
        }
        bleManager.stopScan();
        bleManager.disconnect();
    }
}
