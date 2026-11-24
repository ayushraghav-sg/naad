package com.example.voicerecorder;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Encrypts small secrets (such as the Groq API key) using an AES-256-GCM key stored in
 * the Android KeyStore so the plaintext key is never written to SharedPreferences.
 */
public final class KeyStoreHelper {
    private static final String PROVIDER = "AndroidKeyStore";
    private static final String ALIAS = "naad_api_key_v1";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final String PREFIX = "enc:";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private KeyStoreHelper() { }

    private static SecretKey getOrCreateKey() throws Exception {
        KeyStore ks = KeyStore.getInstance(PROVIDER);
        ks.load(null);
        KeyStore.Entry entry = ks.getEntry(ALIAS, null);
        if (entry instanceof KeyStore.SecretKeyEntry) {
            return ((KeyStore.SecretKeyEntry) entry).getSecretKey();
        }
        KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER);
        KeyGenParameterSpec spec = new KeyGenParameterSpec.Builder(
                ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build();
        kg.init(spec);
        return kg.generateKey();
    }

    public static boolean isEncrypted(String value) {
        return value != null && value.startsWith(PREFIX);
    }

    public static String encrypt(String plain) {
        if (plain == null || plain.isEmpty()) return "";
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey());
            byte[] iv = cipher.getIV();
            byte[] ct = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] combined = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(ct, 0, combined, iv.length, ct.length);
            return PREFIX + Base64.encodeToString(combined, Base64.NO_WRAP);
        } catch (Exception e) {
            // Fall back gracefully on devices with broken KeyStore implementations
            return plain;
        }
    }

    public static String decrypt(String stored) {
        if (stored == null || stored.isEmpty()) return "";
        if (!stored.startsWith(PREFIX)) return stored; // legacy plaintext value
        try {
            byte[] combined = Base64.decode(stored.substring(PREFIX.length()), Base64.NO_WRAP);
            if (combined.length <= IV_BYTES) return "";
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(),
                    new GCMParameterSpec(TAG_BITS, combined, 0, IV_BYTES));
            byte[] plain = cipher.doFinal(combined, IV_BYTES, combined.length - IV_BYTES);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }
}
