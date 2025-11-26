package com.example.nova.ble;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import androidx.core.content.ContextCompat;

import com.example.nova.model.MeshMessage;
import com.example.nova.model.MessageCache;

public class BLEManager {

    private static final String TAG = "BLE-MeshUser";

    private final Context context;
    private final BluetoothAdapter bluetoothAdapter;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private final HopManager hopManager;
    private final BluetoothScanner btScanner;
    private final BluetoothAdvertiser btAdvertiser;

    private boolean isScanning = false;

    public interface OnMessageReceivedListener {
        void onMessageReceived(String decryptedPayload);
    }

    private final OnMessageReceivedListener listener;

    // -------------------------------------------------------------
    // Constructor
    // -------------------------------------------------------------
    public BLEManager(Context ctx, OnMessageReceivedListener listener) {

        this.context = ctx.getApplicationContext();
        this.listener = listener;

        BluetoothManager btManager =
                (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);

        this.bluetoothAdapter = btManager != null ? btManager.getAdapter() : null;

        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
            Toast.makeText(ctx, "Bluetooth disabled!", Toast.LENGTH_SHORT).show();
            Log.e(TAG, "Bluetooth not enabled or unavailable");
        }

        btAdvertiser = new BluetoothAdvertiser(context);

        // Scanner sends headers to HopManager internally
        btScanner = new BluetoothScanner(context, null);

        hopManager = new HopManager(
                context,
                new MessageCache(500),
                btAdvertiser,
                btScanner,
                this::onFullDecryptedMessage   // HopListener callback
        );
    }

    // -------------------------------------------------------------
    // SEND message
    // -------------------------------------------------------------
    public void sendSecureMessage(String senderName, String content) {

        if (!hasPermission(Manifest.permission.BLUETOOTH_ADVERTISE)) {
            Log.w(TAG, "Missing BLUETOOTH_ADVERTISE permission");
            return;
        }

        Log.i(TAG, "Sending secure mesh message → " + content);

        hopManager.sendOutgoing(senderName, 0, content);
    }

    // -------------------------------------------------------------
    // INTERNAL: decrypt callback
    // -------------------------------------------------------------
    private void onFullDecryptedMessage(MeshMessage m) {

        Log.i(TAG, "Decrypted: " + m.payload);

        if (listener != null)
            listener.onMessageReceived(m.payload);
    }

    // -------------------------------------------------------------
    // Scanner control
    // -------------------------------------------------------------
    public void startScanning() {
        if (!hasPermission(Manifest.permission.BLUETOOTH_SCAN)) {
            Log.w(TAG, "Missing BLUETOOTH_SCAN");
            return;
        }

        if (!isScanning) {
            btScanner.startScan();
            isScanning = true;
        }
    }

    public void stopScanning() {
        if (isScanning) {
            btScanner.stopScan();
            isScanning = false;
        }
    }

    public void shutdown() {
        stopScanning();
        Log.d(TAG, "BLE Manager shutdown completed");
    }

    // -------------------------------------------------------------
    // Utility
    // -------------------------------------------------------------
    private boolean hasPermission(String perm) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true;
        return ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED;
    }
}
