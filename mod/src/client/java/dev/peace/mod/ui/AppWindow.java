package dev.peace.mod.ui;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/** A draggable/resizable window that hosts one App. */
public final class AppWindow {
    private static final int TITLE_H = 28, PAD = 10, MIN_W = 200, MIN_H = 130, GRIP = 12;
    public int x, y, w, h;
    public final App app;
    public boolean wantsClose;
    private boolean dragging, resizing;
    private int dragDX, dragDY;

    public AppWindow(App app, int x, int y) {
        this.app = app; this.x = x; this.y = y; this.w = app.prefW; this.h = app.prefH;
    }

    public boolean contains(double mx, double my) { return mx >= x && mx < x + w && my >= y && my < y + h; }
    public int contentX() { return x + PAD; }
    public int contentY() { return y + TITLE_H - 1 + 5; }
    private boolean inTitle(double mx, double my) { return mx >= x && mx < x + w && my >= y && my < y + TITLE_H; }
    private boolean inClose(double mx, double my) { return mx >= x + w - 30 && mx < x + w - 8 && my >= y + 4 && my < y + 24; }
    private boolean inGrip(double mx, double my) { return mx >= x + w - GRIP && mx < x + w && my >= y + h - GRIP && my < y + h; }

    public void render(GuiGraphicsExtractor g, int mx, int my, boolean focused) {
        Theme.card(g, x, y, w, h);
        // thin accent sash on the left of the title, then the label
        Theme.fill(g, x + 10, y + 8, x + 12, y + TITLE_H - 8, focused ? Theme.ACCENT : Theme.ACCENT_DIM);
        Theme.text(g, Theme.trim(app.title, w - 90), x + 20, y + 10, focused ? Theme.TEXT : Theme.MUTED);
        // close
        boolean closeHover = inClose(mx, my);
        if (closeHover) Theme.rounded(g, x + w - 29, y + 5, 20, 20, 3, Theme.ACCENT, Theme.CARD);
        Theme.text(g, "×", x + w - 19, y + 10, closeHover ? Theme.ON : Theme.MUTED);
        // hairline under the header (barely-there, neutral)
        Theme.hline(g, x + 14, y + TITLE_H - 1, w - 28, 0x1dffffff);
        int cx = x + PAD, cy = y + TITLE_H - 1 + 5, cw = w - 2 * PAD, ch = h - TITLE_H - 4 - PAD;
        Theme.scissorOn(g, x + 1, cy, x + w - 1, y + h - 1);
        app.render(g, cx, cy, cw, ch, mx, my);
        Theme.scissorOff(g);
        // resize grip: one slim bar, not dots
        Theme.fill(g, x + w - 24, y + h - 3, x + w - 6, y + h - 2, 0x40acd0ff);
    }

    /** Draw the app's floating popups (open dropdowns) above everything else. */
    public void renderOverlay(GuiGraphicsExtractor g, int mx, int my) {
        app.renderOverlay(g, mx, my);
    }

    public Widgets.ComboBox openCombo() { return app.openCombo(); }
    public Widgets.ComboBox fieldCombo(double mx, double my) { return app.fieldCombo(mx, my); }

    public boolean mouseClicked(double mx, double my, int btn) {
        if (inClose(mx, my)) { wantsClose = true; return true; }
        if (inGrip(mx, my)) { resizing = true; return true; }
        if (inTitle(mx, my)) { dragging = true; dragDX = (int) (mx - x); dragDY = (int) (my - y); return true; }
        if (contains(mx, my)) { app.mouseClicked(mx, my, btn); return true; }
        return false;
    }
    public void mouseDragged(double mx, double my) {
        if (dragging) { x = (int) (mx - dragDX); y = (int) (my - dragDY); }
        else if (resizing) { w = Math.max(MIN_W, (int) (mx - x)); h = Math.max(MIN_H, (int) (my - y)); }
    }
    public void mouseReleased() { dragging = false; resizing = false; app.mouseReleased(); }
    public boolean keyPressed(int key, int sc, int mod) { return app.keyPressed(key, sc, mod); }
    public boolean charTyped(char ch) { return app.charTyped(ch); }
    public boolean scrolled(double mx, double my, double amount) { return contains(mx, my) && app.scrolled(mx, my, amount); }
}