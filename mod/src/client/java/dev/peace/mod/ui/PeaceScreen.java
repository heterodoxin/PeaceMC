package dev.peace.mod.ui;

import dev.peace.mod.ui.apps.AdminApp;
import dev.peace.mod.ui.apps.DangerApp;
import dev.peace.mod.ui.apps.FilesApp;
import dev.peace.mod.ui.apps.GriefingToolsApp;
import dev.peace.mod.ui.apps.ServersApp;
import dev.peace.mod.ui.apps.SettingsApp;
import dev.peace.mod.ui.apps.TerminalApp;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/** PEACE desktop: pitch black, logo, app tiles in a neat grid, floating windows. */
public final class PeaceScreen extends Screen {
    public static volatile boolean OPEN = false;
    private static final int TILE = 40;
    private static final int TOP_H = Theme.logoH() + 14;

    private record Entry(String label, String icon, Supplier<App> make) {}
    private static final List<Entry> APPS = List.of(
        new Entry("Servers",  "NET", ServersApp::new),
        new Entry("Admin",    "ADM", AdminApp::new),
        new Entry("Griefing", "GRF", GriefingToolsApp::new),
        new Entry("Danger",   "DNG", DangerApp::new),
        new Entry("Terminal", "$_",  TerminalApp::new),
        new Entry("Files",    "DIR", FilesApp::new),
        new Entry("Settings", "SET", SettingsApp::new)
    );

    private static final class Shortcut { final Entry e; int x, y; Shortcut(Entry e, int x, int y) { this.e = e; this.x = x; this.y = y; } }
    private final List<Shortcut> icons = new ArrayList<>();
    private final List<AppWindow> windows = new ArrayList<>();
    private final Screen parent;
    private int cascade;

    public PeaceScreen() { this(null); }
    public PeaceScreen(Screen parent) {
        super(Component.literal("PEACE"));
        this.parent = parent;
        int y = TOP_H + 16;
        for (int i = 0; i < APPS.size(); i++) {
            icons.add(new Shortcut(APPS.get(i), 20, y));
            y += TILE + 14;
        }
    }

    @Override public boolean isPauseScreen() { return false; }
    @Override protected void init() { OPEN = true; }
    @Override public void removed() { OPEN = false; }
    @Override public void onClose() {
        OPEN = false;
        if (parent != null) minecraft.setScreenAndShow(parent); else super.onClose();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
        Theme.fill(g, 0, 0, width, height, Theme.BG);
        Theme.logo(g, 12, 6, Theme.LOGO_DISPLAY_W, Theme.logoH());

        for (Shortcut s : icons) renderTile(g, s, mx, my);
        for (int i = 0; i < windows.size(); i++) windows.get(i).render(g, mx, my, i == windows.size() - 1);
        // open dropdowns float over EVERYTHING (screen-level popups, never clipped by windows)
        for (int i = 0; i < windows.size(); i++) windows.get(i).renderOverlay(g, mx, my);
    }

    private void renderTile(GuiGraphicsExtractor g, Shortcut s, int mx, int my) {
        boolean hover = mx >= s.x - 2 && mx < s.x + TILE + 2 && my >= s.y - 1 && my < s.y + TILE + 12;
        int cx = s.x + TILE / 2, cy = s.y + TILE / 2;
        Theme.rounded(g, s.x, s.y, TILE, TILE, 5, hover ? 0xFF1a1c24 : 0xFF101116, Theme.BG);
        if (hover) Theme.rounded(g, s.x + 1, s.y + 1, TILE - 2, TILE - 2, 4, 0xFF22242e, Theme.BG);
        drawGlyph(g, s.e.icon, cx, cy);
        Theme.text(g, s.e.label(), s.x + (TILE - Theme.width(s.e.label())) / 2, s.y + TILE + 4, hover ? Theme.TEXT : Theme.MUTED);
    }

    /** Angular accent glyphs (no circles). */
    private void drawGlyph(GuiGraphicsExtractor g, String key, int cx, int cy) {
        switch (key) {
            case "NET" -> { // two rack units, flat
                Theme.fill(g, cx - 9, cy - 9, cx + 9, cy - 3, Theme.ACCENT);
                Theme.fill(g, cx - 9, cy + 3, cx + 9, cy + 9, Theme.ACCENT_DIM);
            }
            case "ADM" -> { // solid shield with a tapered point
                Theme.rounded(g, cx - 7, cy - 9, 14, 11, 2, Theme.ACCENT, 0xFF101116);
                int w = 10;
                for (int i = 0; i < 6; i++) { int y = cy + 2 + i; Theme.fill(g, cx - w / 2, y, cx + w / 2, y + 1, Theme.ACCENT); w -= 1; }
                Theme.fill(g, cx - 1, cy - 6, cx + 1, cy + 2, 0xFF101116);
            }
            case "GRF" -> { // explosion burst
                Theme.fill(g, cx - 8, cy - 1, cx + 8, cy + 1, Theme.ACCENT);
                Theme.fill(g, cx - 1, cy - 8, cx + 1, cy + 8, Theme.ACCENT);
                Theme.fill(g, cx - 5, cy - 5, cx - 3, cy - 3, Theme.ACCENT);
                Theme.fill(g, cx + 3, cy - 5, cx + 5, cy - 3, Theme.ACCENT);
                Theme.fill(g, cx - 5, cy + 3, cx - 3, cy + 5, Theme.ACCENT);
                Theme.fill(g, cx + 3, cy + 3, cx + 5, cy + 5, Theme.ACCENT);
            }
            case "DNG" -> { // warning triangle
                Theme.fill(g, cx - 7, cy - 8, cx + 7, cy - 6, Theme.ACCENT);
                Theme.fill(g, cx - 3, cy - 6, cx + 3, cy + 7, Theme.ACCENT);
                Theme.fill(g, cx - 1, cy + 5, cx + 1, cy + 8, Theme.ACCENT);
                Theme.fill(g, cx - 1, cy - 2, cx + 1, cy + 2, 0xFF101116);
            }
            case "$_" -> { // terminal screen with prompt
                Theme.rounded(g, cx - 9, cy - 8, 18, 16, 2, Theme.ACCENT, 0xFF101116);
                Theme.text(g, ">_", cx - 4, cy - 4, 0xFF101116);
            }
            case "DIR" -> { // folder
                Theme.fill(g, cx - 8, cy - 6, cx + 3, cy - 2, Theme.ACCENT);
                Theme.rounded(g, cx - 8, cy - 3, 16, 11, 2, Theme.ACCENT, 0xFF101116);
            }
            case "SET" -> { // sliders
                for (int i = 0; i < 3; i++) {
                    int y = cy - 6 + i * 6;
                    Theme.rounded(g, cx - 8, y, 16, 4, 2, Theme.ACCENT_DIM, 0xFF101116);
                    Theme.fill(g, cx + (i % 2 == 0 ? 3 : -4), y, cx + (i % 2 == 0 ? 7 : 0), y + 4, Theme.ACCENT);
                }
            }
            default -> { }
        }
    }

    private int iconHit(double mx, double my) {
        for (int i = icons.size() - 1; i >= 0; i--) {
            Shortcut s = icons.get(i);
            if (mx >= s.x && mx < s.x + TILE && my >= s.y && my < s.y + TILE + 10) return i;
        }
        return -1;
    }

    private void bringToFront(int i) {
        AppWindow w = windows.remove(i); windows.add(w);
        windows.removeIf(x -> x.wantsClose);
    }
    private void closeAllCombos() {
        for (AppWindow w : windows) { Widgets.ComboBox c = w.openCombo(); if (c != null) c.open = false; }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent e, boolean dbl) {
        double mx = e.x(), my = e.y();
        // 1) the topmost open dropdown eats clicks inside its popup (it floats over every window)
        for (int i = windows.size() - 1; i >= 0; i--) {
            AppWindow w = windows.get(i);
            Widgets.ComboBox c = w.openCombo();
            if (c != null && c.clickPopup(mx, my, e.button())) {
                bringToFront(i); return true;
            }
        }
        // 2) clicking a combo field toggles it and dismisses the others
        for (int i = windows.size() - 1; i >= 0; i--) {
            AppWindow w = windows.get(i);
            Widgets.ComboBox c = w.fieldCombo(mx, my);
            if (c != null) {
                bringToFront(i);
                c.mouseClicked(mx, my, e.button());
                for (AppWindow ow : windows) if (ow != w) { Widgets.ComboBox oc = ow.openCombo(); if (oc != null) oc.open = false; }
                return true;
            }
        }
        // 3) normal window handling
        for (int i = windows.size() - 1; i >= 0; i--) {
            AppWindow w = windows.get(i);
            if (w.mouseClicked(mx, my, e.button())) { bringToFront(i); closeAllCombos(); return true; }
        }
        int ic = iconHit(mx, my);
        if (ic >= 0) {
            closeAllCombos();
            openApp(APPS.get(ic));
            return true;
        }
        closeAllCombos();
        return super.mouseClicked(e, dbl);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent e, double dx, double dy) {
        if (!windows.isEmpty()) windows.get(windows.size() - 1).mouseDragged(e.x(), e.y());
        return true;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent e) {
        for (AppWindow w : windows) w.mouseReleased();
        return super.mouseReleased(e);
    }

    private void openApp(Entry entry) {
        windows.add(new AppWindow(entry.make().get(), 80 + cascade % 6 * 26, TOP_H + 16 + cascade % 6 * 22));
        cascade++;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double hAmt, double vAmt) {
        for (int i = windows.size() - 1; i >= 0; i--) {
            Widgets.ComboBox c = windows.get(i).openCombo();
            if (c != null && c.scrollPopup(mx, my, vAmt)) return true;
        }
        for (int i = windows.size() - 1; i >= 0; i--) if (windows.get(i).scrolled(mx, my, vAmt)) return true;
        return super.mouseScrolled(mx, my, hAmt, vAmt);
    }
    @Override
    public boolean keyPressed(KeyEvent e) {
        if (e.key() == GLFW.GLFW_KEY_ESCAPE) { onClose(); return true; }
        if (!windows.isEmpty() && windows.get(windows.size() - 1).keyPressed(e.key(), e.scancode(), e.modifiers())) return true;
        return super.keyPressed(e);
    }
    @Override
    public boolean charTyped(CharacterEvent e) {
        if (!windows.isEmpty() && windows.get(windows.size() - 1).charTyped((char) e.codepoint())) return true;
        return super.charTyped(e);
    }
}