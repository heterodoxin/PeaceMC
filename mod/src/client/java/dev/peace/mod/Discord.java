package dev.peace.mod;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.atomic.AtomicBoolean;

/** Reads the Discord channel for peaceping advertisements and auto-adds those servers; all other ops stay in encrypted packets. */
public final class Discord {
    private static final String API = "https://discord.com/api/v10";
    private static final long POLL_MS = 6000L;
    private static final Gson GSON = new Gson();
    private static volatile String token = "";
    private static volatile String channel = "";
    private static volatile String lastSeen = "";
    private static final AtomicBoolean running = new AtomicBoolean(false);

    public static synchronized void init() {
        load();
        if (running.compareAndSet(false, true)) {
            Thread t = new Thread(Discord::loop, "peace-home");
            t.setDaemon(true);
            t.start();
        }
    }

    /** Credentials changed from Settings; persisted immediately. */
    public static synchronized void configure(String tk, String ch) {
        token = tk == null ? "" : tk.trim();
        channel = ch == null ? "" : ch.trim();
        lastSeen = "";
        save();
    }

    public static String token() { return token; }
    public static String channel() { return channel; }
    public static boolean configured() { return !token.isEmpty() && !channel.isEmpty(); }

    private static void loop() {
        while (running.get()) {
            try { poll(); } catch (Exception ignored) {}
            try { Thread.sleep(POLL_MS); } catch (InterruptedException e) { return; }
        }
    }

    private static synchronized void poll() {
        if (!configured()) return;
        String body = get("/channels/" + channel + "/messages?limit=10");
        if (body == null) return;
        JsonArray arr;
        try { arr = GSON.fromJson(body, JsonArray.class); } catch (Exception e) { return; }
        if (arr == null || arr.size() == 0) return;
        String newest = arr.get(0).getAsJsonObject().has("id") ? arr.get(0).getAsJsonObject().get("id").getAsString() : "";
        for (int i = arr.size() - 1; i >= 0; i--) {
            JsonObject m = arr.get(i).getAsJsonObject();
            String id = m.has("id") ? m.get("id").getAsString() : "";
            if (!id.isEmpty() && !lastSeen.isEmpty() && id.compareTo(lastSeen) <= 0) continue;
            String content = m.has("content") && !m.get("content").isJsonNull() ? m.get("content").getAsString() : "";
            if (content == null || !content.startsWith("peaceping ")) continue;
            String target = content.substring(8).trim();
            if (target.isEmpty() || target.equalsIgnoreCase("test")) continue;
            boolean online = true;
            if (target.endsWith(" offline")) { online = false; target = target.substring(0, target.length() - 8).trim(); }
            else if (target.endsWith(" online")) target = target.substring(0, target.length() - 7).trim();
            String host = target, port = "25565";
            int c = target.lastIndexOf(':');
            if (c > 0) { host = target.substring(0, c); port = target.substring(c + 1); }
            if (host.isEmpty() || port.isEmpty()) continue;
            String h = host, a = host + ":" + port;
            boolean status = online;
            Minecraft.getInstance().execute(() -> Servers.sync(h, a, status));
        }
        if (!newest.isEmpty()) { lastSeen = newest; save(); }
    }

    /** Send one message to the channel (Settings "Test"); returns a human status string. */
    public static String send(String content) {
        if (!configured()) return "no token/channel set";
        try {
            JsonObject o = new JsonObject();
            o.addProperty("content", content);
            HttpURLConnection c = (HttpURLConnection) new URL(API + "/channels/" + channel + "/messages").openConnection();
            c.setRequestMethod("POST");
            c.setConnectTimeout(4000); c.setReadTimeout(4000);
            c.setDoOutput(true);
            c.setRequestProperty("Authorization", "Bot " + token);
            c.setRequestProperty("User-Agent", "DiscordBot (peace home, 1.0)");
            c.setRequestProperty("Content-Type", "application/json");
            c.getOutputStream().write(GSON.toJson(o).getBytes(StandardCharsets.UTF_8));
            int code = c.getResponseCode();
            c.disconnect();
            return code == 200 ? "sent" : ("http " + code);
        } catch (Exception e) { return e.toString(); }
    }

    private static String get(String path) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(API + path).openConnection();
            c.setConnectTimeout(4000); c.setReadTimeout(4000);
            c.setRequestProperty("Authorization", "Bot " + token);
            c.setRequestProperty("User-Agent", "DiscordBot (peace home, 1.0)");
            int code = c.getResponseCode();
            if (code != 200) { c.disconnect(); return null; }
            String body = new String(c.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            c.disconnect();
            return body;
        } catch (Exception e) { return null; }
    }

    private static void load() {
        try {
            Path f = file();
            if (!Files.exists(f)) return;
            String[] p = Files.readString(f).trim().split("\\n", -1);
            if (p.length >= 2) {
                token = Secret.decrypt(p[0].trim());
                channel = Secret.decrypt(p[1].trim());
                if (token == null || channel == null) { token = ""; channel = ""; }
                if (p.length >= 3) lastSeen = p[2].trim();
            }
        } catch (Exception ignored) {}
    }
    private static void save() {
        try { Files.writeString(file(), Secret.encrypt(token) + "\n" + Secret.encrypt(channel) + "\n" + lastSeen + "\n", StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING); }
        catch (Exception ignored) {}
    }
    private static Path file() { return FabricLoader.getInstance().getConfigDir().resolve("qolclient-discord.txt"); }
}