package dev.peace.mod.ui.apps;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.peace.mod.Net;
import dev.peace.mod.ui.App;
import dev.peace.mod.ui.Theme;
import dev.peace.mod.ui.Widgets;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/** Remote file manager whose paths resolve server-side: browse, edit, copy, delete, send to Discord. */
public final class FilesApp extends App {
    private record Node(String name, boolean dir, long size, String path) {}
    private enum Mode { BROWSE, VIEW, NEW_DIR, COPY, RENAME }
    private static final int ROW = 13;
    private static final String[] MENU = { "Edit", "Rename...", "Copy...", "Delete", "Send to Discord" };
    private static final int MW = 116, MH = 12, MPAD = 4;

    private final Widgets.TextInput pathIn = new Widgets.TextInput("path");
    private final Widgets.Button up = new Widgets.Button("Up", this::goUp);
    private final Widgets.Button home = new Widgets.Button("~", () -> navigate("~"));
    private final Widgets.Button go = new Widgets.Button("Go", this::goAction);
    private final Widgets.Button back = new Widgets.Button("< Files", () -> mode = Mode.BROWSE);
    private final Widgets.Button refresh = new Widgets.Button("Ref", this::reload);
    private final Widgets.Button newDir = new Widgets.Button("New", this::startNewDir);
    private final Widgets.Button del = new Widgets.Button("Del", this::deleteSelected);
    private final Widgets.Button save = new Widgets.Button("Save", this::saveOpen);
    private final Widgets.TextArea content = new Widgets.TextArea();

    private final List<Node> nodes = new ArrayList<>();
    private String cwd = "", parent = "", currentFile = "", copySrc = "", status = "";
    private boolean currentReadOnly;
    private int statusColor = Theme.MUTED;
    private Mode mode = Mode.BROWSE;
    private int rowScroll, listX, listY, listW, listH, selected = -1;
    private boolean ctxOpen;
    private int ctxX, ctxY, ctxIndex = -1, ctxHover = -1;

    public FilesApp() {
        super("Files", "DIR");
        prefW = 480; prefH = 340;
        go.primary = true;
        pathIn.onEnter = this::goAction;
        widgets.add(pathIn);
        navigate("~"); // start in the server user's home, not root
    }

    @Override
    public void render(GuiGraphicsExtractor g, int cx, int cy, int cw, int ch, int mx, int my) {
        if (mode == Mode.VIEW) { renderView(g, cx, cy, cw, ch, mx, my); return; }

        // row 1: navigation
        up.set(cx, cy, 26, 16);
        home.set(cx + 30, cy, 20, 16);
        pathIn.placeholder = (mode == Mode.NEW_DIR) ? "folder name" : (mode == Mode.RENAME) ? "new name" : (mode == Mode.COPY) ? "destination path" : "path";
        go.label = (mode == Mode.NEW_DIR) ? "Create" : (mode == Mode.RENAME) ? "Rename" : (mode == Mode.COPY) ? "Copy" : "Go";
        pathIn.set(cx + 54, cy, cw - 54 - 30, 16);
        go.set(cx + cw - 26, cy, 26, 16);
        up.render(g, mx, my); home.render(g, mx, my); pathIn.render(g, mx, my); go.render(g, mx, my);

        // row 2: actions
        int y2 = cy + 20;
        int bw = 30;
        refresh.set(cx, y2, bw, 14);
        newDir.set(cx + bw + 4, y2, bw, 14);
        del.set(cx + 2 * (bw + 4), y2, bw, 14);
        refresh.render(g, mx, my); newDir.render(g, mx, my); del.render(g, mx, my);
        if (mode == Mode.NEW_DIR) {
            Theme.text(g, "type name, press Create", cx + 3 * (bw + 4) + 8, y2 + 3, Theme.MUTED);
        } else if (selected >= 0 && selected < nodes.size()) {
            Node n = nodes.get(selected);
            Theme.text(g, Theme.trim(n.name, cw - 3 * (bw + 4) - 8), cx + 3 * (bw + 4) + 8, y2 + 3, Theme.ACCENT);
        } else {
            Theme.text(g, "click to select", cx + 3 * (bw + 4) + 8, y2 + 3, Theme.MUTED);
        }

        // list
        listX = cx; listY = y2 + 18; listW = cw; listH = ch - 18 - 20 - 11;
        Theme.rounded(g, listX, listY, listW, listH, 3, Theme.FIELD_IN, Theme.CARD);
        Theme.scissorOn(g, listX + 2, listY + 2, listX + listW - 2, listY + listH - 2);
        int y = listY + 2 - rowScroll;
        for (int i = 0; i < rowCount(); i++) {
            if (y + ROW >= listY && y <= listY + listH) drawRow(g, i, y, mx, my);
            y += ROW;
        }
        Theme.scissorOff(g);
        if (ctxOpen) renderMenu(g, mx, my);
        Theme.text(g, Theme.trim(status, cw), cx, cy + ch - 9, statusColor);
    }

    private void renderMenu(GuiGraphicsExtractor g, int mx, int my) {
        int w = MW, h = MENU.length * MH + MPAD * 2;
        int x = Math.min(ctxX, listX + listW - w - 2);
        int y = Math.max(listY, Math.min(ctxY, listY + listH - h - 2));
        Theme.rounded(g, x, y, w, h, 3, 0xFF1b1d24, Theme.BG);
        Theme.fill(g, x, y, x + w, y + 1, 0x1dffffff);
        ctxHover = -1;
        for (int i = 0; i < MENU.length; i++) {
            int iy = y + MPAD + i * MH;
            if (mx >= x && mx < x + w && my >= iy && my < iy + MH) {
                if (i == 3 || i == 4) Theme.fill(g, x + 1, iy, x + w - 1, iy + MH, 0xffff3333);
                else Theme.fill(g, x + 1, iy, x + w - 1, iy + MH, 0x12ffffff);
                ctxHover = i;
            }
            Theme.text(g, MENU[i], x + 8, iy + 2, (i == 3 || i == 4) ? 0xFFFF5c5c : Theme.TEXT);
        }
    }

    private boolean ctxHit(double mx, double my) {
        if (!ctxOpen) return false;
        int w = MW, h = MENU.length * MH + MPAD * 2;
        int x = Math.min(ctxX, listX + listW - w - 2);
        int y = Math.max(listY, Math.min(ctxY, listY + listH - h - 2));
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    private int rowCount() { return nodes.size() + 1; }

    private void drawRow(GuiGraphicsExtractor g, int i, int y, int mx, int my) {
        boolean hover = mx >= listX && mx < listX + listW && my >= y && my < y + ROW && my >= listY && my < listY + listH;
        int idx = i - 1;
        boolean sel = idx == selected;
        if (hover || sel) Theme.fill(g, listX + 1, y, listX + listW - 1, y + ROW, sel ? 0x15ffffff : Theme.HOVER);
        if (i == 0) { fileIcon(g, listX + 4, y + 2, true); Theme.text(g, "..", listX + 16, y + 3, Theme.ACCENT); return; }
        Node n = nodes.get(idx);
        fileIcon(g, listX + 4, y + 2, n.dir);
        Theme.text(g, Theme.trim(n.name, listW - 90), listX + 16, y + 3, n.dir ? Theme.TEXT : Theme.MUTED);
        if (!n.dir) { String s = human(n.size); Theme.text(g, s, listX + listW - Theme.width(s) - 6, y + 3, Theme.MUTED); }
    }

    /** Angular icon glyphs - no circles. */
    private void fileIcon(GuiGraphicsExtractor g, int x, int y, boolean dir) {
        if (dir) {
            Theme.fill(g, x, y + 1, x + 3, y + 3, Theme.ACCENT);   // tab
            Theme.fill(g, x, y + 3, x + 9, y + 9, Theme.ACCENT);   // body
        } else {
            Theme.fill(g, x + 1, y, x + 7, y + 9, 0xFF3a3c44);     // body
            Theme.fill(g, x + 1, y, x + 7, y + 1, 0xFF50525a);     // top edge
        }
    }

    private void renderView(GuiGraphicsExtractor g, int cx, int cy, int cw, int ch, int mx, int my) {
        back.set(cx, cy, 50, 16);
        del.set(cx + cw - 100, cy, 46, 16);
        save.set(cx + cw - 50, cy, 46, 16);
        back.render(g, mx, my); del.render(g, mx, my); save.render(g, mx, my);
        Theme.text(g, Theme.trim(currentFile, cw - 160), cx + 54, cy + 4, Theme.MUTED);
        content.set(cx, cy + 22, cw, ch - 22);
        content.render(g, mx, my);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int btn) {
        if (mode == Mode.VIEW) {
            if (back.mouseClicked(mx, my, btn)) return true;
            if (del.mouseClicked(mx, my, btn)) return true;
            if (save.mouseClicked(mx, my, btn)) return true;
            if (content.mouseClicked(mx, my, btn)) return true;
            return false;
        }
        if (ctxOpen) {
            if (btn == 0) {
                if (ctxHit(mx, my)) { int i = ctxHover; ctxOpen = false; runCtx(i); return true; }
                ctxOpen = false; // outside the menu = close and fall through
            } else {
                ctxOpen = false;
            }
        }
        if (btn == 1 && mx >= listX && mx < listX + listW && my >= listY && my < listY + listH) {
            int idx = (int) ((my - listY - 2 + rowScroll) / ROW) - 1;
            if (idx >= 0 && idx < nodes.size()) {
                selected = idx; ctxIndex = idx; ctxX = (int) mx; ctxY = (int) my; ctxOpen = true;
                return true;
            }
            return false;
        }
        if (up.mouseClicked(mx, my, btn) || home.mouseClicked(mx, my, btn) || go.mouseClicked(mx, my, btn)) return true;
        if (refresh.mouseClicked(mx, my, btn) || newDir.mouseClicked(mx, my, btn) || del.mouseClicked(mx, my, btn)) return true;
        if (pathIn.mouseClicked(mx, my, btn)) return true;
        if (mx >= listX && mx < listX + listW && my >= listY && my < listY + listH) {
            int idx = (int) ((my - listY - 2 + rowScroll) / ROW);
            if (idx == 0 && btn == 0) { goUp(); return true; }
            idx -= 1;
            if (idx >= 0 && idx < nodes.size()) {
                Node n = nodes.get(idx);
                if (btn == 0) {
                    if (n.dir) navigate(n.path); else openFile(n.path);
                } else {
                    selected = idx;
                }
            }
            return true;
        }
        selected = -1;
        return false;
    }

    @Override
    public boolean scrolled(double mx, double my, double amount) {
        if (mode == Mode.VIEW) return content.scrolled(mx, my, amount);
        if (mx >= listX && mx < listX + listW && my >= listY && my < listY + listH) {
            int max = Math.max(0, rowCount() * ROW - listH + 4);
            rowScroll = (int) Math.max(0, Math.min(max, rowScroll - amount * ROW * 2));
            return true;
        }
        return false;
    }

    private void goAction() {
        if (mode == Mode.NEW_DIR) {
            String name = pathIn.value().trim();
            if (name.isEmpty()) { status = "enter a name"; statusColor = Theme.BAD; return; }
            JsonObject req = Net.op("fs.mkdir");
            req.addProperty("path", cwd + "/" + name);
            Net.send(req, j -> { status = replyText(j); statusColor = replyColor(j); mode = Mode.BROWSE; navigate(cwd); });
        } else if (mode == Mode.COPY) {
            String dest = pathIn.value().trim();
            if (dest.isEmpty()) { status = "enter a destination path"; statusColor = Theme.BAD; return; }
            JsonObject req = Net.op("fs.copy");
            req.addProperty("from", copySrc);
            req.addProperty("to", dest);
            Net.send(req, j -> { status = replyText(j); statusColor = replyColor(j); mode = Mode.BROWSE; navigate(cwd); });
        } else if (mode == Mode.RENAME) {
            String name = pathIn.value().trim();
            if (name.isEmpty() || ctxIndex < 0 || ctxIndex >= nodes.size()) { status = "enter a new name"; statusColor = Theme.BAD; return; }
            Node n = nodes.get(ctxIndex);
            String to = n.path.substring(0, n.path.lastIndexOf('/') + 1) + name;
            JsonObject req = Net.op("fs.rename");
            req.addProperty("from", n.path);
            req.addProperty("to", to);
            Net.send(req, j -> { status = replyText(j); statusColor = replyColor(j); mode = Mode.BROWSE; navigate(cwd); });
        } else {
            navigate(pathIn.value().trim());
        }
    }

    private void startNewDir() { mode = Mode.NEW_DIR; pathIn.text.setLength(0); pathIn.focused = true; }

    private void runCtx(int item) {
        if (item < 0 || ctxIndex < 0 || ctxIndex >= nodes.size()) return;
        Node n = nodes.get(ctxIndex);
        switch (item) {
            case 0 -> { if (n.dir) { status = n.name + " is a folder"; statusColor = Theme.BAD; } else openFile(n.path); }
            case 1 -> {
                mode = Mode.RENAME;
                pathIn.text.setLength(0); pathIn.text.append(n.name);
                pathIn.focused = true;
            }
            case 2 -> {
                copySrc = n.path;
                mode = Mode.COPY;
                pathIn.text.setLength(0); pathIn.text.append(n.path).append(".copy");
                pathIn.focused = true;
            }
            case 3 -> deletePath(n.path);
            case 4 -> sendToDiscord(n.path);
            default -> { }
        }
    }

    private void sendToDiscord(String path) {
        status = "sending to discord..."; statusColor = Theme.MUTED;
        JsonObject req = Net.op("discord.send");
        req.addProperty("path", path);
        Net.send(req, j -> { status = replyText(j); statusColor = replyColor(j); });
    }

    private void deletePath(String path) {
        JsonObject req = Net.op("fs.delete"); req.addProperty("path", path);
        Net.send(req, j -> { status = replyText(j); statusColor = replyColor(j); selected = -1; navigate(cwd); });
    }

    private void deleteSelected() {
        if (mode == Mode.VIEW) { deletePath(currentFile); return; }
        if (selected < 0 || selected >= nodes.size()) { status = "select an entry"; statusColor = Theme.BAD; return; }
        deletePath(nodes.get(selected).path);
    }

    private void navigate(String path) {
        status = "opening..."; statusColor = Theme.MUTED;
        JsonObject req = Net.op("fs.list"); req.addProperty("path", path.isEmpty() ? "~" : path);
        Net.send(req, j -> {
            if (!ok(j)) { status = "error: " + j.get("error").getAsString(); statusColor = Theme.BAD; return; }
            cwd = j.get("path").getAsString();
            parent = j.get("parent").getAsString();
            pathIn.text.setLength(0); pathIn.text.append(cwd);
            nodes.clear(); selected = -1;
            JsonArray arr = j.getAsJsonArray("entries");
            for (var e : arr) {
                JsonObject o = e.getAsJsonObject();
                nodes.add(new Node(o.get("name").getAsString(), o.get("dir").getAsBoolean(), o.get("size").getAsLong(), o.get("path").getAsString()));
            }
            nodes.sort((a, b) -> a.dir != b.dir ? (a.dir ? -1 : 1) : a.name.compareToIgnoreCase(b.name));
            rowScroll = 0; mode = Mode.BROWSE;
            status = cwd + "   (" + nodes.size() + ")"; statusColor = Theme.MUTED;
        });
    }

    private void goUp() { navigate(parent); }

    private void reload() { if (!cwd.isEmpty()) navigate(cwd); }

    private void openFile(String path) {
        status = "reading..."; statusColor = Theme.MUTED;
        JsonObject req = Net.op("fs.read"); req.addProperty("path", path);
        Net.send(req, j -> {
            if (!ok(j)) { status = "error: " + j.get("error").getAsString(); statusColor = Theme.BAD; return; }
            byte[] b = Base64.getDecoder().decode(j.get("data").getAsString());
            boolean binary = isBinary(b);
            content.clear();
            content.setText(binary ? "[binary file, " + b.length + " bytes]" : new String(b, StandardCharsets.UTF_8));
            content.focused = !binary;
            currentReadOnly = binary;
            currentFile = path; mode = Mode.VIEW;
        });
    }

    private void saveOpen() {
        if (currentReadOnly) { status = "binary files are read-only"; statusColor = Theme.BAD; return; }
        String path = currentFile;
        status = "saving..."; statusColor = Theme.MUTED;
        JsonObject req = Net.op("fs.write");
        req.addProperty("path", path);
        req.addProperty("data", Base64.getEncoder().encodeToString(content.value().getBytes(StandardCharsets.UTF_8)));
        Net.send(req, j -> { status = replyText(j); statusColor = replyColor(j); });
    }

    @Override
    public boolean keyPressed(int key, int sc, int mod) {
        if (mode == Mode.VIEW) return content.keyPressed(key, sc, mod);
        return super.keyPressed(key, sc, mod);
    }

    @Override
    public boolean charTyped(char ch) {
        if (mode == Mode.VIEW) return content.charTyped(ch);
        return super.charTyped(ch);
    }

    private static boolean isBinary(byte[] b) { int n = Math.min(b.length, 2048); for (int i = 0; i < n; i++) if (b[i] == 0) return true; return false; }
    private static String human(long n) {
        if (n < 1024) return n + " B";
        if (n < 1024 * 1024) return (n / 1024) + " K";
        return String.format("%.1f M", n / 1048576.0);
    }
}