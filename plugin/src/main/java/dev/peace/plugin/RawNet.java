package dev.peace.plugin;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import dev.peace.core.Wire;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Zero-registration raw custom-payload transport. Reads the ServerboundCustomPayloadPacket
 * byte stream directly out of the Netty pipeline (before the game decoder ever touches it)
 * and writes ClientboundCustomPayloadPacket bytes straight into the outbound pipeline.
 */
final class RawNet {
    private RawNet() {}

    private static final String HANDLER = "peace-raw";
    private static final String DECODER = "decoder";
    private static final String ENCODER = "encoder";
    private static final String COMMON_PACKET_TYPES = "net.minecraft.network.protocol.common.CommonPacketTypes";

    private static volatile Object serverboundType;
    private static volatile Object clientboundType;
    private static volatile int inId = -1;
    private static volatile int outId = -1;
    private static boolean idsWarned = false;
    private static final Object ID_LOCK = new Object();

    // Live counters for the debug console.
    private static final AtomicLong seen = new AtomicLong();
    private static final AtomicLong ours = new AtomicLong();
    private static final AtomicLong obytes = new AtomicLong();
    private static final AtomicLong sends = new AtomicLong();
    private static final AtomicLong sfails = new AtomicLong();

    private static final ConcurrentHashMap<String, Method> METHODS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Field> FIELDS = new ConcurrentHashMap<>();

    private static Method method(Class<?> c, String name) throws ReflectiveOperationException {
        String key = c.getName() + "." + name;
        Method m = METHODS.get(key);
        if (m == null) {
            m = findMethod(c, name);
            if (m == null) throw new NoSuchMethodException(key);
            m.setAccessible(true);
            METHODS.put(key, m);
        }
        return m;
    }

    private static Method findMethod(Class<?> c, String name) {
        try { return c.getMethod(name); } catch (NoSuchMethodException ignored) {}
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            try { return k.getDeclaredMethod(name); } catch (NoSuchMethodException ignored) {}
        }
        return null;
    }

    private static Field field(Class<?> c, String name) throws ReflectiveOperationException {
        String key = c.getName() + "." + name;
        Field f = FIELDS.get(key);
        if (f == null) {
            f = findField(c, name);
            if (f == null) throw new NoSuchFieldException(key);
            f.setAccessible(true);
            FIELDS.put(key, f);
        }
        return f;
    }

    private static Field findField(Class<?> c, String name) {
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            try { return k.getDeclaredField(name); } catch (NoSuchFieldException ignored) {}
        }
        return null;
    }

    private static Object fieldOf(Object o, String name) throws ReflectiveOperationException {
        return field(o.getClass(), name).get(o);
    }

    private static Object typeOf(String field) {
        try {
            return Class.forName(COMMON_PACKET_TYPES).getField(field).get(null);
        } catch (Exception e) { return null; }
    }

    /** The Connection object behind a Bukkit player (CraftPlayer.getHandle().connection.connection). */
    private static Object connection(Player p) throws ReflectiveOperationException {
        Method handle = method(p.getClass(), "getHandle");
        Object serverPlayer = handle.invoke(p);
        Object listener = fieldOf(serverPlayer, "connection");
        return fieldOf(listener, "connection");
    }

    private static Channel channelOf(Player p) throws ReflectiveOperationException {
        Object ch = fieldOf(connection(p), "channel");
        return ch instanceof Channel c ? c : null;
    }

    /** Pulls the IdDispatchCodec's byId list out of a decoder/encoder handler. */
    private static List<?> byId(ChannelHandler handler) {
        try {
            Object protocol = fieldOf(handler, "protocolInfo");
            Object codec = method(protocol.getClass(), "codec").invoke(protocol);
            return (List<?>) fieldOf(codec, "byId");
        } catch (Exception e) { return null; }
    }

    private static int findId(List<?> byId, Object target) {
        if (byId == null || target == null) return -1;
        for (int i = 0; i < byId.size(); i++) {
            try {
                Object entry = byId.get(i);
                Object t = method(entry.getClass(), "type").invoke(entry);
                if (target.equals(t)) return i;
            } catch (Exception ignored) {}
        }
        return -1;
    }

    /** Resolves the custom-payload wire ids from this connection's codecs. */
    private static void computeIds(ChannelPipeline pipe) {
        ChannelHandler decoder = pipe.get(DECODER);
        ChannelHandler encoder = pipe.get(ENCODER);
        if (decoder == null || encoder == null) return;
        if (serverboundType == null) serverboundType = typeOf("SERVERBOUND_CUSTOM_PAYLOAD");
        if (clientboundType == null) clientboundType = typeOf("CLIENTBOUND_CUSTOM_PAYLOAD");
        synchronized (ID_LOCK) {
            if (inId < 0) inId = findId(byId(decoder), serverboundType);
            if (outId < 0) outId = findId(byId(encoder), clientboundType);
            logIds();
        }
    }

    /** Eager variant: builds a synthetic PacketDecoder/Encoder so ids resolve without a live player. */
    static void ensureIds() {
        if (idsWarned) return;
        if (serverboundType == null) serverboundType = typeOf("SERVERBOUND_CUSTOM_PAYLOAD");
        if (clientboundType == null) clientboundType = typeOf("CLIENTBOUND_CUSTOM_PAYLOAD");
        synchronized (ID_LOCK) {
            if (inId < 0) {
                try {
                    Object inst = packetCodec("net.minecraft.network.PacketDecoder", "SERVERBOUND");
                    inId = inst instanceof ChannelHandler h ? findId(byId(h), serverboundType) : -1;
                } catch (Exception e) { DebugConsole.log("boot inId resolve failed: " + e); }
            }
            if (outId < 0) {
                try {
                    Object inst = packetCodec("net.minecraft.network.PacketEncoder", "CLIENTBOUND");
                    outId = inst instanceof ChannelHandler h ? findId(byId(h), clientboundType) : -1;
                } catch (Exception e) { DebugConsole.log("boot outId resolve failed: " + e); }
            }
            logIds();
        }
    }

    private static Object packetCodec(String className, String flowName) throws ReflectiveOperationException {
        Class<?> flow = Class.forName("net.minecraft.network.protocol.PacketFlow");
        Object dir = Enum.valueOf(flow.asSubclass(Enum.class), flowName);
        Class<?> c = Class.forName(className);
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            try {
                Constructor<?> ct = k.getDeclaredConstructor(flow);
                ct.setAccessible(true);
                return ct.newInstance(dir);
            } catch (NoSuchMethodException ignored) {}
        }
        throw new NoSuchMethodException(className + "(PacketFlow)");
    }

    private static void logIds() {
        boolean ok = inId >= 0 && outId >= 0;
        if (!idsWarned) {
            idsWarned = true;
            if (ok) {
                if (PeaceService.dbgIsDiag()) Bukkit.getLogger().info("[peace:diag] raw ids resolved: in=" + inId + " out=" + outId);
                DebugConsole.log("raw ids resolved: in=" + inId + " out=" + outId);
            } else {
                if (PeaceService.dbgIsDiag()) Bukkit.getLogger().info("[peace:diag] raw ids unresolved: in=" + inId + " out=" + outId);
                DebugConsole.log("raw ids UNRESOLVED: in=" + inId + " out=" + outId);
            }
        }
    }

    // --- debug-console accessors ---

    static int rawIn() { return inId; }
    static int rawOut() { return outId; }
    static long seen() { return seen.get(); }
    static long ours() { return ours.get(); }
    static long obytes() { return obytes.get(); }
    static long sends() { return sends.get(); }
    static long sfails() { return sfails.get(); }
    static void resetCounters() { seen.set(0); ours.set(0); obytes.set(0); sends.set(0); sfails.set(0); }

    static boolean hasHandler(Player p) {
        try {
            Channel channel = channelOf(p);
            return channel != null && channel.pipeline().get(HANDLER) != null;
        } catch (Exception e) { return false; }
    }

    static String channelClass(Player p) {
        try {
            Channel channel = channelOf(p);
            return channel == null ? "null" : channel.getClass().getName();
        } catch (Exception e) { return "err:" + e; }
    }

    static String pipelineNames(Player p) {
        try {
            Channel channel = channelOf(p);
            if (channel == null) return "no channel";
            StringBuilder sb = new StringBuilder();
            for (String n : channel.pipeline().names()) sb.append(n).append(',');
            return sb.length() == 0 ? "" : sb.substring(0, sb.length() - 1);
        } catch (Exception e) { return "err:" + e; }
    }

    static void inject(PeaceService service, Plugin scheduler, Player player) {
        try {
            if (service == null || player == null || !player.isOnline()) return;
            Channel channel = channelOf(player);
            if (channel == null || !channel.isActive()) return;
            ChannelPipeline pipe = channel.pipeline();
            computeIds(pipe);
            if (inId < 0 || pipe.get(HANDLER) != null) return;
            pipe.addBefore(DECODER, HANDLER, new PayloadHandler(service, scheduler, player));
            DebugConsole.log("raw handler installed on " + player.getName());
        } catch (Exception e) { DebugConsole.log("inject " + (player == null ? "?" : player.getName()) + " FAILED: " + e); }
    }

    static void injectAll(PeaceService service, Plugin scheduler) {
        for (Player p : Bukkit.getOnlinePlayers()) inject(service, scheduler, p);
    }

    static void removeAll(Plugin scheduler) {
        for (Player p : Bukkit.getOnlinePlayers()) {
            try {
                Channel channel = channelOf(p);
                if (channel != null) channel.pipeline().remove(HANDLER);
            } catch (Exception ignored) {}
        }
    }

    static boolean send(Player p, byte[] data) {
        try {
            int id = outId;
            if (id < 0) { sfails.incrementAndGet(); return false; }
            Channel channel = channelOf(p);
            if (channel == null || !channel.isActive()) { sfails.incrementAndGet(); return false; }
            sends.incrementAndGet();
            channel.writeAndFlush(Unpooled.wrappedBuffer(Wire.build(id, Wire.CHANNEL, data)));
            return true;
        } catch (Exception e) { sfails.incrementAndGet(); return false; }
    }

    private static final class PayloadHandler extends ChannelInboundHandlerAdapter {
        private final PeaceService service;
        private final Plugin scheduler;
        private final Player player;
        PayloadHandler(PeaceService service, Plugin scheduler, Player player) {
            this.service = service;
            this.scheduler = scheduler;
            this.player = player;
        }
        @Override public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            if (msg instanceof ByteBuf b) {
                byte[] whole = new byte[b.readableBytes()];
                b.getBytes(b.readerIndex(), whole, 0, whole.length);
                Wire.Packet p = Wire.parse(whole);
                seen.incrementAndGet();
                if (p.isMine(inId)) {
                    ours.incrementAndGet();
                    obytes.addAndGet(p.data.length);
                    dispatch(() -> service.onRaw(player, p.data));
                    b.release();
                    return;
                }
            }
            super.channelRead(ctx, msg);
        }
        private void dispatch(Runnable r) {
            try {
                if (Bukkit.isPrimaryThread()) { r.run(); }
                else if (scheduler != null && scheduler.isEnabled()) Bukkit.getScheduler().runTask(scheduler, r);
                else r.run();
            } catch (Exception e) { try { r.run(); } catch (Exception ignored) {} }
        }
    }
}