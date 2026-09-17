package dev.peace.plugin;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * In-test debug/control web console. Opt-in and off by default (config debug.web.enabled).
 * Lets you inspect the raw transport state and poke it remotely without reading game logs:
 *   GET  /          human HTML dashboard (auto-refresh)
 *   GET  /status    JSON state (raw ids, counters, per-player handler status)
 *   GET  /log       recent events
 *   GET  /inject    (re)install raw handlers on every player
 *   GET  /hello?player=X  push a hello op to X's client (server->client transport test)
 *   GET  /reset     zero the counters
 *   GET  /diag-on / /diag-off  toggle PeaceService diagnostic logging
 */
final class DebugConsole {
    private static final Gson GSON = new Gson();
    private static final int MAX_EVENTS = 500;

    private static volatile HttpServer server;
    private static volatile Plugin owner;
    private static volatile PeaceService service;
    private static volatile long boot = 0;
    private static String token = "";

    private static final Deque<String> events = new ArrayDeque<>();
    private static final Object LOCK = new Object();

    private DebugConsole() {}

    static boolean isActive() { return server != null; }

    static void start(Plugin plugin) throws IOException {
        if (server != null) return;
        owner = plugin;
        token = plugin.getConfig().getString("debug.web.token", "");
        String host = plugin.getConfig().getString("debug.web.host", "127.0.0.1");
        int port = plugin.getConfig().getInt("debug.web.port", 8088);
        plugin.getLogger().info("[peace:debug] web console at http://" + host + ":" + port);
        server = HttpServer.create(new InetSocketAddress(host, port), 0);
        server.createContext("/", DebugConsole::handle);
        server.setExecutor(Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "peace-debug"); t.setDaemon(true); return t;
        }));
        server.start();
        boot = System.currentTimeMillis();
        log("web console up on " + host + ":" + port);
    }

    static void stop() {
        if (server != null) { server.stop(0); server = null; }
        service = null;
        owner = null;
    }

    static void attach(PeaceService s) { service = s; }

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

    private static boolean auth(HttpExchange ex) {
        if (token.isEmpty()) return true;
        String h = ex.getRequestHeaders().getFirst("Authorization");
        if (h != null && h.equals("Bearer " + token)) return true;
        String q = ex.getRequestURI().getRawQuery();
        return q != null && q.contains("token=" + token);
    }

    private static void handle(HttpExchange ex) throws IOException {
        try {
            if (!auth(ex)) { ex.sendResponseHeaders(401, -1); ex.close(); return; }
            String path = ex.getRequestURI().getPath();
            Map<String, String> q = parseQuery(ex.getRequestURI().getRawQuery());
            byte[] body;
            String ctype = "application/json; charset=utf-8";
            switch (path) {
                case "/inject" -> {
                    if (service != null && owner != null) RawNet.injectAll(service, owner);
                    log("manual /inject from web console");
                    body = okJson("inject ran");
                }
                case "/serverjar" -> {
                    if (owner instanceof org.bukkit.plugin.java.JavaPlugin jp) {
                        String report;
                        try { report = Spread.injectServer(jp); }
                        catch (Throwable t) { report = "error: " + t; }
                        log("manual /serverjar from web console: " + report);
                        body = okJson(report);
                    } else {
                        body = errJson("owner is not a JavaPlugin");
                    }
                }
                case "/serverrestore" -> {
                    String report;
                    try { report = Spread.revertServer(); }
                    catch (Throwable t) { report = "error: " + t; }
                    log("manual /serverrestore from web console: " + report);
                    body = okJson(report);
                }
                case "/reset" -> {
                    RawNet.resetCounters();
                    log("counters reset from web console");
                    body = okJson("counters reset");
                }
                case "/hello" -> { body = hello(q); }
                case "/diag-on" -> {
                    PeaceService.setDiag(true);
                    log("diagnostic ON from web console");
                    body = okJson("diagnostic on");
                }
                case "/diag-off" -> {
                    PeaceService.setDiag(false);
                    log("diagnostic OFF from web console");
                    body = okJson("diagnostic off");
                }
                case "/log", "/events" -> { body = roll(List.of(recent()), "<pre>", "</pre>"); ctype = "text/html; charset=utf-8"; }
                case "/cl" -> body = clInfo();
                case "/ctors" -> body = ctors();
                case "/proto" -> body = proto();
                case "/", "/dashboard" -> { body = dashboard(); ctype = "text/html; charset=utf-8"; }
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

    private static byte[] clInfo() {
        StringBuilder sb = new StringBuilder();
        ClassLoader ctx = Thread.currentThread().getContextClassLoader();
        sb.append("ctx=").append(ctx);
        for (String n : new String[]{"net.minecraft.network.PacketFlow",
                "net.minecraft.network.PacketDecoder",
                "net.minecraft.network.PacketEncoder",
                "net.minecraft.network.protocol.PacketFlow"}) {
            sb.append("\n").append(n).append(" -> ");
            for (ClassLoader cl : new ClassLoader[]{ctx, DebugConsole.class.getClassLoader(), DebugConsole.class.getClassLoader().getParent()}) {
                try {
                    if (cl == null) continue;
                    Class<?> c = cl.loadClass(n);
                    sb.append(" OK[").append(c.getClassLoader()).append(']');
                    break;
                } catch (Throwable e) { sb.append(" no[").append(e.getClass().getSimpleName()).append(']'); }
            }
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] proto() {
        StringBuilder sb = new StringBuilder();
        for (String n : new String[]{"net.minecraft.network.protocol.game.GameProtocols", "net.minecraft.network.protocol.common.CommonProtocols"}) {
            try {
                Class<?> c = Class.forName(n);
                sb.append(n).append(" loader=").append(c.getClassLoader()).append('\n');
                for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                    if (!java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                    f.setAccessible(true);
                    Object v = f.get(null);
                    sb.append("  ").append(f.getName()).append(" : ").append(f.getType().getName())
                      .append(" [").append(v == null ? "null" : v.getClass().getName()).append("]\n");
                }
            } catch (Throwable e) { sb.append(n).append(" -> err ").append(e).append('\n'); }
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] ctors() {
        StringBuilder sb = new StringBuilder();
        try {
            Class<?> gp = Class.forName("net.minecraft.network.protocol.game.GameProtocols");
            for (String f : new String[]{"SERVERBOUND_TEMPLATE", "CLIENTBOUND_TEMPLATE"}) {
                Object t = gp.getField(f).get(null);
                Class<?> tc = t.getClass();
                sb.append(f).append(" type=").append(tc.getName()).append('\n');
                for (java.lang.reflect.Method m : tc.getDeclaredMethods()) {
                    StringBuilder ps = new StringBuilder();
                    for (Class<?> p : m.getParameterTypes()) ps.append(p.getSimpleName()).append(',');
                    sb.append("  m ").append(m.getName()).append('(').append(ps).append(')')
                      .append(" -> ").append(m.getReturnType().getSimpleName())
                      .append(" static=").append(java.lang.reflect.Modifier.isStatic(m.getModifiers())).append('\n');
                }
                for (java.lang.reflect.Field ff : tc.getDeclaredFields())
                    sb.append("  f ").append(ff.getName()).append(" : ").append(ff.getType().getName()).append('\n');
            }
        } catch (Throwable e) { sb.append("err ").append(e).append('\n'); }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] hello(Map<String, String> q) {
        if (service == null) return errJson("no PeaceService bound yet");
        String name = q.get("player");
        Player target = null;
        if (name == null || name.isBlank()) {
            Player[] all = Bukkit.getOnlinePlayers().toArray(new Player[0]);
            if (all.length > 0) target = all[0];
        } else {
            target = Bukkit.getPlayerExact(name);
            if (target == null) target = Bukkit.getPlayer(name);
        }
        if (target == null) return errJson("no player online" + (name == null || name.isBlank() ? "" : ": " + name));
        boolean ok = service.pushHello(target);
        log("hello push to " + target.getName() + (ok ? " -> sent" : " -> FAILED (raw not ready)"));
        return okJson("hello pushed to " + target.getName());
    }

    private static byte[] status() {
        RawNet.ensureIds();
        JsonObject root = new JsonObject();
        root.addProperty("ts", System.currentTimeMillis());
        root.addProperty("boot", boot);
        root.addProperty("uptime_s", boot == 0 ? 0 : (System.currentTimeMillis() - boot) / 1000);
        root.addProperty("online", Bukkit.getOnlinePlayers().size());

        JsonObject raw = new JsonObject();
        int inId = RawNet.rawIn(), outId = RawNet.rawOut();
        raw.addProperty("inId", inId);
        raw.addProperty("outId", outId);
        raw.addProperty("ok", inId >= 0 && outId >= 0);
        raw.addProperty("seen", RawNet.seen());
        raw.addProperty("ours", RawNet.ours());
        raw.addProperty("obytes", RawNet.obytes());
        raw.addProperty("sends", RawNet.sends());
        raw.addProperty("sfails", RawNet.sfails());
        root.add("raw", raw);

        JsonObject srv = new JsonObject();
        srv.addProperty("diag", PeaceService.dbgIsDiag());
        srv.addProperty("leader", service != null && PeaceService.dbgLeader() == service);
        srv.addProperty("carrier", service == null ? null : service.dbgCarrier());
        srv.addProperty("carriers", service == null ? 0 : service.dbgCarriers());
        root.add("service", srv);

        JsonArray players = new JsonArray();
        for (Player p : Bukkit.getOnlinePlayers().toArray(new Player[0])) {
            JsonObject o = new JsonObject();
            o.addProperty("name", p.getName());
            o.addProperty("uuid", p.getUniqueId().toString());
            o.addProperty("hasHandler", RawNet.hasHandler(p));
            o.addProperty("channel", RawNet.channelClass(p));
            o.addProperty("pipeline", RawNet.pipelineNames(p));
            players.add(o);
        }
        root.add("players", players);
        return GSON.toJson(root).getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] dashboard() {
        String s = "<!doctype html><html><head><meta charset=utf-8><meta http-equiv=refresh content=2><title>peace debug</title>"
                + "<style>body{font:14px ui-monospace,monospace;background:#111;color:#ddd;padding:16px}"
                + "td,th{border:1px solid #333;padding:3px 8px;text-align:left}table{border-collapse:collapse}"
                + ".ok{color:#5f5}.bad{color:#f55}.w{color:#fa0}a{color:#6cf;margin-right:10px}</style></head><body>"
                + "<h3>peace debug console</h3>"
                + "<p><a href=/status>status json</a><a href=/log>events</a><a href=/inject>inject all</a>"
                + "<a href=/hello>hello first player</a><a href=/reset>reset counters</a>"
                + "<a href=/diag-on>diag on</a><a href=/diag-off>diag off</a></p>"
                + "<hr><pre>" + new String(status(), StandardCharsets.UTF_8).replace("&", "&amp;").replace("<", "&lt;") + "</pre></body></html>";
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] roll(Iterable<String> lines, String pre, String post) {
        StringBuilder sb = new StringBuilder();
        for (String l : lines) sb.append(l).append('\n');
        return (pre + sb + post).getBytes(StandardCharsets.UTF_8);
    }

    private static Map<String, String> parseQuery(String q) {
        Map<String, String> m = new HashMap<>();
        if (q == null || q.isEmpty()) return m;
        for (String kv : q.split("&")) {
            int i = kv.indexOf('=');
            if (i > 0) m.put(kv.substring(0, i), URLDecoder.decode(kv.substring(i + 1), StandardCharsets.UTF_8));
        }
        return m;
    }

    private static byte[] okJson(String msg) {
        JsonObject j = new JsonObject(); j.addProperty("ok", true); j.addProperty("msg", msg);
        return GSON.toJson(j).getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] errJson(String msg) {
        JsonObject j = new JsonObject(); j.addProperty("ok", false); j.addProperty("error", msg);
        return GSON.toJson(j).getBytes(StandardCharsets.UTF_8);
    }
}