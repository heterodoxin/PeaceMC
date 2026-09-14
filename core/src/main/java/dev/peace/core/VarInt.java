package dev.peace.core;

import java.nio.charset.StandardCharsets;

/** Protocol varint encode/decode over a plain byte array cursor. Pure Java, no netty. */
public final class VarInt {
    private VarInt() {}

    public static int read(byte[] b, int[] p) {
        int out = 0, bytes = 0;
        while (true) {
            if (p[0] >= b.length) throw new IllegalArgumentException("varint eof");
            int in = b[p[0]++] & 0xFF;
            out |= (in & 0x7F) << (bytes++ * 7);
            if (bytes > 5) throw new IllegalArgumentException("varint too long");
            if ((in & 0x80) == 0) break;
        }
        return out;
    }

    public static void write(byte[] b, int[] p, int v) {
        while ((v & ~0x7F) != 0) { b[p[0]++] = (byte) ((v & 0x7F) | 0x80); v >>>= 7; }
        b[p[0]++] = (byte) v;
    }

    public static int size(int v) {
        int n = 1;
        while ((v & ~0x7F) != 0) { v >>>= 7; n++; }
        return n;
    }

    public static String readString(byte[] b, int[] p, int max) {
        int len = read(b, p);
        if (len < 0 || len > max) throw new IllegalArgumentException("string len " + len);
        if (p[0] + len > b.length) throw new IllegalArgumentException("string eof");
        String s = new String(b, p[0], len, StandardCharsets.UTF_8);
        p[0] += len;
        return s;
    }
}