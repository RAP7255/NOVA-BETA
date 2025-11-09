package com.example.nova;

import android.os.Bundle;
import android.widget.Button;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import com.example.nova.ble.BLEManager;
import com.example.nova.ble.OnMessageReceivedListener;

public class SOSActivity extends AppCompatActivity {
    Button btnSendSOS;
    BLEManager bleManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_sos);

        btnSendSOS = findViewById(R.id.btnSendSOS);

        bleManager = new BLEManager(this, new OnMessageReceivedListener() {
            @Override
            public void onMessageReceived(String message) {
                Toast.makeText(SOSActivity.this, "🚨 Received: " + message, Toast.LENGTH_LONG).show();
            }
        });

        btnSendSOS.setOnClickListener(v -> {
            bleManager.sendMessage("SOS ALERT!");
            Toast.makeText(SOSActivity.this, "📡 SOS Alert Sent!", Toast.LENGTH_SHORT).show();
        });

        // Optionally also scan for alerts
        bleManager.startScan();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        bleManager.shutdown();
    }
}
