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
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.nova.ble.HopManager;
import com.example.nova.model.MeshMessage;
import com.example.nova.service.BLEForegroundService;
import com.example.nova.service.MeshService;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;

import java.util.ArrayList;

public class MainActivity extends AppCompatActivity {

    private static final String PREFS = "nova_prefs";
    private static final String KEY_USERNAME = "username";
    private static final String KEY_DARK_MODE = "isDarkMode";
    private static final int PERMISSION_CODE = 2001;
    private static final String CHANNEL_ID = "nova_alerts";

    private Button btnSOS;
    private Switch themeSwitch;
    private TextView tvTitle;
    private RecyclerView recycler;

    private ImageButton btnCall, btnLocation, btnContacts, btnInfo;

    private SharedPreferences sharedPrefs;
    private String username;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private MessagesAdapter adapter;

    // ViewModel
    private MessagesViewModel viewModel;
    private ArrayList<MeshMessage> messageList;

    private boolean hopListenerAttached = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {

        sharedPrefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        boolean isDark = sharedPrefs.getBoolean(KEY_DARK_MODE, false);

        AppCompatDelegate.setDefaultNightMode(
                isDark ? AppCompatDelegate.MODE_NIGHT_YES : AppCompatDelegate.MODE_NIGHT_NO
        );

        setTheme(isDark ? R.style.Theme_NovaApp_Night : R.style.Theme_NovaApp);

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // ViewModel
        viewModel = new ViewModelProvider(this).get(MessagesViewModel.class);
        messageList = viewModel.messageList;

        // Bind UI
        btnSOS = findViewById(R.id.btnSOS);
        tvTitle = findViewById(R.id.tvTitle);
        recycler = findViewById(R.id.messagesRecyclerView);
        themeSwitch = findViewById(R.id.theme_switch);

        btnCall = findViewById(R.id.btnCall);
        btnLocation = findViewById(R.id.btnLocation);
        btnContacts = findViewById(R.id.btnContacts);
        btnInfo = findViewById(R.id.btnInfo);   // NEW

        themeSwitch.setChecked(isDark);

        adapter = new MessagesAdapter(this, messageList);
        recycler.setAdapter(adapter);
        recycler.setLayoutManager(new LinearLayoutManager(this));

        startSosPulseAnimation();
        setupThemeSwitchListener();

        requestAllPermissions();
        createNotificationChannel();

        // SOS Send
        btnSOS.setOnClickListener(v -> sendSOS());

        // Info Button → App About Dialog
        btnInfo.setOnClickListener(v -> showAppInfoDialog());

        // CALL MENU
        btnCall.setOnClickListener(v -> {
            AlertDialog.Builder builder = new AlertDialog.Builder(MainActivity.this);
            builder.setTitle("Emergency Services")
                    .setItems(new CharSequence[]{"Police (100)", "Ambulance (108)", "Fire Brigade (101)"}, (dialog, which) -> {

                        String number = "";
                        switch (which) {
                            case 0: number = "100"; break;
                            case 1: number = "108"; break;
                            case 2: number = "101"; break;
                        }

                        Intent intent = new Intent(Intent.ACTION_DIAL);
                        intent.setData(Uri.parse("tel:" + number));
                        startActivity(intent);
                    });
            builder.show();
        });

        // LOCATION SEND
        btnLocation.setOnClickListener(v -> {

            FusedLocationProviderClient fusedLocationClient =
                    LocationServices.getFusedLocationProviderClient(MainActivity.this);

            if (ActivityCompat.checkSelfPermission(
                    MainActivity.this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {

                Toast.makeText(MainActivity.this, "Location permission required", Toast.LENGTH_SHORT).show();
                return;
            }

            fusedLocationClient.getLastLocation()
                    .addOnSuccessListener(location -> {
                        if (location != null) {

                            double lat = location.getLatitude();
                            double lon = location.getLongitude();

                            String text = "Location: Lat=" + lat + " Lon=" + lon;

                            if (HopManager.hopManagerInstance != null && username != null) {
                                HopManager.hopManagerInstance.sendOutgoing(username, 0, text);
                            }

                            Toast.makeText(MainActivity.this, text, Toast.LENGTH_LONG).show();

                        } else {
                            Toast.makeText(MainActivity.this, "Unable to fetch location", Toast.LENGTH_SHORT).show();
                        }
                    });
        });

        // CONTACTS PAGE
        btnContacts.setOnClickListener(v -> {
            Intent i = new Intent(MainActivity.this, EmergencyContactsActivity.class);
            startActivity(i);
        });
    }


    // ---------------------- INFO DIALOG ----------------------
    private void showAppInfoDialog() {

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("About NOVA");

        String msg =
                "🔹 Version: 1.0.0\n" +
                        "🔹 Vision: Offline emergency communication for everyone.\n" +
                        "🔹 Developer: Team NOVA\n" +
                        "🔹 Website: https://teamnova.example\n" +
                        "🔹 Contribute: https://github.com/teamnova";

        builder.setMessage(msg);

        builder.setPositiveButton("OK", null);
        builder.show();
    }



    // ---------------------- THEME SWITCH ----------------------
    private void setupThemeSwitchListener() {
        themeSwitch.setOnCheckedChangeListener((btn, isChecked) -> {
            sharedPrefs.edit().putBoolean(KEY_DARK_MODE, isChecked).apply();
            AppCompatDelegate.setDefaultNightMode(
                    isChecked ? AppCompatDelegate.MODE_NIGHT_YES : AppCompatDelegate.MODE_NIGHT_NO
            );
            recreate();
        });
    }


    // ---------------------- ANIMATION ----------------------
    private void startSosPulseAnimation() {
        ObjectAnimator sx = ObjectAnimator.ofFloat(btnSOS, "scaleX", 1.0f, 1.08f);
        ObjectAnimator sy = ObjectAnimator.ofFloat(btnSOS, "scaleY", 1.0f, 1.08f);

        sx.setRepeatCount(ValueAnimator.INFINITE);
        sy.setRepeatCount(ValueAnimator.INFINITE);
        sx.setRepeatMode(ValueAnimator.REVERSE);
        sy.setRepeatMode(ValueAnimator.REVERSE);
        sx.setDuration(700);
        sy.setDuration(700);

        AnimatorSet set = new AnimatorSet();
        set.playTogether(sx, sy);
        set.setInterpolator(new AccelerateDecelerateInterpolator());
        set.start();
    }


    // ---------------------- PERMISSIONS ----------------------
    private void requestAllPermissions() {
        ArrayList<String> list = new ArrayList<>();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (check(Manifest.permission.BLUETOOTH_SCAN)) list.add(Manifest.permission.BLUETOOTH_SCAN);
            if (check(Manifest.permission.BLUETOOTH_CONNECT)) list.add(Manifest.permission.BLUETOOTH_CONNECT);
            if (check(Manifest.permission.BLUETOOTH_ADVERTISE)) list.add(Manifest.permission.BLUETOOTH_ADVERTISE);
        }

        if (check(Manifest.permission.ACCESS_FINE_LOCATION))
            list.add(Manifest.permission.ACCESS_FINE_LOCATION);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            if (check(Manifest.permission.FOREGROUND_SERVICE))
                list.add(Manifest.permission.FOREGROUND_SERVICE);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (check(Manifest.permission.POST_NOTIFICATIONS))
                list.add(Manifest.permission.POST_NOTIFICATIONS);
        }

        if (!list.isEmpty()) {
            ActivityCompat.requestPermissions(
                    this, list.toArray(new String[0]), PERMISSION_CODE
            );
        } else {
            initAfterPermissions();
        }
    }

    private boolean check(String perm) {
        return ActivityCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED;
    }


    // ---------------------- PERMISSION RESULT ----------------------
    @Override
    public void onRequestPermissionsResult(int code, @NonNull String[] perm, @NonNull int[] res) {
        super.onRequestPermissionsResult(code, perm, res);

        if (code != PERMISSION_CODE) return;

        for (int r : res) {
            if (r != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "All permissions required", Toast.LENGTH_SHORT).show();
                return;
            }
        }

        initAfterPermissions();
    }


    // ---------------------- INIT AFTER PERMISSIONS ----------------------
    private void initAfterPermissions() {
        if (!isBluetoothOn()) {
            Toast.makeText(this, "Enable Bluetooth", Toast.LENGTH_LONG).show();
            return;
        }

        askNameIfRequired();
        startBleForegroundService();
        handler.postDelayed(this::startMeshService, 300);
    }


    private boolean isBluetoothOn() {
        BluetoothAdapter ad = BluetoothAdapter.getDefaultAdapter();
        return ad != null && ad.isEnabled();
    }


    private void askNameIfRequired() {
        username = sharedPrefs.getString(KEY_USERNAME, null);
        if (username != null) return;

        AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setTitle("Enter Your Name");

        EditText e = new EditText(this);
        e.setHint("Your Name");
        b.setView(e);
        b.setCancelable(false);

        b.setPositiveButton("Save", (d, w) -> {
            username = e.getText().toString().trim();
            sharedPrefs.edit().putString(KEY_USERNAME, username).apply();
        });
        b.show();
    }


    private void startBleForegroundService() {
        try {
            Intent svc = new Intent(this, BLEForegroundService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                startForegroundService(svc);
            else startService(svc);

        } catch (Exception e) {
            Log.e("MainActivity", "BLEForegroundService start failed: " + e);
        }
    }


    private void startMeshService() {
        Intent i = new Intent(this, MeshService.class);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            startForegroundService(i);
        else startService(i);

        waitForHop();
    }


    private void waitForHop() {
        handler.postDelayed(() -> {

            if (HopManager.hopManagerInstance == null) {
                waitForHop();
                return;
            }

            if (!hopListenerAttached) {
                attachHopListener();
                hopListenerAttached = true;
            }

        }, 200);
    }


    private void attachHopListener() {
        if (HopManager.hopManagerInstance == null) return;

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


    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;

        NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "NOVA Alerts", NotificationManager.IMPORTANCE_HIGH
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
