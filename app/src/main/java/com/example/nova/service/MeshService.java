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
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.example.nova.R;
import com.example.nova.ble.BluetoothAdvertiser;
import com.example.nova.ble.BluetoothScanner;
import com.example.nova.ble.GattServer;
import com.example.nova.model.MessageCache;
import com.example.nova.ble.HopManager;

public class MeshService extends Service {

    private static final String TAG = "MeshService";
    private static final String CHANNEL_ID = "mesh_service";

    private HopManager hopManager;
    private GattServer gattServer;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "MeshService: onCreate");

        createForegroundChannel();
        startForeground(1, createForegroundNotification());

        // ---------------------------
        // BLUETOOTH VALIDATION
        // ---------------------------
        BluetoothManager btManager =
                (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = btManager != null ? btManager.getAdapter() : null;

        if (adapter == null || !adapter.isEnabled()) {
            Log.e(TAG, "Bluetooth OFF or unavailable — stopping MeshService");
            stopSelf();
            return;
        }

        // ---------------------------
        // MESH COMPONENTS
        // ---------------------------
        MessageCache cache = new MessageCache(500);
        BluetoothAdvertiser advertiser = new BluetoothAdvertiser(this);

        // Scanner will forward headers → HopManager
        BluetoothScanner scanner = new BluetoothScanner(this, null);

        // Our final GATT Server serving ciphertext
        gattServer = new GattServer(this);
        gattServer.start();

        // ---------------------------
        // HopManager — listener attached by MainActivity
        // ---------------------------
        hopManager = new HopManager(
                this,
                cache,
                advertiser,
                scanner,
                null   // listener set later from UI
        );

        hopManager.start();

        Log.d(TAG, "MeshService: Mesh engine started ✔");
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // -----------------------------------------------------------
    // FOREGROUND SERVICE CHANNEL
    // -----------------------------------------------------------
    private void createForegroundChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID,
                    "NOVA Mesh Background",
                    NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    private Notification createForegroundNotification() {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("NOVA Mesh Active")
                .setContentText("BLE Mesh Engine Running")
                .setSmallIcon(R.drawable.ic_sos)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .build();
    }

    // -----------------------------------------------------------
    // CLEANUP
    // -----------------------------------------------------------
    @Override
    public void onDestroy() {
        super.onDestroy();

        if (hopManager != null && hopManager.isRunning())
            hopManager.stop();

        if (gattServer != null)
            gattServer.stop();

        Log.d(TAG, "MeshService: destroyed");
    }
}
