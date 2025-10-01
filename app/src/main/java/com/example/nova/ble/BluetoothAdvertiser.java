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

import java.util.List;
import java.util.UUID;

/**
 * Simple BLE advertiser that sends chunked service-data sequentially.
 */
public class BluetoothAdvertiser {
    private static final String TAG = "BluetoothAdvertiser";
    private final BluetoothLeAdvertiser advertiser;
    private final Handler handler;
    private final ParcelUuid SERVICE_UUID = new ParcelUuid(UUID.fromString("0000FEED-0000-1000-8000-00805F9B34FB"));
    private final int ADVERTISE_DURATION_MS = 1000; // safer than 700ms

    private final Context context;

    public BluetoothAdvertiser(Context context) {
        this.context = context;

        BluetoothManager bluetoothManager =
                (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = (bluetoothManager != null) ? bluetoothManager.getAdapter() : null;

        if (adapter != null && adapter.isEnabled()) {
            advertiser = adapter.getBluetoothLeAdvertiser();
        } else {
            advertiser = null;
        }
        handler = new Handler(Looper.getMainLooper());
    }

    public boolean isSupported() {
        return advertiser != null;
    }

    /**
     * Advertise all chunks sequentially.
     * chunkPayloadSize is used when producing chunks in MeshMessage.
     */
    public void advertiseChunks(final MeshMessage msg, final int chunkPayloadSize, final AdvertiseCompleteCallback callback) throws Exception {
        if (advertiser == null) {
            if (callback != null) callback.onFailure("Advertiser not available");
            return;
        }

        // Runtime permission check (Android 12+)
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_ADVERTISE)
                != PackageManager.PERMISSION_GRANTED) {
            if (callback != null) callback.onFailure("Permission not granted: BLUETOOTH_ADVERTISE");
            return;
        }

        List<String> chunks;
        try {
            chunks = msg.chunkedBase64(chunkPayloadSize);
        } catch (Exception e) {
            Log.e(TAG, "Serialization error", e);
            if (callback != null) callback.onFailure("Serialization error: " + e.getMessage());
            return;
        }

        final int total = chunks.size();
        final byte versionByte = (byte) MeshMessage.VERSION;
        final byte[] msgIdHash = MeshMessage.msgIdHash8(msg.id);

        advertiseChunkAtIndex(chunks, 0, total, versionByte, msg.ttl, msgIdHash, callback);
    }

    private void advertiseChunkAtIndex(final List<String> chunks,
                                       final int idx,
                                       final int total,
                                       final byte versionByte,
                                       final int ttl,
                                       final byte[] msgIdHash,
                                       final AdvertiseCompleteCallback callback) {
        if (idx >= total) {
            if (callback != null) callback.onComplete();
            return;
        }

        final String chunkStr = chunks.get(idx);
        final byte[] serviceData = MeshMessage.buildChunkPayload(versionByte, msgIdHash, idx, total, ttl, chunkStr);

        AdvertiseSettings settings = new AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                .setConnectable(false)
                .build();

        AdvertiseData data = new AdvertiseData.Builder()
                .addServiceUuid(SERVICE_UUID)
                .addServiceData(SERVICE_UUID, serviceData)
                .setIncludeDeviceName(false)
                .build();

        final AdvertiseCallback advCallback = new AdvertiseCallback() {
            @Override
            public void onStartSuccess(AdvertiseSettings settingsInEffect) {
                super.onStartSuccess(settingsInEffect);
                Log.d(TAG, "Advert started chunk " + idx + "/" + (total - 1));

                handler.postDelayed(() -> {
                    try {
                        if (advertiser != null) {
                            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_ADVERTISE)
                                    == PackageManager.PERMISSION_GRANTED) {
                                advertiser.stopAdvertising(this);
                            } else {
                                Log.w(TAG, "Permission not granted to stopAdvertising");
                            }
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Error stopping advertiser", e);
                    }
                    advertiseChunkAtIndex(chunks, idx + 1, total, versionByte, ttl, msgIdHash, callback);
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
