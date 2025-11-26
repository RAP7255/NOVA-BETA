package com.example.nova;

import android.os.Bundle;
import android.widget.Button;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;

import com.example.nova.ble.BLEManager;

public class SOSActivity extends AppCompatActivity {

    Button btnSendSOS;
    BLEManager bleManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_sos);

        btnSendSOS = findViewById(R.id.btnSendSOS);

        // Initialize BLE Manager (listener receives decrypted AES messages)
        bleManager = new BLEManager(this, message -> {
            Toast.makeText(SOSActivity.this,
                    "🚨 Received: " + message,
                    Toast.LENGTH_LONG).show();
        });

        // Send SOS on button click
        btnSendSOS.setOnClickListener(v -> {
            bleManager.sendSecureMessage("User", "SOS ALERT!");
            Toast.makeText(SOSActivity.this, "📡 SOS Alert Sent!", Toast.LENGTH_SHORT).show();
        });

        // Start scanning for mesh messages
        bleManager.startScanning();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        bleManager.shutdown();
    }
}
