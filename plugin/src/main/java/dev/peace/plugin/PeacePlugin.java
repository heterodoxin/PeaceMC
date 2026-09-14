package dev.peace.plugin;

import org.bukkit.plugin.java.JavaPlugin;

/** Thin registered shell so PeaceService can also run as a standalone plugin. */
public final class PeacePlugin extends JavaPlugin {
    private PeaceService svc;
    @Override public void onEnable() {
        saveDefaultConfig();
        if (getConfig().getBoolean("debug.web.enabled", false)) {
            try { DebugConsole.start(this); }
            catch (Exception e) { getLogger().warning("[peace:debug] web console failed to start: " + e); }
        }
        svc = new PeaceService(this); svc.start();
    }
    @Override public void onDisable() {
        if (svc != null) svc.stop();
        DebugConsole.stop();
    }
}