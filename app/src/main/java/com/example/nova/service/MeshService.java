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

        // Initialize cache + advertiser
        MessageCache cache = new MessageCache(1024);
        advertiser = new BluetoothAdvertiser(this);

        // Optional scanner — can be null for testing SOS only
        scanner = new BluetoothScanner(this, null);

        // Initialize HopManager eagerly
        hopManager = new HopManager(cache, advertiser, scanner, this);
        hopManagerInstance = hopManager; // static reference

        // Hook scanner -> hopManager
        if (scanner != null) scanner.setListener(hopManager);

        // Start HopManager after short delay to ensure Bluetooth is ready
        handler.postDelayed(() -> {
            if (isBluetoothReady()) {
                hopManager.start();
                Log.d(TAG, "HopManager started");
            } else {
                Log.w(TAG, "Bluetooth not ready. HopManager not started");
            }
        }, 1000); // 1 second delay
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

    private boolean isBluetoothReady() {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        return adapter != null && adapter.isEnabled();
    }

    @Override
    public void onNewMessage(MeshMessage message) {
        Log.i(TAG, "Received message: " + message.payload);

        // Forward to UI safely
        Intent i = new Intent("MESH_MESSAGE");
        i.putExtra("payload", message.payload);
        i.putExtra("sender", message.sender);
        sendBroadcast(i);
    }

    @Override
    public void onMessageReceived(MeshMessage message) {
        if (hopManager != null) hopManager.onMessageReceived(message);
    }
}
