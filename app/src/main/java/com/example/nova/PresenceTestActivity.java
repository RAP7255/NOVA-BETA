package com.example.nova;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.*;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.ParcelUuid;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.nio.charset.StandardCharsets;
import java.util.*;

@RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
public class PresenceTestActivity extends AppCompatActivity {

    private static final String TAG = "PresenceTest";
    private static final int REQ_CODE = 1001;
    private static final int ENABLE_BT_REQUEST = 2001;
    private static final UUID SERVICE_UUID =
            UUID.fromString("0000FEED-0000-1000-8000-00805F9B34FB");

    private BluetoothLeAdvertiser advertiser;
    private BluetoothLeScanner scanner;
    private AdvertiseCallback advertiseCallback;
    private ScanCallback scanCallback;
    private final Handler handler = new Handler();

    private boolean isAdvertising = false;
    private boolean isScanning = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.d(TAG, "🔹 Presence Test Started");

        if (!hasAllPermissions()) {
            requestPermissions();
            return;
        }
        initBluetooth();
    }

    private boolean hasAllPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        } else {
            return ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH) == PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        }
    }

    private void requestPermissions() {
        List<String> perms = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms.add(Manifest.permission.BLUETOOTH_ADVERTISE);
            perms.add(Manifest.permission.BLUETOOTH_SCAN);
            perms.add(Manifest.permission.BLUETOOTH_CONNECT);
        } else {
            perms.add(Manifest.permission.BLUETOOTH);
            perms.add(Manifest.permission.BLUETOOTH_ADMIN);
        }
        perms.add(Manifest.permission.ACCESS_FINE_LOCATION);

        ActivityCompat.requestPermissions(this, perms.toArray(new String[0]), REQ_CODE);
    }

    private void initBluetooth() {
        BluetoothManager manager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = manager.getAdapter();

        if (adapter == null) {
            Log.e(TAG, "❌ Bluetooth not supported on this device.");
            return;
        }

        if (!adapter.isEnabled()) {
            Log.w(TAG, "⚠️ Bluetooth is disabled. Requesting enable...");
            Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                // TODO: Consider calling
                //    ActivityCompat#requestPermissions
                // here to request the missing permissions, and then overriding
                //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
                //                                          int[] grantResults)
                // to handle the case where the user grants the permission. See the documentation
                // for ActivityCompat#requestPermissions for more details.
                return;
            }
            startActivityForResult(enableBtIntent, ENABLE_BT_REQUEST);
            return;
        }

        advertiser = adapter.getBluetoothLeAdvertiser();
        scanner = adapter.getBluetoothLeScanner();

        if (advertiser == null || scanner == null) {
            Log.e(TAG, "❌ BLE Advertiser or Scanner not available.");
            return;
        }

        startAdvertising();
        startScanning();
    }

    private void startAdvertising() {
        if (isAdvertising) return;
        if (!hasAllPermissions()) {
            Log.e(TAG, "🚫 Missing permissions, cannot advertise.");
            return;
        }

        String payload = "PRES:" + Build.MODEL;
        Log.i(TAG, "📡 Advertising payload: " + payload);

        AdvertiseData data = new AdvertiseData.Builder()
                .addServiceUuid(new ParcelUuid(SERVICE_UUID))
                .addServiceData(new ParcelUuid(SERVICE_UUID), payload.getBytes(StandardCharsets.UTF_8))
                .setIncludeDeviceName(false)
                .build();

        AdvertiseSettings settings = new AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                .setConnectable(false)
                .build();

        advertiseCallback = new AdvertiseCallback() {
            @Override
            public void onStartSuccess(AdvertiseSettings settingsInEffect) {
                Log.i(TAG, "✅ Advertising started successfully!");
                isAdvertising = true;
            }

            @Override
            public void onStartFailure(int errorCode) {
                Log.e(TAG, "❌ Advertise failed: " + errorCode);
                isAdvertising = false;
            }
        };

        try {
            advertiser.startAdvertising(settings, data, advertiseCallback);
        } catch (SecurityException e) {
            Log.e(TAG, "🚫 SecurityException (advertise): " + e.getMessage());
        }
    }

    private void stopAdvertising() {
        if (advertiser != null && advertiseCallback != null) {
            try {
                if (hasAllPermissions()) {
                    advertiser.stopAdvertising(advertiseCallback);
                    Log.d(TAG, "🛑 Advertising stopped");
                }
            } catch (SecurityException e) {
                Log.e(TAG, "🚫 Error stopping advertise: " + e.getMessage());
            }
        }
        isAdvertising = false;
    }

    private void startScanning() {
        if (isScanning) return;
        if (!hasAllPermissions()) {
            Log.e(TAG, "🚫 Missing permissions, cannot scan.");
            return;
        }

        ScanFilter filter = new ScanFilter.Builder()
                .setServiceUuid(new ParcelUuid(SERVICE_UUID))
                .build();

        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build();

        scanCallback = new ScanCallback() {
            @Override
            public void onScanResult(int callbackType, ScanResult result) {
                byte[] data = null;

                if (result.getScanRecord() != null) {
                    data = result.getScanRecord().getServiceData(new ParcelUuid(SERVICE_UUID));
                }

                if (data != null) {
                    String message = new String(data, StandardCharsets.UTF_8);

                    if (ActivityCompat.checkSelfPermission(PresenceTestActivity.this, Manifest.permission.BLUETOOTH_CONNECT)
                            != PackageManager.PERMISSION_GRANTED) {
                        Log.w(TAG, "⚠️ BLUETOOTH_CONNECT permission not granted for reading device info");
                        return;
                    }

                    Log.i(TAG, "📩 Received from " +
                            (result.getDevice() != null ? result.getDevice().getName() : "Unknown") +
                            ": " + message);
                }
            }

            @Override
            public void onScanFailed(int errorCode) {
                Log.e(TAG, "❌ Scan failed: " + errorCode);
            }
        };

        try {
            scanner.startScan(Collections.singletonList(filter), settings, scanCallback);
            Log.d(TAG, "🔎 Scanning started...");
            isScanning = true;
        } catch (SecurityException e) {
            Log.e(TAG, "🚫 SecurityException (scan): " + e.getMessage());
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopAdvertising();

        if (scanner != null && scanCallback != null) {
            try {
                if (hasAllPermissions()) {
                    scanner.stopScan(scanCallback);
                    Log.d(TAG, "🛑 Scanning stopped");
                }
            } catch (SecurityException e) {
                Log.e(TAG, "🚫 Error stopping scan: " + e.getMessage());
            }
        }
        isScanning = false;
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_CODE) {
            boolean allGranted = true;
            for (int r : grantResults) {
                if (r != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }
            if (allGranted) initBluetooth();
            else Log.e(TAG, "❌ Missing permissions!");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == ENABLE_BT_REQUEST) {
            initBluetooth(); // Retry initializing once user enables Bluetooth
        }
    }
}
