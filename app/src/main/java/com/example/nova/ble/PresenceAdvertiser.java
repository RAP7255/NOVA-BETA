package com.example.nova.ble;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.ParcelUuid;
import android.util.Log;

import androidx.core.content.ContextCompat;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * PresenceAdvertiser
 * -------------------
 * Handles periodic short BLE advertising packets
 * Used for broadcasting simple alerts or presence beacons.
 */
public class PresenceAdvertiser {

    private static final String TAG = "PresenceAdvertiser";

    // ✅ SAME UUID AS SCANNER
    private static final ParcelUuid SERVICE_UUID =
            new ParcelUuid(UUID.fromString("0000FEED-0000-1000-8000-00805F9B34FB"));

    private final Context context;
    private final BluetoothAdapter bluetoothAdapter;
    private BluetoothLeAdvertiser advertiser;
    private AdvertiseCallback advertiseCallback;
    private final Handler handler = new Handler();

    private boolean isAdvertising = false;
    private static final long ADVERTISE_DURATION_MS = 4000;   // active broadcast time
    private static final long RETRY_DELAY_MS = 2000;          // retry gap if failed

    public PresenceAdvertiser(Context context) {
        this.context = context.getApplicationContext();
        this.bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
    }

    /** Starts advertising a presence or alert message */
    public void startAdvertising(String message) {
        // ✅ Make it effectively final for use inside inner classes
        final String broadcastMessage = (message.length() > 25)
                ? message.substring(0, 25)
                : message;

        if (isAdvertising) {
            Log.w(TAG, "⏳ Already advertising, skipping new start.");
            return;
        }

        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
            Log.e(TAG, "❌ Bluetooth not enabled.");
            return;
        }

        // ✅ Check required permission (Android 12+)
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_ADVERTISE)
                != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "⚠️ Missing BLUETOOTH_ADVERTISE permission.");
            return;
        }

        try {
            if (advertiser == null) {
                advertiser = bluetoothAdapter.getBluetoothLeAdvertiser();
            }
            if (advertiser == null) {
                Log.e(TAG, "❌ Device doesn't support BLE advertising.");
                return;
            }

            byte[] payload = broadcastMessage.getBytes(StandardCharsets.UTF_8);

            AdvertiseData data = new AdvertiseData.Builder()
                    .addServiceUuid(SERVICE_UUID)
                    .addServiceData(SERVICE_UUID, payload)
                    .setIncludeDeviceName(false)
                    .build();

            AdvertiseSettings settings = new AdvertiseSettings.Builder()
                    .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                    .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                    .setConnectable(false)
                    .build();

            advertiseCallback = new AdvertiseCallback() {
                @Override
                public void onStartSuccess(AdvertiseSettings settingsInEffect) {
                    isAdvertising = true;
                    Log.i(TAG, "✅ Advertising started: " + broadcastMessage);
                    handler.postDelayed(PresenceAdvertiser.this::stopAdvertising, ADVERTISE_DURATION_MS);
                }

                @Override
                public void onStartFailure(int errorCode) {
                    isAdvertising = false;
                    Log.e(TAG, "❌ Advert failed: " + errorCode);

                    if (errorCode == ADVERTISE_FAILED_TOO_MANY_ADVERTISERS) {
                        Log.w(TAG, "⚠️ Too many advertisers. Retrying after delay...");
                        handler.postDelayed(() -> startAdvertising(broadcastMessage), RETRY_DELAY_MS);
                    }
                }
            };

            // ✅ Start advertising safely
            advertiser.startAdvertising(settings, data, advertiseCallback);

        } catch (SecurityException e) {
            Log.e(TAG, "🚫 SecurityException during advertise: " + e.getMessage());
        } catch (Exception e) {
            Log.e(TAG, "⚠️ Unexpected error in advertising: " + e.getMessage());
        }
    }

    /** Stops the current advertising session */
    public void stopAdvertising() {
        if (advertiser == null || advertiseCallback == null) return;

        try {
            advertiser.stopAdvertising(advertiseCallback);
            Log.d(TAG, "🛑 Advertising stopped.");
        } catch (SecurityException e) {
            Log.e(TAG, "🚫 SecurityException stopping advertise: " + e.getMessage());
        } finally {
            isAdvertising = false;
            advertiseCallback = null;
        }
    }
}
