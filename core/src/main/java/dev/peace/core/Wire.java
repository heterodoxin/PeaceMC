package dev.peace.core;

/** Raw custom-payload packet wire format shared by client and server: varint id + utf8 channel + data. */
public final class Wire {
    public static final String CHANNEL = "peace:main";
    private static final int MAX_CHANNEL = 32767;

    private Wire() {}

    /** A parsed raw packet; `valid=false` (a.k.a. FORWARD) means "leave the frame alone". */
    public static final class Packet {
        public final int packetId;
        public final String channel;
        public final byte[] data;
        public final boolean valid;
        public static final Packet FORWARD = new Packet(-1, null, null, false);

        Packet(int packetId, String channel, byte[] data, boolean valid) {
            this.packetId = packetId;
            this.channel = channel;
            this.data = data;
            this.valid = valid;
        }

        /** True only for our exact channel on the exact packet id. */
        public boolean isMine(int targetPacketId) {
            return valid && packetId == targetPacketId && CHANNEL.equals(channel);
        }
    }

    /** Builds one outbound packet: [varint packetId][varint len][utf8 channel][data]. */
    public static byte[] build(int packetId, String channel, byte[] data) {
        byte[] chan = channel.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] out = new byte[VarInt.size(packetId) + VarInt.size(chan.length) + chan.length + data.length];
        int[] p = {0};
        VarInt.write(out, p, packetId);
        VarInt.write(out, p, chan.length);
        System.arraycopy(chan, 0, out, p[0], chan.length);
        p[0] += chan.length;
        System.arraycopy(data, 0, out, p[0], data.length);
        return out;
    }

    /** Parses one inbound frame (full packet bytes, already de-framed/decompressed). Never throws. */
    public static Packet parse(byte[] frame) {
        try {
            int[] p = {0};
            int id = VarInt.read(frame, p);
            String channel = VarInt.readString(frame, p, MAX_CHANNEL);
            byte[] data = new byte[frame.length - p[0]];
            System.arraycopy(frame, p[0], data, 0, data.length);
            return new Packet(id, channel, data, true);
        } catch (Exception e) {
            return Packet.FORWARD;
        }
    }
}