package dev.peace.mod.ui;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

import java.nio.file.Files;
import java.nio.file.Path;

/** Flat dark theme of off-black rounded cards stamped with anti-aliased corner masks. */
public final class Theme {
    // palette (recomputed by apply())
    public static int ACCENT = 0xFFe6202e;
    public static int ACCENT_DIM = 0xFF6a0f16;
    public static int BG = 0xFF060608;          // desktop
    public static int CARD = 0xFF0f1015;        // window body
    public static int CARD_TOP = 0xFF15161c;    // window header
    public static int CARD_BOT = 0xFF0c0d11;    // window footer
    public static int FIELD = 0xFF17181e;       // buttons / inputs
    public static int FIELD_IN = 0xFF0a0b0e;    // recessed surface (lists)
    public static int TEXT = 0xFFececf1;
    public static int MUTED = 0xFF7e8188;
    public static int HOVER = 0x0dffffff; // neutral subtle highlight, no red wash
    public static final int ON = 0xFFFFFFFF;
    public static final int OK = 0xFF4bd07a;
    public static final int BAD = 0xFFff5555;
    public static boolean dark = true;

    public static final int[] ACCENTS = {0xFFe6202e, 0xFF9b30ff, 0xFF2f7bff, 0xFF25c26b, 0xFFff8c1a, 0xFF18c0c0};
    public static final String[] ACCENT_NAMES = {"Red", "Purple", "Blue", "Green", "Orange", "Cyan"};

    // The wordmark tracks a fraction of the window's pixel width so it adapts to the
    // display, but is clamped to a fixed physical band so it never balloons or vanishes;
    // then it is converted into GUI units so the HUD and every screen agree on its size
    // regardless of the GUI Scale setting. One size everywhere, in and out of a GUI.
    public static final int LOGO_W = 866, LOGO_H = 288;
    public static final float LOGO_WINDOW_FRAC = 0.15f;             // share of framebuffer width
    public static final int LOGO_MIN_PX = 180, LOGO_MAX_PX = 420;   // physical size band
    public static final int LOGO_MARGIN_PX = 8;                     // physical top-left inset

    /** Logo width in GUI units for the current window. */
    public static int logoW() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.getWindow() == null) return LOGO_MAX_PX / 2;
        var win = mc.getWindow();
        int fbw = Math.max(1, win.getWidth());
        int gsw = Math.max(1, win.getGuiScaledWidth());
        int target = Math.max(LOGO_MIN_PX, Math.min(LOGO_MAX_PX, Math.round(fbw * LOGO_WINDOW_FRAC)));
        return Math.max(1, Math.round(target * gsw / (float) fbw));
    }
    public static int logoH() { return logoW() * LOGO_H / LOGO_W; }

    /** Top-left inset in GUI units matching a fixed physical margin. */
    public static int logoMargin() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.getWindow() == null) return LOGO_MARGIN_PX;
        var win = mc.getWindow();
        int fbw = Math.max(1, win.getWidth());
        int gsw = Math.max(1, win.getGuiScaledWidth());
        return Math.max(1, Math.round(LOGO_MARGIN_PX * gsw / (float) fbw));
    }

    private static final String NS = "qolclient"; // innocuous mod id: keeps logs clean
    private static final int CMSK = 32; // corner mask texture size
    private static final Identifier LOGO = Identifier.fromNamespaceAndPath(NS, "textures/gui/peace_white.png");
    private static final Identifier C_TL = Identifier.fromNamespaceAndPath(NS, "textures/gui/corner_tl.png");
    private static final Identifier C_TR = Identifier.fromNamespaceAndPath(NS, "textures/gui/corner_tr.png");
    private static final Identifier C_BL = Identifier.fromNamespaceAndPath(NS, "textures/gui/corner_bl.png");
    private static final Identifier C_BR = Identifier.fromNamespaceAndPath(NS, "textures/gui/corner_br.png");
    private static final Identifier GLOW = Identifier.fromNamespaceAndPath(NS, "textures/gui/glow.png");

    public static void apply(boolean darkMode, int accent) {
        dark = darkMode; ACCENT = accent;
        ACCENT_DIM = darken(accent, 0.42f);
        HOVER = 0x0dffffff; // neutral subtle highlight, no accent wash
        if (dark) { BG = 0xFF060608; CARD = 0xFF0f1015; CARD_TOP = 0xFF15161c; CARD_BOT = 0xFF0c0d11; FIELD = 0xFF17181e; FIELD_IN = 0xFF0a0b0e; TEXT = 0xFFececf1; MUTED = 0xFF7e8188; }
        else { BG = 0xFFf2f2f4; CARD = 0xFFffffff; CARD_TOP = 0xFFfbfbfc; CARD_BOT = 0xFFf0f0f2; FIELD = 0xFFe8e8ec; FIELD_IN = 0xFFf7f7f9; TEXT = 0xFF16171a; MUTED = 0xFF6b6d72; }
    }
    static { apply(true, ACCENT); }

    private static int darken(int argb, float f) {
        int a = argb >>> 24, r = (int) (((argb >> 16) & 0xFF) * f), g = (int) (((argb >> 8) & 0xFF) * f), b = (int) ((argb & 0xFF) * f);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private static Path file() { return FabricLoader.getInstance().getConfigDir().resolve("qolclient-theme.txt"); }
    public static void load() {
        try {
            if (!Files.exists(file())) return;
            String[] p = Files.readString(file()).trim().split(",");
            apply(!p[0].equals("light"), (int) Long.parseLong(p[1], 16));
        } catch (Exception ignored) {}
    }
    public static void save() {
        try { Files.writeString(file(), (dark ? "dark" : "light") + "," + Integer.toHexString(ACCENT)); } catch (Exception ignored) {}
    }

    public static Font font() { return Minecraft.getInstance().font; }
    public static int width(String s) { return font().width(s); }

    // --- flat primitives ---
    public static void fill(GuiGraphicsExtractor g, int x1, int y1, int x2, int y2, int argb) { g.fill(x1, y1, x2, y2, argb); }
    public static void gradient(GuiGraphicsExtractor g, int x1, int y1, int x2, int y2, int top, int bot) { g.fillGradient(x1, y1, x2, y2, top, bot); }
    public static void border(GuiGraphicsExtractor g, int x, int y, int w, int h, int argb) { g.outline(x, y, w, h, argb); }
    public static void scissorOn(GuiGraphicsExtractor g, int x1, int y1, int x2, int y2) { g.enableScissor(x1, y1, x2, y2); }
    public static void scissorOff(GuiGraphicsExtractor g) { g.disableScissor(); }
    public static void text(GuiGraphicsExtractor g, String s, int x, int y, int argb) { g.text(font(), s, x, y, argb); }

    public static void hline(GuiGraphicsExtractor g, int x, int y, int w, int color) { if (w > 0) fill(g, x, y, x + w, y + 1, color); }
    public static void vline(GuiGraphicsExtractor g, int x, int y, int h, int color) { if (h > 0) fill(g, x, y, x + 1, y + h, color); }

    public static void line(GuiGraphicsExtractor g, int x1, int y1, int x2, int y2, int color) {
        int dx = Math.abs(x2 - x1), dy = Math.abs(y2 - y1), sx = x1 < x2 ? 1 : -1, sy = y1 < y2 ? 1 : -1, err = dx - dy;
        while (true) {
            g.fill(x1, y1, x1 + 1, y1 + 1, color);
            if (x1 == x2 && y1 == y2) break;
            int e2 = 2 * err;
            if (e2 > -dy) { err -= dy; x1 += sx; }
            if (e2 < dx) { err += dx; y1 += sy; }
        }
    }

    // --- modern rounded primitives ---

    /** Stamp one corner notch (radius r at dest) with `tint` to erase a sharp corner. */
    public static void corner(GuiGraphicsExtractor g, Identifier id, int x, int y, int r, int tint) {
        g.blit(RenderPipelines.GUI_TEXTURED, id, x, y, 0f, 0f, r, r, CMSK, CMSK, CMSK, CMSK, tint);
    }

    /** Draws a soft radial spotlight at (cx, cy) in the given tint. */
    public static void glow(GuiGraphicsExtractor g, int cx, int cy, int size, int tint) {
        g.blit(RenderPipelines.GUI_TEXTURED, GLOW, cx - size / 2, cy - size / 2, 0f, 0f, size, size, size, size, size, size, tint);
    }

    /** Rounded rect. */
    public static void rounded(GuiGraphicsExtractor g, int x, int y, int w, int h, int r, int fill, int outer) {
        if (w <= 0 || h <= 0) return;
        if (r <= 0) { fill(g, x, y, x + w, y + h, fill); return; }
        r = Math.min(r, Math.min(w, h) / 2);
        fill(g, x, y, x + w, y + h, fill);
        corner(g, C_TL, x, y, r, outer);
        corner(g, C_TR, x + w - r, y, r, outer);
        corner(g, C_BL, x, y + h - r, r, outer);
        corner(g, C_BR, x + w - r, y + h - r, r, outer);
    }

    /** Rounded rect with a vertical colour gradient. */
    public static void roundedGrad(GuiGraphicsExtractor g, int x, int y, int w, int h, int r, int top, int bot, int outer) {
        if (w <= 0 || h <= 0) return;
        gradient(g, x, y, x + w, y + h, top, bot);
        r = Math.min(r, Math.min(w, h) / 2);
        stampCorners(g, x, y, w, h, r, outer);
    }

    private static void stampCorners(GuiGraphicsExtractor g, int x, int y, int w, int h, int r, int outer) {
        corner(g, C_TL, x, y, r, outer);
        corner(g, C_TR, x + w - r, y, r, outer);
        corner(g, C_BL, x, y + h - r, r, outer);
        corner(g, C_BR, x + w - r, y + h - r, r, outer);
    }

    /** A rounded border ring (1px) around a fill: draws a bigger outer shape then carves the centre. */
    public static void roundedRing(GuiGraphicsExtractor g, int x, int y, int w, int h, int r, int ring, int inner, int outer) {
        rounded(g, x - 1, y - 1, w + 2, h + 2, r + 1, ring, outer);
        rounded(g, x, y, w, h, Math.max(1, r - 1), inner, inner); // carve with own colour = no-op, ring stays
    }

    /** The big window card: flat rounded body with a faint top-light (flat = clean corner erases). */
    public static void card(GuiGraphicsExtractor g, int x, int y, int w, int h) {
        rounded(g, x, y, w, h, 6, CARD, BG);
        hline(g, x + 14, y + 1, w - 28, 0x22ffffff);
    }

    /** The logo at the standard fixed-physical top-left inset; identical in the HUD and every screen. */
    public static void logo(GuiGraphicsExtractor g) { int m = logoMargin(); logo(g, m, m, logoW(), logoH()); }

    /** Logo tinted with the accent color (white source * accent). */
    public static void logo(GuiGraphicsExtractor g, int x, int y, int drawW, int drawH) {
        g.blit(RenderPipelines.GUI_TEXTURED, LOGO, x, y, 0f, 0f, drawW, drawH, LOGO_W, LOGO_H, LOGO_W, LOGO_H, ACCENT);
    }

    public static String trim(String s, int maxWidth) {
        Font f = font();
        if (f.width(s) <= maxWidth) return s;
        while (s.length() > 1 && f.width(s + "…") > maxWidth) s = s.substring(0, s.length() - 1);
        return s + "…";
    }
}