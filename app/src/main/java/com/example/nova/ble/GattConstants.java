package com.example.nova.ble;

import java.util.UUID;

public final class GattConstants {

    private GattConstants() {}

    // HEADER SERVICE (for scanning)
    public static final UUID SERVICE_HEADER_UUID =
            UUID.fromString("0000FEED-0000-1000-8000-00805F9B34FB");

    // MAIN MESH PAYLOAD GATT SERVICE
    public static final UUID SERVICE_MESH_GATT =
            UUID.fromString("0000BEEF-0000-1000-8000-00805F9B34FB");

    // Write: client sends messageId
    public static final UUID CHAR_REQUEST_MESSAGE =
            UUID.fromString("0000BEE1-0000-1000-8000-00805F9B34FB");

    // Read: server returns ciphertext payload
    public static final UUID CHAR_FETCH_CIPHERTEXT =
            UUID.fromString("0000BEE2-0000-1000-8000-00805F9B34FB");
}
