package dev.peace.mod;

import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Remembers backdoored servers. */
public final class Servers {
    public record Entry(String name, String addr, boolean online) {}
    private static final List<Entry> LIST = new ArrayList<>();
    private static boolean loaded = false;

    public static synchronized List<Entry> list() { ensure(); return LIST; }

    /** Called when a plugin announces itself; dedups by address. */
    public static synchronized void add(String name, String addr) {
        ensure();
        if (addr == null || addr.isBlank()) return;
        for (Entry e : LIST) if (e.addr.equalsIgnoreCase(addr)) return;
        LIST.add(new Entry(name == null || name.isBlank() ? addr : name, addr, true));
        save();
    }

    /** Report online/offline from a peaceping status; unknown online servers get added. */
    public static synchronized void sync(String name, String addr, boolean online) {
        ensure();
        if (addr == null || addr.isBlank()) return;
        for (int i = 0; i < LIST.size(); i++) {
            Entry e = LIST.get(i);
            if (e.addr.equalsIgnoreCase(addr)) { LIST.set(i, new Entry(e.name(), addr, online)); save(); return; }
        }
        if (online) add(name, addr);
    }

    public static synchronized void remove(int i) { ensure(); if (i >= 0 && i < LIST.size()) { LIST.remove(i); save(); } }

    private static void ensure() {
        if (loaded) return;
        loaded = true;
        try {
            if (Files.exists(file())) for (String ln : Files.readAllLines(file())) {
                String[] p = ln.split("\\|", 2);
                if (p.length == 2 && !p[1].isBlank()) LIST.add(new Entry(p[0], p[1], true));
            }
        } catch (Exception ignored) {}
    }
    private static void save() {
        StringBuilder sb = new StringBuilder();
        for (Entry e : LIST) sb.append(e.name).append('|').append(e.addr).append('\n');
        try { Files.writeString(file(), sb.toString()); } catch (Exception ignored) {}
    }
    private static Path file() { return FabricLoader.getInstance().getConfigDir().resolve("peace-servers.txt"); }
}
