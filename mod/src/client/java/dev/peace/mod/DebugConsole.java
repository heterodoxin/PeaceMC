package dev.peace.mod;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.Executors;

/**
 * Client-side counterpart of the server debug console. Bound to 127.0.0.1 only, compiled in but
 * only started when `<configdir>/peace-debug.txt` exists (first non-empty line = port, default 8089).
 * Lets us see exactly what the transport is doing on the mod side:
 *   GET  /          HTML dashboard
 *   GET  /status    ids, handler state, counters, connected server, psk prefix
 *   GET  /log       event trail (installs, swallow, decrypt ok/fail, sends)
 */
final class DebugConsole {
    private static final Gson GSON = new Gson();
    private static final int MAX_EVENTS = 500;

    private static volatile HttpServer server;
    private static volatile long boot = 0;

    private static final Deque<String> events = new ArrayDeque<>();
    private static final Object LOCK = new Object();

    private DebugConsole() {}

    static boolean isActive() { return server != null; }

    static void start(Path configDir) {
        if (server != null) return;
        Path f = configDir.resolve("peace-debug.txt");
        if (!Files.exists(f)) return; // opt-in only; never on in a normal release
        String port = "8089";
        try {
            String c = Files.readString(f).trim();
            if (!c.isEmpty()) port = c.split("\\s+")[0];
        } catch (Exception ignored) {}
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", Integer.parseInt(port)), 0);
            server.createContext("/", DebugConsole::handle);
            server.setExecutor(Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "peace-debug"); t.setDaemon(true); return t;
            }));
            server.start();
            boot = System.currentTimeMillis();
            log("client debug console up on 127.0.0.1:" + port);
        } catch (Exception e) { System.out.println("[peace:diag] debug console failed: " + e); }
    }

    static void stop() {
        if (server != null) { server.stop(0); server = null; }
    }

    static void log(String s) {
        if (!isActive()) return;
        synchronized (LOCK) {
            events.addLast(java.time.Instant.now().toString().substring(11, 23) + "  " + s);
            while (events.size() > MAX_EVENTS) events.removeFirst();
        }
    }

    private static String[] recent() {
        synchronized (LOCK) { return events.toArray(new String[0]); }
    }

    private static void handle(HttpExchange ex) throws IOException {
        try {
            String path = ex.getRequestURI().getPath();
            byte[] body;
            String ctype = "application/json; charset=utf-8";
            switch (path) {
                case "/log", "/events" -> {
                    StringBuilder sb = new StringBuilder();
                    for (String l : recent()) sb.append(l).append('\n');
                    body = ("<pre>" + sb + "</pre>").getBytes(StandardCharsets.UTF_8);
                    ctype = "text/html; charset=utf-8";
                }
                case "/", "/dashboard" -> {
                    String s = "<!doctype html><html><head><meta charset=utf-8><meta http-equiv=refresh content=2><title>peace client debug</title>"
                            + "<style>body{font:14px ui-monospace,monospace;background:#111;color:#ddd;padding:16px}pre{white-space:pre-wrap}"
                            + "a{color:#6cf;margin-right:10px}</style></head><body><h3>peace client debug console</h3>"
                            + "<p><a href=/status>status json</a><a href=/log>events</a></p><hr><pre>"
                            + new String(status(), StandardCharsets.UTF_8).replace("&", "&amp;").replace("<", "&lt;")
                            + "</pre></body></html>";
                    body = s.getBytes(StandardCharsets.UTF_8);
                    ctype = "text/html; charset=utf-8";
                }
                default -> body = status();
            }
            ex.getResponseHeaders().set("Content-Type", ctype);
            ex.getResponseHeaders().set("Cache-Control", "no-store");
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(body); }
        } catch (Exception e) {
            byte[] err = ("err " + e).getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(500, err.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(err); }
        } finally {
            ex.close();
        }
    }

    private static byte[] status() {
        JsonObject root = new JsonObject();
        root.addProperty("ts", System.currentTimeMillis());
        root.addProperty("boot", boot);
        root.addProperty("uptime_s", boot == 0 ? 0 : (System.currentTimeMillis() - boot) / 1000);

        JsonObject conn = new JsonObject();
        Minecraft mc = Minecraft.getInstance();
        ServerData sd = mc == null ? null : mc.getCurrentServer();
        conn.addProperty("serverName", sd == null ? null : sd.name);
        conn.addProperty("serverIp", sd == null ? null : sd.ip);
        conn.addProperty("connected", mc != null && mc.getConnection() != null);
        conn.addProperty("channel", Net.channelClass());
        conn.addProperty("active", Net.channelActive());
        conn.addProperty("pipeline", Net.pipeline());
        conn.addProperty("handler", Net.handlerInstalled());
        root.add("connection", conn);

        JsonObject raw = new JsonObject();
        raw.addProperty("inId", Net.rawIn());
        raw.addProperty("outId", Net.rawOut());
        raw.addProperty("ok", Net.rawIn() >= 0 && Net.rawOut() >= 0);
        raw.addProperty("seen", Net.seen());
        raw.addProperty("ours", Net.ours());
        raw.addProperty("obytes", Net.obytes());
        raw.addProperty("sends", Net.sends());
        raw.addProperty("sfails", Net.sfails());
        raw.addProperty("pending", Net.pending());
        root.add("raw", raw);

        JsonObject psk = new JsonObject();
        psk.addProperty("file", net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("peace.txt").toFile().exists());
        psk.addProperty("prefix", Net.pskPrefix());
        root.add("psk", psk);
        return GSON.toJson(root).getBytes(StandardCharsets.UTF_8);
    }
}