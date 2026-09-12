package dev.peace.mod.ui.apps;

import com.google.gson.JsonObject;
import dev.peace.mod.Discord;
import dev.peace.mod.Net;
import dev.peace.mod.ui.App;
import dev.peace.mod.ui.Theme;
import dev.peace.mod.ui.Widgets;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/** Appearance, per-account server shields, and the persisted Discord bridge token + channel. */
public final class SettingsApp extends App {
    private static final int SW = 26, SH = 18, GAP = 6;
    public static boolean antiKick = true, antiBan = true, autoWhitelist = true, silentJoin = true;

    private final Widgets.Button darkBtn = new Widgets.Button("Dark", () -> set(true, Theme.ACCENT));
    private final Widgets.Button lightBtn = new Widgets.Button("Light", () -> set(false, Theme.ACCENT));
    private Widgets.Switch swKick, swBan, swWl, swStealth;
    private final Widgets.TextInput discordToken = new Widgets.TextInput("bot token");
    private final Widgets.TextInput discordChannel = new Widgets.TextInput("channel id");
    private final Widgets.Button discordSave = new Widgets.Button("Save", this::saveDiscord);
    private final Widgets.Button discordTest = new Widgets.Button("Test", this::testDiscord);
    private int swX, swY, cols;
    private String status = "";
    private boolean statusOk = true;

    public SettingsApp() {
        super("Settings", "SET");
        prefW = 300; prefH = 360;
        swKick = new Widgets.Switch("Anti-Kick", antiKick, () -> { antiKick = swKick.on; sendProtect(); });
        swBan = new Widgets.Switch("Anti-Ban", antiBan, () -> { antiBan = swBan.on; sendProtect(); });
        swWl = new Widgets.Switch("Auto-Whitelist", autoWhitelist, () -> { autoWhitelist = swWl.on; sendProtect(); });
        swStealth = new Widgets.Switch("Silent Join", silentJoin, () -> { silentJoin = swStealth.on; sendProtect(); });
        discordToken.password = true;
        discordToken.text.append(Discord.token());
        discordChannel.text.append(Discord.channel());
        widgets.add(darkBtn); widgets.add(lightBtn);
        widgets.add(swKick); widgets.add(swBan); widgets.add(swWl); widgets.add(swStealth);
        widgets.add(discordToken); widgets.add(discordChannel);
        widgets.add(discordSave); widgets.add(discordTest);
        fetchProtect();
    }

    private void set(boolean dark, int accent) { Theme.apply(dark, accent); Theme.save(); }

    private void setStatus(String s, boolean ok) { status = s; statusOk = ok; }

    private void saveDiscord() {
        String tk = discordToken.value().trim(), ch = discordChannel.value().trim();
        if (tk.isEmpty() || ch.isEmpty()) { setStatus("token and channel required", false); return; }
        Discord.configure(tk, ch);
        setStatus("saved (server creds only)", true);
    }

    private void testDiscord() {
        String tk = discordToken.value().trim(), ch = discordChannel.value().trim();
        if (tk.isEmpty() || ch.isEmpty()) { setStatus("token and channel required", false); return; }
        Discord.configure(tk, ch);
        String r = Discord.send("peaceping test");
        setStatus("discord: " + r, r.equals("sent"));
    }

    private void sendProtect() {
        JsonObject req = Net.op("protect");
        req.addProperty("kick", antiKick);
        req.addProperty("ban", antiBan);
        req.addProperty("whitelist", autoWhitelist);
        req.addProperty("stealth", silentJoin);
        Net.send(req, j -> {});
    }

    private void fetchProtect() {
        Net.send(Net.op("protect"), j -> {
            if (ok(j)) {
                antiKick = j.has("kick") && j.get("kick").getAsBoolean();
                antiBan = j.has("ban") && j.get("ban").getAsBoolean();
                autoWhitelist = j.has("whitelist") && j.get("whitelist").getAsBoolean();
                silentJoin = j.has("stealth") && j.get("stealth").getAsBoolean();
                swKick.on = antiKick; swBan.on = antiBan; swWl.on = autoWhitelist; swStealth.on = silentJoin;
            }
        });
    }

    @Override
    public void render(GuiGraphicsExtractor g, int cx, int cy, int cw, int ch, int mx, int my) {
        int y = cy;
        Theme.text(g, "Mode", cx, y, Theme.MUTED); y += 11;
        int bw = (cw - GAP) / 2;
        darkBtn.primary = Theme.dark; lightBtn.primary = !Theme.dark;
        darkBtn.set(cx, y, bw, 18); lightBtn.set(cx + bw + GAP, y, bw, 18);
        darkBtn.render(g, mx, my); lightBtn.render(g, mx, my);
        y += 26;

        Theme.text(g, "Accent", cx, y, Theme.MUTED); y += 11;
        swX = cx; swY = y; cols = Math.max(1, (cw + GAP) / (SW + GAP));
        for (int i = 0; i < Theme.ACCENTS.length; i++) {
            int col = i % cols, rowI = i / cols;
            int x = cx + col * (SW + GAP), yy = y + rowI * (SH + GAP);
            boolean sel = Theme.ACCENTS[i] == Theme.ACCENT;
            if (sel) Theme.rounded(g, x - 1, yy - 1, SW + 2, SH + 2, 3, Theme.TEXT, Theme.CARD);
            Theme.rounded(g, x, yy, SW, SH, 2, Theme.ACCENTS[i], Theme.CARD);
        }
        int rows = (Theme.ACCENTS.length + cols - 1) / cols;
        y += rows * (SH + GAP) + 10;

        Theme.text(g, "Protection", cx, y, Theme.MUTED); y += 12;
        swKick.set(cx, y, cw, 18); swKick.render(g, mx, my); y += 20;
        swBan.set(cx, y, cw, 18); swBan.render(g, mx, my); y += 20;
        swWl.set(cx, y, cw, 18); swWl.render(g, mx, my); y += 20;
        swStealth.set(cx, y, cw, 18); swStealth.render(g, mx, my); y += 22;

        Theme.text(g, "Ping home", cx, y, Theme.MUTED); y += 11;
        Theme.text(g, "servers advertise on Discord and join this list", cx, y, Theme.MUTED); y += 12;
        discordToken.set(cx, y, cw, 16); discordToken.render(g, mx, my); y += 20;
        discordChannel.set(cx, y, cw, 16); discordChannel.render(g, mx, my); y += 21;
        discordSave.primary = true;
        int bw2 = (cw - GAP) / 2;
        discordSave.set(cx, y, bw2, 18); discordSave.render(g, mx, my);
        discordTest.set(cx + bw2 + GAP, y, bw2, 18); discordTest.render(g, mx, my); y += 21;
        if (!status.isEmpty()) Theme.text(g, status, cx, y, statusOk ? Theme.OK : Theme.BAD);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int btn) {
        for (int i = 0; i < Theme.ACCENTS.length; i++) {
            int col = i % cols, rowI = i / cols;
            int x = swX + col * (SW + GAP), yy = swY + rowI * (SH + GAP);
            if (mx >= x && mx < x + SW && my >= yy && my < yy + SH) { set(Theme.dark, Theme.ACCENTS[i]); return true; }
        }
        return super.mouseClicked(mx, my, btn);
    }
}