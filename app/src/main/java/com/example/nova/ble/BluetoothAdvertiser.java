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
    private final Handler handler = new Handler();   // ⭐ REQUIRED

    private final ParcelUuid SERVICE_UUID =
            new ParcelUuid(GattConstants.SERVICE_HEADER_UUID);

    public BluetoothAdvertiser(Context ctx) {
        this.context = ctx.getApplicationContext();

        BluetoothManager btManager =
                (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);

        BluetoothAdapter adapter = btManager != null ? btManager.getAdapter() : null;

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
    // PUBLIC API (SAFE SINGLE ADVERTISEMENT)
    // ====================================================================
    public void advertiseMeshMessage(
            MeshMessage msg,
            AdvertiseCompleteCallback callback
    ) {
        advertiseInternal(msg, callback);
    }

    // ====================================================================
    // INTERNAL — ALWAYS CONNECTABLE, SAFE FOR REDMI / ONEPLUS
    // ====================================================================
    private void advertiseInternal(
            MeshMessage msg,
            AdvertiseCompleteCallback callback
    ) {

        if (advertiser == null) {
            if (callback != null) callback.onFailure("advertiser null");
            return;
        }

        if (!hasAdvertisePermission()) {
            if (callback != null) callback.onFailure("No ADVERTISE permission");
            return;
        }

        try {

            // -------------------------------
            // HEADER (10 bytes)
            // -------------------------------
            byte[] header = ByteBuffer.allocate(10)
                    .put((byte) MeshMessage.VERSION)
                    .putLong(msg.id)
                    .put((byte) msg.hopCount)
                    .array();

            AdvertiseData data = new AdvertiseData.Builder()
                    .addServiceUuid(SERVICE_UUID)
                    .addServiceData(SERVICE_UUID, header)
                    .setIncludeDeviceName(false)
                    .build();

            // -------------------------------
            // ADVERTISE SETTINGS (ALWAYS CONNECTABLE)
            // -------------------------------
            AdvertiseSettings settings = new AdvertiseSettings.Builder()
                    .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                    .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                    .setConnectable(true)            // ⭐ CRITICAL FIX
                    .build();

            // -------------------------------
            // CALLBACK
            // -------------------------------
            AdvertiseCallback advCallback = new AdvertiseCallback() {
                @Override
                public void onStartSuccess(AdvertiseSettings settingsInEffect) {
                    Log.i(TAG, "Advertising OK id=" + msg.id);
                    if (callback != null) callback.onComplete();
                }

                @Override
                public void onStartFailure(int errorCode) {
                    Log.e(TAG, "Advertising FAIL code=" + errorCode);
                    if (callback != null) callback.onFailure("Fail=" + errorCode);
                }
            };

            // -------------------------------
            // START ADVERTISING (ONLY ONCE)
            // -------------------------------
            advertiser.startAdvertising(settings, data, advCallback);

            // -------------------------------
            // STOP AFTER 350 ms (SAFE)
            // -------------------------------
            handler.postDelayed(() -> {
                try {
                    advertiser.stopAdvertising(advCallback);
                } catch (Exception ignore) {}
            }, 350);

        } catch (Exception e) {
            Log.e(TAG, "startAdvertising err=" + e.getMessage());
            if (callback != null) callback.onFailure("Exception: " + e.getMessage());
        }
    }

    // CALLBACK INTERFACE
    public interface AdvertiseCompleteCallback {
        void onComplete();
        void onFailure(String reason);
    }
}
