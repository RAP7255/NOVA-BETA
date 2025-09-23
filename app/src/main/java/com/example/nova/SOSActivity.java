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
        bleManager = new BLEManager(this,null);

        btnSendSOS.setOnClickListener(v -> {
            bleManager.sendMessage("SOS ALERT!");
            Toast.makeText(SOSActivity.this, "SOS Alert Sent!", Toast.LENGTH_SHORT).show();
        });
    }
}
