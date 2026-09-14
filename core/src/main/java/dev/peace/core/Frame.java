package dev.peace.core;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

/** Chunks one encrypted message into plugin-message packets to stay under the ~32KB channel cap. */
public final class Frame {
    public static final int MAX_CHUNK = 24000;

    private Frame() {}

    public static byte[][] pack(long msgId, byte[] cipher) {
        int total = Math.max(1, (cipher.length + MAX_CHUNK - 1) / MAX_CHUNK);
        byte[][] out = new byte[total][];
        for (int i = 0; i < total; i++) {
            int off = i * MAX_CHUNK, len = Math.min(MAX_CHUNK, cipher.length - off);
            ByteBuffer b = ByteBuffer.allocate(12 + len);
            b.putLong(msgId).putShort((short) i).putShort((short) total).put(cipher, off, len);
            out[i] = b.array();
        }
        return out;
    }

    /** Not thread-safe; guard externally or use per-connection. */
    public static final class Reassembler {
        private final Map<Long, byte[][]> parts = new HashMap<>();
        private final Map<Long, Integer> got = new HashMap<>();

        /** Returns the full ciphertext once all chunks arrive, else null. */
        public byte[] offer(byte[] frame) {
            if (frame.length < 12) return null;
            ByteBuffer b = ByteBuffer.wrap(frame);
            long id = b.getLong();
            int seq = b.getShort() & 0xFFFF, total = b.getShort() & 0xFFFF;
            if (total == 0 || seq >= total) return null;
            byte[] chunk = new byte[b.remaining()];
            b.get(chunk);
            byte[][] arr = parts.computeIfAbsent(id, k -> new byte[total][]);
            if (arr[seq] == null) { arr[seq] = chunk; got.merge(id, 1, Integer::sum); }
            if (got.getOrDefault(id, 0) < total) return null;
            parts.remove(id); got.remove(id);
            ByteArrayOutputStream all = new ByteArrayOutputStream();
            for (byte[] c : arr) all.writeBytes(c);
            return all.toByteArray();
        }
    }
}