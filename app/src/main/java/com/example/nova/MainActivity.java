package com.example.nova;

import android.Manifest;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.bluetooth.BluetoothAdapter;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.nova.ble.HopManager;
import com.example.nova.model.MeshMessage;
import com.example.nova.service.MeshService;

import java.util.ArrayList;

public class  MainActivity extends AppCompatActivity {

    private static final String PREFS = "nova_prefs";
    private static final String KEY_USERNAME = "username";
    // ADDED for Theme Switching
    private static final String KEY_DARK_MODE = "isDarkMode";

    private static final int PERMISSION_CODE = 2001;
    private static final String CHANNEL_ID = "nova_alerts";

    private Button btnSOS;
    private TextView tvTitle;
    private RecyclerView recycler;
    private Switch themeSwitch; // ADDED

    private String username;
    private SharedPreferences sharedPrefs; // ADDED
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final ArrayList<MeshMessage> messageList = new ArrayList<>();
    // NOTE: Assuming MessagesAdapter is defined elsewhere.
    private MessagesAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {

        // --- START: Theme Initialization Logic (MOVED TO TOP) ---
        // 1. Load saved theme preference
        sharedPrefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        boolean isDarkMode = sharedPrefs.getBoolean(KEY_DARK_MODE, false);

        // Apply theme before super.onCreate() is called
        if (isDarkMode) {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
            // This relies on the custom theme being defined in res/values/themes.xml
            setTheme(R.style.Theme_NovaApp_Night);
        } else {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
            // This relies on the custom theme being defined in res/values/themes.xml
            setTheme(R.style.Theme_NovaApp);
        }
        // --- END: Theme Initialization Logic ---

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        btnSOS = findViewById(R.id.btnSOS);
        tvTitle = findViewById(R.id.tvTitle);
        recycler = findViewById(R.id.messagesRecyclerView);
        themeSwitch = findViewById(R.id.theme_switch); // ADDED

        adapter = new MessagesAdapter(this, messageList);
        recycler.setLayoutManager(new LinearLayoutManager(this));
        recycler.setAdapter(adapter);

        // --- START: New UI Logic ---
        themeSwitch.setChecked(isDarkMode);
        startSosPulseAnimation();
        setupThemeSwitchListener();
        // --- END: New UI Logic ---

        requestAllPermissions();
        createNotificationChannel();

        btnSOS.setOnClickListener(v -> sendSOS());

        // start user BLE foreground service to keep GATT alive on aggressive OEMs
        try {
            Intent svc = new Intent(this, com.example.nova.service.BLEForegroundService.class);
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                startForegroundService(svc);
            } else {
                startService(svc);
            }
        } catch (Exception e) {
            Log.w("MainActivity", "Failed to start foreground service: " + e.getMessage());
        }

    }

    // --- START: New UI Methods ---

    /**
     * Sets up the listener for the Dark/Light mode toggle switch.
     */
    private void setupThemeSwitchListener() {
        themeSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            // 1. Save the new preference
            sharedPrefs.edit().putBoolean(KEY_DARK_MODE, isChecked).apply();

            // 2. Set the new theme mode
            if (isChecked) {
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
            } else {
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
            }
            // 3. Recreate the activity for the theme change to take effect
            recreate();
        });
    }

    /**
     * Implements the smooth, infinite pulsing animation for the SOS button.
     */
    private void startSosPulseAnimation() {
        // Animate the scale of the button (out to 1.08 and back to 1.0)
        ObjectAnimator scaleX = ObjectAnimator.ofFloat(btnSOS, "scaleX", 1.0f, 1.08f);
        ObjectAnimator scaleY = ObjectAnimator.ofFloat(btnSOS, "scaleY", 1.0f, 1.08f);

        long duration = 700;

        // FIX: Set repeat properties on individual animators, not the AnimatorSet
        scaleX.setRepeatCount(ValueAnimator.INFINITE);
        scaleX.setRepeatMode(ValueAnimator.REVERSE);
        scaleX.setDuration(duration);

        scaleY.setRepeatCount(ValueAnimator.INFINITE);
        scaleY.setRepeatMode(ValueAnimator.REVERSE);
        scaleY.setDuration(duration);

        AnimatorSet pulsingSet = new AnimatorSet();
        pulsingSet.playTogether(scaleX, scaleY);
        // Duration and Interpolator can be set on the set or individual animators
        pulsingSet.setInterpolator(new AccelerateDecelerateInterpolator());

        pulsingSet.start();
    }
    // --- END: New UI Methods ---


    // --------------------------------------------------------------------
    // PERMISSIONS (No Change)
    // --------------------------------------------------------------------
    private void requestAllPermissions() {
        ArrayList<String> list = new ArrayList<>();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {

            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
                    != PackageManager.PERMISSION_GRANTED)
                list.add(Manifest.permission.BLUETOOTH_SCAN);

            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADVERTISE)
                    != PackageManager.PERMISSION_GRANTED)
                list.add(Manifest.permission.BLUETOOTH_ADVERTISE);

            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED)
                list.add(Manifest.permission.BLUETOOTH_CONNECT);
        }

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED)
            list.add(Manifest.permission.ACCESS_FINE_LOCATION);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED)
            list.add(Manifest.permission.POST_NOTIFICATIONS);

        if (!list.isEmpty()) {
            ActivityCompat.requestPermissions(
                    this,
                    list.toArray(new String[0]),
                    PERMISSION_CODE
            );
        } else {
            initAfterPermissions();
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, @NonNull String[] perm, @NonNull int[] res) {
        super.onRequestPermissionsResult(code, perm, res);

        if (code != PERMISSION_CODE) return;

        for (int r : res) {
            if (r != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "All permissions required", Toast.LENGTH_LONG).show();
                return;
            }
        }
        initAfterPermissions();
    }

    private void initAfterPermissions() {
        if (!isBluetoothOn()) {
            Toast.makeText(this, "Enable Bluetooth", Toast.LENGTH_LONG).show();
            return;
        }

        askNameIfRequired();
        startMeshService();
    }

    private boolean isBluetoothOn() {
        BluetoothAdapter ad = BluetoothAdapter.getDefaultAdapter();
        return ad != null && ad.isEnabled();
    }

    // --------------------------------------------------------------------
    // USERNAME (Slight change to use sharedPrefs field)
    // --------------------------------------------------------------------
    private void askNameIfRequired() {
        username = sharedPrefs.getString(KEY_USERNAME, null); // Using class field sharedPrefs

        if (username != null) return;

        AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setTitle("Enter Your Name");

        EditText input = new EditText(this);
        input.setHint("Your Name");
        b.setView(input);
        b.setCancelable(false);

        b.setPositiveButton("Save", (d, w) -> {
            username = input.getText().toString().trim();
            sharedPrefs.edit().putString(KEY_USERNAME, username).apply();
        });

        b.show();
    }

    // --------------------------------------------------------------------
    // SERVICE START (No Change)
    // --------------------------------------------------------------------
    private void startMeshService() {
        Intent i = new Intent(this, MeshService.class);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            startForegroundService(i);
        else
            startService(i);

        waitForHop();
    }

    private void waitForHop() {
        handler.postDelayed(() -> {

            if (HopManager.hopManagerInstance == null) {
                waitForHop();
                return;
            }

            attachHopListener();
            HopManager.hopManagerInstance.start();

        }, 200);
    }

    // --------------------------------------------------------------------
    // HOP LISTENER (No Change)
    // --------------------------------------------------------------------
    private void attachHopListener() {
        HopManager.hopManagerInstance.setListener(message -> {

            runOnUiThread(() -> {
                messageList.add(0, message);
                adapter.notifyItemInserted(0);
                recycler.scrollToPosition(0);

                tvTitle.setText("Received from " + message.sender + " : " + message.payload);
                showNotification(message);
            });

        });
    }

    // --------------------------------------------------------------------
    // SEND SOS (No Change)
    // --------------------------------------------------------------------
    private void sendSOS() {
        if (HopManager.hopManagerInstance == null) {
            Toast.makeText(this, "Mesh not ready", Toast.LENGTH_SHORT).show();
            return;
        }
        if (username == null) {
            Toast.makeText(this, "Username missing", Toast.LENGTH_SHORT).show();
            return;
        }

        MeshMessage msg = HopManager.hopManagerInstance.sendOutgoing(username, 0, "SOS");

        if (msg == null) {
            Toast.makeText(this, "Send failed", Toast.LENGTH_SHORT).show();
            return;
        }

        messageList.add(0, msg);
        adapter.notifyItemInserted(0);
        recycler.scrollToPosition(0);
    }

    // --------------------------------------------------------------------
    // NOTIFICATIONS (No Change)
    // --------------------------------------------------------------------
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;

        NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID,
                "NOVA Alerts",
                NotificationManager.IMPORTANCE_HIGH
        );

        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(ch);
    }

    private void showNotification(MeshMessage msg) {

        Intent i = new Intent(this, MainActivity.class);

        PendingIntent pi = PendingIntent.getActivity(
                this,
                0,
                i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_sos)
                .setContentTitle("Alert from " + msg.sender)
                .setContentText(msg.payload)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pi);

        NotificationManager nm =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);

        nm.notify((int) System.currentTimeMillis(), b.build());
    }
}