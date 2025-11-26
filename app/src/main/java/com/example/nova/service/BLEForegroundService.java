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

import java.util.Map;

public class BLEForegroundService extends Service {

    private static final String CHANNEL_ID = "NOVA_USER_BLE";
    private BLEManager bleManager;

    @Override
    public void onCreate() {
        super.onCreate();

        createChannel();

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("NOVA - Mesh Active")
                .setContentText("Keeping BLE mesh services alive")
                .setSmallIcon(R.mipmap.ic_launcher)
                .setOngoing(true)
                .build();

        startForeground(2, notification);

        // Initialize BLEManager (user-side) to keep scanning/advertising/GATT server alive
        bleManager = new BLEManager(
                getApplicationContext(),
                new BLEManager.OnMessageReceivedListener() {
                    @Override
                    public void onMessageReceived(String decryptedPayload) {
                        // user service doesn't need UI — but you can post notifications here if wanted
                    }


                    public void onPresenceUpdate(Map<String, String> liveUsers) {
                        // optional: persist presence if needed
                    }
                }
        );

        // Start scanner so device participates in mesh
        bleManager.startScanning();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (bleManager != null) bleManager.shutdown();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel chan = new NotificationChannel(CHANNEL_ID,
                    "NOVA BLE User Service", NotificationManager.IMPORTANCE_LOW);
            chan.setDescription("Foreground service to keep BLE mesh alive");
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(chan);
        }
    }
}
