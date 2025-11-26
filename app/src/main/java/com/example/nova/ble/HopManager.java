package com.example.nova.ble;

import static com.example.nova.model.Utils.bytesToHex;

import android.Manifest;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.core.content.ContextCompat;

import com.example.nova.model.MeshMessage;
import com.example.nova.model.MessageCache;
import com.example.nova.util.NotificationHelper;

import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * FINAL - BLE Mesh Hop Manager
 * Patched for OnePlus + BLE Connectable-only TX
 */
public class HopManager implements BluetoothScanner.BluetoothScannerListener {

    private static final String TAG = "HopManager";
    private static final int MAX_HOPS = 5;
    private static final long PAYLOAD_TTL_MS = 10 * 60 * 1000L;
    private static final long CLEAN_INTERVAL_MS = 60 * 1000L;

    private final ConcurrentMap<Long, byte[]> payloadMap = new ConcurrentHashMap<>();
    private final ConcurrentMap<Long, Long> payloadTimestamps = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Boolean> fetchingSet = new ConcurrentHashMap<>();

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Random random = new Random();

    private final MessageCache cache;
    private final BluetoothAdvertiser advertiser;
    private final BluetoothScanner scanner;
    private final PayloadGattClient gattClient;
    private final Context ctx;

    private GattServer gattServer;
    private HopListener listener;
    private volatile boolean running = false;

    public static HopManager hopManagerInstance;

    public interface HopListener {
        void onNewMessage(MeshMessage m);
    }

    public boolean isRunning() { return running; }
    public void setListener(HopListener l) { this.listener = l; }

    // Constructor
    public HopManager(Context ctx,
                      MessageCache cache,
                      BluetoothAdvertiser advertiser,
                      BluetoothScanner scanner,
                      HopListener listener) {

        this.ctx = ctx.getApplicationContext();
        this.cache = cache;
        this.advertiser = advertiser;
        this.scanner = scanner;
        this.listener = listener;

        if (scanner != null)
            scanner.setListener(this);

        this.gattClient = new PayloadGattClient(this.ctx);
        hopManagerInstance = this;

        startGattServerIfNeeded();
        handler.postDelayed(this::cleanupTask, CLEAN_INTERVAL_MS);

        Log.d(TAG, "HopManager INIT ✔");
    }

    private boolean check(String perm) {
        return ContextCompat.checkSelfPermission(ctx, perm) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasScanPermission() {
        return Build.VERSION.SDK_INT >= 31
                ? check(Manifest.permission.BLUETOOTH_SCAN)
                : check(Manifest.permission.ACCESS_FINE_LOCATION);
    }

    private boolean hasConnectPermission() {
        return Build.VERSION.SDK_INT >= 31
                ? check(Manifest.permission.BLUETOOTH_CONNECT)
                : true;
    }

    // ----------------------------------------------------------
    // PRESENCE BROADCAST
    // ----------------------------------------------------------
    public void broadcastPresence(String username) {

        long now = System.currentTimeMillis();
        String json = "{ \"presence\": true, \"user\": \"" + username + "\", \"time\": " + now + " }";

        MeshMessage m = MeshMessage.createNew(username, 0, json, "");
        byte[] aad = ByteBuffer.allocate(8).putLong(m.id).array();

        try {
            byte[] encrypted = CryptoUtil.encrypt(json.getBytes("UTF-8"), aad);
            m.encryptedPayload = encrypted;

            payloadMap.put(m.id, encrypted);
            payloadTimestamps.put(m.id, now);

            // FIX: Always connectable (handled in BluetoothAdvertiser)
            advertiser.advertiseMeshMessage(m, null);

        } catch (Exception e) {
            Log.e("Presence", "Encrypt error: " + e.getMessage());
        }
    }

    // ----------------------------------------------------------
    // START / STOP
    // ----------------------------------------------------------
    public void start() {

        startGattServerIfNeeded();

        if (!scanner.isSupported()) {
            Log.e(TAG, "Scanner not supported");
            return;
        }
        if (!hasScanPermission()) {
            Log.e(TAG, "SCAN PERMISSION missing");
            return;
        }

        scanner.startScan();
        running = true;
        Log.d(TAG, "Mesh START ✔");
    }

    public void stop() {
        if (scanner != null) scanner.stopScan();
        stopGattServerIfNeeded();
        running = false;
    }

    // ----------------------------------------------------------
    // Incoming HEADER
    // ----------------------------------------------------------
    @Override
    public void onMessageReceived(MeshMessage header) {

        if (header == null) return;

        long id = header.id;
        String key = String.valueOf(id);

        if (cache.contains(key)) return;
        cache.put(key);

        Log.d("MESH_DEBUG", "🟨 HEADER RECEIVED → id=" + id
                + " hop=" + header.hopCount
                + " device=" + (header.bluetoothDevice != null ? header.bluetoothDevice.getAddress() : "null"));


        byte[] cipher = payloadMap.get(id);

        if (cipher == null) {
            if (header.bluetoothDevice != null)
                fetchPayloadFromDevice(header.bluetoothDevice, id);
            return;
        }

        processDecrypted(header, cipher);
    }

    // ----------------------------------------------------------
    // DECRYPT
    // ----------------------------------------------------------
    private void processDecrypted(MeshMessage header, byte[] ciphertext) {

        Log.d("MESH_DEBUG", "🟧 DECRYPT-START → id=" + header.id);
        Log.d("MESH_DEBUG", "🟥 CIPHERTEXT (" + (ciphertext != null ? ciphertext.length : 0) + " bytes) id=" + header.id);

        try {
            if (ciphertext == null) {
                Log.w("MESH_DEBUG", "ciphertext is null for id=" + header.id);
                return;
            }

            // --------------------------------------------------------
            // 🔵 1. Detect ESP plaintext (non-encrypted)
            // --------------------------------------------------------
            String asString;
            try {
                asString = new String(ciphertext, "UTF-8");
            } catch (Exception ex) {
                asString = null;
            }

            if (asString != null && (asString.startsWith("CIPHERTEXT_FROM_ESP32") ||
                    asString.startsWith("MESH:TYPE:"))) {

                Log.d("MESH_DEBUG", "🟩 ESP-PLAINTEXT DETECTED → " + asString);

                // --------------------------------------------------------
                // Parse "KEY=VALUE" or "KEY:VALUE" pairs
                // --------------------------------------------------------
                java.util.Map<String, String> kv = new java.util.HashMap<>();
                String[] parts = asString.split(";");
                for (String part : parts) {
                    if (part == null) continue;
                    part = part.trim();
                    if (part.isEmpty()) continue;

                    int eq = part.indexOf('=');
                    int col = part.indexOf(':');

                    if (eq > 0) {
                        kv.put(part.substring(0, eq).trim().toUpperCase(),
                                part.substring(eq + 1).trim());
                    } else if (col > 0) {
                        kv.put(part.substring(0, col).trim().toUpperCase(),
                                part.substring(col + 1).trim());
                    }
                }

                // --------------------------------------------------------
                // Sender (SRC field)
                // --------------------------------------------------------
                String sender = kv.get("SRC");
                if (sender == null) sender = "ESP32";

                // --------------------------------------------------------
                // Message body (MSG field)
                // --------------------------------------------------------
                String msgText = kv.get("MSG");
                if (msgText == null) msgText = asString;   // fallback

                // --------------------------------------------------------
                // UI timestamp (required)
                // --------------------------------------------------------
                String ts = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                        .format(new Date());

                // --------------------------------------------------------
                // Build MeshMessage correctly using your class fields
                // --------------------------------------------------------
                MeshMessage m = new MeshMessage();

                m.id = header.id;         // keep original ID
                m.hopCount = header.hopCount;
                m.sender = sender;
                m.payload = msgText;
                m.timestamp = ts;
                m.encryptedPayload = null;        // ESP does not encrypt
                m.bluetoothDevice = header.bluetoothDevice;

                // --------------------------------------------------------
                // IMPORTANT: Store ciphertext so we don't refetch (prevents timeouts)
                // --------------------------------------------------------
                payloadMap.put(m.id, ciphertext);
                payloadTimestamps.put(m.id, System.currentTimeMillis());

                // --------------------------------------------------------
                // Deliver to UI/listener
                // --------------------------------------------------------
                if (listener != null)
                    listener.onNewMessage(m);

                // --------------------------------------------------------
                // Trigger user-facing notification for ESP alerts
                // --------------------------------------------------------
                NotificationHelper.showNotification(
                        ctx,
                        "Emergency Alert",
                        m.sender + ": " + m.payload,
                        null
                );

                // --------------------------------------------------------
                // Rebroadcast raw ciphertext
                // --------------------------------------------------------
                if (m.hopCount < MAX_HOPS)
                    scheduleRebroadcast(m);

                return;   // 🚨 stop here — do NOT AES decrypt
            }

            // --------------------------------------------------------
            // 🔵 2. Normal AES decryption for phone→phone messages
            // --------------------------------------------------------
            byte[] aad = ByteBuffer.allocate(8).putLong(header.id).array();
            byte[] plain = CryptoUtil.decrypt(ciphertext, aad);

            String json = new String(plain, "UTF-8");
            Log.d("MESH_PAYLOAD", "🟩 DECRYPTED → " + json);

            MeshMessage.parseJsonInto(header, json);

            if (listener != null)
                listener.onNewMessage(header);

            if (header.hopCount < MAX_HOPS)
                scheduleRebroadcast(header);

        } catch (Exception e) {
            Log.e("MESH_DEBUG", "❌ DECRYPT FAIL id=" + header.id + " error=" + e.getMessage());
        }
    }


    // ----------------------------------------------------------
    // OUTGOING USER MESSAGE (connectable)
    // ----------------------------------------------------------
    public MeshMessage sendOutgoing(String sender, int hop, String text) {

        String ts = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
        MeshMessage m = MeshMessage.createNew(sender, hop, text, ts);

        cache.put(String.valueOf(m.id));

        try {
            byte[] aad = ByteBuffer.allocate(8).putLong(m.id).array();
            byte[] jsonBytes = MeshMessage.buildJsonPayload(sender, text, ts);

            m.encryptedPayload = CryptoUtil.encrypt(jsonBytes, aad);

            payloadMap.put(m.id, m.encryptedPayload);
            payloadTimestamps.put(m.id, System.currentTimeMillis());

            startGattServerIfNeeded();

        } catch (Exception ex) {
            Log.e(TAG, "Encrypt fail: " + ex.getMessage());
            return null;
        }

        advertiser.advertiseMeshMessage(m, null);

        Log.i(TAG, "OUTGOING id=" + m.id);
        return m;
    }

    // ----------------------------------------------------------
    // REBROADCAST
    // ----------------------------------------------------------
    private void scheduleRebroadcast(MeshMessage h) {
        handler.postDelayed(() -> rebroadcast(h),
                80 + random.nextInt(200));
    }

    private void rebroadcast(MeshMessage old) {

        startGattServerIfNeeded();

        MeshMessage m = old.copy();
        m.hopCount++;

        byte[] cipher = payloadMap.get(m.id);
        if (cipher == null) return;

        m.encryptedPayload = cipher;

        advertiser.advertiseMeshMessage(m, null);

        Log.d(TAG, "REBROADCAST id=" + m.id + " hop=" + m.hopCount);
    }

    // ----------------------------------------------------------
    // GATT SERVER
    // ----------------------------------------------------------
    private void startGattServerIfNeeded() {
        if (gattServer != null) return;
        if (!hasConnectPermission()) return;

        gattServer = new GattServer(ctx);
        gattServer.start();
    }

    private void stopGattServerIfNeeded() {
        if (gattServer != null) gattServer.stop();
        gattServer = null;
    }

    // ----------------------------------------------------------
    // GATT FETCH
    // ----------------------------------------------------------
    public void fetchPayloadFromDevice(BluetoothDevice dev, long id) {

        if (dev == null) return;
        if (fetchingSet.putIfAbsent(id, true) != null) return;

        // 🔵 Log: starting GATT fetch
        Log.d("MESH_DEBUG", "🟦 GATT-FETCH START → id=" + id
                + " from " + dev.getAddress());

        gattClient.fetchPayload(dev, id, new PayloadGattClient.Callback() {

            @Override
            public void onPayload(byte[] cipher) {

                // 🟩 Log: Cipher received
                Log.d("MESH_DEBUG", "🟩 GATT-FETCH SUCCESS → id=" + id
                        + " bytes=" + (cipher != null ? cipher.length : 0));

                try {
                    payloadMap.put(id, cipher);
                    payloadTimestamps.put(id, System.currentTimeMillis());

                    MeshMessage m = new MeshMessage();
                    m.id = id;

                    // 🟧 Log: Decryption starting
                    Log.d("MESH_DEBUG", "🟧 DECRYPT-START → id=" + id);

                    // optional: log raw hex
                    Log.d("MESH_DEBUG", "🟥 CIPHERTEXT HEX → " + bytesToHex(cipher));

                    processDecrypted(m, cipher);

                } finally {
                    fetchingSet.remove(id);
                }
            }

            @Override
            public void onError(String reason) {
                // ❌ Log: Error
                Log.w("MESH_DEBUG", "❌ GATT-FETCH FAIL → id=" + id
                        + " device=" + dev.getAddress()
                        + " reason=" + reason);

                fetchingSet.remove(id);
            }
        });
    }


    public byte[] getStoredCiphertext(long id) {
        return payloadMap.get(id);
    }

    // ----------------------------------------------------------
    // CLEANUP
    // ----------------------------------------------------------
    private void cleanupTask() {

        long now = System.currentTimeMillis();

        for (Long id : payloadTimestamps.keySet()) {

            Long ts = payloadTimestamps.get(id);

            if (ts != null && now - ts > PAYLOAD_TTL_MS) {

                payloadMap.remove(id);
                payloadTimestamps.remove(id);
                fetchingSet.remove(id);

                Log.d(TAG, "CLEAN: removed id=" + id);
            }
        }

        handler.postDelayed(this::cleanupTask, CLEAN_INTERVAL_MS);
    }
}
