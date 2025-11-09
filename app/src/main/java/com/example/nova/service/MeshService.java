package com.example.nova.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
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
import com.example.nova.ble.HopManager;
import com.example.nova.model.MessageCache;
import com.example.nova.model.MeshMessage;

public class MeshService extends Service implements HopManager.HopListener, BluetoothScanner.BluetoothScannerListener {

    private static final String TAG = "MeshService";
    private static final String CHANNEL_ID = "mesh_foreground_channel";

    public static HopManager hopManagerInstance;

    private HopManager hopManager;
    private BluetoothScanner scanner;
    private BluetoothAdvertiser advertiser;

    private final Handler handler = new Handler();

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "Service created");

        createNotificationChannel();

        // Initialize cache, advertiser, and scanner with valid context
        MessageCache cache = new MessageCache(1024);
        advertiser = new BluetoothAdvertiser(getApplicationContext());     // ✅ context fixed
        scanner = new BluetoothScanner(getApplicationContext(), null);      // ✅ context fixed

        // Initialize HopManager with valid context
        hopManager = new HopManager(
                getApplicationContext(),                                   // ✅ fix: non-null
                cache,
                advertiser,
                scanner,
                this
        );
        hopManagerInstance = hopManager; // static reference for global access

        // Link scanner to HopManager
        if (scanner != null) scanner.setListener(hopManager);

        // Delay start to ensure Bluetooth stack is ready
        handler.postDelayed(() -> {
            if (isBluetoothReady()) {
                hopManager.start();
                Log.d(TAG, "HopManager started");
            } else {
                Log.w(TAG, "Bluetooth not ready. HopManager not started");
            }
        }, 1000);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Notification notif = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("NOVA Mesh Active")
                .setContentText("Bluetooth Mesh network running…")
                .setSmallIcon(R.mipmap.ic_launcher)
                .setOngoing(true)
                .build();

        startForeground(1, notif);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (hopManager != null) hopManager.stop();
        hopManagerInstance = null;
        Log.d(TAG, "Service destroyed");
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null; // not a bound service
    }

    /** Create Foreground Notification Channel (Android 8+) */
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel chan = new NotificationChannel(
                    CHANNEL_ID,
                    "Mesh Foreground Channel",
                    NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(chan);
        }
    }

    /** Check if Bluetooth is available and enabled */
    private boolean isBluetoothReady() {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        return adapter != null && adapter.isEnabled();
    }

    /** Called when HopManager receives new mesh message */
    @Override
    public void onNewMessage(MeshMessage message) {
        Log.i(TAG, "Received message: " + message.payload);

        // Broadcast to UI (MainActivity)
        Intent i = new Intent("MESH_MESSAGE");
        i.putExtra("payload", message.payload);
        i.putExtra("sender", message.sender);
        sendBroadcast(i);
    }

    /** Forward received message from scanner to HopManager */
    @Override
    public void onMessageReceived(MeshMessage message) {
        if (hopManager != null) hopManager.onMessageReceived(message);
    }
}
