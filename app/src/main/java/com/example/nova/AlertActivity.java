package com.example.nova;

import android.os.Bundle;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;

public class AlertActivity extends AppCompatActivity {
    private static TextView txtAlert;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_alert);

        txtAlert = findViewById(R.id.txtAlert);
    }

    public static void setAlertMessage(String message) {
        if (txtAlert != null) {
            txtAlert.setText(message);
        }
    }
}
