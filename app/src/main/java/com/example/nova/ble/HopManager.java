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
 * Patched HopManager (FINAL)
 * - With messageFetchLock (prevents duplicate GATT fetch)
 * - Stable with new PayloadGattClient device lock
 * - OEM safe
 * - Bounded retry/backoff for fetchPayloadFromDevice
 */
public class HopManager implements BluetoothScanner.BluetoothScannerListener {

    private static final String TAG = "HopManager";
    private static final int MAX_HOPS = 5;
    private static final long PAYLOAD_TTL_MS = 10 * 60 * 1000L;
    private static final long CLEAN_INTERVAL_MS = 60 * 1000L;

    private final ConcurrentMap<Long, byte[]> payloadMap = new ConcurrentHashMap<>();
    private final ConcurrentMap<Long, Long> payloadTimestamps = new ConcurrentHashMap<>();

    // 🔵 NEW: Prevent duplicate fetch for same message id
    private final ConcurrentHashMap<Long, Boolean> messageFetchLock = new ConcurrentHashMap<>();

    // 🔵 NEW: retry counter for fetch attempts
    private final ConcurrentHashMap<Long, Integer> fetchRetryCount = new ConcurrentHashMap<>();

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Random random = new Random();

    private final MessageCache cache;
    private final BluetoothAdvertiser advertiser;
    private final BluetoothScanner scanner;
    private final PayloadGattClient gattClient;
    private final Context ctx;

    // GATT server instance
    private GattServer gattServer;

    private HopListener listener;
    private volatile boolean running = false;

    public static HopManager hopManagerInstance;

    public interface HopListener {
        void onNewMessage(MeshMessage m);
    }

    public boolean isRunning() { return running; }
    public void setListener(HopListener l) { this.listener = l; }

    // Retry policy
    private static final int MAX_FETCH_RETRIES = 5;
    private static final long BASE_RETRY_DELAY_MS = 1500L; // backoff base
    private static final long MIN_RETRY_DELAY_MS = 400L; // legacy/minimum (not used for repeated failures)

    // ----------------------------------------------------------
    // CONSTRUCTOR
    // ----------------------------------------------------------
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
        return Build.VERSION.SDK_INT >= 31 ?
                check(Manifest.permission.BLUETOOTH_SCAN) :
                check(Manifest.permission.ACCESS_FINE_LOCATION);
    }

    private boolean hasConnectPermission() {
        return Build.VERSION.SDK_INT >= 31 ?
                check(Manifest.permission.BLUETOOTH_CONNECT) : true;
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

            if (gattServer != null)
                gattServer.notifyAllSubscribed(encrypted);

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
        try { if (scanner != null) scanner.stopScan(); } catch (Exception ignored) {}

        stopGattServer();
        running = false;
        hopManagerInstance = null;

        Log.d(TAG, "HopManager stopped");
    }

    // ----------------------------------------------------------
    // HEADER RECEIVED
    // ----------------------------------------------------------
    @Override
    public void onMessageReceived(MeshMessage header) {

        if (header == null) return;

        long id = header.id;

        if (cache.contains(String.valueOf(id))) return;
        cache.put(String.valueOf(id));

        Log.d("MESH_DEBUG", "🟨 HEADER RECEIVED → id=" + id
                + " hop=" + header.hopCount
                + " device=" + (header.bluetoothDevice != null ?
                header.bluetoothDevice.getAddress() : "null"));

        byte[] cipher = payloadMap.get(id);

        if (cipher != null) {
            processDecrypted(header, cipher);
            return;
        }

        // FETCH if device exists & we don't already have the ciphertext
        if (header.bluetoothDevice != null)
            fetchPayloadFromDevice(header.bluetoothDevice, id);
    }

    // ----------------------------------------------------------
    // DECRYPT PROCESS
    // ----------------------------------------------------------
    private void processDecrypted(MeshMessage header, byte[] ciphertext) {

        if (ciphertext == null) return;

        payloadMap.put(header.id, ciphertext);
        payloadTimestamps.put(header.id, System.currentTimeMillis());

        if (gattServer != null)
            gattServer.notifyAllSubscribed(ciphertext);

        Log.d("MESH_DEBUG", "🟧 DECRYPT-START → id=" + header.id);

        try {
            String asString = null;
            try { asString = new String(ciphertext, "UTF-8"); } catch (Exception ignore) {}

            // ESP plaintext detection
            if (asString != null &&
                    (asString.startsWith("CIPHERTEXT_FROM_ESP32") ||
                            asString.startsWith("MESH:TYPE:"))) {

                MeshMessage m = parseEspPlaintext(header, asString);
                if (m == null) return;

                if (listener != null)
                    listener.onNewMessage(m);

                NotificationHelper.showNotification(
                        ctx, "ESP Alert", m.payload, null
                );

                if (m.hopCount < MAX_HOPS)
                    scheduleRebroadcast(m);

                return;
            }

            // AES decrypt path
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
            Log.e("MESH_DEBUG", "❌ DECRYPT FAIL id=" + header.id + " error=" + e);
        }
    }

    private MeshMessage parseEspPlaintext(MeshMessage header, String raw) {
        try {
            java.util.Map<String, String> map = new java.util.HashMap<>();
            for (String part : raw.split(";")) {
                int eq = part.indexOf('=');
                int col = part.indexOf(':');
                if (eq > 0)
                    map.put(part.substring(0, eq).toUpperCase(), part.substring(eq + 1));
                else if (col > 0)
                    map.put(part.substring(0, col).toUpperCase(), part.substring(col + 1));
            }

            MeshMessage m = new MeshMessage();
            m.id = header.id;
            m.hopCount = header.hopCount;
            m.sender = map.getOrDefault("SRC", "ESP32");
            m.payload = map.getOrDefault("MSG", raw);
            m.bluetoothDevice = header.bluetoothDevice;
            m.timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                    .format(new Date());
            return m;

        } catch (Exception e) {
            Log.e("ESP_PARSE", "fail: " + e);
            return null;
        }
    }

    // ----------------------------------------------------------
    // OUTGOING
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

            if (gattServer != null)
                gattServer.notifyAllSubscribed(m.encryptedPayload);

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
                120 + random.nextInt(180));
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
    // GATT SERVER MANAGEMENT
    // ----------------------------------------------------------
    private final Object gattLock = new Object();

    private void startGattServerIfNeeded() {
        synchronized (gattLock) {
            if (gattServer != null) return;
            if (!hasConnectPermission()) return;

            try {
                gattServer = new GattServer(ctx);
                gattServer.start();
                Log.d(TAG, "GattServer started");
            } catch (Exception e) {
                Log.e(TAG, "Failed to start GattServer", e);
                gattServer = null;
            }
        }
    }

    public void stopGattServer() {
        synchronized (gattLock) {
            if (gattServer != null) {
                try {
                    gattServer.stop();
                    Log.d(TAG, "GattServer stopped");
                } catch (Exception e) {
                    Log.e(TAG, "Error stopping GattServer", e);
                } finally {
                    gattServer = null;
                }
            }
        }
    }

    public byte[] getStoredCiphertext(long id) {
        try {
            return payloadMap.get(id);
        } catch (Exception e) {
            Log.e("HopManager", "getStoredCiphertext error: " + e);
            return null;
        }
    }

    // ----------------------------------------------------------
    // FETCH PAYLOAD (patched)
    // ----------------------------------------------------------
    public void fetchPayloadFromDevice(BluetoothDevice dev, long id) {

        if (dev == null) return;

        // 🔵 Prevent duplicate fetch attempts
        synchronized (messageFetchLock) {
            if (messageFetchLock.putIfAbsent(id, true) != null) {
                Log.d("MESH_DEBUG", "Fetch already in-progress for id=" + id);
                return; // already fetching this id
            }
        }

        int currentRetry = fetchRetryCount.getOrDefault(id, 0);
        if (currentRetry >= MAX_FETCH_RETRIES) {
            Log.w("MESH_DEBUG", "Max retries reached for id=" + id + " — aborting fetch");
            // ensure lock removed to avoid deadlocks
            messageFetchLock.remove(id);
            return;
        }

        String devAddr = dev != null ? dev.getAddress() : "null";
        Log.d("MESH_DEBUG", "🟦 GATT-FETCH START → id=" + id
                + " from " + devAddr + " retry=" + currentRetry);

        gattClient.fetchPayload(dev, id, new PayloadGattClient.Callback() {

            @Override
            public void onPayload(byte[] cipher) {

                Log.d("MESH_DEBUG", "🟩 GATT-FETCH SUCCESS → id=" + id);

                try {
                    // reset retry counter
                    fetchRetryCount.remove(id);

                    payloadMap.put(id, cipher);
                    payloadTimestamps.put(id, System.currentTimeMillis());
                    cache.put(String.valueOf(id));

                    MeshMessage h = new MeshMessage();
                    h.id = id;
                    h.hopCount = 0;
                    h.bluetoothDevice = dev;

                    processDecrypted(h, cipher);

                } finally {
                    messageFetchLock.remove(id);
                }
            }

            @Override
            public void onError(String reason) {

                int nextRetry = fetchRetryCount.getOrDefault(id, 0) + 1;
                fetchRetryCount.put(id, nextRetry);

                Log.w("MESH_DEBUG", "❌ GATT-FETCH FAIL → id=" + id
                        + " dev=" + devAddr
                        + " reason=" + reason
                        + " retry=" + nextRetry);

                // release lock for this id (so other threads aren't blocked)
                messageFetchLock.remove(id);

                // If retries exceeded, abort further attempts
                if (nextRetry >= MAX_FETCH_RETRIES) {
                    Log.w("MESH_DEBUG", "Aborting fetch for id=" + id + " after " + nextRetry + " attempts");
                    return;
                }

                // Backoff: increase delay with each retry to avoid flooding BLE stack
                long delay = BASE_RETRY_DELAY_MS + (nextRetry * 300L);

                // For transient 'DeviceBusy' or 'GATT_FAIL_257' prefer slightly longer backoff
                if (reason != null && (reason.contains("DeviceBusy") || reason.contains("GATT_FAIL_257"))) {
                    delay += 800;
                }

                // Schedule retry
                handler.postDelayed(() ->
                        fetchPayloadFromDevice(dev, id), delay);
            }
        });
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
                messageFetchLock.remove(id);
                fetchRetryCount.remove(id);

                Log.d(TAG, "CLEAN: removed id=" + id);
            }
        }

        handler.postDelayed(this::cleanupTask, CLEAN_INTERVAL_MS);
    }
}
