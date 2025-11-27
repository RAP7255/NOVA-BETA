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

    private static final long TIMEOUT_MS = 10000; // ⭐ OEM-safe timeout
    private static final int RETRY_LIMIT = 2;     // ⭐ for Xiaomi/Samsung GATT 133/257

    private final Context ctx;
    private final Handler handler = new Handler(Looper.getMainLooper());

    public interface Callback {
        void onPayload(byte[] ciphertext);
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

    // -------------------------------------------------------------------------
    // PUBLIC API
    // -------------------------------------------------------------------------
    public void fetchPayload(BluetoothDevice device, long msgId, Callback cb) {
        fetchInternal(device, msgId, cb, 0);
    }

    // -------------------------------------------------------------------------
    // INTERNAL FETCH with RETRY
    // -------------------------------------------------------------------------
    private void fetchInternal(BluetoothDevice device, long messageId, Callback cb, int retryCount) {

        if (device == null) {
            cb.onError("Device null");
            return;
        }

        if (!hasAllPermissions()) {
            cb.onError("Permissions missing");
            return;
        }

        final AtomicBoolean done = new AtomicBoolean(false);
        final BluetoothGatt[] gRef = new BluetoothGatt[1];

        // ⭐ Timeout
        Runnable timeoutRunnable = () -> {
            if (done.compareAndSet(false, true)) {
                cb.onError("Timeout");
                safeClose(gRef[0]);
            }
        };
        handler.postDelayed(timeoutRunnable, TIMEOUT_MS);

        try {
            Log.d(TAG, "Connecting GATT (attempt=" + retryCount + ") → " + device.getAddress());

            gRef[0] = device.connectGatt(ctx, false, new BluetoothGattCallback() {

                BluetoothGattCharacteristic reqChar;
                BluetoothGattCharacteristic respChar;

                // ---------------------------------------------------------
                // CONNECTION STATE
                // ---------------------------------------------------------
                @Override
                public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {

                    if (done.get()) return;

                    if (status != BluetoothGatt.GATT_SUCCESS) {

                        Log.w(TAG, "❌ GATT ERROR status=" + status);

                        safeClose(g);

                        if (retryCount < RETRY_LIMIT) {
                            handler.postDelayed(() ->
                                            fetchInternal(device, messageId, cb, retryCount + 1),
                                    300
                            );
                        } else {
                            cb.onError("GATT_FAIL_" + status);
                        }
                        return;
                    }

                    if (newState == BluetoothGatt.STATE_CONNECTED) {
                        Log.d(TAG, "Connected → requesting MTU 512");
                        g.requestMtu(512);
                    } else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                        if (done.compareAndSet(false, true))
                            cb.onError("Disconnected");
                        safeClose(g);
                    }
                }

                // ---------------------------------------------------------
                // MTU
                // ---------------------------------------------------------
                @Override
                public void onMtuChanged(BluetoothGatt g, int mtu, int status) {
                    Log.d(TAG, "MTU=" + mtu + " status=" + status);
                    handler.postDelayed(g::discoverServices, 200); // ⭐ OEM-safe delay
                }

                // ---------------------------------------------------------
                // SERVICES DISCOVERED
                // ---------------------------------------------------------
                @Override
                public void onServicesDiscovered(BluetoothGatt g, int status) {

                    if (done.get()) return;

                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        fail("Service discovery fail", g);
                        return;
                    }

                    BluetoothGattService svc =
                            g.getService(GattConstants.SERVICE_MESH_GATT);

                    if (svc == null) {
                        fail("Service MESH not found", g);
                        return;
                    }

                    reqChar = svc.getCharacteristic(GattConstants.CHAR_REQUEST_MESSAGE);
                    respChar = svc.getCharacteristic(GattConstants.CHAR_FETCH_CIPHERTEXT);

                    if (reqChar == null || respChar == null) {
                        fail("Missing GATT characteristics", g);
                        return;
                    }

                    g.setCharacteristicNotification(respChar, true);

                    BluetoothGattDescriptor cccd =
                            respChar.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"));

                    if (cccd == null) {
                        fail("Missing CCCD", g);
                        return;
                    }

                    cccd.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);

                    boolean ok = g.writeDescriptor(cccd);
                    Log.d(TAG, "Write CCCD => " + ok);

                    if (!ok) fail("CCCD write failed", g);
                }

                // ---------------------------------------------------------
                // CCCD WRITTEN → SEND ID
                // ---------------------------------------------------------
                @Override
                public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int status) {

                    if (done.get()) return;

                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        fail("Descriptor write error=" + status, g);
                        return;
                    }

                    byte[] idBytes = ByteBuffer.allocate(8)
                            .order(ByteOrder.BIG_ENDIAN)
                            .putLong(messageId)
                            .array();

                    reqChar.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
                    reqChar.setValue(idBytes);

                    boolean ok = g.writeCharacteristic(reqChar);
                    Log.d(TAG, "writeCharacteristic(ID) => " + ok);

                    if (!ok) fail("writeCharacteristic failed", g);
                }

                @Override
                public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
                    Log.d(TAG, "ID request sent (status=" + status + ")");
                }

                // ---------------------------------------------------------
                // NOTIFICATION RECEIVED
                // ---------------------------------------------------------
                @Override
                public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {
                    if (done.get()) return;

                    if (!c.getUuid().equals(GattConstants.CHAR_FETCH_CIPHERTEXT))
                        return;

                    byte[] val = c.getValue();

                    if (val == null || val.length == 0) {
                        fail("Empty payload", g);
                        return;
                    }

                    // Check for ESP plaintext
                    if (looksLikeEsp(val)) {
                        Log.d(TAG, "ESP PLAINTEXT → " + new String(val));
                        succeed(val, g);
                        return;
                    }

                    Log.d(TAG, "Encrypted payload received (" + val.length + " bytes)");
                    succeed(val, g);
                }

                private boolean looksLikeEsp(byte[] d) {
                    try {
                        String s = new String(d, "UTF-8");
                        return s.startsWith("CIPHERTEXT_FROM_ESP32")
                                || s.startsWith("MESH:TYPE:");
                    } catch (Exception ignored) {
                        return false;
                    }
                }

                // ---------------------------------------------------------
                // SUCCESS
                // ---------------------------------------------------------
                private void succeed(byte[] data, BluetoothGatt g) {
                    if (done.compareAndSet(false, true)) {
                        handler.removeCallbacks(timeoutRunnable);

                        handler.postDelayed(() -> {
                            cb.onPayload(data);
                            safeClose(g);
                        }, 60);  // ⭐ allow OS to flush buffers
                    }
                }

                // ---------------------------------------------------------
                // FAIL
                // ---------------------------------------------------------
                private void fail(String reason, BluetoothGatt g) {
                    if (done.compareAndSet(false, true)) {
                        handler.removeCallbacks(timeoutRunnable);
                        cb.onError(reason);
                        safeClose(g);
                    }
                }

            });

        } catch (Exception e) {
            handler.removeCallbacks(timeoutRunnable);
            cb.onError("Exception " + e.getMessage());
            safeClose(gRef[0]);
        }
    }

    // -------------------------------------------------------------------------
    // SAFE CLOSE
    // -------------------------------------------------------------------------
    private void safeClose(BluetoothGatt g) {
        try {
            if (g != null) {
                g.disconnect();
                g.close();
            }
        } catch (Exception ignored) { }
    }
}
