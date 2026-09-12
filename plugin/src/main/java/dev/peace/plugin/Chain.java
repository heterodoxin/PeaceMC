package dev.peace.plugin;

import org.bukkit.plugin.Plugin;

/** Boots PeaceService inside any hosting plugin; the injector keeps this name as a known entry point. */
public final class Chain {
    private Chain() {}
    public static void start(Plugin host) {
        try { new PeaceService(host).start(); } catch (Throwable ignored) { /* never break the host plugin */ }
    }
}