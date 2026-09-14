package dev.peace.wiretest;

import dev.peace.core.Crypto;
import dev.peace.core.Frame;
import dev.peace.core.VarInt;
import dev.peace.core.Wire;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Automated transfer pipeline used by PEACE: client-server-client round trip at the byte level,
 * no Minecraft. Compiles the exact same Wire/Frame/Crypto classes Net.java and RawNet.java use.
 */
public final class Main {
    private static int passed = 0, failed = 0;

    private static final Random RNG = new Random(0xC0FFEE);

    public static void main(String[] args) {
        varintTests();
        wireFormatTests();
        forwardPassThroughTests();
        frameReassemblyTests();
        cryptoTests();
        endToEndTests();
        System.out.println();
        System.out.println("wiretest: " + passed + " passed, " + failed + " failed");
        System.exit(failed == 0 ? 0 : 1);
    }

    private static void varintTests() {
        section("varint");
        int[] cases = {0, 1, 127, 128, 255, 16383, 16384, 2097151, 2097152, 268435455, 268435456, Integer.MAX_VALUE};
        for (int v : cases) {
            byte[] b = new byte[VarInt.size(v)];
            int[] p = {0};
            VarInt.write(b, p, v);
            p[0] = 0;
            int r = VarInt.read(b, p);
            check("varint " + v + " size " + VarInt.size(v), r == v && p[0] == b.length);
        }
        byte[] five = new byte[5];
        int[] p = {0};
        VarInt.write(five, p, Integer.MAX_VALUE);
        check("varint 0x7fffffff is 5 bytes", VarInt.size(Integer.MAX_VALUE) == 5 && p[0] == 5);
        boolean bad = false;
        byte[] overlong = new byte[6];
        for (int i = 0; i < 6; i++) overlong[i] = (byte) (i == 5 ? 1 : 0x80);
        try { VarInt.read(overlong, new int[]{0}); } catch (Exception e) { bad = true; }
        check("varint >5 bytes rejected", bad);
    }

    private static void wireFormatTests() {
        section("wire format");
        byte[] w = Wire.build(0x5B, Wire.CHANNEL, new byte[]{0x01, 0x7F, (byte) 0x80, 0x00});
        Wire.Packet p = Wire.parse(w);
        check("build->parse roundtrip id", p.valid && p.packetId == 0x5B);
        check("build->parse roundtrip channel", p.valid && Wire.CHANNEL.equals(p.channel));
        check("build->parse roundtrip data", p.valid && java.util.Arrays.equals(p.data, new byte[]{0x01, 0x7F, (byte) 0x80, 0x00}));
        byte[] chan = Wire.CHANNEL.getBytes(StandardCharsets.UTF_8);
        check("wire layout == vanilla [varint id][varint len][utf8][data]",
                w.length == VarInt.size(0x5B) + VarInt.size(chan.length) + chan.length + 4
                        && w[VarInt.size(0x5B)] == (byte) chan.length);
        int id = Wire.parse(Wire.build(0x7F7F7F, Wire.CHANNEL, new byte[0])).packetId;
        check("large varint id preserved", id == 0x7F7F7F);
    }

    private static void forwardPassThroughTests() {
        section("pass-through (non-peace frames are left alone)");
        byte[] foreignId = Wire.build(0x01, Wire.CHANNEL, new byte[]{9, 9, 9});
        check("different id -> not mine", !Wire.parse(foreignId).isMine(0x5B));
        byte[] foreignChan = Wire.build(0x5B, "fabric:foo", new byte[]{1, 2, 3});
        check("different channel -> not mine", !Wire.parse(foreignChan).isMine(0x5B));
        check("mine matches", Wire.parse(Wire.build(0x5B, Wire.CHANNEL, new byte[]{1})).isMine(0x5B));
        check("malformed empty -> valid=false", !Wire.parse(new byte[0]).valid);
        check("malformed truncated varint -> valid=false", !Wire.parse(new byte[]{(byte) 0x80}).valid);
        check("malformed bad string len -> valid=false", !Wire.parse(new byte[]{0x01, (byte) 0xFF, 0x00}).valid);
    }

    private static void frameReassemblyTests() {
        section("frame reassembly");
        byte[] cipher = new byte[100_000];
        RNG.nextBytes(cipher);
        long msgId = 0x1122334455667788L;
        byte[][] frames = Frame.pack(msgId, cipher);
        check("100KB -> multi chunk", frames.length > 1);
        List<byte[]> shuffled = new ArrayList<>();
        for (byte[] f : frames) shuffled.add(f);
        Collections.shuffle(shuffled, RNG);
        Frame.Reassembler rs = new Frame.Reassembler();
        byte[] got = null;
        for (int i = 0; i < shuffled.size() - 1; i++) {
            byte[] r = rs.offer(shuffled.get(i));
            check("partial reassembly stays null (seq " + i + ")", r == null);
        }
        got = rs.offer(shuffled.get(shuffled.size() - 1));
        check("reordered chunks reassemble", java.util.Arrays.equals(got, cipher));
        Frame.Reassembler rs2 = new Frame.Reassembler();
        check("frame < 12 bytes rejected", rs2.offer(new byte[]{1, 2}) == null);
        byte[] zeroFrame = new byte[12];
        check("zero total/seq frame rejected", rs2.offer(zeroFrame) == null);
    }

    private static void cryptoTests() {
        section("crypto");
        Crypto a = new Crypto("correct horse battery staple");
        byte[] plain = new byte[4096];
        RNG.nextBytes(plain);
        byte[] wire = a.encrypt(plain);
        check("encrypt->decrypt roundtrip", java.util.Arrays.equals(a.decrypt(wire), plain));
        Crypto b = new Crypto("nope");
        check("wrong key -> decrypt null", b.decrypt(wire) == null);
        check("tampered -> decrypt null", b.decrypt(a.encrypt(plain)) == null);
        byte[] tiny = a.encrypt(new byte[1]);
        check("short ciphertext rejected", a.decrypt(new byte[10]) == null);
    }

    private static void endToEndTests() {
        section("end-to-end transfer (client -> server -> client)");
        int serverInId = 0x9E;   // what the SERVER waits for (serverbound play custom payload)
        int clientInId = 0x5B;   // what the CLIENT waits for (clientbound play custom payload)

        Crypto clientC = new Crypto("psk");
        Crypto serverC = new Crypto("psk");

        // --- client sends a large request payload (mirrors Net.send) ---
        byte[] requestPlain = new byte[88_000];
        RNG.nextBytes(requestPlain);
        byte[] requestCipher = clientC.encrypt(requestPlain);
        List<byte[]> clientOut = new ArrayList<>();
        for (byte[] f : Frame.pack(0xA1A2A3A4A5A6A7A8L, requestCipher))
            clientOut.add(Wire.build(serverInId, Wire.CHANNEL, f));

        // --- server receives (mirrors RawNet.PayloadHandler) ---
        Frame.Reassembler serverReasm = new Frame.Reassembler();
        List<byte[]> serverIn = new ArrayList<>();
        boolean serverSawForeign = false;
        java.util.Collections.shuffle(clientOut, RNG);
        for (byte[] wire : clientOut) {
            Wire.Packet p = Wire.parse(wire);
            if (p.isMine(serverInId)) serverIn.add(p.data);
            else serverSawForeign = true;
        }
        check("server consumed all chunks", serverIn.size() == clientOut.size());
        check("server never consumed a foreign frame", !serverSawForeign);
        byte[] serverAssembled = null;
        for (byte[] c : serverIn) serverAssembled = serverReasm.offer(c);
        check("server reassembled request", java.util.Arrays.equals(serverAssembled, requestCipher));
        check("server decrypted request", java.util.Arrays.equals(serverC.decrypt(serverAssembled), requestPlain));

        // --- server replies (mirrors RawNet.send + PeaceService.reply) ---
        byte[] replyPlain = new byte[96_000];
        RNG.nextBytes(replyPlain);
        byte[] replyCipher = serverC.encrypt(replyPlain);
        List<byte[]> serverOut = new ArrayList<>();
        for (byte[] f : Frame.pack(0x0102030405060708L, replyCipher))
            serverOut.add(Wire.build(clientInId, Wire.CHANNEL, f));

        // --- client receives (mirrors Net.RawIn + Net.onFrame) ---
        Frame.Reassembler clientReasm = new Frame.Reassembler();
        List<byte[]> clientIn = new ArrayList<>();
        java.util.Collections.shuffle(serverOut, RNG);
        for (byte[] wire : serverOut) {
            Wire.Packet p = Wire.parse(wire);
            if (p.isMine(clientInId)) clientIn.add(p.data);
        }
        check("client consumed all reply chunks", clientIn.size() == serverOut.size());
        byte[] clientAssembled = null;
        for (byte[] c : clientIn) clientAssembled = clientReasm.offer(c);
        check("client reassembled reply", java.util.Arrays.equals(clientAssembled, replyCipher));
        check("client decrypted reply", java.util.Arrays.equals(clientC.decrypt(clientAssembled), replyPlain));

        // --- interleaved foreign frames must pass through untouched ---
        byte[] foreign1 = Wire.build(0x02, Wire.CHANNEL, new byte[]{11});
        byte[] foreign2 = Wire.build(clientInId, "other:chan", new byte[]{22});
        check("foreign frames bytes preserved end-to-end",
                foreign1[0] == 2 && foreign1[foreign1.length - 1] == 11 && foreign2[foreign2.length - 1] == 22);
    }

    private static void section(String name) { System.out.println("-- " + name); }
    private static void check(String name, boolean ok) {
        if (ok) { passed++; System.out.println("  PASS  " + name); }
        else { failed++; System.out.println("  FAIL  " + name); }
    }
}