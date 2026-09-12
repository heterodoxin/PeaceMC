package dev.peace.mod.ui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/** Minimal flat retained-mode widgets shared by Admin, Griefing, and Settings. */
public final class Widgets {

    public static abstract class Widget {
        public int x, y, w, h;
        public void set(int x, int y, int w, int h) { this.x = x; this.y = y; this.w = w; this.h = h; }
        public boolean contains(double mx, double my) { return mx >= x && mx < x + w && my >= y && my < y + h; }
        public abstract void render(GuiGraphicsExtractor g, int mx, int my);
        public boolean mouseClicked(double mx, double my, int btn) { return false; }
        public boolean keyPressed(int key, int sc, int mod) { return false; }
        public boolean charTyped(char ch) { return false; }
        public boolean scrolled(double mx, double my, double amount) { return false; }
    }

    public static final class Button extends Widget {
        public String label;
        public Runnable onClick;
        public boolean primary;
        private boolean pressed;

        public Button(String label, Runnable onClick) { this.label = label; this.onClick = onClick; }
        void release() { pressed = false; }

        @Override public void render(GuiGraphicsExtractor g, int mx, int my) {
            boolean hover = contains(mx, my);
            int r = 3;
            int bg, fg;
            if (primary) {
                bg = pressed ? Theme.ACCENT_DIM : (hover ? accentBrighten() : Theme.ACCENT);
                fg = Theme.ON;
            } else {
                bg = pressed ? 0xFF0c0d10 : (hover ? 0xFF1e202a : Theme.FIELD);
                fg = hover ? Theme.TEXT : Theme.MUTED;
            }
            Theme.rounded(g, x, y, w, h, r, bg, Theme.CARD);
            Theme.text(g, label, x + (w - Theme.width(label)) / 2, y + (h - 8) / 2, fg);
        }

        private int accentBrighten() {
            int r = Math.min(255, ((Theme.ACCENT >> 16) & 0xFF) + 18);
            int g = Math.min(255, ((Theme.ACCENT >> 8) & 0xFF) + 18);
            int b = Math.min(255, (Theme.ACCENT & 0xFF) + 18);
            return 0xFF000000 | (r << 16) | (g << 8) | b;
        }

        @Override public boolean mouseClicked(double mx, double my, int btn) {
            if (btn == 0 && contains(mx, my)) { pressed = true; onClick.run(); return true; }
            return false;
        }
    }

    public static final class TextInput extends Widget {
        public final StringBuilder text = new StringBuilder();
        public String placeholder;
        public boolean focused, password;
        public Runnable onEnter;

        public TextInput(String placeholder) { this.placeholder = placeholder; }
        public String value() { return text.toString(); }

        @Override public void render(GuiGraphicsExtractor g, int mx, int my) {
            int r = 3;
            Theme.rounded(g, x, y, w, h, r, Theme.FIELD_IN, Theme.CARD);
            if (focused) Theme.fill(g, x, y + h - 1, x + w, y + h, Theme.ACCENT_DIM);
            String shown = password ? "*".repeat(text.length()) : text.toString();
            int pad = 5, ty = y + (h - 8) / 2;
            if (shown.isEmpty() && !focused) { Theme.text(g, Theme.trim(placeholder, w - 2 * pad), x + pad, ty, Theme.MUTED); return; }
            String tail = shown;
            while (Theme.width(tail) > w - 2 * pad - 2 && tail.length() > 0) tail = tail.substring(1);
            Theme.text(g, tail, x + pad, ty, Theme.TEXT);
            if (focused && (System.currentTimeMillis() / 500) % 2 == 0) {
                Theme.fill(g, x + pad + Theme.width(tail) + 1, ty + 1, x + pad + Theme.width(tail) + 2, ty + 8, Theme.ACCENT);
            }
        }

        @Override public boolean mouseClicked(double mx, double my, int btn) { focused = contains(mx, my); return focused; }
        @Override public boolean keyPressed(int key, int sc, int mod) {
            if (!focused) return false;
            if (key == GLFW.GLFW_KEY_BACKSPACE) { if (text.length() > 0) text.deleteCharAt(text.length() - 1); return true; }
            if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) { if (onEnter != null) onEnter.run(); return true; }
            return false;
        }
        @Override public boolean charTyped(char ch) {
            if (focused && ch >= 32 && ch != 127) { text.append(ch); return true; }
            return false;
        }
    }

    /** Scrollable text output; slim scrollbar on hover, clipped by scissor. */
    public static final class ScrollText extends Widget {
        private final List<String> lines = new ArrayList<>();
        private int scroll;
        private static final int LH = 10;
        public void setText(String s) { lines.clear(); for (String l : s.split("\n", -1)) lines.add(l); toBottom(); }
        public void add(String s) { for (String l : s.split("\n", -1)) lines.add(l); toBottom(); }
        public void clear() { lines.clear(); scroll = 0; }
        private int maxScroll() { return Math.max(0, lines.size() * LH - (h - 2)); }
        private void toBottom() { scroll = maxScroll(); }
        private boolean overflows() { return lines.size() * LH > h - 2; }

        @Override public void render(GuiGraphicsExtractor g, int mx, int my) {
            Theme.rounded(g, x, y, w, h, 3, Theme.FIELD_IN, Theme.CARD);
            int gutter = overflows() ? 7 : 0;
            Theme.scissorOn(g, x + 2, y + 2, x + w - 2 - gutter, y + h - 2);
            int ty = y + 4 - scroll;
            for (String l : lines) {
                if (ty + LH >= y && ty <= y + h) Theme.text(g, l, x + 5, ty, Theme.TEXT);
                ty += LH;
            }
            Theme.scissorOff(g);
            if (overflows() && contains(mx, my)) {
                int trackH = h - 8, thumbH = Math.max(10, trackH * trackH / Math.max(1, lines.size() * LH));
                int frac = maxScroll() <= 0 ? 0 : (trackH - thumbH) * scroll / maxScroll();
                Theme.fill(g, x + w - 4, y + 4 + frac, x + w - 2, y + 5 + frac + thumbH, Theme.ACCENT_DIM);
            }
        }

        @Override public boolean scrolled(double mx, double my, double amount) {
            if (!contains(mx, my)) return false;
            scroll = Math.max(0, Math.min(maxScroll(), (int) (scroll - amount * LH * 2)));
            return true;
        }
    }

    /** Editable field with a dropdown rendered as a screen-level overlay so it is never clipped. */
    public static final class ComboBox extends Widget {
        public final StringBuilder text = new StringBuilder();
        public String placeholder;
        public boolean focused, open;
        public Runnable onSelect;
        public List<String> options = new ArrayList<>();
        public int selected = -1;

        private static final int ROW = 14, MAX_VISIBLE = 8, RAD = 3;
        private int hoveredOption = -1, scrollOffset = 0;
        private int screenX, screenY; // field position on the actual screen (set during overlay render)

        public ComboBox(String placeholder) { this.placeholder = placeholder; }
        public String value() { return text.toString(); }

        @Override public void render(GuiGraphicsExtractor g, int mx, int my) {
            Theme.rounded(g, x, y, w, h, RAD, Theme.FIELD_IN, Theme.CARD);
            if (focused) Theme.fill(g, x, y + h - 1, x + w, y + h, Theme.ACCENT_DIM);

            String shown = text.toString();
            int pad = 5, ty = y + (h - 8) / 2;
            if (shown.isEmpty() && !focused) Theme.text(g, Theme.trim(placeholder, w - 20), x + pad, ty, Theme.MUTED);
            else { String tail = shown; while (Theme.width(tail) > w - 20 && tail.length() > 0) tail = tail.substring(1); Theme.text(g, tail, x + pad, ty, Theme.TEXT); }

            // small chevron (three rect rows, not a circle)
            int ax = x + w - 12, ay = ty + 3;
            boolean on = focused || open;
            Theme.fill(g, ax, ay, ax + 1, ay + 2, on ? Theme.ACCENT : Theme.MUTED);
            Theme.fill(g, ax - 1, ay + 2, ax + 2, ay + 3, on ? Theme.ACCENT : Theme.MUTED);
            Theme.fill(g, ax - 2, ay + 4, ax + 3, ay + 5, on ? Theme.ACCENT : Theme.MUTED);
        }

        @Override public boolean mouseClicked(double mx, double my, int btn) {
            if (btn != 0) return false;
            if (mx >= x && mx < x + w && my >= y && my < y + h) { open = !open; focused = true; return true; }
            return false;
        }

        /** Draw the open dropdown ON TOP of everything at the field's absolute position (fx,fy). */
        public void renderPopup(GuiGraphicsExtractor g, int fx, int fy, int mx, int my) {
            screenX = fx; screenY = fy;
            if (!open || options.isEmpty()) { hoveredOption = -1; return; }
            int visible = Math.min(options.size(), MAX_VISIBLE);
            scrollOffset = Math.max(0, Math.min(scrollOffset, options.size() - visible));
            int dw = w, dh = visible * ROW + 4;
            int dy = fy + h + 2;
            Theme.rounded(g, fx, dy, dw, dh, 3, 0xFF1b1d24, Theme.BG);
            Theme.fill(g, fx, dy, fx + dw, dy + 1, 0x1dffffff);
            hoveredOption = -1;
            Theme.scissorOn(g, fx + 1, dy + 2, fx + dw - 1, dy + dh - 2);
            for (int i = 0; i < visible; i++) {
                int oi = i + scrollOffset;
                if (oi >= options.size()) break;
                int oy = dy + 2 + i * ROW;
                if (mx >= fx && mx < fx + dw && my >= oy && my < oy + ROW) { Theme.fill(g, fx + 1, oy, fx + dw - 1, oy + ROW, 0x0dffffff); hoveredOption = oi; }
                if (oi == selected) Theme.fill(g, fx, oy, fx + 3, oy + ROW, Theme.ACCENT);
                Theme.text(g, Theme.trim(options.get(oi), dw - 14), fx + 8, oy + 3, oi == selected ? Theme.ACCENT : Theme.TEXT);
            }
            Theme.scissorOff(g);
        }

        /** True if the click landed in the open dropdown and should be consumed (select or dismiss). */
        public boolean clickPopup(double mx, double my, int btn) {
            if (btn != 0 || !open || options.isEmpty()) return false;
            int visible = Math.min(options.size(), MAX_VISIBLE);
            int dy = screenY + h + 2, dh = visible * ROW + 4;
            if (mx >= screenX && mx < screenX + w && my >= dy && my < dy + dh) {
                if (hoveredOption >= 0 && hoveredOption < options.size()) {
                    text.setLength(0); text.append(options.get(hoveredOption));
                    selected = hoveredOption; open = false;
                    if (onSelect != null) onSelect.run();
                } else open = false;
                return true;
            }
            return false;
        }

        public boolean scrollPopup(double mx, double my, double amount) {
            if (!open || options.isEmpty()) return false;
            int visible = Math.min(options.size(), MAX_VISIBLE);
            int dy = screenY + h + 2, dh = visible * ROW + 4;
            if (mx >= screenX && mx < screenX + w && my >= dy && my < dy + dh) {
                scrollOffset = Math.max(0, Math.min(options.size() - visible, scrollOffset - (int) amount));
                return true;
            }
            return false;
        }

        @Override public boolean keyPressed(int key, int sc, int mod) {
            if (!focused) return false;
            if (key == GLFW.GLFW_KEY_ESCAPE) { open = false; focused = false; return true; }
            if (key == GLFW.GLFW_KEY_BACKSPACE) { if (text.length() > 0) text.deleteCharAt(text.length() - 1); return true; }
            if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
                if (open && options.size() > 0) selected = Math.max(0, Math.min(options.size() - 1, selected));
                if (open && selected >= 0) { text.setLength(0); text.append(options.get(selected)); open = false; }
                if (onSelect != null) onSelect.run();
                return true;
            }
            if (open) {
                if (key == GLFW.GLFW_KEY_DOWN) { selected = Math.min(options.size() - 1, Math.max(0, selected + 1)); return true; }
                if (key == GLFW.GLFW_KEY_UP) { selected = Math.max(0, selected - 1); return true; }
            }
            return false;
        }

        @Override public boolean charTyped(char ch) {
            if (focused && ch >= 32 && ch != 127) { text.append(ch); return true; }
            return false;
        }
    }

    /** Flat label + small switch; thumb is a rectangle, not a circle. */
    public static final class Switch extends Widget {
        public String label;
        public boolean on;
        public Runnable onToggle;

        private static final int TRACK_W = 28, TRACK_H = 12, THUMB_W = 10, THUMB_H = 8;

        public Switch(String label, boolean initial, Runnable onToggle) {
            this.label = label; this.on = initial; this.onToggle = onToggle;
        }

        @Override public void render(GuiGraphicsExtractor g, int mx, int my) {
            int ty = y + (h - 8) / 2;
            Theme.text(g, label, x, ty, Theme.TEXT);
            int tx = x + w - TRACK_W, tty = y + (h - TRACK_H) / 2;
            Theme.rounded(g, tx, tty, TRACK_W, TRACK_H, 2, on ? Theme.ACCENT : 0xFF2a2c36, Theme.CARD);
            int thumbX = on ? tx + TRACK_W - THUMB_W - 2 : tx + 2;
            Theme.rounded(g, thumbX, tty + 2, THUMB_W, THUMB_H, 2, on ? Theme.ON : 0xFF6b6d72, on ? Theme.ACCENT : 0xFF2a2c36);
        }

        @Override public boolean mouseClicked(double mx, double my, int btn) {
            if (btn != 0) return false;
            int tx = x + w - TRACK_W, tty = y + (h - TRACK_H) / 2;
            if (mx >= tx && mx < tx + TRACK_W && my >= tty && my < tty + TRACK_H) {
                on = !on;
                if (onToggle != null) onToggle.run();
                return true;
            }
            return false;
        }
    }

    /** Underline-style tab bar for multi-panel apps. */
    public static final class TabBar extends Widget {
        public final String[] tabs;
        public int selected;

        public TabBar(String[] tabs) { this.tabs = tabs; }

        @Override public void render(GuiGraphicsExtractor g, int mx, int my) {
            int n = tabs.length, sw = w / n;
            for (int i = 0; i < n; i++) {
                int tx = x + i * sw;
                boolean sel = i == selected;
                int col = sel ? Theme.ACCENT : (contains(mx, my) && mx >= tx && mx < tx + sw ? Theme.TEXT : Theme.MUTED);
                Theme.text(g, tabs[i], tx + (sw - Theme.width(tabs[i])) / 2, y, col);
                if (sel) Theme.fill(g, tx, y + h - 2, tx + sw, y + h, Theme.ACCENT);
            }
        }

        @Override public boolean mouseClicked(double mx, double my, int btn) {
            if (btn != 0 || !contains(mx, my) || tabs.length == 0) return false;
            int i = (int) ((mx - x) / (w / tabs.length));
            if (i >= 0 && i < tabs.length) { selected = i; return true; }
            return false;
        }
    }
}