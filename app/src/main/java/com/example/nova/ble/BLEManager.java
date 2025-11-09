package com.example.nova.ble;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.util.Log;
import android.widget.Toast;

import androidx.core.content.ContextCompat;

import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * BLEManager (User App)
 * - Handles bidirectional alert communication
 * - Prevents overlapping BLE advertising
 * - Handles ADVERTISE_FAILED_TOO_MANY_ADVERTISERS gracefully
 */
public class BLEManager {

    private static final String TAG = "BLE-USER";
    private static final UUID SERVICE_UUID =
            UUID.fromString("0000FEED-0000-1000-8000-00805F9B34FB");

    private final Context context;
    private final BluetoothAdapter bluetoothAdapter;
    private BluetoothLeAdvertiser advertiser;
    private BluetoothLeScanner scanner;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final Set<String> receivedAlerts = new HashSet<>();
    private boolean isAdvertising = false;
    private boolean isScanning = false;

    private static final long ADVERTISE_DURATION_MS = 3000;
    private static final long SCAN_CYCLE_MS = 10000;
    private static final long ADVERTISE_COOLDOWN_MS = 500; // half a second delay to prevent overlap

    private final OnMessageReceivedListener listener;

    public BLEManager(Context context, OnMessageReceivedListener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;

        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if (bluetoothAdapter != null && bluetoothAdapter.isEnabled()) {
            advertiser = bluetoothAdapter.getBluetoothLeAdvertiser();
            scanner = bluetoothAdapter.getBluetoothLeScanner();
        } else {
            Log.e(TAG, "❌ Bluetooth not enabled or unavailable.");
        }
    }

    // --------------------------------------------------------------------
    // ✅ Advertiser support check
    // --------------------------------------------------------------------
    private boolean isAdvertisingSupported() {
        if (bluetoothAdapter == null) {
            Log.e(TAG, "❌ No Bluetooth adapter found.");
            return false;
        }

        if (!bluetoothAdapter.isEnabled()) {
            Log.w(TAG, "⚠️ Bluetooth is OFF.");
            Toast.makeText(context, "Please enable Bluetooth", Toast.LENGTH_SHORT).show();
            return false;
        }

        if (!context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            Log.e(TAG, "🚫 BLE not supported on this device.");
            Toast.makeText(context, "BLE not supported on this device", Toast.LENGTH_LONG).show();
            return false;
        }

        if (!bluetoothAdapter.isMultipleAdvertisementSupported()) {
            Log.e(TAG, "🚫 BLE advertising not supported by hardware.");
            Toast.makeText(context, "Device does not support BLE advertising", Toast.LENGTH_LONG).show();
            return false;
        }

        if (advertiser == null) {
            advertiser = bluetoothAdapter.getBluetoothLeAdvertiser();
            if (advertiser == null) {
                Log.e(TAG, "🚫 BLE advertiser unavailable.");
                return false;
            }
        }

        if (!hasPermission(Manifest.permission.BLUETOOTH_ADVERTISE)) {
            Log.w(TAG, "🚫 Missing BLUETOOTH_ADVERTISE permission.");
            return false;
        }

        return true;
    }

    // --------------------------------------------------------------------
    // ✅ Send SOS or alert message (prevents overlap)
    // --------------------------------------------------------------------
    @SuppressLint("MissingPermission")
    public void sendMessage(String message) {
        if (!isAdvertisingSupported()) return;

        stopAdvertisingSafely(); // ✅ stop old advert before starting new one
        try { Thread.sleep(ADVERTISE_COOLDOWN_MS); } catch (InterruptedException ignored) {}

        String time = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
        String deviceName = bluetoothAdapter.getName() != null
                ? bluetoothAdapter.getName()
                : Build.MODEL;

        String payload = "ALERT:" + message + " | " + deviceName + " | " + time;
        byte[] data = payload.getBytes(StandardCharsets.UTF_8);

        AdvertiseData advertiseData = new AdvertiseData.Builder()
                .addServiceData(new ParcelUuid(SERVICE_UUID), data)
                .setIncludeDeviceName(false)
                .build();

        AdvertiseSettings settings = new AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                .setConnectable(false)
                .build();

        try {
            advertiser.startAdvertising(settings, advertiseData, advertiseCallback);
            isAdvertising = true;
            Log.i(TAG, "🚨 SOS broadcast sent: " + message);
            handler.postDelayed(this::stopAdvertising, ADVERTISE_DURATION_MS);
        } catch (SecurityException e) {
            Log.e(TAG, "🚫 SecurityException during alert broadcast", e);
        }
    }

    // --------------------------------------------------------------------
    // ✅ Broadcast presence (username-based, prevents overlap)
    // --------------------------------------------------------------------
    @SuppressLint("MissingPermission")
    public void startPresenceAdvert(String username) {
        if (!isAdvertisingSupported()) return;

        stopAdvertisingSafely();
        try { Thread.sleep(ADVERTISE_COOLDOWN_MS); } catch (InterruptedException ignored) {}

        String payload = "PRESENCE:" + username;
        byte[] data = payload.getBytes(StandardCharsets.UTF_8);

        AdvertiseData advertiseData = new AdvertiseData.Builder()
                .addServiceData(new ParcelUuid(SERVICE_UUID), data)
                .setIncludeDeviceName(false)
                .build();

        AdvertiseSettings settings = new AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                .setConnectable(false)
                .build();

        try {
            advertiser.startAdvertising(settings, advertiseData, advertiseCallback);
            isAdvertising = true;
            Log.i(TAG, "📡 Presence broadcast sent: " + username);
            handler.postDelayed(this::stopAdvertising, ADVERTISE_DURATION_MS);
        } catch (SecurityException e) {
            Log.e(TAG, "🚫 SecurityException during presence advertising", e);
        }
    }

    // --------------------------------------------------------------------
    // ✅ Stop advertising safely
    // --------------------------------------------------------------------
    @SuppressLint("MissingPermission")
    public void stopAdvertisingSafely() {
        if (advertiser != null && isAdvertising && hasPermission(Manifest.permission.BLUETOOTH_ADVERTISE)) {
            try {
                advertiser.stopAdvertising(advertiseCallback);
                Log.d(TAG, "🛑 Previous advertising stopped safely.");
            } catch (SecurityException e) {
                Log.e(TAG, "🚫 Error stopping advertising", e);
            }
        }
        isAdvertising = false;
    }

    @SuppressLint("MissingPermission")
    public void stopAdvertising() {
        stopAdvertisingSafely();
    }

    // --------------------------------------------------------------------
    // ✅ Start and stop scan
    // --------------------------------------------------------------------
    @SuppressLint("MissingPermission")
    public void startScan() {
        if (scanner == null) {
            Log.e(TAG, "❌ BLE scanner not available.");
            return;
        }

        if (isScanning) {
            Log.w(TAG, "⚠️ Already scanning.");
            return;
        }

        if (!hasPermission(Manifest.permission.BLUETOOTH_SCAN)) {
            Log.w(TAG, "🚫 Missing BLUETOOTH_SCAN permission.");
            return;
        }

        try {
            scanner.startScan(scanCallback);
            Log.d(TAG, "🔍 Started scanning for alerts...");
            isScanning = true;
            handler.postDelayed(this::stopScan, SCAN_CYCLE_MS);
        } catch (SecurityException e) {
            Log.e(TAG, "🚫 SecurityException during scan start", e);
        }
    }

    @SuppressLint("MissingPermission")
    public void stopScan() {
        if (scanner != null && hasPermission(Manifest.permission.BLUETOOTH_SCAN)) {
            try {
                scanner.stopScan(scanCallback);
                Log.d(TAG, "🛑 Scanning stopped.");
            } catch (SecurityException e) {
                Log.e(TAG, "🚫 Error stopping scan", e);
            }
        }
        isScanning = false;
    }

    // --------------------------------------------------------------------
    // ✅ Callbacks
    // --------------------------------------------------------------------
    private final AdvertiseCallback advertiseCallback = new AdvertiseCallback() {
        @Override
        public void onStartSuccess(AdvertiseSettings settingsInEffect) {
            Log.d(TAG, "✅ Advertising started successfully.");
        }

        @Override
        public void onStartFailure(int errorCode) {
            String reason;
            switch (errorCode) {
                case ADVERTISE_FAILED_ALREADY_STARTED:
                    reason = "Already started";
                    break;
                case ADVERTISE_FAILED_DATA_TOO_LARGE:
                    reason = "Data too large";
                    break;
                case ADVERTISE_FAILED_TOO_MANY_ADVERTISERS:
                    reason = "Too many advertisers";
                    break;
                case ADVERTISE_FAILED_INTERNAL_ERROR:
                    reason = "Internal error";
                    break;
                case ADVERTISE_FAILED_FEATURE_UNSUPPORTED:
                    reason = "Feature unsupported";
                    break;
                default:
                    reason = "Unknown error";
            }
            Log.w(TAG, "⚠️ Advertise failed: " + reason + " (code " + errorCode + ")");
        }
    };

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            if (result.getScanRecord() == null) return;

            byte[] data = result.getScanRecord().getServiceData(new ParcelUuid(SERVICE_UUID));
            if (data == null || data.length == 0) return;

            String msg = new String(data, StandardCharsets.UTF_8);
            if (msg.startsWith("ALERT:")) {
                String alert = msg.substring(6).trim();
                if (receivedAlerts.add(alert)) {
                    Log.i(TAG, "📩 Received alert: " + alert);
                    if (listener != null) listener.onMessageReceived(alert);
                }
            }
        }

        @Override
        public void onScanFailed(int errorCode) {
            Log.e(TAG, "❌ Scan failed: " + errorCode);
        }
    };

    // --------------------------------------------------------------------
    // ✅ Helpers
    // --------------------------------------------------------------------
    private boolean hasPermission(String permission) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true;
        return ContextCompat.checkSelfPermission(context, permission)
                == PackageManager.PERMISSION_GRANTED;
    }

    public void shutdown() {
        stopScan();
        stopAdvertisingSafely();
        receivedAlerts.clear();
        Log.d(TAG, "🧹 BLEManager shutdown complete.");
    }
}
