package com.example.nova.model;

import java.security.MessageDigest;

public class Utils {
    // produce 8-byte hash from string (sha256 first 8 bytes)
    public static byte[] sha256_8(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(s.getBytes("UTF-8"));
            byte[] out = new byte[8];
            System.arraycopy(d, 0, out, 0, 8);
            return out;
        } catch (Exception e) {
            // fallback
            byte[] out = new byte[8];
            byte[] bs = s.getBytes();
            for (int i = 0; i < 8 && i < bs.length; i++) out[i] = bs[i];
            return out;
        }
    }

    public static final char[] HEX_ARRAY = "0123456789ABCDEF".toCharArray();
    public static String bytesToHex(final byte[] bytes) {
        char[] hexChars = new char[bytes.length * 2];
        for (int j = 0; j < bytes.length; j++) {
            int v = bytes[j] & 0xFF;
            hexChars[j * 2] = HEX_ARRAY[v >>> 4];
            hexChars[j * 2 + 1] = HEX_ARRAY[v & 0x0F];
        }
        return new String(hexChars);
    }
}
