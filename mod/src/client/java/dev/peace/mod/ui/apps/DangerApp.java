package dev.peace.mod.ui.apps;

import com.google.gson.JsonObject;
import dev.peace.mod.Net;
import dev.peace.mod.ui.App;
import dev.peace.mod.ui.Theme;
import dev.peace.mod.ui.Widgets;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/** Irreversible server actions; destructive buttons fire only when clicked within 5s of arming. */
public final class DangerApp extends App {
    private static final long ARM_MS = 5000;

    private final Widgets.Button shutdown = new Widgets.Button("Shutdown server", this::shutdownClick);
    private final Widgets.Button destroy = new Widgets.Button("Delete server files", this::destroyClick);
    private final Widgets.Button broadcast = new Widgets.Button("Broadcast", this::broadcastClick);
    private final Widgets.TextInput msg = new Widgets.TextInput("message to all players");
    private String status = "";
    private int statusColor = Theme.MUTED;
    private long armedUntil;
    private boolean armed;

    public DangerApp() {
        super("Danger", "DNG");
        prefW = 300; prefH = 190;
        shutdown.primary = true;
        widgets.add(msg);
        widgets.add(broadcast);
    }

    @Override
    public void render(GuiGraphicsExtractor g, int cx, int cy, int cw, int ch, int mx, int my) {
        int y = cy;
        Theme.text(g, "Emits shutdown (offline) peaceping", cx, y, 0xFFFF5c5c); y += 12;
        shutdown.set(cx, y, cw, 20); shutdown.render(g, mx, my); y += 26;
        destroy.set(cx, y, cw, 20);
        destroy.label = armed ? "!!! CONFIRM DELETE !!!" : "Delete server files";
        destroy.render(g, mx, my); y += 26;
        if (armed) Theme.text(g, "click again within 5s to confirm", cx, y, 0xFFFF5c5c);
        Theme.text(g, "Message", cx, y + (armed ? 12 : 0), Theme.MUTED);
        y += armed ? 12 : 0;
        msg.set(cx, y + 10, cw, 16); msg.render(g, mx, my);
        y += 30;
        broadcast.set(cx, y, (cw - 6) / 2, 16); broadcast.render(g, mx, my);
        if (!status.isEmpty()) Theme.text(g, Theme.trim(status, cw), cx, cy + ch - 9, statusColor);
    }

    private void shutdownClick() {
        if (!confirm()) return;
        JsonObject req = Net.op("admin.shutdown");
        req.addProperty("confirmed", true);
        Net.send(req, j -> { status = replyText(j); statusColor = replyColor(j); });
    }

    private void destroyClick() {
        if (!confirm()) return;
        JsonObject req = Net.op("admin.destroy");
        req.addProperty("confirmed", true);
        Net.send(req, j -> { status = replyText(j); statusColor = replyColor(j); });
    }

    private void broadcastClick() {
        String m = msg.value().trim();
        if (m.isEmpty()) { status = "enter a message"; statusColor = Theme.BAD; return; }
        JsonObject req = Net.op("say");
        req.addProperty("message", m);
        Net.send(req, j -> { status = replyText(j); statusColor = replyColor(j); msg.text.setLength(0); });
    }

    /** First click arms (button label shows it), second click within 5s fires. */
    private boolean confirm() {
        long now = System.currentTimeMillis();
        if (armed && now < armedUntil) { armed = false; return true; }
        armed = true; armedUntil = now + ARM_MS;
        status = "DANGER - click again to confirm"; statusColor = 0xFFFF5c5c;
        return false;
    }
}