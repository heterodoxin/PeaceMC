package dev.peace.mod.ui.apps;

import com.google.gson.JsonObject;
import dev.peace.mod.Net;
import dev.peace.mod.ui.App;
import dev.peace.mod.ui.Theme;
import dev.peace.mod.ui.Widgets;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.ArrayList;
import java.util.List;

/** Real-time console: subscribe to server output and run commands as the console. */
public final class ConsoleApp extends App {
    private final Widgets.ScrollText out = new Widgets.ScrollText();
    private final Widgets.TextInput cmd = new Widgets.TextInput("/help");
    private final Widgets.Button run = new Widgets.Button("Run", this::run);
    private final Widgets.Button sub = new Widgets.Button("Subscribe", this::toggle);
    private boolean subscribed = false;
    private final List<String> history = new ArrayList<>();
    private int histIdx = 0;

    public ConsoleApp() {
        super("Console", ">>");
        prefW = 460; prefH = 320;
        cmd.onEnter = this::run;
        widgets.add(out); widgets.add(cmd); widgets.add(run); widgets.add(sub);
        out.add("Peace Console - subscribe to see server output");
        Net.setConsoleSink(out::add);
    }

    @Override
    public void render(GuiGraphicsExtractor g, int cx, int cy, int cw, int ch, int mx, int my) {
        int y = cy;
        out.set(cx, y, cw, ch - 44);
        out.render(g, mx, my);
        y += ch - 44;

        int bw = (cw - 6) / 2;
        cmd.set(cx, y, bw, 16);
        run.set(cx + bw + 6, y, cw - bw - 6, 16);
        cmd.render(g, mx, my); run.render(g, mx, my);
        y += 22;

        sub.label = subscribed ? "Unsubscribe" : "Subscribe";
        sub.primary = subscribed;
        sub.set(cx, y, 80, 16); sub.render(g, mx, my);
    }

    private void run() {
        String line = cmd.value().trim();
        if (line.isEmpty()) return;
        out.add("> " + line);
        history.add(line); histIdx = history.size();
        cmd.text.setLength(0);

        JsonObject req = Net.op("console.run");
        req.addProperty("cmd", line);
        Net.send(req, j -> {
            if (ok(j)) {
                String o = j.has("out") ? j.get("out").getAsString() : "";
                if (!o.isEmpty()) out.add(o);
            } else {
                out.add("error: " + j.get("error").getAsString());
            }
        });
    }

    private void toggle() {
        if (subscribed) {
            JsonObject req = Net.op("console.unsubscribe");
            Net.send(req, j -> { subscribed = false; out.add("unsubscribed"); });
        } else {
            JsonObject req = Net.op("console.subscribe");
            Net.send(req, j -> { subscribed = true; out.add("subscribed to console output"); });
        }
    }

    @Override
    public boolean keyPressed(int key, int sc, int mod) {
        if (key == 265) { // up arrow
            if (!history.isEmpty()) {
                histIdx = Math.max(0, histIdx - 1);
                if (histIdx < history.size()) cmd.text.setLength(0);
                cmd.text.append(history.get(histIdx));
            }
            return true;
        }
        if (key == 264) { // down arrow
            if (!history.isEmpty()) {
                histIdx = Math.min(history.size(), histIdx + 1);
                if (histIdx < history.size()) { cmd.text.setLength(0); cmd.text.append(history.get(histIdx)); }
            }
            return true;
        }
        return super.keyPressed(key, sc, mod);
    }
}