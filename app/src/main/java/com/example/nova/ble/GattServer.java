package com.example.nova.ble;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;

import androidx.core.content.ContextCompat;

import java.nio.ByteBuffer;

/**
 * Lightweight GATT server that supports:
 *  - Client writes 8-byte messageId to CHAR_REQUEST_MESSAGE (WRITE or WRITE_NO_RESPONSE)
 *  - Client reads CHAR_FETCH_CIPHERTEXT to get the ciphertext bytes
 *
 * All UUIDs come from GattConstants.
 */
@SuppressLint("MissingPermission")
public class GattServer {

    private static final String TAG = "GattServer";

    private final Context context;
    private BluetoothGattServer gattServer;

    private BluetoothGattCharacteristic requestChar;
    private BluetoothGattCharacteristic responseChar;

    private long requestedMessageId = -1L;

    public GattServer(Context ctx) {
        this.context = ctx.getApplicationContext();
    }

    private boolean hasConnectPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return ContextCompat.checkSelfPermission(
                    context, Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    public void start() {

        if (!hasConnectPermission()) {
            Log.w(TAG, "Missing BLUETOOTH_CONNECT — cannot start GATT server");
            return;
        }

        BluetoothManager btManager =
                (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);

        if (btManager == null) {
            Log.e(TAG, "BluetoothManager unavailable");
            return;
        }

        try {
            gattServer = btManager.openGattServer(context, serverCallback);
        } catch (SecurityException se) {
            Log.e(TAG, "openGattServer SecurityException: " + se.getMessage());
            return;
        }

        if (gattServer == null) {
            Log.e(TAG, "openGattServer returned null");
            return;
        }

        BluetoothGattService svc = new BluetoothGattService(
                GattConstants.SERVICE_MESH_GATT,
                BluetoothGattService.SERVICE_TYPE_PRIMARY
        );

        requestChar = new BluetoothGattCharacteristic(
                GattConstants.CHAR_REQUEST_MESSAGE,
                BluetoothGattCharacteristic.PROPERTY_WRITE | BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
                BluetoothGattCharacteristic.PERMISSION_WRITE
        );

        responseChar = new BluetoothGattCharacteristic(
                GattConstants.CHAR_FETCH_CIPHERTEXT,
                BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_READ
        );

        svc.addCharacteristic(requestChar);
        svc.addCharacteristic(responseChar);

        boolean added = gattServer.addService(svc);
        Log.i(TAG, "GATT Server started (service added=" + added + ")");
    }

    public void stop() {
        try {
            if (gattServer != null) {
                gattServer.close();
                gattServer = null;
                Log.i(TAG, "GATT server stopped");
            }
        } catch (Exception e) {
            Log.w(TAG, "stop() error: " + e.getMessage());
        }
    }

    private void safeSend(BluetoothDevice dev, int reqId, int status, byte[] value, boolean needed) {
        if (!needed) return;
        if (!hasConnectPermission()) return;
        if (gattServer == null) return;
        try {
            gattServer.sendResponse(dev, reqId, status, 0, value);
        } catch (Exception e) {
            Log.w(TAG, "sendResponse failed: " + e.getMessage());
        }
    }

    private final BluetoothGattServerCallback serverCallback = new BluetoothGattServerCallback() {

        @Override
        public void onCharacteristicWriteRequest(BluetoothDevice device,
                                                 int requestId,
                                                 BluetoothGattCharacteristic characteristic,
                                                 boolean preparedWrite,
                                                 boolean responseNeeded,
                                                 int offset,
                                                 byte[] value) {

            if (!hasConnectPermission()) {
                safeSend(device, requestId, BluetoothGatt.GATT_FAILURE, null, responseNeeded);
                return;
            }

            if (characteristic.getUuid().equals(GattConstants.CHAR_REQUEST_MESSAGE)) {

                if (value == null || value.length < 8) {
                    safeSend(device, requestId, BluetoothGatt.GATT_INVALID_ATTRIBUTE_LENGTH, null, responseNeeded);
                    return;
                }

                try {
                    requestedMessageId = ByteBuffer.wrap(value).getLong();
                    Log.d(TAG, "GATT request -> msgId=" + requestedMessageId);
                } catch (Exception ex) {
                    Log.w(TAG, "Bad request value", ex);
                    safeSend(device, requestId, BluetoothGatt.GATT_FAILURE, null, responseNeeded);
                    return;
                }
            }

            safeSend(device, requestId, BluetoothGatt.GATT_SUCCESS, null, responseNeeded);
        }

        @Override
        public void onCharacteristicReadRequest(BluetoothDevice device,
                                                int requestId,
                                                int offset,
                                                BluetoothGattCharacteristic characteristic) {

            if (!hasConnectPermission()) {
                safeSend(device, requestId, BluetoothGatt.GATT_FAILURE, null, true);
                return;
            }

            if (!characteristic.getUuid().equals(GattConstants.CHAR_FETCH_CIPHERTEXT)) {
                safeSend(device, requestId, BluetoothGatt.GATT_FAILURE, null, true);
                return;
            }

            byte[] payload = null;
            try {
                if (HopManager.hopManagerInstance != null) {
                    // safe call — HopManager must expose getStoredCiphertext(long)
                    payload = HopManager.hopManagerInstance.getStoredCiphertext(requestedMessageId);
                } else {
                    Log.w(TAG, "HopManager instance null — cannot fetch ciphertext");
                }
            } catch (Exception e) {
                Log.w(TAG, "Error getting payload: " + e.getMessage());
            }

            if (payload == null || payload.length == 0) {
                safeSend(device, requestId, BluetoothGatt.GATT_FAILURE, null, true);
                return;
            }

            safeSend(device, requestId, BluetoothGatt.GATT_SUCCESS, payload, true);
            Log.i(TAG, "Served payload for id=" + requestedMessageId + " len=" + payload.length);
        }

        @Override
        public void onConnectionStateChange(BluetoothDevice device, int status, int newState) {
            Log.d(TAG, "GATT state=" + newState + " status=" + status + " dev=" + device);
        }
    };
}
