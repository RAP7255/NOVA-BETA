package com.example.nova.ble;

import android.util.Log;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.util.Arrays;

public final class CryptoUtil {
    private static final String TAG = "CryptoUtil";
    // 256-bit key (replace with secure storage). For demo use only.
    private static final byte[] RAW_KEY = new byte[] {
            // 32 bytes - random / placeholder; replace with secure key!
            0x01,0x02,0x03,0x04,0x05,0x06,0x07,0x08,
            0x09,0x0A,0x0B,0x0C,0x0D,0x0E,0x0F,0x10,
            0x11,0x12,0x13,0x14,0x15,0x16,0x17,0x18,
            0x19,0x1A,0x1B,0x1C,0x1D,0x1E,0x1F,0x20
    };

    private static final SecretKey SECRET_KEY = new SecretKeySpec(RAW_KEY, "AES");
    private static final String TRANS = "AES/GCM/NoPadding";
    private static final int GCM_TAG_BITS = 128;
    private static final int IV_LEN = 12; // 96 bits recommended

    private CryptoUtil() {}

    // Encrypt: returns IV || ciphertext (concatenated).
    public static byte[] encrypt(byte[] plaintext, byte[] aad) throws Exception {
        Cipher cipher = Cipher.getInstance(TRANS);
        byte[] iv = new byte[IV_LEN];
        new SecureRandom().nextBytes(iv);
        GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_BITS, iv);
        cipher.init(Cipher.ENCRYPT_MODE, SECRET_KEY, spec);
        if (aad != null) cipher.updateAAD(aad);
        byte[] ct = cipher.doFinal(plaintext);
        byte[] out = new byte[iv.length + ct.length];
        System.arraycopy(iv, 0, out, 0, iv.length);
        System.arraycopy(ct, 0, out, iv.length, ct.length);
        return out;
    }

    // Decrypt: expects IV || ciphertext.
    public static byte[] decrypt(byte[] ivAndCiphertext, byte[] aad) throws Exception {
        if (ivAndCiphertext == null || ivAndCiphertext.length < IV_LEN) {
            throw new IllegalArgumentException("Invalid ciphertext");
        }
        byte[] iv = Arrays.copyOfRange(ivAndCiphertext, 0, IV_LEN);
        byte[] ct = Arrays.copyOfRange(ivAndCiphertext, IV_LEN, ivAndCiphertext.length);
        Cipher cipher = Cipher.getInstance(TRANS);
        GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_BITS, iv);
        cipher.init(Cipher.DECRYPT_MODE, SECRET_KEY, spec);
        if (aad != null) cipher.updateAAD(aad);
        return cipher.doFinal(ct);
    }
}
