package com.example.nova.util;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;

import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

public class NotificationHelper {

    public static final String CHANNEL_ID = "sos_alert_channel";
    private static final String CHANNEL_NAME = "SOS Alerts";
    private static final String CHANNEL_DESC = "Notifications for SOS alerts";

    /**
     * Create notification channel (for Android O+).
     * Call this once in MainActivity.onCreate().
     */
    public static void createNotificationChannel(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_HIGH
            );
            channel.setDescription(CHANNEL_DESC);
            channel.enableLights(true);
            channel.enableVibration(true);

            NotificationManager manager = context.getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    /**
     * Show SOS notification with sound, auto-dismiss after 1 hour,
     * and optional clickable location link that opens Google Maps.
     *
     * @param context   Android context
     * @param title     Notification title
     * @param message   Notification body text
     * @param mapsUrl   Google Maps URI (e.g., "geo:lat,lon?q=lat,lon(Label)"), nullable
     */
    public static void showNotification(Context context, String title, String message, String mapsUrl) {
        // Check notification permission for Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ActivityCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
            // Permission not granted, skip showing notification
            return;
        }

        Uri alarmSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);

        PendingIntent pendingIntent = null;
        if (mapsUrl != null) {
            Intent mapIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(mapsUrl));
            mapIntent.setPackage("com.google.android.apps.maps"); // Prefer Google Maps
            pendingIntent = PendingIntent.getActivity(
                    context,
                    0,
                    mapIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
            );
        }

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle(title)
                .setContentText(message)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(message))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setSound(alarmSound)
                .setAutoCancel(true)
                .setTimeoutAfter(60 * 60 * 1000); // Auto-dismiss after 1 hour

        if (pendingIntent != null) {
            builder.setContentIntent(pendingIntent);
        }

        NotificationManagerCompat manager = NotificationManagerCompat.from(context);
        manager.notify((int) System.currentTimeMillis(), builder.build());
    }
}
