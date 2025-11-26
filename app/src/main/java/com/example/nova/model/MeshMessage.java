package com.example.nova.model;

import android.bluetooth.BluetoothDevice;

import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MeshMessage - encrypted JSON model
 */
public class MeshMessage {

    public static final int VERSION = 1;

    // header
    public long id;
    public int hopCount = 0;

    // encrypted bytes (null for ESP32 SOS broadcast)
    public byte[] encryptedPayload;

    // metadata
    public String sender;
    public String payload;
    public String timestamp;

    // device reference for GATT
    public BluetoothDevice bluetoothDevice;

    // ---------------------------------------------------------
    // Create outgoing (normal app) message
    // ---------------------------------------------------------
    public static MeshMessage createNew(String sender,
                                        int initialHop,
                                        String text,
                                        String ts) {

        MeshMessage m = new MeshMessage();

        m.id = UUID.randomUUID().getMostSignificantBits() ^ System.nanoTime();
        m.hopCount = initialHop;

        m.sender = sender;
        m.payload = text;
        m.timestamp = ts;

        return m;
    }

    // ---------------------------------------------------------
    // Build JSON (to encrypt)
    // ---------------------------------------------------------
    public static byte[] buildJsonPayload(String sender,
                                          String message,
                                          String timestamp) {

        try {
            JSONObject obj = new JSONObject();
            obj.put("sender", sender);
            obj.put("message", message);
            obj.put("timestamp", timestamp);

            return obj.toString().getBytes(StandardCharsets.UTF_8);

        } catch (JSONException e) {
            return null;
        }
    }

    // ---------------------------------------------------------
    // Parse JSON after decrypt
    // ---------------------------------------------------------
    public static void parseJsonInto(MeshMessage m, String jsonString) {
        try {
            JSONObject obj = new JSONObject(jsonString);

            m.sender = obj.optString("sender", "Unknown");
            m.payload = obj.optString("message", "");
            m.timestamp = obj.optString("timestamp", "");

        } catch (Exception e) {
            m.sender = "Unknown";
            m.payload = jsonString; // fallback
        }
    }

    // ---------------------------------------------------------
    // COPY
    // ---------------------------------------------------------
    public MeshMessage copy() {
        MeshMessage m = new MeshMessage();
        m.id = this.id;
        m.hopCount = this.hopCount;
        m.encryptedPayload = this.encryptedPayload;
        m.sender = this.sender;
        m.payload = this.payload;
        m.timestamp = this.timestamp;
        m.bluetoothDevice = this.bluetoothDevice;
        return m;
    }

    // ---------------------------------------------------------
    // DEDUP CACHE
    // ---------------------------------------------------------
    private static ConcurrentHashMap<Long, Boolean> localCache;
    public static ConcurrentHashMap<Long, Boolean> getCache() {
        if (localCache == null) localCache = new ConcurrentHashMap<>();
        return localCache;
    }

    // ---------------------------------------------------------
    // 📌 NEW → SOS Message from ESP32 (raw advertisement)
    // ---------------------------------------------------------
    public static MeshMessage sosFromESP32() {
        MeshMessage m = new MeshMessage();

        // Unique ID (similar to normal messages)
        m.id = UUID.randomUUID().getMostSignificantBits() ^ System.nanoTime();

        m.hopCount = 0;
        m.sender = "ESP32";
        m.payload = "SOS";   // plain text alert
        m.timestamp = String.valueOf(System.currentTimeMillis());

        // no encryption
        m.encryptedPayload = null;

        return m;
    }
}
