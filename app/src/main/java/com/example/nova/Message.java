package com.example.nova;

import java.nio.ByteBuffer;
import java.util.UUID;

public class Message {

    private long messageId;      // unique id for dedup
    private int hopCount;        // number of hops
    private boolean hasPayload;  // indicates if GATT payload exists

    private String sender;       // your existing fields
    private String content;
    private String date;

    // ----------- CONSTRUCTOR FOR NORMAL MESSAGE -----------
    public Message(String sender, String content, String date) {
        this.sender = sender;
        this.content = content;
        this.date = date;

        this.messageId = System.currentTimeMillis(); // unique ID
        this.hopCount = 0;
        this.hasPayload = true;
    }

    // ----------- EMPTY CONSTRUCTOR FOR PARSING HEADER -----------
    public Message(long id) {
        this.messageId = id;
        this.hopCount = 0;
        this.hasPayload = false;
    }

    // ----------- GETTERS -----------
    public long getMessageId() { return messageId; }
    public int getHopCount() { return hopCount; }
    public boolean hasPayload() { return hasPayload; }

    public String getSender() { return sender; }
    public String getContent() { return content; }
    public String getDate() { return date; }

    // ----------- SETTERS -----------
    public void setHopCount(int hop) { this.hopCount = hop; }
    public void incrementHopCount() { this.hopCount++; }

    public void setHasPayload(boolean flag) { this.hasPayload = flag; }

    public void setContent(String content) { this.content = content; }
    public void setSender(String sender) { this.sender = sender; }
    public void setDate(String date) { this.date = date; }

    // =============================================================
    //              HEADER PACKING (BLE ADVERTISING)
    // =============================================================
    // 11 bytes total:
    // version (1) | messageId (8) | hopCount (1) | hasPayload (1)
    public byte[] toHeader() {
        ByteBuffer bb = ByteBuffer.allocate(11);
        bb.put((byte) 1); // version
        bb.putLong(messageId);
        bb.put((byte) hopCount);
        bb.put((byte)(hasPayload ? 1 : 0));
        return bb.array();
    }

    // =============================================================
    //              PARSE HEADER BACK INTO MESSAGE OBJECT
    // =============================================================
    public static Message parseHeader(byte[] data) {
        ByteBuffer bb = ByteBuffer.wrap(data);

        byte version = bb.get();
        long msgId = bb.getLong();
        int hop = bb.get() & 0xFF;
        boolean hasPayload = bb.get() == 1;

        Message m = new Message(msgId);
        m.setHopCount(hop);
        m.setHasPayload(hasPayload);

        return m;
    }

    // =============================================================
    //            CONVERT PLAINTEXT TO BYTE[] FOR ENCRYPTION
    // =============================================================
    public byte[] getPlaintextForEncryption() {
        // Your message content (sender + content + date)
        String full = sender + "|" + content + "|" + date;
        return full.getBytes();
    }

    // =============================================================
    //     RESTORE PLAINTEXT AFTER DECRYPTION BACK INTO FIELDS
    // =============================================================
    public void applyDecryptedData(byte[] plaintext) {
        String full = new String(plaintext);
        String[] parts = full.split("\\|");

        if (parts.length >= 3) {
            this.sender = parts[0];
            this.content = parts[1];
            this.date = parts[2];
        }
    }
}
