package com.example.nova.ble;

import android.bluetooth.*;
import android.content.Context;
import android.util.Log;

import java.nio.ByteBuffer;

public class GattClient {

    private static final String TAG = "GattClient";

    private final Context context;

    public interface FetchCallback {
        void onFetched(long msgId, byte[] ciphertext);
        void onFailed(long msgId);
    }

    public GattClient(Context ctx) {
        this.context = ctx.getApplicationContext();
    }

    public void fetchCiphertext(BluetoothDevice device, long messageId, FetchCallback cb) {

        device.connectGatt(context, false, new BluetoothGattCallback() {

            BluetoothGattCharacteristic reqChar;
            BluetoothGattCharacteristic cipherChar;

            @Override
            public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {

                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    gatt.discoverServices();
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    cb.onFailed(messageId);
                }
            }

            @Override
            public void onServicesDiscovered(BluetoothGatt gatt, int status) {

                BluetoothGattService service = gatt.getService(GattConstants.SERVICE_MESH_GATT);

                if (service == null) {
                    cb.onFailed(messageId);
                    return;
                }

                reqChar = service.getCharacteristic(GattConstants.CHAR_REQUEST_MESSAGE);
                cipherChar = service.getCharacteristic(GattConstants.CHAR_FETCH_CIPHERTEXT);

                if (reqChar == null || cipherChar == null) {
                    cb.onFailed(messageId);
                    return;
                }

                // send messageId
                ByteBuffer bb = ByteBuffer.allocate(8).putLong(messageId);
                reqChar.setValue(bb.array());
                gatt.writeCharacteristic(reqChar);
            }

            @Override
            public void onCharacteristicWrite(BluetoothGatt gatt,
                                              BluetoothGattCharacteristic characteristic,
                                              int status) {

                if (status == BluetoothGatt.GATT_SUCCESS &&
                        characteristic.getUuid().equals(GattConstants.CHAR_REQUEST_MESSAGE)) {
                    // now read ciphertext
                    gatt.readCharacteristic(cipherChar);
                }
            }

            @Override
            public void onCharacteristicRead(BluetoothGatt gatt,
                                             BluetoothGattCharacteristic characteristic,
                                             int status) {

                if (status == BluetoothGatt.GATT_SUCCESS &&
                        characteristic.getUuid().equals(GattConstants.CHAR_FETCH_CIPHERTEXT)) {

                    byte[] ciphertext = characteristic.getValue();
                    gatt.close();

                    cb.onFetched(messageId, ciphertext);
                } else {
                    gatt.close();
                    cb.onFailed(messageId);
                }
            }
        });
    }
}
