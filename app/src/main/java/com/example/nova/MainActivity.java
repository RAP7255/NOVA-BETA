package com.example.nova;

import android.Manifest;
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

import com.example.nova.ble.HopManager;
import com.example.nova.service.MeshService;
import com.example.nova.model.MeshMessage;

import java.util.ArrayList;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private static final String PREFS_NAME = "nova_prefs";
    private static final String KEY_USERNAME = "username";
    private static final int PERMISSIONS_REQUEST_CODE = 101;

    private Button btnSOS;
    private TextView tvTitle;

    private String username;

    private final Handler handler = new Handler(Looper.getMainLooper());

    @RequiresApi(api = Build.VERSION_CODES.M)
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        btnSOS = findViewById(R.id.btnSOS);
        tvTitle = findViewById(R.id.tvTitle);

        checkFirstTimeSetup();
        checkPermissions();

        btnSOS.setOnClickListener(v -> sendSOS());
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

    @Override
    protected void onResume() {
        super.onResume();

        // Attach listener dynamically
        if (HopManager.hopManagerInstance != null) {
            HopManager.hopManagerInstance.setListener(message -> {
                runOnUiThread(() -> {
                    tvTitle.setText("Received from " + message.sender + ": " + message.payload);
                });
            });
            // Start scanning if not already
            HopManager.hopManagerInstance.start();
        }
    }

    private void sendSOS() {
        if (HopManager.hopManagerInstance != null && username != null) {
            HopManager.hopManagerInstance.sendOutgoing(username, 3, "SOS");
        } else {
            Toast.makeText(this, "Mesh not ready yet", Toast.LENGTH_SHORT).show();
            handler.postDelayed(this::sendSOS, 500);
        }
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
