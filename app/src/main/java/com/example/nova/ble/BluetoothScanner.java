package com.example.nova.ble;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.bluetooth.le.BluetoothLeScanner;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.util.Log;

import androidx.core.content.ContextCompat;

import com.example.nova.model.MeshMessage;
import com.example.nova.model.Utils;

import org.json.JSONException;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * BluetoothScanner (backward-compatible version)
 * - Handles small (ALERT/PRESENCE) + chunked MeshMessages
 * - Compatible with API 21+
 */
public class BluetoothScanner {

    private static final String TAG = "BluetoothScanner";
    private static final UUID RAW_UUID = UUID.fromString("0000FEED-0000-1000-8000-00805F9B34FB");

    private final Context context;
    private final BluetoothLeScanner scanner;
    private final ParcelUuid SERVICE_UUID = new ParcelUuid(RAW_UUID);
    private final Handler handler;
    private static final int REASSEMBLY_TIMEOUT_MS = 6000;

    // Data maps
    private final Map<String, ReassemblyBuffer> buffers = new ConcurrentHashMap<>();
    private final Map<String, Runnable> cleanupRunnables = new ConcurrentHashMap<>();

    private BluetoothScannerListener listener;

    public BluetoothScanner(Context context, BluetoothScannerListener listener) {
        this.context = context.getApplicationContext();
        BluetoothManager btManager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = (btManager != null) ? btManager.getAdapter() : null;
        this.scanner = (adapter != null && adapter.isEnabled()) ? adapter.getBluetoothLeScanner() : null;
        this.handler = new Handler(Looper.getMainLooper());
        this.listener = listener;
    }

    public void setListener(BluetoothScannerListener listener) {
        this.listener = listener;
    }

    public boolean isSupported() {
        return scanner != null;
    }

    /** ✅ Safe scan start with full permission checks */
    public void startScan() {
        if (scanner == null) {
            Log.w(TAG, "❌ BLE scanner not available");
            return;
        }

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN)
                != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "🚫 Missing BLUETOOTH_SCAN permission");
            return;
        }

        List<ScanFilter> filters = new ArrayList<>();
        filters.add(new ScanFilter.Builder().setServiceUuid(SERVICE_UUID).build());

        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build();

        try {
            scanner.startScan(filters, settings, scanCallback);
            Log.d(TAG, "🔍 BLE scanning started...");
        } catch (SecurityException se) {
            Log.e(TAG, "⚠️ Permission denied for BLE scan", se);
        } catch (Exception e) {
            Log.e(TAG, "startScan failed: " + e.getMessage(), e);
        }
    }

    /** ✅ Safe stop scan with cleanup */
    public void stopScan() {
        if (scanner == null) return;

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN)
                != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "🚫 Missing BLUETOOTH_SCAN permission to stop");
            return;
        }

        try {
            scanner.stopScan(scanCallback);
        } catch (SecurityException se) {
            Log.e(TAG, "⚠️ Permission denied for BLE stop", se);
        } catch (Exception e) {
            Log.w(TAG, "stopScan failed: " + e.getMessage());
        }

        cleanupAll();
        Log.d(TAG, "🛑 BLE scan stopped and buffers cleared");
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            handleScanResult(result);
        }
    };

    /** ✅ Handles simple + chunked BLE packets safely */
    private void handleScanResult(ScanResult result) {
        try {
            if (result == null || result.getScanRecord() == null) return;

            byte[] data = result.getScanRecord().getServiceData(SERVICE_UUID);
            if (data == null || data.length == 0) return;

            // 🧩 Simple packet check
            String payload = new String(data, StandardCharsets.UTF_8);
            if (payload.startsWith("ALERT:") || payload.startsWith("PRESENCE:") || payload.startsWith("SOS")) {
                Log.d(TAG, "📩 Simple packet received: " + payload);

                MeshMessage msg = new MeshMessage();
                msg.payload = payload;
                msg.sender = (result.getDevice() != null && result.getDevice().getName() != null)
                        ? result.getDevice().getName() : "Unknown";

                if (listener != null) listener.onMessageReceived(msg);
                return;
            }

            // 🧠 Chunked packet reassembly
            if (data.length < 12) return;
            int idx = 0;
            byte version = data[idx++];

            byte[] msgIdHash = new byte[8];
            System.arraycopy(data, idx, msgIdHash, 0, 8);
            idx += 8;

            int seqIndex = data[idx++] & 0xFF;
            int total = data[idx++] & 0xFF;
            int ttl = data[idx++] & 0xFF;

            int payloadLen = data.length - idx;
            if (payloadLen <= 0) return;

            String base64Part = new String(data, idx, payloadLen, StandardCharsets.UTF_8);
            String key = Utils.bytesToHex(msgIdHash);

            ReassemblyBuffer buf = buffers.get(key);
            if (buf == null) {
                buf = new ReassemblyBuffer(total);
                buffers.put(key, buf);
            }
            buf.addChunk(seqIndex, base64Part);

            Runnable prev = cleanupRunnables.remove(key);
            if (prev != null) handler.removeCallbacks(prev);

            Runnable cleanup = () -> {
                buffers.remove(key);
                cleanupRunnables.remove(key);
                Log.d(TAG, "🧹 Timeout cleared buffer " + key);
            };
            cleanupRunnables.put(key, cleanup);
            handler.postDelayed(cleanup, REASSEMBLY_TIMEOUT_MS);

            if (buf.isComplete()) {
                String concatenated = buf.rebuildConcatenated();
                try {
                    String json = MeshMessage.rebuildJsonFromBase64Concatenation(concatenated);
                    MeshMessage msg = MeshMessage.fromJsonString(json);
                    if (listener != null) listener.onMessageReceived(msg);
                } catch (JSONException e) {
                    Log.w(TAG, "⚠️ JSON parse failed: " + e.getMessage());
                }

                Runnable r = cleanupRunnables.remove(key);
                if (r != null) handler.removeCallbacks(r);
                buffers.remove(key);
            }

        } catch (SecurityException se) {
            Log.e(TAG, "⚠️ BLE permission missing", se);
        } catch (Throwable t) {
            Log.e(TAG, "Error in handleScanResult", t);
        }
    }

    private void cleanupAll() {
        for (Runnable r : cleanupRunnables.values()) handler.removeCallbacks(r);
        cleanupRunnables.clear();
        buffers.clear();
    }

    private static class ReassemblyBuffer {
        private final int totalParts;
        private final String[] parts;
        private int received = 0;

        public ReassemblyBuffer(int totalParts) {
            this.totalParts = Math.max(1, totalParts);
            this.parts = new String[this.totalParts];
        }

        public synchronized void addChunk(int idx, String part) {
            if (idx < 0 || idx >= totalParts) return;
            if (parts[idx] == null) {
                parts[idx] = part;
                received++;
            }
        }

        public synchronized boolean isComplete() {
            return received >= totalParts;
        }

        public synchronized String rebuildConcatenated() {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < totalParts; i++) {
                if (parts[i] != null) sb.append(parts[i]);
            }
            return sb.toString();
        }
    }

    public interface BluetoothScannerListener {
        void onMessageReceived(MeshMessage message);
    }
}
