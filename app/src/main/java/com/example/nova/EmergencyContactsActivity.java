package com.example.nova;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;

public class EmergencyContactsActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_emergency_contacts);

        findViewById(R.id.callPolice).setOnClickListener(v -> dial("100"));
        findViewById(R.id.callAmbulance).setOnClickListener(v -> dial("108"));
        findViewById(R.id.callFire).setOnClickListener(v -> dial("101"));
        findViewById(R.id.callAdmin).setOnClickListener(v -> dial("9146405670"));
        findViewById(R.id.callResponsible).setOnClickListener(v -> dial("8010551367"));
    }

    private void dial(String number) {
        Intent intent = new Intent(Intent.ACTION_DIAL);
        intent.setData(Uri.parse("tel:" + number));
        startActivity(intent);
    }
}
