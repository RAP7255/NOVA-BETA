package com.example.nova;

import android.Manifest;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.widget.Button;
import android.widget.EditText;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private Button sosButton;
    private RecyclerView messagesRecyclerView;
    private MessagesAdapter adapter;
    private List<Message> messages;

    private boolean sosPending = false;
    private Handler sosHandler = new Handler();
    private Runnable sosRunnable;

    private FusedLocationProviderClient fusedLocationClient;

    // Required permissions including POST_NOTIFICATIONS for Android 13+
    private final String[] requiredPermissions = new String[]{
            Manifest.permission.BLUETOOTH,
            Manifest.permission.BLUETOOTH_ADMIN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.POST_NOTIFICATIONS
    };

    private ActivityResultLauncher<String[]> permissionLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        sosButton = findViewById(R.id.sosButton);
        messagesRecyclerView = findViewById(R.id.messagesRecyclerView);

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);

        // Initialize messages list and adapter
        messages = new ArrayList<>();
        adapter = new MessagesAdapter(this, messages); // ✅ pass context
        messagesRecyclerView.setLayoutManager(new LinearLayoutManager(this));
        messagesRecyclerView.setAdapter(adapter);

        // Permission launcher for runtime permissions
        permissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestMultiplePermissions(),
                result -> {
                    boolean granted = true;
                    for (Boolean b : result.values()) {
                        if (!b) {
                            granted = false;
                            break;
                        }
                    }
                    if (granted) {
                        initializeApp();
                        askUserName(); // Ask name after permissions granted
                    } else {
                        Log.d("MainActivity", "Required permissions denied.");
                    }
                });

        checkPermissions();

        // SOS button click
        sosButton.setOnClickListener(v -> handleSosClick());
    }

    // Ask user for their name and save in SharedPreferences
    private void askUserName() {
        SharedPreferences prefs = getSharedPreferences("NovaPrefs", MODE_PRIVATE);
        String name = prefs.getString("user_name", null);

        if (name == null) {
            AlertDialog.Builder builder = new AlertDialog.Builder(this);
            builder.setTitle("Enter Your Name");

            final EditText input = new EditText(this);
            input.setHint("Your name");
            builder.setView(input);

            builder.setCancelable(false);
            builder.setPositiveButton("OK", (dialog, which) -> {
                String userName = input.getText().toString().trim();
                if (!userName.isEmpty()) {
                    prefs.edit().putString("user_name", userName).apply();
                } else {
                    askUserName(); // keep asking if empty
                }
            });
            builder.show();
        }
    }

    // Check required permissions
    private void checkPermissions() {
        boolean allGranted = true;
        for (String perm : requiredPermissions) {
            if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
                allGranted = false;
                break;
            }
        }
        if (allGranted) {
            initializeApp();
            askUserName();
        } else {
            permissionLauncher.launch(requiredPermissions);
        }
    }

    // Initialize app after permissions granted
    private void initializeApp() {
        NotificationHelper.createNotificationChannel(this);
        addDummyMessages();
    }

    private void addDummyMessages() {
        SimpleDateFormat sdf = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm", Locale.getDefault());
        messages.add(new Message("Admin", "Welcome to Nova app!", sdf.format(new Date())));
        messages.add(new Message("User1", "SOS triggered at location XYZ", sdf.format(new Date())));
        adapter.notifyDataSetChanged();
    }

    private void handleSosClick() {
        if (!sosPending) {
            sosPending = true;
            int countdown = 5;

            sosRunnable = new Runnable() {
                int secondsLeft = countdown;

                @Override
                public void run() {
                    if (secondsLeft > 0 && sosPending) {
                        sosButton.setText("Cancel SOS (" + secondsLeft + "s)");
                        secondsLeft--;
                        sosHandler.postDelayed(this, 1000);
                    } else if (sosPending) {
                        sendSos();
                        resetSosButton();
                    }
                }
            };
            sosHandler.post(sosRunnable);
        } else {
            sosHandler.removeCallbacks(sosRunnable);
            resetSosButton();
        }
    }

    private void resetSosButton() {
        sosPending = false;
        sosButton.setText("SOS");
    }

    // Send SOS message with name + timestamp + location
    private void sendSos() {
        SharedPreferences prefs = getSharedPreferences("NovaPrefs", MODE_PRIVATE);
        String userName = prefs.getString("user_name", "Unknown");

        SimpleDateFormat sdf = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss", Locale.getDefault());
        String timestamp = sdf.format(new Date());

        fusedLocationClient.getLastLocation().addOnSuccessListener(location -> {
            String locString;
            String geoUri = null;
            if (location != null) {
                locString = "Lat: " + location.getLatitude() + ", Lon: " + location.getLongitude();
                geoUri = "geo:" + location.getLatitude() + "," + location.getLongitude() +
                        "?q=" + location.getLatitude() + "," + location.getLongitude();
            } else {
                locString = "Location unavailable";
            }

            String sosMessage = userName + " triggered SOS at " + timestamp + "\n" + locString;

            // Add message at top of RecyclerView
            messages.add(0, new Message(userName, sosMessage, timestamp));
            adapter.notifyItemInserted(0);
            messagesRecyclerView.scrollToPosition(0);

            // Trigger notification if permission granted
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                    ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                            == PackageManager.PERMISSION_GRANTED) {
                NotificationHelper.showNotification(this, "SOS Alert!", sosMessage, geoUri);
            }

            Log.d("MainActivity", "SOS sent: " + sosMessage);
        });
    }
}
