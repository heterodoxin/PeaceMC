package dev.peace.mod.ui;

import com.google.gson.JsonObject;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.ArrayList;
import java.util.List;

/** One Peace "app". */
public abstract class App {
    public final String title;
    public final String icon;
    public int prefW = 320, prefH = 220;
    protected final List<Widgets.Widget> widgets = new ArrayList<>();

    protected App(String title, String icon) { this.title = title; this.icon = icon; }

    /** Lay out widgets into the content rect and draw everything. */
    public abstract void render(GuiGraphicsExtractor g, int cx, int cy, int cw, int ch, int mx, int my);

    public boolean mouseClicked(double mx, double my, int btn) {
        for (Widgets.Widget w : widgets) if (w.mouseClicked(mx, my, btn)) return true;
        return false;
    }
    public void mouseReleased() {
        for (Widgets.Widget w : widgets) if (w instanceof Widgets.Button b) b.release();
    }
    public boolean keyPressed(int key, int sc, int mod) {
        for (Widgets.Widget w : widgets) if (w.keyPressed(key, sc, mod)) return true;
        return false;
    }
    public boolean charTyped(char ch) {
        for (Widgets.Widget w : widgets) if (w.charTyped(ch)) return true;
        return false;
    }
    public boolean scrolled(double mx, double my, double amount) {
        for (Widgets.Widget w : widgets) if (w.scrolled(mx, my, amount)) return true;
        return false;
    }

    protected void focusOnly(Widgets.TextInput f) {
        for (Widgets.Widget w : widgets) if (w instanceof Widgets.TextInput t) t.focused = (t == f);
    }

    /** Draws screen-level popups that float over every window at absolute screen coords. */
    public void renderOverlay(GuiGraphicsExtractor g, int mx, int my) {
        for (Widgets.Widget w : widgets)
            if (w instanceof Widgets.ComboBox c) c.renderPopup(g, c.x, c.y, mx, my);
    }

    /** First open combo, or null. */
    public Widgets.ComboBox openCombo() {
        for (Widgets.Widget w : widgets) if (w instanceof Widgets.ComboBox c && c.open) return c;
        return null;
    }

    /** Combo whose field contains the point, or null. */
    public Widgets.ComboBox fieldCombo(double mx, double my) {
        for (Widgets.Widget w : widgets)
            if (w instanceof Widgets.ComboBox c && c.contains(mx, my)) return c;
        return null;
    }

    protected static boolean ok(JsonObject j) { return j.has("ok") && j.get("ok").getAsBoolean(); }
    protected static String replyText(JsonObject j) {
        return ok(j) ? (j.has("msg") ? j.get("msg").getAsString() : "ok") : "error: " + j.get("error").getAsString();
    }
    protected static int replyColor(JsonObject j) { return ok(j) ? Theme.OK : Theme.BAD; }
}
