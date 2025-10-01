package com.example.nova.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
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

    // Expose hopManager so MainActivity can send outgoing messages
    public static HopManager hopManagerInstance;

    private HopManager hopManager;
    private BluetoothScanner scanner;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "Service created");

        createNotificationChannel();

        // Initialize cache + advertiser
        MessageCache cache = new MessageCache(1024);
        BluetoothAdvertiser advertiser = new BluetoothAdvertiser(this);

        // Scanner
        scanner = new BluetoothScanner(this, null);

        // HopManager
        hopManager = new HopManager(cache, advertiser, scanner, this);
        hopManagerInstance = hopManager; // save static reference

        // Hook scanner -> hopManager
        scanner.setListener(hopManager);

        // Start mesh
        hopManager.start();
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
        if (hopManager != null) {
            hopManager.stop();
        }
        hopManagerInstance = null; // clear static ref
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
            if (manager != null) {
                manager.createNotificationChannel(chan);
            }
        }
    }

    /** Called when HopManager receives a new mesh message */
    @Override
    public void onNewMessage(MeshMessage message) {
        Log.i(TAG, "Received message: " + message.payload);

        // Forward to UI
        Intent i = new Intent("MESH_MESSAGE");
        i.putExtra("payload", message.payload);
        i.putExtra("sender", message.senderId); // include sender info
        sendBroadcast(i);
    }

    /** Scanner callback — forward raw messages into hopManager */
    @Override
    public void onMessageReceived(MeshMessage message) {
        if (hopManager != null) {
            hopManager.onMessageReceived(message);
        }
    }
}
