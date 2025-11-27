package com.example.nova.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.example.nova.R;
import com.example.nova.ble.BluetoothAdvertiser;
import com.example.nova.ble.BluetoothScanner;
import com.example.nova.model.MessageCache;
import com.example.nova.ble.HopManager;

public class MeshService extends Service {

    private static final String TAG = "MeshService";
    private static final String CHANNEL_ID = "nova_mesh_channel";

    private HopManager hopManager;
    private boolean meshStarted = false;

    private final Handler handler = new Handler();

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "MeshService: onCreate");

        createNotificationChannel();
        startForeground(1, buildNotification());

        if (!isBluetoothAvailable()) {
            Log.e(TAG, "Bluetooth OFF → stopping service");
            stopSelf();
            return;
        }

        // Delay small time so BLE stack fully loads
        handler.postDelayed(this::startMeshEngineSafe, 350);
    }

    private boolean isBluetoothAvailable() {
        BluetoothManager bt = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        if (bt == null) return false;
        BluetoothAdapter ad = bt.getAdapter();
        return ad != null && ad.isEnabled();
    }

    /**
     * Ensures mesh starts only once, even across restart loops.
     */
    private void startMeshEngineSafe() {

        if (meshStarted) {
            Log.d(TAG, "MeshService: ALREADY started → skip");
            return;
        }

        meshStarted = true;

        try {
            MessageCache cache = new MessageCache(500);
            BluetoothAdvertiser advertiser = new BluetoothAdvertiser(this);
            BluetoothScanner scanner = new BluetoothScanner(this, null);

            hopManager = new HopManager(
                    this,
                    cache,
                    advertiser,
                    scanner,
                    null   // listener optional
            );

            hopManager.start();

            Log.d(TAG, "MeshService: Mesh engine STARTED ✔");

        } catch (Exception e) {
            Log.e(TAG, "MeshService: mesh engine FAILED", e);
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID,
                    "NOVA Mesh Service",
                    NotificationManager.IMPORTANCE_HIGH
            );
            ch.setDescription("Background mesh engine");
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    private Notification buildNotification() {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("NOVA Mesh Active")
                .setContentText("Running background mesh engine")
                .setSmallIcon(R.drawable.ic_sos)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setOngoing(true)
                .build();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "MeshService: onStartCommand");
        return START_STICKY;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        super.onTaskRemoved(rootIntent);

        Log.w(TAG, "MeshService: onTaskRemoved → scheduling restart");

        // delay restart to prevent double HopManager
        handler.postDelayed(() -> {
            Intent restartIntent = new Intent(getApplicationContext(), MeshService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                startForegroundService(restartIntent);
            else
                startService(restartIntent);
        }, 5000);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "MeshService: destroyed");

        // stop everything cleanly
        try {
            if (hopManager != null) {
                hopManager.stop();
                hopManager.stopGattServer();  // ⭐ VERY IMPORTANT FIX
            }
        } catch (Exception e) {
            Log.e(TAG, "Error stopping HopManager", e);
        }

        meshStarted = false;
    }
}
