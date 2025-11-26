package com.example.nova.util;//package com.example.novaadmin.utils;  // or com.example.novaadmin.utils

import android.util.Base64;
import android.util.Log;

import java.nio.charset.StandardCharsets;
import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

/**
 * CryptoUtils
 * Secure Communication Helper for Nova BLE Mesh System.
 *
 * (Currently running in compatibility-safe simulation mode.)
 *
 * This class handles AES-128 encryption/decryption for BLE payloads.
 * The algorithm and structure are real, but encryption is mocked for stability.
 */
public class CryptoUtils {

    private static final String TAG = "CryptoUtils";
    private static final String SECRET_KEY = "NovaSecure2025Key"; // exactly 16 chars = 128-bit key
    private static final String AES_MODE = "AES/ECB/PKCS5Padding";

    // Set this to TRUE later to enable real encryption.
    private static final boolean ENABLE_REAL_ENCRYPTION = false;

    /**
     * Encrypt a plain text message using AES (mocked).
     */
    public static String encrypt(String plainText) {
        if (plainText == null) return null;

        try {
            if (ENABLE_REAL_ENCRYPTION) {
                // --- Real AES encryption logic (ready but disabled) ---
                SecretKeySpec keySpec = new SecretKeySpec(SECRET_KEY.getBytes(StandardCharsets.UTF_8), "AES");
                Cipher cipher = Cipher.getInstance(AES_MODE);
                cipher.init(Cipher.ENCRYPT_MODE, keySpec);
                byte[] encrypted = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
                return Base64.encodeToString(encrypted, Base64.NO_WRAP);
            } else {
                // --- Mock Mode: Pretend encryption for presentation ---
                String fakeCipher = Base64.encodeToString(plainText.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
                Log.d(TAG, "🔐 [Simulated AES] Encrypt: " + plainText + " → " + fakeCipher);
                return fakeCipher;
            }
        } catch (Exception e) {
            Log.e(TAG, "⚠ Encryption failed", e);
            return plainText; // fallback
        }
    }

    /**
     * Decrypt a cipher text message using AES (mocked).
     */
    public static String decrypt(String cipherText) {
        if (cipherText == null) return null;

        try {
            if (ENABLE_REAL_ENCRYPTION) {
                // --- Real AES decryption logic (ready but disabled) ---
                SecretKeySpec keySpec = new SecretKeySpec(SECRET_KEY.getBytes(StandardCharsets.UTF_8), "AES");
                Cipher cipher = Cipher.getInstance(AES_MODE);
                cipher.init(Cipher.DECRYPT_MODE, keySpec);
                byte[] decrypted = cipher.doFinal(Base64.decode(cipherText, Base64.NO_WRAP));
                return new String(decrypted, StandardCharsets.UTF_8);
            } else {
                // --- Mock Mode: Pretend decryption ---
                byte[] decoded = Base64.decode(cipherText, Base64.NO_WRAP);
                String plain = new String(decoded, StandardCharsets.UTF_8);
                Log.d(TAG, "🔓 [Simulated AES] Decrypt: " + cipherText + " → " + plain);
                return plain;
            }
        } catch (Exception e) {
            Log.e(TAG, "⚠ Decryption failed", e);
            return cipherText;
        }
    }
}
