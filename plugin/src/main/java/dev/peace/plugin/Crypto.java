package dev.peace.plugin;import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;

/** AES-256-GCM frames keyed by SHA-256 of the PSK; a valid tag proves key possession. */
public final class Crypto {
    private static final SecureRandom RNG = new SecureRandom();
    private final SecretKeySpec key;

    public Crypto(String psk) {
        try {
            byte[] k = MessageDigest.getInstance("SHA-256").digest(psk.getBytes(StandardCharsets.UTF_8));
            this.key = new SecretKeySpec(k, "AES");
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    public byte[] encrypt(byte[] plain) {
        try {
            byte[] iv = new byte[12];
            RNG.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
            byte[] ct = c.doFinal(plain);
            byte[] out = new byte[12 + ct.length];
            System.arraycopy(iv, 0, out, 0, 12);
            System.arraycopy(ct, 0, out, 12, ct.length);
            return out;
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    /** Returns null on any auth/format failure (wrong key, tampered frame). */
    public byte[] decrypt(byte[] wire) {
        try {
            if (wire.length < 28) return null;
            byte[] iv = Arrays.copyOfRange(wire, 0, 12);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));
            return c.doFinal(wire, 12, wire.length - 12);
        } catch (Exception e) { return null; }
    }

}
