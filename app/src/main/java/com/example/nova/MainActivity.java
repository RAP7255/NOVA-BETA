package com.example.nova;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.NotificationCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.nova.ble.HopManager;
import com.example.nova.model.MeshMessage;
import com.example.nova.service.MeshService;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private static final String PREFS_NAME = "nova_prefs";
    private static final String KEY_USERNAME = "username";
    private static final int PERMISSIONS_REQUEST_CODE = 101;
    private static final String CHANNEL_ID = "nova_alerts_channel";

    private Button btnSOS;
    private TextView tvTitle;
    private RecyclerView messagesRecyclerView;

    private String username;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final List<MeshMessage> messageList = new ArrayList<>();
    private MessagesAdapter messagesAdapter;

    @RequiresApi(api = Build.VERSION_CODES.M)
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        btnSOS = findViewById(R.id.btnSOS);
        tvTitle = findViewById(R.id.tvTitle);
        messagesRecyclerView = findViewById(R.id.messagesRecyclerView);

        messagesAdapter = new MessagesAdapter(this, messageList);
        messagesRecyclerView.setLayoutManager(new LinearLayoutManager(this));
        messagesRecyclerView.setAdapter(messagesAdapter);

        checkFirstTimeSetup();
        checkPermissions();
        createNotificationChannel();

        btnSOS.setOnClickListener(v -> sendSOS());

        attachHopManagerListener();
    }

    private void checkFirstTimeSetup() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        username = prefs.getString(KEY_USERNAME, null);

        if (username == null) {
            AlertDialog.Builder builder = new AlertDialog.Builder(this);
            builder.setTitle("Enter Your Name");
            EditText input = new EditText(this);
            input.setHint("Your Name");
            builder.setView(input);
            builder.setCancelable(false);
            builder.setPositiveButton("Save", (dialog, which) -> {
                username = input.getText().toString().trim();
                prefs.edit().putString(KEY_USERNAME, username).apply();
            });
            builder.show();
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.M)
    private void checkPermissions() {
        ArrayList<String> permissions = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED)
                permissions.add(Manifest.permission.BLUETOOTH_SCAN);
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) != PackageManager.PERMISSION_GRANTED)
                permissions.add(Manifest.permission.BLUETOOTH_ADVERTISE);
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
                permissions.add(Manifest.permission.BLUETOOTH_CONNECT);
        } else {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH) != PackageManager.PERMISSION_GRANTED)
                permissions.add(Manifest.permission.BLUETOOTH);
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_ADMIN) != PackageManager.PERMISSION_GRANTED)
                permissions.add(Manifest.permission.BLUETOOTH_ADMIN);
        }
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            permissions.add(Manifest.permission.POST_NOTIFICATIONS);

        if (!permissions.isEmpty()) {
            requestPermissions(permissions.toArray(new String[0]), PERMISSIONS_REQUEST_CODE);
        } else {
            startMeshService();
        }
    }

    private void startMeshService() {
        Intent serviceIntent = new Intent(this, MeshService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
    }

    private void attachHopManagerListener() {
        if (HopManager.hopManagerInstance != null) {
            HopManager.hopManagerInstance.setListener(message -> {
                runOnUiThread(() -> {
                    // Add received message
                    messageList.add(0, message);
                    messagesAdapter.notifyItemInserted(0);
                    messagesRecyclerView.scrollToPosition(0);

                    // Update title briefly
                    tvTitle.setText("Received from " + message.sender + ": " + message.payload);

                    // Show notification
                    showNotification(message);
                });
            });

            // Start scanning immediately
            HopManager.hopManagerInstance.start();
        } else {
            // Retry attaching listener after 1 sec if HopManager not ready
            handler.postDelayed(this::attachHopManagerListener, 1000);
        }
    }

    private void sendSOS() {
        if (HopManager.hopManagerInstance != null && username != null) {
            String timestamp = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.getDefault()).format(new Date());
            MeshMessage msg = MeshMessage.createNew(username, 3, "SOS", timestamp);

            // Add sent message to list
            messageList.add(0, msg);
            messagesAdapter.notifyItemInserted(0);
            messagesRecyclerView.scrollToPosition(0);

            // Send via HopManager
            HopManager.hopManagerInstance.sendOutgoing(username, 3, "SOS");

        } else {
            Toast.makeText(this, "Mesh not ready yet", Toast.LENGTH_SHORT).show();
            handler.postDelayed(this::sendSOS, 500);
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            CharSequence name = "NOVA Alerts";
            String description = "Notifications for received alerts";
            int importance = NotificationManager.IMPORTANCE_HIGH;
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, name, importance);
            channel.setDescription(description);

            NotificationManager notificationManager = getSystemService(NotificationManager.class);
            if (notificationManager != null) notificationManager.createNotificationChannel(channel);
        }
    }

    private void showNotification(MeshMessage message) {
        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_sos) // replace with your SOS icon
                .setContentTitle("Alert from " + message.sender)
                .setContentText(message.payload)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true);

        NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (notificationManager != null)
            notificationManager.notify((int) System.currentTimeMillis(), builder.build());
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        boolean allGranted = true;
        for (int result : grantResults) {
            if (result != PackageManager.PERMISSION_GRANTED) {
                allGranted = false;
                break;
            }
        }

        if (allGranted) startMeshService();
        else Toast.makeText(this, "Required permissions not granted.", Toast.LENGTH_LONG).show();
    }
}
