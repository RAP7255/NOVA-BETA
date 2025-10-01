package com.example.nova.ble;

import android.util.Log;

import com.example.nova.model.MeshMessage;
import com.example.nova.model.MessageCache;

import org.json.JSONException;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * HopManager ties scanner + advertiser + cache together.
 */
public class HopManager implements BluetoothScanner.BluetoothScannerListener {
    private static final String TAG = "HopManager";
    private final MessageCache cache;
    private final BluetoothAdvertiser advertiser;
    private BluetoothScanner scanner; // can inject later
    private final int CHUNK_PAYLOAD_SIZE = 18; // tune this
    private final HopListener listener;

    public interface HopListener {
        void onNewMessage(MeshMessage message);
    }

    public HopManager(MessageCache cache, BluetoothAdvertiser advertiser, BluetoothScanner scanner, HopListener listener) {
        this.cache = cache;
        this.advertiser = advertiser;
        this.scanner = scanner;
        this.listener = listener;

        if (scanner != null) {
            scanner.setListener(this); // inject this HopManager as listener
        }
    }

    /** Late binding of scanner (optional) */
    public void setScanner(BluetoothScanner scanner) {
        this.scanner = scanner;
        if (scanner != null) {
            scanner.setListener(this);
        }
    }

    @Override
    public void onMessageReceived(MeshMessage message) {
        if (message == null) return;
        if (cache.contains(message.id)) {
            Log.d(TAG, "Seen message " + message.id + " - ignore");
            return;
        }

        cache.put(message.id);
        Log.i(TAG, "New message: " + message.id + " ttl=" + message.ttl);

        if (listener != null) listener.onNewMessage(message);

        if (message.ttl > 0) {
            MeshMessage toRebroadcast;
            try {
                toRebroadcast = MeshMessage.fromJsonString(message.toJsonString());
            } catch (JSONException e) {
                Log.e(TAG, "copy error: " + e.getMessage());
                return;
            }
            toRebroadcast.ttl = message.ttl - 1;
            toRebroadcast.timestamp =
                    new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).format(new Date());

            long backoff = 150 + (long) (Math.random() * 400);
            new Thread(() -> {
                try { Thread.sleep(backoff); } catch (InterruptedException ignored) {}

                if (advertiser != null && advertiser.isSupported()) {
                    try {
                        advertiser.advertiseChunks(toRebroadcast, CHUNK_PAYLOAD_SIZE,
                                new BluetoothAdvertiser.AdvertiseCompleteCallback() {
                                    @Override
                                    public void onComplete() {
                                        Log.d(TAG, "Rebroadcast complete for " + toRebroadcast.id);
                                    }

                                    @Override
                                    public void onFailure(String reason) {
                                        Log.w(TAG, "Rebroadcast failed: " + reason);
                                    }
                                });
                    } catch (Exception e) {
                        Log.e(TAG, "AdvertiseChunks failed", e);
                    }
                } else {
                    Log.w(TAG, "Advertiser not supported; cannot rebroadcast");
                }
            }).start();
        } else {
            Log.d(TAG, "TTL=0 - no rebroadcast");
        }
    }

    /** Start scanner if available */
    public void start() {
        if (scanner != null && scanner.isSupported()) {
            scanner.startScan();
        } else {
            Log.w(TAG, "Scanner not available or not supported");
        }
    }

    /** Stop scanner safely */
    public void stop() {
        if (scanner != null) {
            try { scanner.stopScan(); } catch (Exception ignored) {}
        }
    }

    /** Send an outgoing message and advertise */
    public void sendOutgoing(String senderId, int initialTtl, String payload) {
        String now = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).format(new Date());
        MeshMessage msg = MeshMessage.createNew(senderId, initialTtl, payload, now);
        cache.put(msg.id);

        if (advertiser != null && advertiser.isSupported()) {
            try {
                advertiser.advertiseChunks(msg, CHUNK_PAYLOAD_SIZE,
                        new BluetoothAdvertiser.AdvertiseCompleteCallback() {
                            @Override
                            public void onComplete() {
                                Log.i(TAG, "Outgoing message advertised " + msg.id);
                            }

                            @Override
                            public void onFailure(String reason) {
                                Log.w(TAG, "Outgoing advertise failed: " + reason);
                            }
                        });
            } catch (Exception e) {
                Log.e(TAG, "AdvertiseChunks failed", e);
            }
        } else {
            Log.w(TAG, "Advertiser not supported - cannot send");
        }
    }
}
