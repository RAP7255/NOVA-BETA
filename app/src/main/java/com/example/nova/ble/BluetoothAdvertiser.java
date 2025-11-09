package com.example.nova.ble;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.util.Log;

import androidx.core.app.ActivityCompat;

import com.example.nova.model.MeshMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * BLE Advertiser that automatically splits payloads into BLE-safe chunks.
 */
public class BluetoothAdvertiser {
    private static final String TAG = "BluetoothAdvertiser";
    private final BluetoothLeAdvertiser advertiser;
    private final Handler handler;
    private final ParcelUuid SERVICE_UUID = new ParcelUuid(UUID.fromString("0000FEED-0000-1000-8000-00805F9B34FB"));
    private final int ADVERTISE_DURATION_MS = 1000;
    private final int MAX_BLE_PAYLOAD = 20; // max bytes per BLE advertisement for service data
    private final Context context;

    public BluetoothAdvertiser(Context context) {
        this.context = context;
        BluetoothManager bluetoothManager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = (bluetoothManager != null) ? bluetoothManager.getAdapter() : null;
        advertiser = (adapter != null && adapter.isEnabled()) ? adapter.getBluetoothLeAdvertiser() : null;
        handler = new Handler(Looper.getMainLooper());
    }

    public boolean isSupported() {
        return advertiser != null;
    }

    /**
     * Splits a MeshMessage into BLE-safe chunks and advertises them sequentially.
     */
    public void advertiseMeshMessage(final MeshMessage msg, final AdvertiseCompleteCallback callback) throws Exception {
        if (advertiser == null) {
            if (callback != null) callback.onFailure("Advertiser not available");
            return;
        }

        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_ADVERTISE)
                != PackageManager.PERMISSION_GRANTED) {
            if (callback != null) callback.onFailure("Permission not granted: BLUETOOTH_ADVERTISE");
            return;
        }

        // Determine safe chunk size
        final int overhead = 1 + 8 + 1 + 1 + 1; // version + msgIdHash + idx + total + ttl
        final int safeChunkSize = MAX_BLE_PAYLOAD - overhead;
        List<String> chunks = msg.chunkedBase64(safeChunkSize);

        final byte versionByte = (byte) MeshMessage.VERSION;
        final byte[] msgIdHash = MeshMessage.msgIdHash8(msg.id);

        advertiseChunkAtIndex(chunks, 0, versionByte, msg.ttl, msgIdHash, callback);
    }

    private void advertiseChunkAtIndex(final List<String> chunks,
                                       final int idx,
                                       final byte versionByte,
                                       final int ttl,
                                       final byte[] msgIdHash,
                                       final AdvertiseCompleteCallback callback) {
        if (idx >= chunks.size()) {
            if (callback != null) callback.onComplete();
            return;
        }

        final String chunkStr = chunks.get(idx);
        final byte[] payload = MeshMessage.buildChunkPayload(versionByte, msgIdHash, idx, chunks.size(), ttl, chunkStr);

        // Safety check
        if (payload.length > MAX_BLE_PAYLOAD) {
            Log.e(TAG, "Chunk payload too large! size=" + payload.length + " bytes, max=" + MAX_BLE_PAYLOAD);
            if (callback != null) callback.onFailure("Chunk too large: " + payload.length + " bytes");
            return;
        }

        AdvertiseSettings settings = new AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                .setConnectable(false)
                .build();

        AdvertiseData data = new AdvertiseData.Builder()
                .addServiceUuid(SERVICE_UUID)
                .addServiceData(SERVICE_UUID, payload)
                .setIncludeDeviceName(false)
                .build();

        final AdvertiseCallback advCallback = new AdvertiseCallback() {
            @Override
            public void onStartSuccess(AdvertiseSettings settingsInEffect) {
                super.onStartSuccess(settingsInEffect);
                Log.d(TAG, "Advert started chunk " + idx + "/" + (chunks.size() - 1) + ", size=" + payload.length);

                // Delay and advertise next chunk
                handler.postDelayed(() -> {
                    try {
                        if (advertiser != null &&
                                ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_ADVERTISE)
                                        == PackageManager.PERMISSION_GRANTED) {
                            advertiser.stopAdvertising(this);
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Error stopping advertiser", e);
                    }
                    advertiseChunkAtIndex(chunks, idx + 1, versionByte, ttl, msgIdHash, callback);
                }, ADVERTISE_DURATION_MS);
            }

            @Override
            public void onStartFailure(int errorCode) {
                super.onStartFailure(errorCode);
                Log.e(TAG, "Advert failed: " + errorCode);
                if (callback != null) callback.onFailure("Advert failed code: " + errorCode);
            }
        };

        try {
            advertiser.startAdvertising(settings, data, advCallback);
        } catch (Exception e) {
            Log.e(TAG, "startAdvertising exception: " + e.getMessage(), e);
            if (callback != null) callback.onFailure("startAdvertising exception: " + e.getMessage());
        }
    }

    public interface AdvertiseCompleteCallback {
        void onComplete();
        void onFailure(String reason);
    }
}




