package com.example.nova.ble;

import android.Manifest;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.core.content.ContextCompat;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

public class PayloadGattClient {

    private static final String TAG = "PayloadGattClient";
    private static final long TIMEOUT_MS = 8000;

    private final Context ctx;
    private final Handler handler = new Handler(Looper.getMainLooper());

    public interface Callback {
        void onPayload(byte[] ciphertext); // raw from ESP32 or encrypted
        void onError(String reason);
    }

    public PayloadGattClient(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    private boolean hasAllPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
                    && ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED;
        }
        return ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }


    // ------------------------------------------------------------------------------------------
    // MAIN FETCH FUNCTION
    // ------------------------------------------------------------------------------------------
    public void fetchPayload(BluetoothDevice device, long messageId, Callback cb) {
        fetch(device, messageId, cb);
    }

    public void fetch(BluetoothDevice device, long messageId, Callback cb) {

        if (!hasAllPermissions()) {
            cb.onError("Missing BLE permissions");
            return;
        }

        if (device == null) {
            cb.onError("Device null");
            return;
        }

        final AtomicBoolean done = new AtomicBoolean(false);
        final BluetoothGatt[] gattRef = new BluetoothGatt[1];

        // 🔥 Timeout safety
        Runnable timeout = () -> {
            if (done.compareAndSet(false, true)) {
                cb.onError("Timeout");
                if (gattRef[0] != null) safeClose(gattRef[0]);
            }
        };

        handler.postDelayed(timeout, TIMEOUT_MS);

        try {

            gattRef[0] = device.connectGatt(ctx, false, new BluetoothGattCallback() {

                BluetoothGattCharacteristic reqChar;
                BluetoothGattCharacteristic respChar;

                // -------------------------------------------------------------
                // CONNECTION STATE
                // -------------------------------------------------------------
                @Override
                public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {

                    if (done.get()) return;

                    if (newState == BluetoothGatt.STATE_CONNECTED) {
                        Log.d(TAG, "Connected → requesting MTU 517");
                        g.requestMtu(517);
                    } else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                        if (done.compareAndSet(false, true)) {
                            cb.onError("Disconnected");
                        }
                        safeClose(g);
                    }
                }

                // -------------------------------------------------------------
                // MTU RESULT
                // -------------------------------------------------------------
                @Override
                public void onMtuChanged(BluetoothGatt g, int mtu, int status) {
                    Log.d(TAG, "MTU changed → " + mtu + " (status=" + status + ")");
                    handler.postDelayed(() -> g.discoverServices(), 120);
                }

                // -------------------------------------------------------------
                // SERVICE DISCOVERY
                // -------------------------------------------------------------
                @Override
                public void onServicesDiscovered(BluetoothGatt g, int status) {

                    if (done.get()) return;

                    BluetoothGattService svc =
                            g.getService(GattConstants.SERVICE_MESH_GATT);

                    if (svc == null) {
                        fail("Service BEEF not found", g);
                        return;
                    }

                    reqChar = svc.getCharacteristic(GattConstants.CHAR_REQUEST_MESSAGE);
                    respChar = svc.getCharacteristic(GattConstants.CHAR_FETCH_CIPHERTEXT);

                    if (reqChar == null || respChar == null) {
                        fail("Required characteristics missing", g);
                        return;
                    }

                    // Enable notifications
                    g.setCharacteristicNotification(respChar, true);

                    BluetoothGattDescriptor cccd =
                            respChar.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"));

                    if (cccd == null) {
                        fail("Missing CCCD 0x2902", g);
                        return;
                    }

                    cccd.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                    g.writeDescriptor(cccd);

                    Log.d(TAG, "Writing CCCD descriptor → enable notify");
                }

                // -------------------------------------------------------------
                // CCCD WRITE → SEND REQUEST
                // -------------------------------------------------------------
                @Override
                public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int status) {

                    if (done.get()) return;

                    byte[] idBytes = ByteBuffer.allocate(8)
                            .order(ByteOrder.BIG_ENDIAN)
                            .putLong(messageId)
                            .array();

                    reqChar.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
                    reqChar.setValue(idBytes);

                    boolean ok = g.writeCharacteristic(reqChar);
                    Log.d(TAG, "writeCharacteristic(reqChar) => " + ok);

                    if (!ok) fail("writeCharacteristic failed", g);
                }

                @Override
                public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
                    Log.d(TAG, "Request ID sent");
                }

                // -------------------------------------------------------------
                // NOTIFICATION RECEIVED
                // -------------------------------------------------------------
                @Override
                public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {

                    if (done.get()) return;

                    if (!c.getUuid().equals(GattConstants.CHAR_FETCH_CIPHERTEXT))
                        return;

                    byte[] val = c.getValue();

                    if (val == null || val.length == 0) {
                        fail("Empty notify payload", g);
                        return;
                    }

                    // ⭐ SPECIAL PATCH — Detect ESP plaintext (ASCII)
                    if (looksLikeEspPlaintext(val)) {
                        Log.d(TAG, "🟩 RAW ESP-PLAINTEXT FETCHED → " + new String(val));
                        succeed(val, g);
                        return;
                    }

                    // Otherwise → encrypted AES data
                    Log.d(TAG, "🟩 RAW ENCRYPTED PAYLOAD FETCHED (" + val.length + " bytes)");
                    succeed(val, g);
                }

                // -------------------------------------------------------------
                // HELPER: detect ESP plaintext
                // -------------------------------------------------------------
                private boolean looksLikeEspPlaintext(byte[] data) {
                    try {
                        String s = new String(data, "UTF-8");
                        return s.startsWith("CIPHERTEXT_FROM_ESP32");
                    } catch (Exception ex) {
                        return false;
                    }
                }

                // SUCCESS WRAPPER
                private void succeed(byte[] data, BluetoothGatt g) {
                    if (done.compareAndSet(false, true)) {
                        handler.removeCallbacks(timeout);
                        cb.onPayload(data);
                        safeClose(g);
                    }
                }

                // FAIL WRAPPER
                private void fail(String reason, BluetoothGatt g) {
                    if (done.compareAndSet(false, true)) {
                        handler.removeCallbacks(timeout);
                        cb.onError(reason);
                        safeClose(g);
                    }
                }
            });

        } catch (Exception e) {
            handler.removeCallbacks(timeout);
            cb.onError("Exception: " + e.getMessage());
            if (gattRef[0] != null) safeClose(gattRef[0]);
        }
    }

    // ------------------------------------------------------------------------------------------
    // SAFE CLOSE
    // ------------------------------------------------------------------------------------------
    private void safeClose(BluetoothGatt g) {
        try {
            if (g != null) {
                g.disconnect();
                g.close();
            }
        } catch (Exception ignored) {}
    }
}
