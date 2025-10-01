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

public class BluetoothService extends Service {

    private static final String CHANNEL_ID = "NOVA_BLE_CHANNEL";

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("NOVA Mesh Active")
                .setContentText("Listening and relaying emergency messages")
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .build();

        // Run as foreground service
        startForeground(1, notification);

        // 👉 Here we’ll start BLE Scanner + Advertiser + MessageHopper
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Service will stay alive until explicitly stopped
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        // Stop scanning/advertising when service is killed
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null; // We are not using bound service
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    CHANNEL_ID,
                    "NOVA BLE Mesh",
                    NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(serviceChannel);
            }
        }
    }
}
