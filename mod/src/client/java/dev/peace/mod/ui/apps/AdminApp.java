package dev.peace.mod.ui.apps;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.peace.mod.Net;
import dev.peace.mod.ui.App;
import dev.peace.mod.ui.Theme;
import dev.peace.mod.ui.Widgets;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.ArrayList;
import java.util.List;

/** Comprehensive admin panel: moderation, privileges, gamemode and player utilities. */
public final class AdminApp extends App {
    private final Widgets.ComboBox target = new Widgets.ComboBox("player");
    private final Widgets.TextInput reason = new Widgets.TextInput("reason (ban/kick)");
    private final Widgets.Button ban, unban, op, deop, kick, kill;
    private final Widgets.Button gmS, gmC, gmA, gmSp;
    private final Widgets.Button heal, feed, clear, fly, god;
    private String status = "";
    private int statusColor = Theme.MUTED;
    private List<String> playerList = new ArrayList<>();

    public AdminApp() {
        super("Admin", "ADM");
        prefW = 345; prefH = 330;
        ban = btn("Ban", () -> act("ban", true));
        unban = btn("Unban", () -> act("unban", false));
        op = btn("Op", () -> act("op", false));
        deop = btn("Deop", () -> act("deop", false));
        kick = btn("Kick", () -> act("kick", true));
        kill = btn("Kill", () -> act("kill", false));
        gmS = btn("Surv", () -> gm("survival"));
        gmC = btn("Creat", () -> gm("creative"));
        gmA = btn("Adv", () -> gm("adventure"));
        gmSp = btn("Spec", () -> gm("spectator"));
        heal = btn("Heal", () -> util("heal"));
        feed = btn("Feed", () -> util("feed"));
        clear = btn("Clear", () -> util("clear"));
        fly = btn("Fly", () -> util("fly"));
        god = btn("God", () -> util("god"));
        ban.primary = true;
        widgets.add(target);
        widgets.add(reason);
        fetchPlayers();
    }

    private Widgets.Button btn(String label, Runnable r) { Widgets.Button b = new Widgets.Button(label, r); widgets.add(b); return b; }

    private void fetchPlayers() {
        Net.send(Net.op("players"), j -> {
            if (ok(j)) {
                playerList.clear();
                JsonArray arr = j.getAsJsonArray("names");
                for (var e : arr) playerList.add(e.getAsString());
                target.options = playerList;
            }
        });
    }

    @Override
    public void render(GuiGraphicsExtractor g, int cx, int cy, int cw, int ch, int mx, int my) {
        int y = cy;
        Theme.text(g, "Player", cx, y, Theme.MUTED); y += 10;
        target.set(cx, y, cw, 16); target.render(g, mx, my); y += 20;
        Theme.text(g, "Reason", cx, y, Theme.MUTED); y += 10;
        reason.set(cx, y, cw, 16); reason.render(g, mx, my); y += 22;

        int w4 = (cw - 3 * 6) / 4;
        row(g, y, w4, cx, mx, my, ban, unban, op, deop); y += 22;
        int w2 = (cw - 6) / 2;
        kick.set(cx, y, w2, 18); kill.set(cx + w2 + 6, y, w2, 18);
        kick.render(g, mx, my); kill.render(g, mx, my); y += 24;

        Theme.text(g, "Gamemode", cx, y, Theme.MUTED); y += 10;
        row(g, y, w4, cx, mx, my, gmS, gmC, gmA, gmSp); y += 24;

        Theme.text(g, "Utility", cx, y, Theme.MUTED); y += 10;
        int w3 = (cw - 2 * 6) / 3;
        row(g, y, w3, cx, mx, my, heal, feed, clear); y += 22;
        int hw = (cw - 6) / 2;
        fly.set(cx, y, hw, 18); god.set(cx + hw + 6, y, hw, 18);
        fly.render(g, mx, my); god.render(g, mx, my); y += 24;

        if (!status.isEmpty()) Theme.text(g, Theme.trim(status, cw), cx, y, statusColor);
    }

    private void row(GuiGraphicsExtractor g, int y, int bw, int cx, int mx, int my, Widgets.Button... bs) {
        for (int i = 0; i < bs.length; i++) { bs[i].set(cx + i * (bw + 6), y, bw, 18); bs[i].render(g, mx, my); }
    }

    private void act(String op, boolean withReason) {
        if (target.value().isBlank()) { status = "select a player"; statusColor = Theme.BAD; return; }
        JsonObject req = Net.op(op);
        req.addProperty("player", target.value().trim());
        if (withReason && !reason.value().isBlank()) req.addProperty("reason", reason.value().trim());
        send(req);
    }

    private void gm(String mode) {
        if (target.value().isBlank()) { status = "select a player"; statusColor = Theme.BAD; return; }
        JsonObject req = Net.op("gamemode");
        req.addProperty("player", target.value().trim());
        req.addProperty("mode", mode);
        send(req);
    }

    private void util(String op) {
        if (target.value().isBlank()) { status = "select a player"; statusColor = Theme.BAD; return; }
        JsonObject req = Net.op(op);
        req.addProperty("player", target.value().trim());
        send(req);
    }

    private void send(JsonObject req) {
        status = "working..."; statusColor = Theme.MUTED;
        Net.send(req, j -> { status = replyText(j); statusColor = replyColor(j); fetchPlayers(); });
    }
}