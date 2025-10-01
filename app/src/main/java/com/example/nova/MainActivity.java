package com.example.nova;

import android.Manifest;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.nova.model.MeshMessage;
import com.example.nova.service.MeshService;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";
    private static final String PREFS_NAME = "nova_prefs";
    private static final String KEY_USERNAME = "username";
    private static final int PERMISSIONS_REQUEST_CODE = 101;

    private Button btnSOS;
    private ImageButton btnCall, btnLocation, btnContacts;
    private TextView tvTitle, tvSubtitle;
    private RecyclerView messagesRecyclerView;

    private MessagesAdapter messagesAdapter;
    private final ArrayList<MeshMessage> messagesList = new ArrayList<>();
    private String username;

    private boolean sosPending = false;
    private Handler sosHandler = new Handler(Looper.getMainLooper());
    private Runnable sosRunnable;

    private FusedLocationProviderClient fusedLocationClient;

    private final BroadcastReceiver meshReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if ("MESH_MESSAGE".equals(intent.getAction())) {
                String payload = intent.getStringExtra("payload");
                String sender = intent.getStringExtra("sender");
                String timestamp = intent.getStringExtra("timestamp");

                if (payload != null && sender != null) {
                    if (timestamp == null || timestamp.isEmpty()) {
                        timestamp = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.getDefault())
                                .format(new Date());
                    }
                    MeshMessage msg = MeshMessage.createNew(sender, 3, payload, timestamp);
                    addMessage(msg);
                }
            }
        }
    };

    @RequiresApi(api = Build.VERSION_CODES.M)
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Link XML views
        btnSOS = findViewById(R.id.btnSOS);
        btnCall = findViewById(R.id.btnCall);
        btnLocation = findViewById(R.id.btnLocation);
        btnContacts = findViewById(R.id.btnContacts);
        tvTitle = findViewById(R.id.tvTitle);
        tvSubtitle = findViewById(R.id.tvSubtitle);
        messagesRecyclerView = findViewById(R.id.messagesRecyclerView);

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);

        // Setup RecyclerView
        messagesAdapter = new MessagesAdapter(this, messagesList);
        messagesRecyclerView.setLayoutManager(new LinearLayoutManager(this));
        messagesRecyclerView.setAdapter(messagesAdapter);

        // Check permissions
        checkPermissions();

        // First-time username setup
        checkFirstTimeSetup();

        // Start MeshService safely
        Intent serviceIntent = new Intent(this, MeshService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }

        // SOS button with countdown/cancel
        btnSOS.setOnClickListener(v -> handleSOSButton());

        // Other buttons (logs for now)
        btnCall.setOnClickListener(v -> Log.d(TAG, "Call clicked"));
        btnLocation.setOnClickListener(v -> Log.d(TAG, "Location clicked"));
        btnContacts.setOnClickListener(v -> Log.d(TAG, "Contacts clicked"));
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
        }
    }

    private void handleSOSButton() {
        if (!sosPending) {
            sosPending = true;
            btnSOS.setText("Cancel SOS (5)");
            final int[] countdown = {5};

            sosRunnable = new Runnable() {
                @Override
                public void run() {
                    if (countdown[0] > 0) {
                        btnSOS.setText("Cancel SOS (" + countdown[0] + ")");
                        countdown[0]--;
                        sosHandler.postDelayed(this, 1000);
                    } else {
                        sosPending = false;
                        btnSOS.setText("SOS");
                        sendSOSMessage();
                    }
                }
            };
            sosHandler.post(sosRunnable);
        } else {
            // Cancel
            sosPending = false;
            btnSOS.setText("SOS");
            sosHandler.removeCallbacks(sosRunnable);
            Toast.makeText(this, "SOS cancelled", Toast.LENGTH_SHORT).show();
        }
    }

    private void sendSOSMessage() {
        String timestamp = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.getDefault()).format(new Date());
        String minimalPayload = "SOS"; // Minimal message for testing

        try {
            if (MeshService.hopManagerInstance != null) {
                // Send minimal SOS message via hop manager
                MeshService.hopManagerInstance.sendOutgoing(username, 3, minimalPayload);

                // Show locally in RecyclerView
                MeshMessage localMsg = MeshMessage.createNew(username, 3, minimalPayload, timestamp);
                addMessage(localMsg);
            } else {
                Log.w(TAG, "HopManager instance is null. Cannot send SOS.");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error sending minimal SOS message", e);
        }
    }


    private void addMessage(MeshMessage msg) {
        if (msg == null) return;
        messagesList.add(0, msg);
        messagesAdapter.notifyItemInserted(0);
        messagesRecyclerView.scrollToPosition(0);
    }

    @Override
    protected void onResume() {
        super.onResume();
        registerReceiver(meshReceiver, new IntentFilter("MESH_MESSAGE"));
    }

    @Override
    protected void onPause() {
        super.onPause();
        try {
            unregisterReceiver(meshReceiver);
        } catch (IllegalArgumentException e) {
            Log.w(TAG, "Mesh receiver already unregistered", e);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        // Optional: handle permission denials
    }
}
