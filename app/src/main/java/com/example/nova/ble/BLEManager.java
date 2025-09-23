package com.example.nova.ble;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.core.content.ContextCompat;

import java.util.UUID;

// We no longer extend Context. This was a critical bug.
public class BLEManager {
    private static final String TAG = "NOVA-BLE";

    private BluetoothAdapter bluetoothAdapter;
    private BluetoothGatt bluetoothGatt;
    private Context context;
    private OnMessageReceivedListener messageListener;

    // UUIDs (replace with BitChat’s service/characteristic later)
    private static final UUID SERVICE_UUID = UUID.fromString("00001810-0000-1000-8000-00805f9b34fb");
    private static final UUID CHAR_UUID = UUID.fromString("00002a35-0000-1000-8000-00805f9b34fb");

    /**
     * Constructor for the BLEManager.
     * @param context The application or activity context.
     * @param listener A callback listener for received messages.
     */
    public BLEManager(Context context, OnMessageReceivedListener listener) {
        this.context = context;
        this.messageListener = listener;
        BluetoothManager btManager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        if (btManager != null) {
            bluetoothAdapter = btManager.getAdapter();
        }
    }

    /**
     * Checks if the required BLE permissions are granted.
     * Note: This method is a helper. The host Activity or Fragment must
     * call ActivityCompat.requestPermissions if permissions are not granted.
     * @return true if all necessary permissions are granted, false otherwise.
     */
    private boolean hasScanPermissions() {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
            Log.d(TAG, "Bluetooth is not enabled.");
            return false;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        } else {
            // For older Android versions, only location permission is needed for scanning.
            return ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        }
    }

    /**
     * Starts the BLE scan.
     * The host Activity/Fragment is responsible for requesting permissions beforehand.
     */
    public void startScan() {
        if (!hasScanPermissions()) {
            Log.d(TAG, "Permissions not granted. Cannot start scan. Please request permissions from your Activity/Fragment.");
            return;
        }

        // We no longer use `ActivityCompat.checkSelfPermission` here as the host activity must handle it.
        bluetoothAdapter.startLeScan(scanCallback);
        Log.d(TAG, "Scanning for devices...");
    }

    /**
     * Stops the BLE scan.
     */
    public void stopScan() {
        if (bluetoothAdapter != null) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "BLUETOOTH_SCAN permission not granted. Cannot stop scan.");
                return;
            }
            bluetoothAdapter.stopLeScan(scanCallback);
            Log.d(TAG, "Scan stopped.");
        }
    }

    private final BluetoothAdapter.LeScanCallback scanCallback = (device, rssi, scanRecord) -> {
        // We only check for connect permission when we actually try to connect.
        Log.d(TAG, "Device found: " + device.getName() + " - " + device.getAddress());
        // Auto-connect to first found device (for testing)
        stopScan(); // Stop scanning once a device is found
        connectToDevice(device);
    };

    /**
     * Attempts to connect to a Bluetooth device.
     * @param device The BluetoothDevice object to connect to.
     */
    public void connectToDevice(BluetoothDevice device) {
        if (device == null) return;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            Log.d(TAG, "BLUETOOTH_CONNECT permission not granted. Cannot connect.");
            return;
        }

        // The first argument to connectGatt is the context.
        bluetoothGatt = device.connectGatt(context, false, gattCallback);
        Log.d(TAG, "Connecting to " + device.getAddress());
    }

    /**
     * Disconnects from the GATT server.
     */
    public void disconnect() {
        if (bluetoothGatt == null) {
            return;
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "BLUETOOTH_CONNECT permission not granted. Cannot disconnect.");
            return;
        }
        bluetoothGatt.disconnect();
        bluetoothGatt.close();
        bluetoothGatt = null;
        Log.d(TAG, "Disconnected from GATT server.");
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            if (newState == BluetoothGatt.STATE_CONNECTED) {
                Log.d(TAG, "Connected to GATT server.");
                // Discover services once connected.
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                    Log.w(TAG, "BLUETOOTH_CONNECT permission not granted for service discovery.");
                    return;
                }
                gatt.discoverServices();
            } else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                Log.d(TAG, "Disconnected from GATT server.");
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                    Log.w(TAG, "BLUETOOTH_CONNECT permission not granted for service discovery.");
                    return;
                }

                BluetoothGattService service = gatt.getService(SERVICE_UUID);
                if (service != null) {
                    BluetoothGattCharacteristic characteristic = service.getCharacteristic(CHAR_UUID);
                    if (characteristic != null) {
                        // Correctly set characteristic notification and read/write permissions
                        if ((characteristic.getProperties() & BluetoothGattCharacteristic.PROPERTY_NOTIFY) > 0) {
                            gatt.setCharacteristicNotification(characteristic, true);
                            Log.d(TAG, "Subscribed to characteristic notifications.");
                        } else {
                            Log.w(TAG, "Characteristic does not support notifications.");
                        }
                    } else {
                        Log.w(TAG, "Characteristic " + CHAR_UUID + " not found.");
                    }
                } else {
                    Log.w(TAG, "Service " + SERVICE_UUID + " not found.");
                }
            } else {
                Log.w(TAG, "Service discovery failed with status: " + status);
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, byte[] value) {
            String msg = new String(value);
            Log.d(TAG, "Message received: " + msg);

            // Notify the listener on the main thread. This is a much better practice than
            // having a hardcoded dependency on an Activity.
            new Handler(Looper.getMainLooper()).post(() -> {
                if (messageListener != null) {
                    messageListener.onMessageReceived(msg);
                }
            });
        }
    };

    /**
     * Sends a message to the connected device.
     * @param message The string message to send.
     */
    public void sendMessage(String message) {
        if (bluetoothGatt == null) {
            Log.e(TAG, "GATT server is not connected.");
            return;
        }

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "BLUETOOTH_CONNECT permission not granted for sending message.");
            return;
        }

        BluetoothGattService service = bluetoothGatt.getService(SERVICE_UUID);
        if (service != null) {
            BluetoothGattCharacteristic characteristic = service.getCharacteristic(CHAR_UUID);
            if (characteristic != null) {
                characteristic.setValue(message.getBytes());
                // Ensure the characteristic has the write property
                if ((characteristic.getProperties() & BluetoothGattCharacteristic.PROPERTY_WRITE) > 0) {
                    bluetoothGatt.writeCharacteristic(characteristic);
                    Log.d(TAG, "Message sent: " + message);
                } else {
                    Log.w(TAG, "Characteristic does not support writing.");
                }
            } else {
                Log.w(TAG, "Characteristic " + CHAR_UUID + " not found.");
            }
        } else {
            Log.w(TAG, "Service " + SERVICE_UUID + " not found.");
        }
    }
}
