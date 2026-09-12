package dev.peace.mod.ui.apps;

import dev.peace.mod.Net;
import dev.peace.mod.Servers;
import dev.peace.mod.ui.App;
import dev.peace.mod.ui.Theme;
import dev.peace.mod.ui.Widgets;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;

/** Backdoored servers - auto-added when their plugin says hello. */
public final class ServersApp extends App {
    private static final int ROW = 16, JOIN_W = 34, VAN_W = 46, X_W = 12;

    private final Widgets.TextInput nameIn = new Widgets.TextInput("name");
    private final Widgets.TextInput addrIn = new Widgets.TextInput("host:port");
    private final Widgets.Button addBtn = new Widgets.Button("Add", this::add);
    private String status = "";
    private int listX, listY, listW;

    public ServersApp() {
        super("Servers", "NET");
        prefW = 380; prefH = 250;
        addBtn.primary = true;
        addrIn.onEnter = this::add;
        widgets.add(nameIn); widgets.add(addrIn);
    }

    @Override
    public void render(GuiGraphicsExtractor g, int cx, int cy, int cw, int ch, int mx, int my) {
        int y = cy;
        nameIn.set(cx, y, 90, 16);
        addrIn.set(cx + 94, y, cw - 94 - 44, 16);
        addBtn.set(cx + cw - 40, y, 40, 16);
        nameIn.render(g, mx, my); addrIn.render(g, mx, my); addBtn.render(g, mx, my);
        y += 24;

        listX = cx; listY = y; listW = cw;
        var list = Servers.list();
        int listH = ch - 10 - (y - cy);
        Theme.rounded(g, cx, y - 2, cw, listH + 2, 5, Theme.FIELD_IN, Theme.CARD);
        if (list.isEmpty())
            Theme.text(g, "join a backdoored server once - it appears here", cx + 8, y + 7, Theme.MUTED);
        for (int i = 0; i < list.size(); i++) {
            int ry = y + 3 + i * ROW;
            boolean hover = mx >= cx && mx < cx + cw && my >= ry && my < ry + ROW - 1;
            if (hover) Theme.rounded(g, cx + 2, ry - 1, cw - 4, ROW, 4, Theme.FIELD, Theme.FIELD_IN);
            Servers.Entry s = list.get(i);
            int dot = s.online() ? 0xFF3fd65f : 0xFFe04f4f;
            Theme.rounded(g, cx + 8, ry + 7, 7, 7, 3, dot, dot);
            Theme.text(g, Theme.trim(s.name(), 88), cx + 21, ry + 4, s.online() ? Theme.TEXT : Theme.MUTED);
            Theme.text(g, Theme.trim(s.addr(), cw - 100 - JOIN_W - VAN_W - X_W - 20), cx + 108, ry + 4, Theme.MUTED);
            int vx = cx + cw - X_W - 8 - VAN_W, jx = vx - 4 - JOIN_W;
            btn(g, jx, ry, JOIN_W, "Join", true);
            btn(g, vx, ry, VAN_W, "Vanish", false);
            Theme.text(g, "×", cx + cw - X_W + 3, ry + 4, Theme.MUTED);
        }
        if (!status.isEmpty()) Theme.text(g, Theme.trim(status, cw), cx, cy + ch - 9, Theme.MUTED);
    }

    private void btn(GuiGraphicsExtractor g, int x, int ry, int w, String label, boolean primary) {
        Theme.rounded(g, x, ry, w, ROW - 2, 4, primary ? Theme.ACCENT : 0xFF2a2c36, Theme.FIELD_IN);
        Theme.text(g, label, x + (w - Theme.width(label)) / 2, ry + 4, primary ? Theme.ON : Theme.MUTED);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int btn) {
        if (nameIn.mouseClicked(mx, my, btn) || addrIn.mouseClicked(mx, my, btn) || addBtn.mouseClicked(mx, my, btn)) return true;
        var list = Servers.list();
        for (int i = 0; i < list.size(); i++) {
            int ry = listY + 3 + i * ROW;
            if (my < ry || my >= ry + ROW) continue;
            int vx = listX + listW - X_W - 6 - VAN_W, jx = vx - 4 - JOIN_W;
            if (mx >= jx && mx < jx + JOIN_W) { join(list.get(i), false); return true; }
            if (mx >= vx && mx < vx + VAN_W) { join(list.get(i), true); return true; }
            if (mx >= listX + listW - X_W) { Servers.remove(i); return true; }
        }
        return false;
    }

    private void add() {
        String addr = addrIn.value().trim();
        if (addr.isEmpty()) { status = "enter host:port"; return; }
        Servers.add(nameIn.value().trim(), addr);
        nameIn.text.setLength(0); addrIn.text.setLength(0);
    }

    private void join(Servers.Entry s, boolean vanished) {
        if (vanished) Net.requestVanishOnJoin();
        Minecraft mc = Minecraft.getInstance();
        ServerData data = new ServerData(s.name(), s.addr(), ServerData.Type.OTHER);
        ServerAddress addr = ServerAddress.parseString(s.addr());
        ConnectScreen.startConnecting(new TitleScreen(), mc, addr, data, false, null);
    }
}