package com.example.nova.ble;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;

import androidx.core.app.ActivityCompat;

import com.example.nova.model.MeshMessage;
import com.example.nova.model.MessageCache;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class HopManager implements BluetoothScanner.BluetoothScannerListener {

    private static final String TAG = "HopManager";

    private final MessageCache cache;
    private final BluetoothAdvertiser advertiser;
    private BluetoothScanner scanner;
    private HopListener listener;
    private Context context;   // ✅ used for permission checks

    // Singleton instance
    public static HopManager hopManagerInstance;

    public interface HopListener {
        void onNewMessage(MeshMessage message);
    }

    /** ✅ Correct constructor: requires valid Context */
    public HopManager(Context ctx, MessageCache cache, BluetoothAdvertiser advertiser,
                      BluetoothScanner scanner, HopListener initialListener) {

        if (ctx == null) {
            Log.e(TAG, "❌ Null context passed to HopManager! This will cause issues.");
            throw new IllegalArgumentException("Context cannot be null for HopManager");
        }

        this.context = ctx.getApplicationContext(); // ✅ always safe
        this.cache = cache;
        this.advertiser = advertiser;
        this.scanner = scanner;
        this.listener = initialListener;

        if (scanner != null) scanner.setListener(this);

        hopManagerInstance = this;
        Log.d(TAG, "✅ HopManager initialized successfully");
    }

    /** Dynamically attach or replace listener */
    public void setListener(HopListener listener) {
        this.listener = listener;
        Log.d(TAG, "HopListener attached");
    }

    /** Start scanner safely */
    public void start() {
        if (scanner == null) {
            Log.w(TAG, "Scanner is null");
            return;
        }

        if (!scanner.isSupported()) {
            Log.w(TAG, "Scanner not supported on this device");
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN)
                        != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Permission not granted: BLUETOOTH_SCAN");
            return;
        }

        try {
            scanner.startScan();
            Log.d(TAG, "Scanner started successfully");
        } catch (Exception e) {
            Log.e(TAG, "Failed to start scanner", e);
        }
    }

    /** Stop scanner safely */
    public void stop() {
        if (scanner != null) {
            try {
                scanner.stopScan();
                Log.d(TAG, "Scanner stopped");
            } catch (Exception e) {
                Log.w(TAG, "Failed to stop scanner", e);
            }
        }
    }

    /** Called by scanner when a message is received */
    @Override
    public void onMessageReceived(MeshMessage message) {
        if (message == null) return;

        if (cache.contains(message.id)) return; // avoid duplicates
        cache.put(message.id);

        Log.d(TAG, "📩 Received message: " + message.payload + " from " + message.sender);

        if (listener != null) listener.onNewMessage(message);
    }

    /** Send an outgoing message safely with permission check */
    public void sendOutgoing(String senderName, int initialTtl, String payload) {
        String now = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).format(new Date());
        MeshMessage msg = MeshMessage.createNew(senderName, initialTtl, payload, now);
        cache.put(msg.id);

        if (advertiser == null || !advertiser.isSupported()) {
            Log.w(TAG, "Advertiser not supported - cannot send");
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_ADVERTISE)
                        != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Outgoing advertise blocked: Missing BLUETOOTH_ADVERTISE permission");
            return;
        }

        try {
            advertiser.advertiseMeshMessage(msg, new BluetoothAdvertiser.AdvertiseCompleteCallback() {
                @Override
                public void onComplete() {
                    Log.i(TAG, "✅ Outgoing message advertised: " + msg.payload);
                }

                @Override
                public void onFailure(String reason) {
                    Log.w(TAG, "⚠️ Outgoing advertise failed: " + reason);
                }
            });
        } catch (SecurityException se) {
            Log.e(TAG, "SecurityException: Missing advertise permission", se);
        } catch (Exception e) {
            Log.e(TAG, "advertiseMeshMessage failed", e);
        }
    }
}



