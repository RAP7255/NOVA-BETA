package com.example.nova.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.example.nova.R;
import com.example.nova.ble.BLEManager;
import com.example.nova.ble.OnMessageReceivedListener;

public class BLEForegroundService extends Service {

    private static final String CHANNEL_ID = "NOVA_BLE_CHANNEL";
    private BLEManager bleManager;

    @Override
    public void onCreate() {
        super.onCreate();

        createNotificationChannel();

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("NOVA Running")
                .setContentText("Scanning for messages...")
                .setSmallIcon(R.mipmap.ic_launcher)
                .setOngoing(true)
                .build();

        startForeground(1, notification);

        // Initialize BLEManager with a listener
        bleManager = new BLEManager(getApplicationContext(), new OnMessageReceivedListener() {
            @Override
            public void onMessageReceived(String message) {
                // Handle background alert propagation
                // Optional: trigger notifications or relay messages
            }
        });

        bleManager.startScan(); // Start scanning for admin / users
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (bleManager != null) {
            bleManager.stopScan();
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null; // Not binding
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    CHANNEL_ID,
                    "NOVA BLE Service",
                    NotificationManager.IMPORTANCE_LOW
            );
            serviceChannel.setDescription("Foreground service for BLE scanning and relay");

            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(serviceChannel);
            }
        }
    }
}
