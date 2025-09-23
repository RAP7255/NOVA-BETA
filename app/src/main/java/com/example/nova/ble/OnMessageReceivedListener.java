package com.example.nova.ble;

/**
 * Interface to provide a callback mechanism for received BLE messages.
 * This decouples the BLEManager from specific Activities or Fragments.
 */

public interface OnMessageReceivedListener {
    void onMessageReceived(String message);
}
