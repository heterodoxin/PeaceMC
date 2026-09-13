package dev.peace.mod.ui.apps;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.peace.mod.Net;
import dev.peace.mod.ui.App;
import dev.peace.mod.ui.Theme;
import dev.peace.mod.ui.Widgets;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Spread control: pick targets (or all), inject in memory, persist onto disk, reset carriers. */
public final class SpreadApp extends App {
    private final Widgets.Switch persist = new Widgets.Switch("Persist to disk", true, null);
    private final Widgets.Switch all = new Widgets.Switch("All plugins", true, null);
    private final PluginList list = new PluginList();
    private final Widgets.Button inject = new Widgets.Button("Inject", this::inject);
    private final Widgets.Button refresh = new Widgets.Button("Refresh", this::status);
    private final Widgets.Button reset = new Widgets.Button("Reset", this::reset);
    private final Widgets.ScrollText log = new Widgets.ScrollText();
    private String active = "-";
    private boolean activeLoaded;

    public SpreadApp() {
        super("Spread", "SPR");
        prefW = 480; prefH = 320;
        inject.primary = true;
        widgets.add(persist); widgets.add(all); widgets.add(list);
        widgets.add(inject); widgets.add(refresh); widgets.add(reset); widgets.add(log);
        log.add("Spread folds Peace under other loaded plugins.");
        log.add("Pick targets (or All), then Inject.");
        log.add("Persist rewrites jars on disk so Peace");
        log.add("survives removing the first carrier.");
        status();
    }

    @Override
    public void render(GuiGraphicsExtractor g, int cx, int cy, int cw, int ch, int mx, int my) {
        Theme.text(g, "active: " + Theme.trim(active, cw - 40), cx, cy + 1, activeLoaded ? Theme.OK : Theme.MUTED);
        int y = cy + 13;
        Theme.text(g, "Targets", cx, y + 1, Theme.MUTED);
        all.set(cx + cw / 2, y, cw / 2 - 4, 14);
        persist.set(cx + cw / 2, y + 15, cw / 2 - 4, 14);
        y += 32;
        int remain = cy + ch - 22 - y;
        list.set(cx, y, (cw - 8) / 2, Math.max(40, remain));
        log.set(cx + (cw - 8) / 2 + 8, y, (cw - 8) / 2, Math.max(40, remain));
        int by = cy + ch - 20;
        int bw = (cw - 12) / 3;
        inject.set(cx, by, bw, 18);
        refresh.set(cx + bw + 6, by, bw, 18);
        reset.set(cx + 2 * (bw + 6), by, bw, 18);
        persist.render(g, mx, my); all.render(g, mx, my);
        list.render(g, mx, my); log.render(g, mx, my);
        inject.render(g, mx, my); refresh.render(g, mx, my); reset.render(g, mx, my);
    }

    private void inject() {
        JsonObject req = Net.op("spread.inject");
        req.addProperty("persist", persist.on);
        List<String> targets = targets();
        if (targets != null) {
            JsonArray arr = new JsonArray();
            for (String t : targets) arr.add(t);
            req.add("targets", arr);
        }
        Net.send(req, j -> {
            if (ok(j)) {
                log.add(j.get("msg").getAsString() + "   persisted=" + j.get("persisted").getAsInt());
                active = j.get("active").getAsString();
                activeLoaded = true;
            } else {
                log.add(replyText(j));
            }
            status();
        });
    }

    private void status() {
        Net.send(Net.op("spread.status"), j -> {
            if (!ok(j)) { log.add(replyText(j)); return; }
            active = j.get("active").getAsString();
            activeLoaded = !active.equals("none");
            List<Row> fresh = new ArrayList<>();
            for (var e : j.getAsJsonArray("plugins")) {
                JsonObject o = e.getAsJsonObject();
                Row r = new Row(o.get("name").getAsString());
                r.carrier = o.get("carrier").getAsBoolean();
                r.active = o.get("active").getAsBoolean();
                r.enabled = true;
                Row prev = list.rows.get(r.name);
                r.on = prev != null && prev.on;
                fresh.add(r);
            }
            list.rows.clear();
            for (Row r : fresh) list.rows.put(r.name, r);
        });
    }

    private void reset() {
        Net.send(Net.op("spread.unregister"), j -> log.add(replyText(j)));
    }

    private List<String> targets() {
        if (all.on) return null; // all plugins
        List<String> t = new ArrayList<>();
        for (Row r : list.rows.values()) if (r.on && r.enabled) t.add(r.name);
        return t.isEmpty() ? null : t;
    }

    /** One plugin row; click toggles whether it is a target (when "All plugins" is off). */
    private static final class Row {
        final String name;
        boolean enabled, carrier, active, on;
        Row(String name) { this.name = name; }
    }

    private static final class PluginList extends Widgets.Widget {
        final Map<String, Row> rows = new LinkedHashMap<>();

        @Override public void render(GuiGraphicsExtractor g, int mx, int my) {
            Theme.rounded(g, x, y, w, h, 3, Theme.FIELD_IN, Theme.CARD);
            Theme.scissorOn(g, x + 2, y + 2, x + w - 2, y + h - 2);
            int ty = y + 4, rh = 13;
            for (Row r : rows.values()) {
                if (ty + rh < y) { ty += rh; continue; }
                if (ty > y + h) break;
                boolean hover = mx >= x && mx < x + w && my >= ty && my < ty + rh;
                if (r.on) Theme.fill(g, x + 2, ty, x + w - 2, ty + rh, 0x14ffffff);
                else if (hover) Theme.fill(g, x + 2, ty, x + w - 2, ty + rh, 0x0dffffff);
                Theme.text(g, r.active ? ">" : " ", x + 4, ty + 2, r.active ? Theme.ACCENT : Theme.MUTED);
                Theme.text(g, Theme.trim(r.name, w - 66), x + 11, ty + 2, r.on ? Theme.TEXT : (r.carrier ? 0xFF9f9fa8 : Theme.MUTED));
                Theme.text(g, r.carrier ? "on" : "clean", x + w - 38, ty + 2, r.carrier ? Theme.OK : Theme.MUTED);
                if (r.carrier) Theme.text(g, "x", x + w - 16, ty + 2, r.on ? Theme.OK : Theme.MUTED);
                Theme.hline(g, x + 4, ty + rh - 1, w - 8, 0x12ffffff);
                ty += rh;
            }
            if (rows.isEmpty()) Theme.text(g, "no plugins loaded", x + 6, y + 5, Theme.MUTED);
            Theme.scissorOff(g);
        }

        @Override public boolean mouseClicked(double mx, double my, int btn) {
            if (btn != 0 || !contains(mx, my)) return false;
            int rh = 13, i = (int) ((my - y - 4) / rh);
            int k = 0;
            for (Row r : rows.values()) {
                if (k++ == i) { r.on = !r.on; return true; }
            }
            return false;
        }
    }
}