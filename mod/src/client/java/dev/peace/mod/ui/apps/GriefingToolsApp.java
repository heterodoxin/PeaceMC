package dev.peace.mod.ui.apps;

import com.google.gson.JsonObject;
import dev.peace.mod.Net;
import dev.peace.mod.ui.App;
import dev.peace.mod.ui.Theme;
import dev.peace.mod.ui.Widgets;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;

/** Self-centered griefing: a 50-block wand ray, mass effects, and entity control, never aimed at another player. */
public final class GriefingToolsApp extends App {
    private static final List<String> ALL_BLOCKS = allBlocks();
    private static final String[] MODES = { "FILL", "REPLACE", "BOOM" };
    private static final String[] ENTITIES = { "ZOMBIE", "CREEPER", "SKELETON", "SPIDER", "PHANTOM", "VEX", "BLAZE", "WITHER", "WARDEN" };
    private static final String[] SMALL = { "2", "3", "4", "6", "8", "10" };
    private static final String[] COUNTS = { "1", "2", "3", "5", "8", "12" };

    private final Widgets.TabBar tabs = new Widgets.TabBar(new String[]{"Wand", "Mass", "Entities"});
    private final Widgets.ComboBox wandBlock = cmb("target block", ALL_BLOCKS, "minecraft:cobblestone");
    private final Widgets.ComboBox wandRadius = cmb("radius", SMALL, "4");
    private final Widgets.ComboBox wandMode = cmb("mode", MODES, "FILL");
    private final Widgets.Button wandGive = new Widgets.Button("Give Wand", this::giveWand);
    private final Widgets.ComboBox massBlock = cmb("target block", ALL_BLOCKS, "minecraft:cobblestone");
    private final Widgets.ComboBox massRadius = cmb("radius", SMALL, "4");
    private final Widgets.Button massFill = new Widgets.Button("Fill Radius", this::massFill);
    private final Widgets.Button massBoom = new Widgets.Button("Explode", this::massBoom);
    private final Widgets.ComboBox stormCount = cmb("strikes", COUNTS, "3");
    private final Widgets.Button stormGo = new Widgets.Button("Lightning Storm", this::massStorm);
    private final Widgets.ComboBox rainCount = cmb("count", COUNTS, "3");
    private final Widgets.Button rainGo = new Widgets.Button("TNT Rain", this::massRain);
    private final Widgets.ComboBox entType = cmb("entity", ENTITIES, "ZOMBIE");
    private final Widgets.ComboBox entCount = cmb("count", COUNTS, "5");
    private final Widgets.Button entSpawn = new Widgets.Button("Spawn Around Me", this::massMobs);
    private String status = "";
    private int statusColor = Theme.OK;

    /** Every block id in the game's registry, so the wand can stamp ANY block. */
    private static List<String> allBlocks() {
        List<String> out = new ArrayList<>();
        for (Identifier id : BuiltInRegistries.BLOCK.keySet()) {
            String n = id.toString();
            if (n.equals("minecraft:air") || n.equals("minecraft:cave_air") || n.equals("minecraft:void_air")) continue;
            out.add(n);
        }
        out.sort(String.CASE_INSENSITIVE_ORDER);
        return out;
    }

    public GriefingToolsApp() {
        super("Griefing", "GRF");
        prefW = 300; prefH = 312;
        widgets.add(tabs);
        widgets.add(wandBlock); widgets.add(wandRadius); widgets.add(wandMode); widgets.add(wandGive);
        widgets.add(massBlock); widgets.add(massRadius); widgets.add(massFill); widgets.add(massBoom);
        widgets.add(stormCount); widgets.add(stormGo); widgets.add(rainCount); widgets.add(rainGo);
        widgets.add(entType); widgets.add(entCount); widgets.add(entSpawn);
    }

    private static Widgets.ComboBox cmb(String ph, String[] opts, String def) {
        Widgets.ComboBox c = new Widgets.ComboBox(ph);
        c.options.addAll(java.util.List.of(opts));
        if (def != null) c.text.append(def);
        return c;
    }
    private static Widgets.ComboBox cmb(String ph, List<String> opts, String def) {
        Widgets.ComboBox c = new Widgets.ComboBox(ph);
        c.options.addAll(opts);
        if (def != null) c.text.append(def);
        return c;
    }

    private int n(Widgets.ComboBox c) {
        try { return Math.max(0, Integer.parseInt(c.value().trim())); } catch (Exception e) { return 0; }
    }

    private void send(JsonObject req) {
        Net.send(req, j -> { status = replyText(j); statusColor = replyColor(j); });
    }

    private void giveWand() {
        JsonObject req = Net.op("wand");
        req.addProperty("block", wandBlock.value().trim());
        req.addProperty("radius", n(wandRadius));
        req.addProperty("mode", wandMode.value().trim().toUpperCase());
        send(req);
    }
    private void massFill() {
        JsonObject req = Net.op("mass.fill");
        req.addProperty("block", massBlock.value().trim());
        req.addProperty("radius", n(massRadius));
        send(req);
    }
    private void massBoom() {
        JsonObject req = Net.op("mass.boom");
        req.addProperty("radius", n(massRadius));
        send(req);
    }
    private void massStorm() {
        JsonObject req = Net.op("mass.storm");
        req.addProperty("count", n(stormCount));
        send(req);
    }
    private void massRain() {
        JsonObject req = Net.op("mass.tntrain");
        req.addProperty("count", n(rainCount));
        send(req);
    }
    private void massMobs() {
        JsonObject req = Net.op("mass.mobs");
        req.addProperty("type", entType.value().trim().toUpperCase());
        req.addProperty("count", n(entCount));
        send(req);
    }

    private static void park(Widgets.Widget w) { w.set(-100000, -100000, 0, 0); }

    @Override
    public void render(GuiGraphicsExtractor g, int cx, int cy, int cw, int ch, int mx, int my) {
        int y = cy;
        tabs.set(cx, y, cw, 18); tabs.render(g, mx, my);
        y += 25;

        if (tabs.selected == 2) {
            park(wandBlock); park(wandRadius); park(wandMode); park(wandGive);
            park(massBlock); park(massRadius); park(massFill); park(massBoom);
            park(stormCount); park(stormGo); park(rainCount); park(rainGo);
            Theme.text(g, "spawn entities around you", cx, y, Theme.MUTED); y += 12;
            int hw = (cw - 6) / 2;
            entType.set(cx, y, hw, 16); entType.render(g, mx, my);
            entCount.set(cx + hw + 6, y, hw, 16); entCount.render(g, mx, my); y += 20;
            entSpawn.primary = true;
            entSpawn.set(cx, y, cw, 18); entSpawn.render(g, mx, my);
        } else if (tabs.selected == 1) {
            park(wandBlock); park(wandRadius); park(wandMode); park(wandGive);
            park(entType); park(entCount); park(entSpawn);
            Theme.text(g, "mass effects centered on you", cx, y, Theme.MUTED); y += 12;
            massBlock.set(cx, y, cw, 16); massBlock.render(g, mx, my); y += 20;
            int hw = (cw - 6) / 2;
            massRadius.set(cx, y, hw, 16); massRadius.render(g, mx, my);
            massFill.primary = true;
            massFill.set(cx + hw + 6, y, hw, 16); massFill.render(g, mx, my); y += 20;
            massBoom.set(cx, y, cw, 16); massBoom.render(g, mx, my); y += 20;
            stormCount.set(cx, y, hw, 16); stormCount.render(g, mx, my);
            stormGo.set(cx + hw + 6, y, hw, 16); stormGo.render(g, mx, my); y += 20;
            rainCount.set(cx, y, hw, 16); rainCount.render(g, mx, my);
            rainGo.set(cx + hw + 6, y, hw, 16); rainGo.render(g, mx, my);
        } else {
            park(massBlock); park(massRadius); park(massFill); park(massBoom);
            park(stormCount); park(stormGo); park(rainCount); park(rainGo);
            park(entType); park(entCount); park(entSpawn);
            Theme.text(g, "right-click anywhere: casts a 50-block ray from your eyes", cx, y, Theme.MUTED); y += 12;
            Theme.text(g, "and stamps the first block hit (or the ray's end)", cx, y, Theme.MUTED); y += 12;
            wandBlock.set(cx, y, cw, 16); wandBlock.render(g, mx, my); y += 20;
            int hw = (cw - 6) / 2;
            wandRadius.set(cx, y, hw, 16); wandRadius.render(g, mx, my);
            wandMode.set(cx + hw + 6, y, hw, 16); wandMode.render(g, mx, my); y += 20;
            wandGive.primary = true;
            wandGive.set(cx, y, cw, 18); wandGive.render(g, mx, my);
        }
        if (!status.isEmpty()) Theme.text(g, status, cx, cy + ch - 11, statusColor);
    }
}