package com.example.nova.ble;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanFilter;
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
 * Scanner that reassembles chunks using the 8-byte msgIdHash from header.
 * Supports listener injection via setListener().
 */
public class BluetoothScanner {
    private static final String TAG = "BluetoothScanner";

    private final Context context;
    private final BluetoothLeScanner scanner;
    private final ParcelUuid SERVICE_UUID = new ParcelUuid(UUID.fromString("0000FEED-0000-1000-8000-00805F9B34FB"));
    private final Handler handler;
    private final int REASSEMBLY_TIMEOUT_MS = 6000;

    // key: hex(msgIdHash8) -> ReassemblyBuffer
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

    /** Inject or replace the listener at any time */
    public void setListener(BluetoothScannerListener listener) {
        this.listener = listener;
    }

    public boolean isSupported() {
        return scanner != null;
    }

    public void startScan() {
        if (scanner == null) {
            Log.w(TAG, "BluetoothLeScanner not available");
            return;
        }

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN)
                != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Missing BLUETOOTH_SCAN permission, cannot start scan");
            return;
        }

        List<ScanFilter> filters = new ArrayList<>();
        filters.add(new ScanFilter.Builder().setServiceUuid(SERVICE_UUID).build());

        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build();

        try {
            scanner.startScan(filters, settings, scanCallback);
            Log.d(TAG, "Scan started");
        } catch (Exception e) {
            Log.e(TAG, "startScan failed: " + e.getMessage(), e);
        }
    }

    public void stopScan() {
        if (scanner == null) return;

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN)
                != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Missing BLUETOOTH_SCAN permission, cannot stop scan");
            return;
        }

        try {
            scanner.stopScan(scanCallback);
        } catch (Exception e) {
            Log.w(TAG, "stopScan failed: " + e.getMessage());
        }

        // cleanup
        for (Runnable r : cleanupRunnables.values()) {
            handler.removeCallbacks(r);
        }
        cleanupRunnables.clear();
        buffers.clear();
        Log.d(TAG, "Scan stopped and buffers cleared");
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            handleScanResult(result);
        }
    };

    private void handleScanResult(ScanResult result) {
        try {
            if (result == null || result.getScanRecord() == null) return;

            byte[] data = result.getScanRecord().getServiceData(SERVICE_UUID);
            if (data == null || data.length < 12) return; // minimal header length

            int idx = 0;
            byte version = data[idx++];

            // read 8 byte msgIdHash
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

            ReassemblyBuffer buf = buffers.computeIfAbsent(key, k -> new ReassemblyBuffer(total));
            buf.addChunk(seqIndex, base64Part);

            // cancel previous cleanup and schedule new runnable
            Runnable prev = cleanupRunnables.remove(key);
            if (prev != null) handler.removeCallbacks(prev);

            Runnable cleanup = () -> {
                buffers.remove(key);
                cleanupRunnables.remove(key);
                Log.d(TAG, "Reassembly timeout removed buffer " + key);
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
                    Log.w(TAG, "JSON parse error: " + e.getMessage());
                }

                // cleanup immediately
                Runnable r = cleanupRunnables.remove(key);
                if (r != null) handler.removeCallbacks(r);
                buffers.remove(key);
            }
        } catch (Throwable t) {
            Log.w(TAG, "handleScanResult error: " + t.getMessage(), t);
        }
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
