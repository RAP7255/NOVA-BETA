package com.example.nova.ble;

import android.Manifest;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanSettings;
import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.content.pm.PackageManager;
import android.util.Log;
import android.util.SparseArray;

import androidx.core.content.ContextCompat;

import com.example.nova.model.MeshMessage;

import java.nio.ByteBuffer;
import java.util.HashMap;

public class BluetoothScanner {

    private static final String TAG = "BluetoothScanner";
    private static final String RAW_TAG = "RAW_AD";

    private final Context ctx;
    private final BluetoothLeScanner scanner;
    private BluetoothScannerListener listener;

    // ============================
    // NEW: Prevent duplicate GATT fetch calls
    // ============================
    private long lastFetchId = -1;
    private long lastFetchTime = 0;

    private boolean shouldFetch(long id) {
        long now = System.currentTimeMillis();

        // suppress duplicates for 2500 ms
        if (id == lastFetchId && (now - lastFetchTime) < 2500) {
            return false;
        }

        lastFetchId = id;
        lastFetchTime = now;
        return true;
    }

    public BluetoothScanner(Context ctx, BluetoothScannerListener l) {
        this.ctx = ctx.getApplicationContext();

        BluetoothManager bm =
                (BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);

        if (bm != null && bm.getAdapter() != null && bm.getAdapter().isEnabled())
            scanner = bm.getAdapter().getBluetoothLeScanner();
        else
            scanner = null;

        listener = l;
    }

    public void setListener(BluetoothScannerListener l) { listener = l; }
    public boolean isSupported() { return scanner != null; }

    // ========================================================
    // START SCAN
    // ========================================================
    public void startScan() {
        if (scanner == null) return;

        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_SCAN)
                != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "SCAN permission missing");
            return;
        }

        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
                .setNumOfMatches(ScanSettings.MATCH_NUM_MAX_ADVERTISEMENT)
                .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                .build();

        try {
            scanner.startScan(null, settings, scanCallback);
            Log.d(TAG, "🔍 BLE Scan Started");
        } catch (Exception e) {
            Log.e(TAG, "scan start error", e);
        }
    }

    public void stopScan() {
        if (scanner == null) return;
        try { scanner.stopScan(scanCallback); } catch (Exception ignored) {}
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

            // 1) ESP32 SOS (Manufacturer data)
            if (checkEsp32Manufacturer(result)) return;

            // 2) Normal NOVA 10-byte service data
            checkServiceData(result);
        }
    };

    // ========================================================
    // DEBUG RAW LOGGING
    // ========================================================
    private void logRaw(ScanResult result) {
        Log.d(RAW_TAG, "RSSI: " + result.getRssi() +
                " | Device: " + result.getDevice().getAddress() +
                " | Name: " + result.getDevice().getName());

        byte[] raw = result.getScanRecord().getBytes();
        if (raw == null) return;

        StringBuilder sb = new StringBuilder();
        for (byte b : raw) sb.append(String.format("%02X ", b));
        Log.d(RAW_TAG, "RAW_AD BYTES: " + sb);
    }

    // ========================================================
    // ESP32 MANUFACTURER-SOS PARSER
    // ========================================================
    private boolean checkEsp32Manufacturer(ScanResult result) {

        SparseArray<byte[]> mfMap = result.getScanRecord().getManufacturerSpecificData();
        if (mfMap == null || mfMap.size() == 0) return false;

        for (int i = 0; i < mfMap.size(); i++) {
            int key = mfMap.keyAt(i);
            byte[] data = mfMap.get(key);
            if (data == null) continue;

            String payload = new String(data);
            if (!payload.startsWith("MESH:")) continue;

            Log.d("ESP32-MESH", "PAYLOAD → " + payload);

            String body = payload.substring(5);
            String[] tokens = body.split(";");

            HashMap<String, String> map = new HashMap<>();
            for (String t : tokens) {
                String[] p = t.split(":", 2);
                if (p.length == 2) map.put(p[0], p[1]);
            }

            String type = map.get("TYPE");

            if ("SOS".equalsIgnoreCase(type)) {
                Log.d("ESP32-MESH", "🚨 SOS RECEIVED FROM ESP32");

                if (listener != null)
                    listener.onMessageReceived(MeshMessage.sosFromESP32());

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
            bb.get();                 // version
            long id = bb.getLong();   // message id
            int hop = bb.get() & 0xFF;

            // THROTTLE duplicates 💥
            if (!shouldFetch(id)) {
                Log.d(TAG, "⏸ SKIP duplicate fetch for id=" + id);
                return;
            }

            MeshMessage msg = new MeshMessage();
            msg.id = id;
            msg.hopCount = hop;
            msg.bluetoothDevice = result.getDevice();
            msg.sender = result.getDevice().getName();

            if (listener != null)
                listener.onMessageReceived(msg);

            Log.d(TAG, "HEADER → id=" + id + " hop=" + hop);

        } catch (Exception e) {
            Log.e(TAG, "Header parse error", e);
        }
    }

    public interface BluetoothScannerListener {
        void onMessageReceived(MeshMessage msg);
    }
}
