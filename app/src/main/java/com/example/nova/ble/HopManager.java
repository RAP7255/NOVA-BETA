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
    }

    /** Dynamically attach or replace listener (e.g., from MainActivity) */
    public void setListener(HopListener listener) {
        this.listener = listener;
    }

    /** Start scanner */
    public void start() {
        if (scanner != null && scanner.isSupported()) {
            scanner.startScan();
            Log.d(TAG, "Scanner started");
        } else {
            Log.w(TAG, "Scanner not available");
        }
    }

    /** Stop scanner */
    public void stop() {
        if (scanner != null) {
            try { scanner.stopScan(); } catch (Exception ignored) {}
        }
    }

    /** Receive message callback from scanner */
    @Override
    public void onMessageReceived(MeshMessage message) {
        if (message == null) return;
        if (cache.contains(message.id)) return;

        cache.put(message.id);
        Log.d(TAG, "Received message: " + message.payload + " from " + message.sender);

        if (listener != null) listener.onNewMessage(message);
    }

    /** Send a single outgoing message */
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
