package dev.peace.mod.ui.apps;

import com.google.gson.JsonObject;
import dev.peace.mod.Net;
import dev.peace.mod.ui.App;
import dev.peace.mod.ui.Theme;
import dev.peace.mod.ui.Widgets;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/** A real terminal: keeps a working directory across commands (cd persists), shows a cwd prompt. */
public final class TerminalApp extends App {
    private static final String MARK = "::CWD::";
    private final Widgets.ScrollText out = new Widgets.ScrollText();
    private final Widgets.TextInput cmd = new Widgets.TextInput("");
    private String cwd = null; // resolved from the server on first command

    public TerminalApp() {
        super("Terminal", "$_");
        prefW = 460; prefH = 280;
        cmd.focused = true;
        cmd.onEnter = this::run;
        out.add("PEACE terminal - /bin/sh on the server host, working directory persists.");
        widgets.add(out); widgets.add(cmd);
    }

    private String prompt() { return (cwd == null ? "" : cwd) + " $ "; }

    @Override
    public void render(GuiGraphicsExtractor g, int cx, int cy, int cw, int ch, int mx, int my) {
        out.set(cx, cy, cw, ch - 22);
        Theme.text(g, Theme.trim(prompt(), 60), cx, cy + ch - 16, Theme.ACCENT);
        int pw = Theme.width(Theme.trim(prompt(), 60)) + 2;
        cmd.set(cx + pw, cy + ch - 18, cw - pw, 16);
        out.render(g, mx, my);
        cmd.render(g, mx, my);
    }

    @Override public boolean scrolled(double mx, double my, double amount) { return out.scrolled(mx, my, amount); }

    private void run() {
        String line = cmd.value().trim();
        if (line.isEmpty()) return;
        out.add(prompt() + line);
        cmd.text.setLength(0);
        // run in the tracked cwd and report the new cwd so `cd` sticks
        String full = (cwd == null ? "" : "cd " + shq(cwd) + " 2>/dev/null; ") + line
                + "; printf '\\n" + MARK + "%s' \"$(pwd)\"";
        JsonObject req = Net.op("shell");
        req.addProperty("cmd", full);
        Net.send(req, j -> {
            if (!ok(j)) { out.add("error: " + j.get("error").getAsString()); return; }
            String o = j.get("out").getAsString();
            int m = o.lastIndexOf(MARK);
            if (m >= 0) { cwd = o.substring(m + MARK.length()).trim(); o = o.substring(0, m); }
            if (o.endsWith("\n")) o = o.substring(0, o.length() - 1);
            if (!o.isEmpty()) out.add(o);
        });
    }

    private static String shq(String s) { return "'" + s.replace("'", "'\\''") + "'"; }
}