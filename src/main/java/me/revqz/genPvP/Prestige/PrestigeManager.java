package me.revqz.genPvP.Prestige;

import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.UpdateOptions;
import me.revqz.genPvP.Database.DatabaseManager;
import me.revqz.genPvP.GenPvP;
import me.revqz.genPvP.util.ColorUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bson.Document;
import org.bukkit.Sound;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static com.mongodb.client.model.Filters.eq;

public class PrestigeManager implements Listener {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();

    private final GenPvP plugin;
    private final MongoDatabase db;
    private final boolean dbConnected;

    private final ConcurrentHashMap<UUID, Integer> prestigeCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Integer> levelCache    = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Double>  xpCache       = new ConcurrentHashMap<>();

    private final Set<UUID> prestigeNotified       = ConcurrentHashMap.newKeySet();
    private final Set<UUID> loadedPlayers           = ConcurrentHashMap.newKeySet();
    private final Set<UUID> prestigeBlacklist       = ConcurrentHashMap.newKeySet();
    private final Set<UUID> levelBlacklist          = ConcurrentHashMap.newKeySet();
    private final Set<UUID> xpBlacklist             = ConcurrentHashMap.newKeySet();

    private double xpPerLevel;
    private int    levelsPerPrestige;
    private String prestigeReadyMessage;

    private String msgPrestigeAdded, msgPrestigeRemoved, msgPrestigeReset;
    private String msgPrestigeBlacklisted, msgPrestigeUnblacklisted;
    private String msgLevelAdded, msgLevelRemoved, msgLevelReset;
    private String msgLevelBlacklisted, msgLevelUnblacklisted;
    private String msgBlacklistedFromPrestige, msgBlacklistedFromLeveling;
    private String msgXpAdded, msgXpRemoved, msgXpReset;
    private String msgXpBlacklisted, msgXpUnblacklisted, msgBlacklistedFromXp;

    public PrestigeManager(GenPvP plugin, DatabaseManager dbManager) {
        this.plugin      = plugin;
        this.db          = dbManager.isMongoConnected() ? dbManager.getDatabase() : null;
        this.dbConnected = dbManager.isMongoConnected();
        reloadConfig();
    }

    public void reloadConfig() {
        FileConfiguration cfg = plugin.getConfig();
        xpPerLevel          = cfg.getDouble("prestige.xp-per-level", 100.0);
        levelsPerPrestige   = cfg.getInt("prestige.levels-per-prestige", 10);
        prestigeReadyMessage = cfg.getString("prestige.messages.ready",
                "&a&lYou are ready to prestige! &e&l[CLICK HERE]");

        msgPrestigeAdded        = cfg.getString("prestige.messages.prestige-added",
                "&aAdded &e%amount% &aprestige level(s) to &e%player%&a. Now: &e%total%");
        msgPrestigeRemoved      = cfg.getString("prestige.messages.prestige-removed",
                "&aRemoved &e%amount% &aprestige level(s) from &e%player%&a. Now: &e%total%");
        msgPrestigeReset        = cfg.getString("prestige.messages.prestige-reset",
                "&aReset &e%player%&a's prestige to &e0&a.");
        msgPrestigeBlacklisted  = cfg.getString("prestige.messages.prestige-blacklisted",
                "&c%player% &7has been &cblacklisted &7from prestiging.");
        msgPrestigeUnblacklisted = cfg.getString("prestige.messages.prestige-unblacklisted",
                "&a%player% &7has been &aunblacklisted &7from prestiging.");
        msgLevelAdded           = cfg.getString("prestige.messages.level-added",
                "&aAdded &e%amount% &alevel(s) to &e%player%&a. Now: &e%total%");
        msgLevelRemoved         = cfg.getString("prestige.messages.level-removed",
                "&aRemoved &e%amount% &alevel(s) from &e%player%&a. Now: &e%total%");
        msgLevelReset           = cfg.getString("prestige.messages.level-reset",
                "&aReset &e%player%&a's level to &e0&a.");
        msgLevelBlacklisted     = cfg.getString("prestige.messages.level-blacklisted",
                "&c%player% &7has been &cblacklisted &7from leveling.");
        msgLevelUnblacklisted   = cfg.getString("prestige.messages.level-unblacklisted",
                "&a%player% &7has been &aunblacklisted &7from leveling.");
        msgBlacklistedFromPrestige = cfg.getString("prestige.messages.blacklisted-from-prestige",
                "&cYou are blacklisted from prestiging.");
        msgBlacklistedFromLeveling = cfg.getString("prestige.messages.blacklisted-from-leveling",
                "&cYou are blacklisted from leveling.");
        msgXpAdded         = cfg.getString("prestige.messages.xp-added",
                "&aAdded &e%amount% &aXP to &e%player%&a. Now: &e%total%");
        msgXpRemoved       = cfg.getString("prestige.messages.xp-removed",
                "&aRemoved &e%amount% &aXP from &e%player%&a. Now: &e%total%");
        msgXpReset         = cfg.getString("prestige.messages.xp-reset",
                "&aReset &e%player%&a's XP to &e0&a.");
        msgXpBlacklisted   = cfg.getString("prestige.messages.xp-blacklisted",
                "&c%player% &7has been &cblacklisted &7from gaining XP.");
        msgXpUnblacklisted = cfg.getString("prestige.messages.xp-unblacklisted",
                "&a%player% &7has been &aunblacklisted &7from gaining XP.");
        msgBlacklistedFromXp = cfg.getString("prestige.messages.blacklisted-from-xp",
                "&cYou are blacklisted from gaining XP.");
    }

    // ── Player lifecycle ──────────────────────────────────────────────────────

    public void inject(UUID uuid, int prestige, int level, double xp,
                       boolean pBl, boolean lBl, boolean xpBl) {
        prestigeCache.put(uuid, prestige);
        levelCache.put(uuid, level);
        xpCache.put(uuid, xp);
        if (pBl) prestigeBlacklist.add(uuid);
        if (lBl) levelBlacklist.add(uuid);
        if (xpBl) xpBlacklist.add(uuid);
        loadedPlayers.add(uuid);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        UUID   uuid = event.getPlayer().getUniqueId();
        String name = event.getPlayer().getName();

        if (loadedPlayers.contains(uuid)) return;

        if (!dbConnected) {
            prestigeCache.put(uuid, 0);
            levelCache.put(uuid, 0);
            xpCache.put(uuid, 0.0);
            loadedPlayers.add(uuid);
            return;
        }

        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            loadOrCreate(uuid, name);
            loadedPlayers.add(uuid);
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        prestigeCache.remove(uuid);
        levelCache.remove(uuid);
        xpCache.remove(uuid);
        prestigeNotified.remove(uuid);
        loadedPlayers.remove(uuid);
        prestigeBlacklist.remove(uuid);
        levelBlacklist.remove(uuid);
        xpBlacklist.remove(uuid);
    }

    // ── Getters ───────────────────────────────────────────────────────────────

    public int getPrestige(UUID uuid) { return prestigeCache.getOrDefault(uuid, 0); }
    public int getLevel(UUID uuid)    { return levelCache.getOrDefault(uuid, 0); }
    public double getXp(UUID uuid)    { return xpCache.getOrDefault(uuid, 0.0); }
    public double getXpPerLevel()     { return xpPerLevel; }
    public int getLevelsPerPrestige()  { return levelsPerPrestige; }
    public boolean isLoaded(UUID uuid) { return loadedPlayers.contains(uuid); }
    public boolean isPrestigeBlacklisted(UUID uuid) { return prestigeBlacklist.contains(uuid); }
    public boolean isLevelBlacklisted(UUID uuid)    { return levelBlacklist.contains(uuid); }
    public boolean isXpBlacklisted(UUID uuid)       { return xpBlacklist.contains(uuid); }

    // ── Progress helpers ──────────────────────────────────────────────────────

    public int getLevelPercentage(UUID uuid) {
        int level = getLevel(uuid);
        if (level >= levelsPerPrestige) return 100;
        double xp = getXp(uuid);
        double pct = (xp / xpPerLevel) * 100.0;
        return Math.min(100, Math.max(0, (int) pct));
    }

    public String buildBarLines(UUID uuid) {
        int pct = getLevelPercentage(uuid);
        int filled = pct / 5;
        if (filled > 20) filled = 20;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 20; i++) {
            sb.append(i < filled ? "§a|" : "§c|");
        }
        return sb.toString();
    }

    // ── XP / Level mutations ─────────────────────────────────────────────────

    public void addXp(UUID uuid, String playerName, double amount) {
        if (amount <= 0) return;
        if (xpBlacklist.contains(uuid)) {
            Player player = plugin.getServer().getPlayer(uuid);
            if (player != null) sendActionBar(player, msgBlacklistedFromXp);
            return;
        }
        if (levelBlacklist.contains(uuid)) {
            Player player = plugin.getServer().getPlayer(uuid);
            if (player != null) sendActionBar(player, msgBlacklistedFromLeveling);
            return;
        }

        double currentXp = xpCache.merge(uuid, amount, Double::sum);
        while (currentXp >= xpPerLevel) {
            int currentLevel = levelCache.getOrDefault(uuid, 0);
            if (currentLevel >= levelsPerPrestige) {
                xpCache.put(uuid, xpPerLevel);
                currentXp = xpPerLevel;
                sendPrestigeReady(uuid);
                break;
            }
            currentXp -= xpPerLevel;
            xpCache.put(uuid, Math.max(0, currentXp));
            levelCache.merge(uuid, 1, Integer::sum);
            playSound(uuid, Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.0f);
        }
        if (levelCache.getOrDefault(uuid, 0) >= levelsPerPrestige) {
            sendPrestigeReady(uuid);
        }
        if (dbConnected) asyncSave(uuid, playerName);
    }

    // ── Admin: Prestige mutations ────────────────────────────────────────────

    public int addPrestige(UUID uuid, String playerName, int amount) {
        int next = prestigeCache.merge(uuid, Math.max(0, amount), Integer::sum);
        prestigeNotified.remove(uuid);
        if (dbConnected) asyncSave(uuid, playerName);
        return next;
    }

    public int removePrestige(UUID uuid, String playerName, int amount) {
        int current = prestigeCache.getOrDefault(uuid, 0);
        int next = Math.max(0, current - amount);
        prestigeCache.put(uuid, next);
        if (dbConnected) asyncSave(uuid, playerName);
        return next;
    }

    public void resetPrestige(UUID uuid, String playerName) {
        prestigeCache.put(uuid, 0);
        prestigeNotified.remove(uuid);
        if (dbConnected) asyncSave(uuid, playerName);
    }

    public boolean togglePrestigeBlacklist(UUID uuid, String playerName) {
        boolean nowBlacklisted;
        if (prestigeBlacklist.contains(uuid)) {
            prestigeBlacklist.remove(uuid);
            nowBlacklisted = false;
        } else {
            prestigeBlacklist.add(uuid);
            nowBlacklisted = true;
        }
        if (dbConnected) asyncSaveBlacklists(uuid, playerName);
        return nowBlacklisted;
    }

    // ── Admin: Level mutations ───────────────────────────────────────────────

    public int addLevel(UUID uuid, String playerName, int amount) {
        int current = levelCache.getOrDefault(uuid, 0);
        int next = Math.min(levelsPerPrestige, current + amount);
        levelCache.put(uuid, next);
        if (next >= levelsPerPrestige) {
            xpCache.put(uuid, 0.0);
            sendPrestigeReady(uuid);
        }
        if (dbConnected) asyncSave(uuid, playerName);
        return next;
    }

    public int removeLevel(UUID uuid, String playerName, int amount) {
        int current = levelCache.getOrDefault(uuid, 0);
        int next = Math.max(0, current - amount);
        levelCache.put(uuid, next);
        prestigeNotified.remove(uuid);
        if (dbConnected) asyncSave(uuid, playerName);
        return next;
    }

    public void resetLevel(UUID uuid, String playerName) {
        levelCache.put(uuid, 0);
        xpCache.put(uuid, 0.0);
        prestigeNotified.remove(uuid);
        if (dbConnected) asyncSave(uuid, playerName);
    }

    public boolean toggleLevelBlacklist(UUID uuid, String playerName) {
        boolean nowBlacklisted;
        if (levelBlacklist.contains(uuid)) {
            levelBlacklist.remove(uuid);
            nowBlacklisted = false;
        } else {
            levelBlacklist.add(uuid);
            nowBlacklisted = true;
        }
        if (dbConnected) asyncSaveBlacklists(uuid, playerName);
        return nowBlacklisted;
    }

    // ── Admin: XP mutations ──────────────────────────────────────────────────

    public double addXpAdmin(UUID uuid, String playerName, double amount) {
        if (amount <= 0) return getXp(uuid);
        double currentXp = xpCache.merge(uuid, amount, Double::sum);
        while (currentXp >= xpPerLevel) {
            int currentLevel = levelCache.getOrDefault(uuid, 0);
            if (currentLevel >= levelsPerPrestige) {
                xpCache.put(uuid, xpPerLevel);
                currentXp = xpPerLevel;
                sendPrestigeReady(uuid);
                break;
            }
            currentXp -= xpPerLevel;
            xpCache.put(uuid, Math.max(0, currentXp));
            levelCache.merge(uuid, 1, Integer::sum);
        }
        if (levelCache.getOrDefault(uuid, 0) >= levelsPerPrestige) sendPrestigeReady(uuid);
        if (dbConnected) asyncSave(uuid, playerName);
        return xpCache.getOrDefault(uuid, 0.0);
    }

    public double removeXpAdmin(UUID uuid, String playerName, double amount) {
        double current = xpCache.getOrDefault(uuid, 0.0);
        double next = Math.max(0, current - amount);
        xpCache.put(uuid, next);
        if (dbConnected) asyncSave(uuid, playerName);
        return next;
    }

    public void resetXpAdmin(UUID uuid, String playerName) {
        xpCache.put(uuid, 0.0);
        if (dbConnected) asyncSave(uuid, playerName);
    }

    public boolean toggleXpBlacklist(UUID uuid, String playerName) {
        boolean nowBlacklisted;
        if (xpBlacklist.contains(uuid)) {
            xpBlacklist.remove(uuid);
            nowBlacklisted = false;
        } else {
            xpBlacklist.add(uuid);
            nowBlacklisted = true;
        }
        if (dbConnected) asyncSaveBlacklists(uuid, playerName);
        return nowBlacklisted;
    }

    // ── Legacy setters ──────────────────────────────────────────────────────

    public void setPrestige(UUID uuid, String playerName, int level) {
        prestigeCache.put(uuid, Math.max(0, level));
        prestigeNotified.remove(uuid);
        if (dbConnected) asyncSave(uuid, playerName);
    }

    public void setLevel(UUID uuid, String playerName, int level) {
        levelCache.put(uuid, Math.max(0, Math.min(level, levelsPerPrestige)));
        if (dbConnected) asyncSave(uuid, playerName);
    }

    public void setXp(UUID uuid, String playerName, double xp) {
        xpCache.put(uuid, Math.max(0, xp));
        if (dbConnected) asyncSave(uuid, playerName);
    }

    public void prestigeUp(UUID uuid, String playerName) {
        if (prestigeBlacklist.contains(uuid)) {
            Player player = plugin.getServer().getPlayer(uuid);
            if (player != null) sendActionBar(player, msgBlacklistedFromPrestige);
            return;
        }
        int current = prestigeCache.getOrDefault(uuid, 0);
        prestigeCache.put(uuid, current + 1);
        levelCache.put(uuid, 0);
        xpCache.put(uuid, 0.0);
        prestigeNotified.remove(uuid);
        if (dbConnected) asyncSave(uuid, playerName);
        Player prestigePlayer = plugin.getServer().getPlayer(uuid);
        if (prestigePlayer != null && prestigePlayer.isOnline()) {
            prestigePlayer.playSound(prestigePlayer.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.7f, 1.0f);
            prestigePlayer.playSound(prestigePlayer.getLocation(), Sound.ENTITY_FIREWORK_ROCKET_BLAST, 0.6f, 1.2f);
        }
    }

    // ── Prestige-ready notification ──────────────────────────────────────────

    private void sendPrestigeReady(UUID uuid) {
        if (prestigeNotified.contains(uuid)) return;
        if (prestigeBlacklist.contains(uuid)) return;
        prestigeNotified.add(uuid);

        Player player = plugin.getServer().getPlayer(uuid);
        if (player == null || !player.isOnline()) return;

        int nextPrestige = getPrestige(uuid) + 1;
        String raw = prestigeReadyMessage.replace("%prestige%", String.valueOf(nextPrestige));
        Component message = LEGACY.deserialize(ColorUtil.colorize(raw))
                .clickEvent(ClickEvent.runCommand("/prestige"));

        plugin.getServer().getScheduler().runTask(plugin, () -> {
            player.sendMessage(message);
            player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.8f, 2.0f);
        });
    }

    // ── Message helpers ──────────────────────────────────────────────────────

    public void sendPrestigeAdded(Player admin, String targetName, int amount, int total) {
        sendActionBar(admin, msgPrestigeAdded.replace("%player%", targetName)
                .replace("%amount%", String.valueOf(amount)).replace("%total%", String.valueOf(total)));
    }
    public void sendPrestigeRemoved(Player admin, String targetName, int amount, int total) {
        sendActionBar(admin, msgPrestigeRemoved.replace("%player%", targetName)
                .replace("%amount%", String.valueOf(amount)).replace("%total%", String.valueOf(total)));
    }
    public void sendPrestigeReset(Player admin, String targetName) {
        sendActionBar(admin, msgPrestigeReset.replace("%player%", targetName));
    }
    public void sendPrestigeBlacklistToggle(Player admin, String targetName, boolean blacklisted) {
        sendActionBar(admin, (blacklisted ? msgPrestigeBlacklisted : msgPrestigeUnblacklisted)
                .replace("%player%", targetName));
    }
    public void sendLevelAdded(Player admin, String targetName, int amount, int total) {
        sendActionBar(admin, msgLevelAdded.replace("%player%", targetName)
                .replace("%amount%", String.valueOf(amount)).replace("%total%", String.valueOf(total)));
    }
    public void sendLevelRemoved(Player admin, String targetName, int amount, int total) {
        sendActionBar(admin, msgLevelRemoved.replace("%player%", targetName)
                .replace("%amount%", String.valueOf(amount)).replace("%total%", String.valueOf(total)));
    }
    public void sendLevelReset(Player admin, String targetName) {
        sendActionBar(admin, msgLevelReset.replace("%player%", targetName));
    }
    public void sendLevelBlacklistToggle(Player admin, String targetName, boolean blacklisted) {
        sendActionBar(admin, (blacklisted ? msgLevelBlacklisted : msgLevelUnblacklisted)
                .replace("%player%", targetName));
    }
    public void sendXpAdded(Player admin, String targetName, double amount, double total) {
        sendActionBar(admin, msgXpAdded.replace("%player%", targetName)
                .replace("%amount%", formatXp(amount)).replace("%total%", formatXp(total)));
    }
    public void sendXpRemoved(Player admin, String targetName, double amount, double total) {
        sendActionBar(admin, msgXpRemoved.replace("%player%", targetName)
                .replace("%amount%", formatXp(amount)).replace("%total%", formatXp(total)));
    }
    public void sendXpReset(Player admin, String targetName) {
        sendActionBar(admin, msgXpReset.replace("%player%", targetName));
    }
    public void sendXpBlacklistToggle(Player admin, String targetName, boolean blacklisted) {
        sendActionBar(admin, (blacklisted ? msgXpBlacklisted : msgXpUnblacklisted)
                .replace("%player%", targetName));
    }

    private static String formatXp(double xp) {
        return xp == Math.floor(xp) ? String.valueOf((int) xp) : String.format("%.1f", xp);
    }

    private void sendActionBar(Player player, String message) {
        Component comp = LEGACY.deserialize(ColorUtil.colorize(message));
        player.sendActionBar(comp);
    }

    // ── Database ──────────────────────────────────────────────────────────────

    private void loadOrCreate(UUID uuid, String name) {
        try {
            db.getCollection("players").updateOne(
                    eq("_id", uuid.toString()),
                    new Document("$set", new Document("player_name", name))
                            .append("$setOnInsert", new Document("balance", 0.0)
                                    .append("prestige", 0).append("level", 0).append("xp", 0.0)
                                    .append("prestige_blacklisted", false)
                                    .append("level_blacklisted", false)
                                    .append("xp_blacklisted", false)),
                    new UpdateOptions().upsert(true));

            Document doc = db.getCollection("players").find(eq("_id", uuid.toString())).first();
            if (doc != null) {
                prestigeCache.put(uuid, doc.getInteger("prestige", 0));
                levelCache.put(uuid, doc.getInteger("level", 0));
                xpCache.put(uuid, doc.getDouble("xp") != null ? doc.getDouble("xp") : 0.0);
                if (doc.getBoolean("prestige_blacklisted", false)) prestigeBlacklist.add(uuid);
                if (doc.getBoolean("level_blacklisted", false))    levelBlacklist.add(uuid);
                if (doc.getBoolean("xp_blacklisted", false))       xpBlacklist.add(uuid);
            } else {
                prestigeCache.put(uuid, 0);
                levelCache.put(uuid, 0);
                xpCache.put(uuid, 0.0);
            }
        } catch (Exception e) {
            plugin.getLogger().warning("[PrestigeManager] Failed to load data for " + uuid + ": " + e.getMessage());
            prestigeCache.put(uuid, 0);
            levelCache.put(uuid, 0);
            xpCache.put(uuid, 0.0);
        }
    }

    private void asyncSave(UUID uuid, String playerName) {
        int prestige = prestigeCache.getOrDefault(uuid, 0);
        int level    = levelCache.getOrDefault(uuid, 0);
        double xp    = xpCache.getOrDefault(uuid, 0.0);

        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                db.getCollection("players").updateOne(
                        eq("_id", uuid.toString()),
                        new Document("$set", new Document("player_name", playerName)
                                .append("prestige", prestige)
                                .append("level", level)
                                .append("xp", xp)),
                        new UpdateOptions().upsert(true));
            } catch (Exception e) {
                plugin.getLogger().warning("[PrestigeManager] Failed to save data for " + uuid + ": " + e.getMessage());
            }
        });
    }

    private void asyncSaveBlacklists(UUID uuid, String playerName) {
        boolean pBl = prestigeBlacklist.contains(uuid);
        boolean lBl = levelBlacklist.contains(uuid);
        boolean xBl = xpBlacklist.contains(uuid);

        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                db.getCollection("players").updateOne(
                        eq("_id", uuid.toString()),
                        new Document("$set", new Document("prestige_blacklisted", pBl)
                                .append("level_blacklisted", lBl)
                                .append("xp_blacklisted", xBl)));
            } catch (Exception e) {
                plugin.getLogger().warning("[PrestigeManager] Failed to save blacklists for " + uuid + ": " + e.getMessage());
            }
        });
    }

    private void playSound(UUID uuid, Sound sound, float volume, float pitch) {
        Player player = plugin.getServer().getPlayer(uuid);
        if (player != null && player.isOnline()) {
            player.playSound(player.getLocation(), sound, volume, pitch);
        }
    }

    public void unload(UUID uuid) {
        prestigeCache.remove(uuid);
        levelCache.remove(uuid);
        xpCache.remove(uuid);
        prestigeNotified.remove(uuid);
        loadedPlayers.remove(uuid);
        prestigeBlacklist.remove(uuid);
        levelBlacklist.remove(uuid);
        xpBlacklist.remove(uuid);
    }

    // ── Wipe ──────────────────────────────────────────────────────────────────

    public void wipeAllMemory() {
        prestigeCache.clear();
        levelCache.clear();
        xpCache.clear();
        loadedPlayers.clear();
    }
}
