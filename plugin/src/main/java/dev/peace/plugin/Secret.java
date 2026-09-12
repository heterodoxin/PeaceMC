package dev.peace.plugin;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/** Encrypt/decrypt secrets using AES-256-GCM with key from PEACE_SECRET_KEY env var. */
public final class Secret {
    private static final SecureRandom RNG = new SecureRandom();
    private static SecretKeySpec KEY = null;

    private static SecretKeySpec getKey() {
        if (KEY != null) return KEY;
        String keyStr = System.getenv("PEACE_SECRET_KEY");
        if (keyStr == null || keyStr.isEmpty()) {
            keyStr = "peace-secret-key-change-me-in-production";
        }
        try {
            KEY = new SecretKeySpec(MessageDigest.getInstance("SHA-256").digest(keyStr.getBytes(StandardCharsets.UTF_8)), "AES");
        } catch (Exception e) { throw new RuntimeException(e); }
        return KEY;
    }

    /** Encrypt plaintext to Base64 string. */
    public static String encrypt(String plain) {
        try {
            byte[] iv = new byte[12];
            RNG.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, getKey(), new GCMParameterSpec(128, iv));
            byte[] ct = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[12 + ct.length];
            System.arraycopy(iv, 0, out, 0, 12);
            System.arraycopy(ct, 0, out, 12, ct.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    /** Decrypt Base64 string to plaintext; returns null on failure. */
    public static String decrypt(String cipherB64) {
        try {
            byte[] buf = Base64.getDecoder().decode(cipherB64);
            if (buf.length < 28) return null;
            byte[] iv = new byte[12];
            System.arraycopy(buf, 0, iv, 0, 12);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, getKey(), new GCMParameterSpec(128, iv));
            return new String(c.doFinal(buf, 12, buf.length - 12), StandardCharsets.UTF_8);
        } catch (Exception e) { return null; }
    }
}