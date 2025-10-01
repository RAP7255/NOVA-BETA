package com.example.nova.ble;

import android.util.Log;
import com.example.nova.model.MeshMessage;
import com.example.nova.model.MessageCache;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class HopManager implements BluetoothScanner.BluetoothScannerListener {

    private static final String TAG = "HopManager";

    private final MessageCache cache;
    private final BluetoothAdvertiser advertiser;
    private BluetoothScanner scanner;
    private HopListener listener;

    // Singleton instance
    public static HopManager hopManagerInstance;

    public interface HopListener {
        void onNewMessage(MeshMessage message);
    }

    public HopManager(MessageCache cache, BluetoothAdvertiser advertiser, BluetoothScanner scanner, HopListener initialListener) {
        this.cache = cache;
        this.advertiser = advertiser;
        this.scanner = scanner;
        this.listener = initialListener;

        if (scanner != null) scanner.setListener(this);

        hopManagerInstance = this;
        Log.d(TAG, "HopManager initialized");
    }

    /** Dynamically attach or replace listener */
    public void setListener(HopListener listener) {
        this.listener = listener;
        Log.d(TAG, "HopListener attached");
    }

    /** Start scanner */
    public void start() {
        if (scanner != null && scanner.isSupported()) {
            scanner.startScan();
            Log.d(TAG, "Scanner started");
        } else {
            Log.w(TAG, "Scanner not available or unsupported");
        }
    }

    /** Stop scanner safely */
    public void stop() {
        if (scanner != null) {
            try {
                scanner.stopScan();
                Log.d(TAG, "Scanner stopped");
            } catch (Exception e) {
                Log.w(TAG, "Failed to stop scanner", e);
            }
        }
    }

    /** Called by scanner when a message is received */
    @Override
    public void onMessageReceived(MeshMessage message) {
        if (message == null) return;

        // Avoid duplicate messages
        if (cache.contains(message.id)) return;

        cache.put(message.id);
        Log.d(TAG, "Received message: " + message.payload + " from " + message.sender);

        // Notify listener (MainActivity or any UI)
        if (listener != null) {
            listener.onNewMessage(message);
        }
    }

    /** Send an outgoing message */
    public void sendOutgoing(String senderName, int initialTtl, String payload) {
        String now = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).format(new Date());
        MeshMessage msg = MeshMessage.createNew(senderName, initialTtl, payload, now);

        cache.put(msg.id);

        if (advertiser != null && advertiser.isSupported()) {
            try {
                advertiser.advertiseMeshMessage(msg, new BluetoothAdvertiser.AdvertiseCompleteCallback() {
                    @Override
                    public void onComplete() {
                        Log.i(TAG, "Outgoing message advertised: " + msg.payload);
                    }

                    @Override
                    public void onFailure(String reason) {
                        Log.w(TAG, "Outgoing advertise failed: " + reason);
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "advertiseMeshMessage failed", e);
            }
        } else {
            Log.w(TAG, "Advertiser not supported - cannot send");
        }
    }
}
