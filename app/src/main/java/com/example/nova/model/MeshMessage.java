package com.example.nova.model;

import android.util.Base64;

import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * MeshMessage model used by advertiser/scanner/hop manager.
 *
 * JSON fields: id, sender, ttl, payload (string), timestamp
 *
 * Chunking: produces Base64 chunks from the JSON string. buildChunkPayload
 * formats the service-data as:
 * [version:1][msgIdHash8:8][seqIndex:1][total:1][ttl:1][base64 bytes...]
 */
public class MeshMessage {
    public static final int VERSION = 1;

    public String id;
    public String sender;      // updated
    public int ttl;
    public String payload;
    public String timestamp;
    public String senderId;
    public MeshMessage() {}

    public static MeshMessage createNew(String sender, int ttl, String payload, String timestamp) {
        MeshMessage m = new MeshMessage();
        m.id = UUID.randomUUID().toString();
        m.sender = sender;
        m.ttl = ttl;
        m.payload = payload;
        m.timestamp = timestamp;
        return m;
    }

    // JSON serialization
    public String toJsonString() {
        JSONObject o = new JSONObject();
        try {
            o.put("id", id);
            o.put("sender", sender);
            o.put("ttl", ttl);
            o.put("payload", payload);
            o.put("timestamp", timestamp);
        } catch (JSONException ignored) {}
        return o.toString();
    }

    public static MeshMessage fromJsonString(String json) throws JSONException {
        JSONObject o = new JSONObject(json);
        MeshMessage m = new MeshMessage();
        m.id = o.getString("id");
        m.sender = o.optString("sender", "");
        m.ttl = o.optInt("ttl", 0);
        m.payload = o.optString("payload", "");
        m.timestamp = o.optString("timestamp", "");
        return m;
    }

    // Getters for adapter
    public String getSender() { return sender; }
    public String getPayload() { return payload; }
    public String getTimestamp() { return timestamp; }

    // Base64 chunking
    public List<String> chunkedBase64(int chunkPayloadSize) {
        String json = toJsonString();
        byte[] raw = json.getBytes(StandardCharsets.UTF_8);
        String base64 = Base64.encodeToString(raw, Base64.NO_WRAP);
        List<String> out = new ArrayList<>();
        int i = 0;
        while (i < base64.length()) {
            int end = Math.min(i + chunkPayloadSize, base64.length());
            out.add(base64.substring(i, end));
            i = end;
        }
        if (out.isEmpty()) out.add("");
        return out;
    }

    // Rebuild JSON from concatenated base64
    public static String rebuildJsonFromBase64Concatenation(String concatenatedBase64) {
        byte[] raw = Base64.decode(concatenatedBase64, Base64.NO_WRAP);
        return new String(raw, StandardCharsets.UTF_8);
    }

    // 8-byte msgId hash
    public static byte[] msgIdHash8(String id) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] d = md.digest(id.getBytes(StandardCharsets.UTF_8));
        byte[] out = new byte[8];
        System.arraycopy(d, 0, out, 0, 8);
        return out;
    }

    /**
     * Build service-data payload for a chunk.
     * Format:
     * [version:1][msgIdHash8:8][seqIndex:1][total:1][ttl:1][base64 bytes...]
     */
    public static byte[] buildChunkPayload(byte versionByte, byte[] msgIdHash8, int seqIndex, int total, int ttl, String base64Chunk) {
        byte[] base64Bytes = base64Chunk.getBytes(StandardCharsets.UTF_8);
        int headerLen = 1 + 8 + 1 + 1 + 1;
        byte[] out = new byte[headerLen + base64Bytes.length];
        int p = 0;
        out[p++] = versionByte;
        System.arraycopy(msgIdHash8, 0, out, p, 8); p += 8;
        out[p++] = (byte) (seqIndex & 0xFF);
        out[p++] = (byte) (total & 0xFF);
        out[p++] = (byte) (ttl & 0xFF);
        System.arraycopy(base64Bytes, 0, out, p, base64Bytes.length);
        return out;
    }
}
