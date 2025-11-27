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
import android.os.Build;
import android.os.Handler;
import android.os.ParcelUuid;
import android.util.Log;

import androidx.core.content.ContextCompat;

import com.example.nova.model.MeshMessage;

import java.nio.ByteBuffer;

public class BluetoothAdvertiser {

    private static final String TAG = "BluetoothAdvertiser";

    private final BluetoothLeAdvertiser advertiser;
    private final Context context;
    private final Handler handler = new Handler();
    private AdvertiseCallback lastCallback;  // ⭐ prevents duplicate failures

    private final ParcelUuid SERVICE_UUID =
            new ParcelUuid(GattConstants.SERVICE_HEADER_UUID);

    public BluetoothAdvertiser(Context ctx) {
        this.context = ctx.getApplicationContext();

        BluetoothManager manager =
                (BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter adapter =
                (manager != null) ? manager.getAdapter() : null;

        if (adapter != null && adapter.isEnabled())
            advertiser = adapter.getBluetoothLeAdvertiser();
        else
            advertiser = null;
    }

    public boolean isSupported() {
        return advertiser != null;
    }

    private boolean hasAdvertisePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            return ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_ADVERTISE)
                    == PackageManager.PERMISSION_GRANTED;

        return ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    // ====================================================================
    // PUBLIC ENTRY POINT
    // ====================================================================
    public void advertiseMeshMessage(
            MeshMessage msg,
            AdvertiseCompleteCallback callback
    ) {
        advertiseInternal(msg, callback);
    }

    // ====================================================================
    // INTERNAL ADVERTISING (OEM-STABLE VERSION)
    // ====================================================================
    private void advertiseInternal(
            MeshMessage msg,
            AdvertiseCompleteCallback callback
    ) {
        if (advertiser == null) {
            if (callback != null) callback.onFailure("BLE advertiser null");
            return;
        }

        if (!hasAdvertisePermission()) {
            if (callback != null) callback.onFailure("Missing ADVERTISE permission");
            return;
        }

        try {

            // ====================================================
            // BUILD 10-BYTE HEADER PAYLOAD
            // ====================================================
            byte[] header = ByteBuffer.allocate(10)
                    .put((byte) MeshMessage.VERSION)  // version
                    .putLong(msg.id)                 // message id
                    .put((byte) msg.hopCount)        // hop
                    .array();

            // ====================================================
            // ADVERTISE DATA (MUST be < 31 bytes)
            // ====================================================
            AdvertiseData data = new AdvertiseData.Builder()
                    .addServiceUuid(SERVICE_UUID)
                    .addServiceData(SERVICE_UUID, header)
                    .setIncludeDeviceName(false)
                    .setIncludeTxPowerLevel(false)   // ⭐ CRITICAL: prevents OEM overflow
                    .build();

            // ====================================================
            // SETTINGS — OEM SAFE
            // ====================================================
            AdvertiseSettings settings = new AdvertiseSettings.Builder()
                    .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                    .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                    .setConnectable(true)            // required for Android 12+
                    .setTimeout(0)                   // unlimited
                    .build();

            // ====================================================
            // CALLBACK — REUSABLE & SAFE
            // ====================================================
            lastCallback = new AdvertiseCallback() {

                @Override
                public void onStartSuccess(AdvertiseSettings settingsInEffect) {
                    Log.i(TAG, "Advertising START OK → id=" + msg.id);
                    if (callback != null) callback.onComplete();
                }

                @Override
                public void onStartFailure(int errorCode) {
                    Log.e(TAG, "Advertising FAIL: code=" + errorCode);

                    String reason;

                    switch (errorCode) {
                        case ADVERTISE_FAILED_DATA_TOO_LARGE:
                            reason = "DATA_TOO_LARGE";
                            break;
                        case ADVERTISE_FAILED_TOO_MANY_ADVERTISERS:
                            reason = "TOO_MANY_ADVERTISERS";
                            break;
                        case ADVERTISE_FAILED_ALREADY_STARTED:
                            reason = "ALREADY_STARTED";
                            break;
                        case ADVERTISE_FAILED_INTERNAL_ERROR:
                            reason = "INTERNAL_ERROR";
                            break;
                        case ADVERTISE_FAILED_FEATURE_UNSUPPORTED:
                            reason = "UNSUPPORTED";
                            break;
                        default:
                            reason = "UNKNOWN";
                    }

                    if (callback != null) callback.onFailure(reason);
                }
            };

            // ====================================================
            // START ADVERTISING — SINGLE START
            // ====================================================
            advertiser.startAdvertising(settings, data, lastCallback);

            // ====================================================
            // STOP after 500 ms (OEM optimal)
            // Xiaomi/Samsung/Realme require >400ms for detection
            // ====================================================
            handler.postDelayed(() -> {
                try {
                    advertiser.stopAdvertising(lastCallback);
                } catch (Exception ignore) {}
            }, 500);

        } catch (Exception e) {
            Log.e(TAG, "Advertise exception " + e);
            if (callback != null) callback.onFailure("EXCEPTION:" + e.getMessage());
        }
    }

    // ====================================================
    // CALLBACK INTERFACE
    // ====================================================
    public interface AdvertiseCompleteCallback {
        void onComplete();
        void onFailure(String reason);
    }
}
