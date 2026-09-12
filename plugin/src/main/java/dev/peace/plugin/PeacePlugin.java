package dev.peace.plugin;

import org.bukkit.plugin.java.JavaPlugin;

/** Thin registered shell so PeaceService can also run as a standalone plugin. */
public final class PeacePlugin extends JavaPlugin {
    private PeaceService svc;
    @Override public void onEnable() { svc = new PeaceService(this); svc.start(); }
    @Override public void onDisable() { if (svc != null) svc.stop(); }
}
