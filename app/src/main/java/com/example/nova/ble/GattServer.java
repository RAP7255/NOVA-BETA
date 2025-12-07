package com.example.nova.ble;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
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
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * FINAL STABLE GATT SERVER (Admin & User Compatible)
 * ---------------------------------------------------
 *  - CCCD support
 *  - Chunked NOTIFY
 *  - WRITE(id) triggers immediate notify-if-available
 *  - NO infinite retry loops
 *  - No READ fallback confusion → returns FAIL (expected)
 */
@SuppressLint("MissingPermission")
public class GattServer {

    private static final String TAG = "GattServer";

    private final Context context;
    private BluetoothGattServer gattServer;

    private BluetoothGattCharacteristic requestChar;
    private BluetoothGattCharacteristic responseChar;

    private final ConcurrentHashMap<String, Boolean> subscribedDevices = new ConcurrentHashMap<>();

    private static final int NOTIFY_CHUNK_SIZE = 490;

    public GattServer(Context ctx) {
        this.context = ctx.getApplicationContext();
    }

    private boolean hasConnectPermission() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
                        == PackageManager.PERMISSION_GRANTED;
    }

    // ---------------------------------------------------------
    // START
    // ---------------------------------------------------------
    public void start() {

        if (!hasConnectPermission()) {
            Log.w(TAG, "Missing CONNECT permission. Cannot start server.");
            return;
        }

        BluetoothManager manager =
                (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);

        if (manager == null) {
            Log.e(TAG, "BluetoothManager NULL");
            return;
        }

        try {
            gattServer = manager.openGattServer(context, callback);
        } catch (Exception e) {
            Log.e(TAG, "openGattServer failed: " + e);
            return;
        }

        if (gattServer == null) {
            Log.e(TAG, "GATT Server NULL");
            return;
        }

        BluetoothGattService service = new BluetoothGattService(
                GattConstants.SERVICE_MESH_GATT,
                BluetoothGattService.SERVICE_TYPE_PRIMARY
        );

        // WRITE → message ID (8 bytes)
        requestChar = new BluetoothGattCharacteristic(
                GattConstants.CHAR_REQUEST_MESSAGE,
                BluetoothGattCharacteristic.PROPERTY_WRITE |
                        BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
                BluetoothGattCharacteristic.PERMISSION_WRITE
        );

        // NOTIFY → encrypted payload
        responseChar = new BluetoothGattCharacteristic(
                GattConstants.CHAR_FETCH_CIPHERTEXT,
                BluetoothGattCharacteristic.PROPERTY_NOTIFY |
                        BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_READ
        );

        // CCCD descriptor for notifications
        BluetoothGattDescriptor cccd = new BluetoothGattDescriptor(
                UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"),
                BluetoothGattDescriptor.PERMISSION_WRITE | BluetoothGattDescriptor.PERMISSION_READ
        );
        responseChar.addDescriptor(cccd);

        service.addCharacteristic(requestChar);
        service.addCharacteristic(responseChar);

        boolean ok = gattServer.addService(service);
        Log.i(TAG, "GATT Service added = " + ok);
    }

    public void stop() {
        try {
            if (gattServer != null) gattServer.close();
        } catch (Exception e) {
            Log.e(TAG, "stop() error", e);
        }
        gattServer = null;
        subscribedDevices.clear();
    }

    // ---------------------------------------------------------
    // Notification helpers
    // ---------------------------------------------------------
    private void notifyChunks(BluetoothDevice device, byte[] data) {

        if (gattServer == null || device == null || data == null) return;

        String addr = device.getAddress();
        if (!Boolean.TRUE.equals(subscribedDevices.get(addr))) {
            Log.w(TAG, "Device NOT subscribed for notify: " + addr);
            return;
        }

        if (data.length <= NOTIFY_CHUNK_SIZE) {
            responseChar.setValue(data);
            gattServer.notifyCharacteristicChanged(device, responseChar, false);
            return;
        }

        int offset = 0;
        while (offset < data.length) {
            int len = Math.min(NOTIFY_CHUNK_SIZE, data.length - offset);
            byte[] chunk = Arrays.copyOfRange(data, offset, offset + len);
            responseChar.setValue(chunk);

            try {
                gattServer.notifyCharacteristicChanged(device, responseChar, false);
            } catch (Exception ignore) {}

            offset += len;

            try { Thread.sleep(8); } catch (Exception ignore) {}
        }
    }

    public void notifyAllSubscribed(byte[] payload) {

        if (payload == null || gattServer == null) return;

        BluetoothManager manager =
                (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);

        if (manager == null) return;

        for (Map.Entry<String, Boolean> e : subscribedDevices.entrySet()) {

            if (!Boolean.TRUE.equals(e.getValue())) continue;

            BluetoothDevice dev = manager.getAdapter().getRemoteDevice(e.getKey());
            if (dev != null) notifyChunks(dev, payload);
        }
    }

    // ---------------------------------------------------------
    // CALLBACK
    // ---------------------------------------------------------
    private final BluetoothGattServerCallback callback =
            new BluetoothGattServerCallback() {

                // -------------------------------
                // CCCD write
                // -------------------------------
                @Override
                public void onDescriptorWriteRequest(BluetoothDevice device, int requestId,
                                                     BluetoothGattDescriptor descriptor,
                                                     boolean preparedWrite,
                                                     boolean responseNeeded,
                                                     int offset, byte[] value) {

                    if (!hasConnectPermission()) return;

                    boolean enable =
                            value != null &&
                                    value.length > 0 &&
                                    (value[0] == 0x01 || value[0] == 0x02);

                    if (enable) {
                        subscribedDevices.put(device.getAddress(), true);
                        Log.d(TAG, "CCCD ENABLE → " + device.getAddress());
                    } else {
                        subscribedDevices.remove(device.getAddress());
                        Log.d(TAG, "CCCD DISABLE → " + device.getAddress());
                    }

                    gattServer.sendResponse(
                            device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null
                    );
                }

                // -------------------------------
                // WRITE → messageId from client
                // -------------------------------
                @Override
                public void onCharacteristicWriteRequest(BluetoothDevice device, int requestId,
                                                         BluetoothGattCharacteristic characteristic,
                                                         boolean preparedWrite,
                                                         boolean responseNeeded,
                                                         int offset, byte[] value) {

                    if (!hasConnectPermission()) return;

                    if (!characteristic.getUuid().equals(GattConstants.CHAR_REQUEST_MESSAGE)) {
                        gattServer.sendResponse(device, requestId,
                                BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED,
                                0, null);
                        return;
                    }

                    if (value == null || value.length != 8) {
                        gattServer.sendResponse(device, requestId,
                                BluetoothGatt.GATT_INVALID_ATTRIBUTE_LENGTH,
                                0, null);
                        return;
                    }

                    long msgId = ByteBuffer.wrap(value).getLong();
                    Log.d(TAG, "WRITE(id)=" + msgId + " from " + device.getAddress());

                    // Required ACK
                    gattServer.sendResponse(device, requestId,
                            BluetoothGatt.GATT_SUCCESS, 0, null);

                    // Try immediate payload send
                    byte[] payload = null;

                    try {
                        if (HopManager.hopManagerInstance != null)
                            payload = HopManager.hopManagerInstance.getStoredCiphertext(msgId);
                    } catch (Exception ignore) {}

                    if (payload != null && payload.length > 0) {
                        notifyChunks(device, payload);
                        Log.d(TAG, "Immediate notify OK for id=" + msgId);
                    } else {
                        Log.d(TAG, "Payload not ready. HopManager will notify later.");
                    }
                }

                // -------------------------------
                // READ fallback (not used normally)
                // Always fail (expected by client)
                // -------------------------------
                @Override
                public void onCharacteristicReadRequest(BluetoothDevice device, int requestId,
                                                        int offset,
                                                        BluetoothGattCharacteristic characteristic) {

                    gattServer.sendResponse(device, requestId,
                            BluetoothGatt.GATT_FAILURE,
                            0, null);
                }
            };
}
