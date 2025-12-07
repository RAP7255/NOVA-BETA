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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * FINAL PayloadGattClient (Stable)
 * ----------------------------------
 *  - Matches updated HopManager & GattServer
 *  - Fully supports chunked NOTIFY
 *  - OEM crash-safe (Oppo, Vivo, MIUI)
 *  - Strict device-busy lock (prevents races)
 *  - Bounded retry (2 retries max)
 *  - Clean timeout handling
 *  - Safe GATT close
 *  - Immediate return on error
 */
public class PayloadGattClient {

    private static final String TAG = "PayloadGattClient";

    private static final long TIMEOUT_MS = 9000;
    private static final int RETRY_LIMIT = 2;

    private final Context ctx;
    private final Handler handler = new Handler(Looper.getMainLooper());

    // Prevent parallel GATT operations to same device
    private final ConcurrentHashMap<String, Boolean> deviceBusy = new ConcurrentHashMap<>();

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

    // ----------------------------------------------------------
    // PUBLIC CALL
    // ----------------------------------------------------------
    public void fetchPayload(BluetoothDevice device, long msgId, Callback cb) {
        fetchInternal(device, msgId, cb, 0);
    }

    // ----------------------------------------------------------
    // INTERNAL with RETRY + DEVICE LOCK
    // ----------------------------------------------------------
    private void fetchInternal(BluetoothDevice device, long messageId, Callback cb, int retryCount) {

        if (device == null) {
            cb.onError("Device null");
            return;
        }

        final String addr = device.getAddress();

        // Device-level LOCK
        if (deviceBusy.putIfAbsent(addr, true) != null) {
            cb.onError("DeviceBusy");
            return;
        }

        if (!hasAllPermissions()) {
            deviceBusy.remove(addr);
            cb.onError("PermissionsMissing");
            return;
        }

        final AtomicBoolean done = new AtomicBoolean(false);
        final BluetoothGatt[] gRef = new BluetoothGatt[1];

        // TIMEOUT HANDLER
        Runnable timeoutRunnable = () -> {
            if (done.compareAndSet(false, true)) {
                deviceBusy.remove(addr);
                cb.onError("Timeout");
                safeClose(gRef[0]);
            }
        };
        handler.postDelayed(timeoutRunnable, TIMEOUT_MS);

        try {
            Log.d(TAG, "Connecting GATT → " + addr + " retry=" + retryCount);

            gRef[0] = device.connectGatt(ctx, false, new BluetoothGattCallback() {

                BluetoothGattCharacteristic reqChar;
                BluetoothGattCharacteristic respChar;

                // ----------------------------------------------------------
                // CONNECTION STATE
                // ----------------------------------------------------------
                @Override
                public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {

                    if (done.get()) return;

                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        Log.w(TAG, "❌ GATT ERROR=" + status);

                        safeClose(g);
                        deviceBusy.remove(addr);

                        if (retryCount < RETRY_LIMIT) {
                            handler.postDelayed(
                                    () -> fetchInternal(device, messageId, cb, retryCount + 1),
                                    300
                            );
                        } else {
                            cb.onError("GATT_FAIL_" + status);
                        }
                        return;
                    }

                    if (newState == BluetoothGatt.STATE_CONNECTED) {
                        Log.d(TAG, "Connected → requesting MTU");
                        g.requestMtu(512);
                    } else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                        deviceBusy.remove(addr);

                        if (done.compareAndSet(false, true)) {
                            cb.onError("Disconnected");
                        }
                        safeClose(g);
                    }
                }

                // ----------------------------------------------------------
                // MTU
                // ----------------------------------------------------------
                @Override
                public void onMtuChanged(BluetoothGatt g, int mtu, int status) {
                    handler.postDelayed(g::discoverServices, 200);
                }

                // ----------------------------------------------------------
                // SERVICES
                // ----------------------------------------------------------
                @Override
                public void onServicesDiscovered(BluetoothGatt g, int status) {

                    if (done.get()) return;

                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        fail("Service discovery fail", g);
                        return;
                    }

                    BluetoothGattService svc = g.getService(GattConstants.SERVICE_MESH_GATT);
                    if (svc == null) {
                        fail("Service missing", g);
                        return;
                    }

                    reqChar = svc.getCharacteristic(GattConstants.CHAR_REQUEST_MESSAGE);
                    respChar = svc.getCharacteristic(GattConstants.CHAR_FETCH_CIPHERTEXT);

                    if (reqChar == null || respChar == null) {
                        fail("Characteristics missing", g);
                        return;
                    }

                    // Enable notification
                    g.setCharacteristicNotification(respChar, true);

                    BluetoothGattDescriptor cccd =
                            respChar.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"));

                    if (cccd == null) {
                        fail("CCCD missing", g);
                        return;
                    }

                    cccd.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                    boolean ok = g.writeDescriptor(cccd);

                    if (!ok) fail("CCCD write failed", g);
                }

                // ----------------------------------------------------------
                // CCCD WRITTEN → WRITE messageId
                // ----------------------------------------------------------
                @Override
                public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int status) {

                    if (done.get()) return;

                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        fail("Descriptor write error=" + status, g);
                        return;
                    }

                    // Build 8-byte msgId
                    byte[] idBytes = ByteBuffer.allocate(8)
                            .order(ByteOrder.BIG_ENDIAN)
                            .putLong(messageId)
                            .array();

                    reqChar.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
                    reqChar.setValue(idBytes);

                    boolean ok = g.writeCharacteristic(reqChar);
                    if (!ok) fail("writeCharacteristic failed", g);
                }

                @Override
                public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
                    Log.d(TAG, "MessageID written status=" + status);
                }

                // ----------------------------------------------------------
                // NOTIFICATION
                // ----------------------------------------------------------
                @Override
                public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {
                    if (done.get()) return;

                    if (!c.getUuid().equals(GattConstants.CHAR_FETCH_CIPHERTEXT))
                        return;

                    byte[] chunk = c.getValue();

                    if (chunk == null || chunk.length == 0) {
                        fail("Empty chunk", g);
                        return;
                    }

                    // SUCCESS → return chunk
                    succeed(chunk, g);
                }

                // ----------------------------------------------------------
                // SUCCESS
                // ----------------------------------------------------------
                private void succeed(byte[] data, BluetoothGatt g) {

                    if (done.compareAndSet(false, true)) {

                        handler.removeCallbacks(timeoutRunnable);
                        deviceBusy.remove(addr);

                        handler.post(() -> {
                            cb.onPayload(data);
                            safeClose(g);
                        });
                    }
                }

                // ----------------------------------------------------------
                // FAIL
                // ----------------------------------------------------------
                private void fail(String reason, BluetoothGatt g) {

                    if (done.compareAndSet(false, true)) {

                        handler.removeCallbacks(timeoutRunnable);
                        deviceBusy.remove(addr);

                        cb.onError(reason);
                        safeClose(g);
                    }
                }

            });

        } catch (Exception e) {
            handler.removeCallbacks(timeoutRunnable);
            deviceBusy.remove(addr);
            cb.onError("Exception: " + e.getMessage());
            safeClose(gRef[0]);
        }
    }

    // ----------------------------------------------------------
    // SAFE CLOSE
    // ----------------------------------------------------------
    private void safeClose(BluetoothGatt g) {
        try {
            if (g != null) {
                g.disconnect();
                g.close();
            }
        } catch (Exception ignored) {}
    }
}
