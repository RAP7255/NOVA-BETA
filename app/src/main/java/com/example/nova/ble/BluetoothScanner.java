package com.example.nova.ble;

import android.Manifest;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanSettings;
import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;
import android.util.SparseArray;

import androidx.core.content.ContextCompat;

import com.example.nova.model.MeshMessage;

import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.HashMap;

public class BluetoothScanner {

    private static final String TAG = "BluetoothScanner";
    private static final String RAW_TAG = "RAW_AD";

    private final Context ctx;
    private final BluetoothLeScanner scanner;
    private BluetoothScannerListener listener;

    // Duplicate suppression variables
    private long lastFetchId = -1;
    private long lastFetchTime = 0;

    private boolean shouldFetch(long id) {
        long now = System.currentTimeMillis();
        if (id == lastFetchId && (now - lastFetchTime) < 2500) {
            return false;
        }
        lastFetchId = id;
        lastFetchTime = now;
        return true;
    }

    public BluetoothScanner(Context ctx, BluetoothScannerListener l) {
        this.ctx = ctx.getApplicationContext();

        BluetoothManager bm = (BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);

        if (bm != null && bm.getAdapter() != null && bm.getAdapter().isEnabled()) {
            scanner = bm.getAdapter().getBluetoothLeScanner();
        } else {
            scanner = null;
        }

        listener = l;
    }

    public void setListener(BluetoothScannerListener l) { listener = l; }
    public boolean isSupported() { return scanner != null; }

    // ========================================================
    // START SCAN (FULLY OEM-COMPATIBLE)
    // ========================================================
    public void startScan() {
        if (scanner == null) {
            Log.e(TAG, "Scanner NULL — Bluetooth disabled");
            return;
        }

        if (!hasScanPermission()) {
            Log.w(TAG, "SCAN permission missing → cannot start scanning");
            return;
        }

        ScanSettings settings;

        try {
            ScanSettings.Builder sb = new ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY);

            // Samsung & Xiaomi REQUIRE MATCH_MODE_STICKY
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                sb.setMatchMode(ScanSettings.MATCH_MODE_STICKY);
                sb.setNumOfMatches(ScanSettings.MATCH_NUM_MAX_ADVERTISEMENT);
            }

            // Android 26+ allows callbackType
            sb.setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES);

            // Android 26+ PHY balanced mode
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                sb.setPhy(ScanSettings.PHY_LE_ALL_SUPPORTED);
            }

            settings = sb.build();

        } catch (Exception e) {
            Log.e(TAG, "Scan settings failed — using fallback", e);

            settings = new ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .build();
        }

        try {
            scanner.startScan(Collections.<ScanFilter>emptyList(), settings, scanCallback);
            Log.d(TAG, "🔍 BLE SCAN STARTED (OEM-safe)");
        } catch (Exception e) {
            Log.e(TAG, "SCAN START ERROR", e);
        }
    }

    public void stopScan() {
        if (scanner == null) return;
        try { scanner.stopScan(scanCallback); }
        catch (Exception ignored) {}
    }

    private boolean hasScanPermission() {
        return ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_SCAN)
                == PackageManager.PERMISSION_GRANTED;
    }

    // ========================================================
    // SCAN CALLBACK
    // ========================================================
    private final ScanCallback scanCallback = new ScanCallback() {

        @Override
        public void onScanResult(int type, ScanResult result) {
            super.onScanResult(type, result);

            if (result.getScanRecord() == null) return;

            logRaw(result);

            // ESP32 manufacturer SOS packet
            if (checkEsp32Manufacturer(result)) return;

            // NOVA service header
            checkServiceData(result);
        }
    };

    // ========================================================
    // RAW DEBUG LOGS — throttled for performance
    // ========================================================
    private long lastLog = 0;

    private void logRaw(ScanResult result) {
        long now = System.currentTimeMillis();
        if (now - lastLog < 1000) return;  // throttle logs 1/sec
        lastLog = now;

        Log.d(RAW_TAG, "RSSI: " + result.getRssi() +
                " | Device: " + result.getDevice().getAddress());

        byte[] raw = result.getScanRecord().getBytes();
        if (raw == null) return;

        StringBuilder sb = new StringBuilder();
        for (byte b : raw) sb.append(String.format("%02X ", b));
        Log.d(RAW_TAG, "RAW: " + sb);
    }

    // ========================================================
    // ESP32 MANUFACTURER-SOS PARSER
    // ========================================================
    private boolean checkEsp32Manufacturer(ScanResult result) {

        SparseArray<byte[]> mfMap = result.getScanRecord().getManufacturerSpecificData();
        if (mfMap == null || mfMap.size() == 0) return false;

        for (int i = 0; i < mfMap.size(); i++) {
            byte[] data = mfMap.valueAt(i);
            if (data == null) continue;

            String payload;
            try { payload = new String(data); }
            catch (Exception e) { continue; }

            if (!payload.startsWith("MESH:")) continue;

            Log.d("ESP32-MESH", "PAYLOAD → " + payload);

            String[] tokens = payload.substring(5).split(";");
            HashMap<String, String> map = new HashMap<>();

            for (String t : tokens) {
                String[] kv = t.split(":", 2);
                if (kv.length == 2) map.put(kv[0], kv[1]);
            }

            if ("SOS".equalsIgnoreCase(map.get("TYPE"))) {
                Log.d("ESP32-MESH", "🚨 SOS RECEIVED FROM ESP32");
                if (listener != null) listener.onMessageReceived(MeshMessage.sosFromESP32());
                return true;
            }
        }

        return false;
    }

    // ========================================================
    // NOVA 10-BYTE SERVICE DATA PARSER
    // ========================================================
    private void checkServiceData(ScanResult result) {

        byte[] data = result.getScanRecord().getServiceData(
                new android.os.ParcelUuid(GattConstants.SERVICE_HEADER_UUID)
        );

        if (data == null || data.length < 10) return;

        try {
            ByteBuffer bb = ByteBuffer.wrap(data);
            bb.get();                     // version byte
            long id = bb.getLong();       // message ID
            int hop = bb.get() & 0xFF;

            // prevent duplicate read/fetch
            if (!shouldFetch(id)) {
                Log.d(TAG, "⏸ DUPLICATE HEADER SKIPPED ID=" + id);
                return;
            }

            MeshMessage msg = new MeshMessage();
            msg.id = id;
            msg.hopCount = hop;
            msg.bluetoothDevice = result.getDevice();
            msg.sender = result.getDevice().getAddress();

            if (listener != null)
                listener.onMessageReceived(msg);

            Log.d(TAG, "HEADER RECEIVED → id=" + id + " hop=" + hop);

        } catch (Exception e) {
            Log.e(TAG, "Header parse error", e);
        }
    }

    // Listener Interface
    public interface BluetoothScannerListener {
        void onMessageReceived(MeshMessage msg);
    }
}
