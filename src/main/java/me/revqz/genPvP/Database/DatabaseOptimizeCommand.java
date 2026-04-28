package me.revqz.genPvP.Database;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import me.revqz.genPvP.Bank.BankManager;
import me.revqz.genPvP.DevilFruits.DevilFruitManager;
import me.revqz.genPvP.DevilFruits.FruitRollManager;
import me.revqz.genPvP.GenPvP;
import me.revqz.genPvP.Prestige.PrestigeManager;
import me.revqz.genPvP.Stats.StatsManager;
import me.revqz.genPvP.Teams.TeamManager;
import org.bson.Document;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static com.mongodb.client.model.Filters.eq;

/**
 * /database optimize mongo
 *
 * One-time migration that converts existing data to the compact storage formats:
 *
 *   devil_fruits   : many docs (one per fruit) → one doc per player
 *                    { _id: uuid, owned: [...], equipped: "..." }
 *
 *   kit_cooldowns  : many docs (one per cooldown) → one doc per player
 *                    { _id: uuid, cooldowns: { kitId: expiresMs, ... } }
 *
 *   protected_blocks: one doc per block → one doc per chunk
 *                    { _id: "world:chunkX:chunkZ", b: [[x,y,z], ...] }
 *
 * Safe to re-run (idempotent). Old legacy documents are removed after migration.
 */
public class DatabaseOptimizeCommand implements CommandExecutor, TabCompleter {

    private static final Set<String> WIPE_ALLOWED = Set.of("javarev", "revqz");

    // ── Backup constants ──────────────────────────────────────────────────────
    /** Master DB that holds only the index of all backups. */
    private static final String       BACKUPS_INDEX_DB    = "Backups";
    private static final String       BACKUPS_INDEX_COLL  = "backups_index";
    /** Each backup gets its own DB named "Backups_<id>" — Compass renders it as a folder. */
    private static final String       BACKUP_DB_PREFIX    = "Backups_";
    /** Source collections in the main DB that get copied on backup / restored on restore. */
    private static final List<String> BACKUP_COLLECTIONS  = List.of(
            "players", "devil_fruits", "fruit_rolls", "teams", "team_members", "enderchest");
    private static final String       PLAYERDATA_COLL     = "playerdata";
    /** Max 54 so "Backups_" + id stays under MongoDB's 63-char database-name limit. */
    private static final Pattern      ID_PATTERN          = Pattern.compile("^[A-Za-z0-9_-]{1,54}$");
    private static final DateTimeFormatter ID_FORMAT      =
            DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss").withZone(ZoneOffset.UTC);
    private static final int          INSERT_BATCH_SIZE   = 1000;

    /** In-memory cache of completed backup IDs — drives /database restore tab completion. */
    private final Set<String> knownBackupIds = ConcurrentHashMap.newKeySet();

    private final GenPvP            plugin;
    private final DatabaseManager   databaseManager;
    private final BankManager       bankManager;
    private final PrestigeManager   prestigeManager;
    private final StatsManager      statsManager;
    private final TeamManager       teamManager;
    private final DevilFruitManager devilFruitManager;
    private final FruitRollManager  fruitRollManager;
    private final Logger            log;
    private final AtomicBoolean     running = new AtomicBoolean(false);

    public DatabaseOptimizeCommand(GenPvP plugin, DatabaseManager databaseManager,
                                   BankManager bankManager, PrestigeManager prestigeManager,
                                   StatsManager statsManager, TeamManager teamManager,
                                   DevilFruitManager devilFruitManager, FruitRollManager fruitRollManager) {
        this.plugin           = plugin;
        this.databaseManager  = databaseManager;
        this.bankManager      = bankManager;
        this.prestigeManager  = prestigeManager;
        this.statsManager     = statsManager;
        this.teamManager      = teamManager;
        this.devilFruitManager = devilFruitManager;
        this.fruitRollManager  = fruitRollManager;
        this.log              = plugin.getLogger();

        // Populate the backup-id cache asynchronously so /database restore tab
        // completion works without blocking the main thread or hitting Mongo on every TAB.
        if (databaseManager.isMongoConnected()) {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, this::reloadBackupIds);
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sendUsage(sender);
            return true;
        }

        if (!databaseManager.isMongoConnected()) {
            sender.sendMessage("§cMongoDB is not connected.");
            return true;
        }

        // /database wipe_release confirm
        if (args[0].equalsIgnoreCase("wipe_release")) {
            handleWipeRelease(sender, args);
            return true;
        }

        // /database backup [id]
        if (args[0].equalsIgnoreCase("backup")) {
            handleBackup(sender, args);
            return true;
        }

        // /database restore <id> confirm
        if (args[0].equalsIgnoreCase("restore")) {
            handleRestore(sender, args);
            return true;
        }

        // /database fruit_sync
        if (args[0].equalsIgnoreCase("fruit_sync")) {
            if (running.getAndSet(true)) {
                sender.sendMessage("§eA database task is already running.");
                return true;
            }
            sender.sendMessage("§a[DB] Starting fruit_sync... check console for progress.");
            log.info("[DB] fruit_sync started by " + sender.getName());
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                try {
                    syncFruitDocs(databaseManager.getDatabase(), sender);
                } catch (Exception e) {
                    sender.sendMessage("§c[DB] fruit_sync failed: " + e.getMessage());
                    log.severe("[DB] fruit_sync: " + e.getMessage());
                    e.printStackTrace();
                } finally {
                    running.set(false);
                }
            });
            return true;
        }

        // /database optimize mongo
        if (args.length < 2
                || !args[0].equalsIgnoreCase("optimize")
                || !args[1].equalsIgnoreCase("mongo")) {
            sendUsage(sender);
            return true;
        }

        if (running.getAndSet(true)) {
            sender.sendMessage("§eOptimization is already running.");
            return true;
        }

        sender.sendMessage("§a[DB Optimize] Starting... check console for full progress.");
        log.info("[DBOptimize] Started by " + sender.getName());

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                MongoDatabase db = databaseManager.getDatabase();
                optimizeDevilFruits(db, sender);
                optimizeKitCooldowns(db, sender);
                optimizeProtectedBlocks(db, sender);
                String done = "§a§l[DB Optimize] All optimizations complete!";
                sender.sendMessage(done);
                log.info("[DBOptimize] Done.");
            } catch (Exception e) {
                sender.sendMessage("§c[DB Optimize] Failed: " + e.getMessage());
                log.severe("[DBOptimize] " + e.getMessage());
                e.printStackTrace();
            } finally {
                running.set(false);
            }
        });

        return true;
    }

    // ── Backup ───────────────────────────────────────────────────────────────

    private void handleBackup(CommandSender sender, String[] args) {
        if (sender instanceof Player p && !WIPE_ALLOWED.contains(p.getName().toLowerCase())) {
            sender.sendMessage("§cYou do not have permission to run this command.");
            return;
        }

        // Resolve / validate the backup ID
        final String id;
        if (args.length >= 2) {
            String supplied = args[1];
            if (!ID_PATTERN.matcher(supplied).matches()) {
                sender.sendMessage("§cInvalid backup id. Allowed: A-Z, a-z, 0-9, _ , - (max 54 chars).");
                return;
            }
            id = supplied;
        } else {
            id = ID_FORMAT.format(ZonedDateTime.now(ZoneOffset.UTC));
        }

        if (running.getAndSet(true)) {
            sender.sendMessage("§eA database task is already running.");
            return;
        }

        // Flush every online player's in-memory state to disk on the main thread
        // before starting the async backup. Paper's CraftPlayer.saveData() is
        // synchronous (writes the .dat via NbtIo.writeCompressed before returning),
        // so once this loop completes every .dat file reflects current state.
        // Note: causes a brief main-thread freeze proportional to online count.
        int onlineCount = Bukkit.getOnlinePlayers().size();
        long flushStart = System.currentTimeMillis();
        int flushed = 0;
        for (Player p : Bukkit.getOnlinePlayers()) {
            try { p.saveData(); flushed++; }
            catch (Throwable t) { log.warning("[Backup] saveData failed for " + p.getName() + ": " + t.getMessage()); }
        }
        long flushElapsed = System.currentTimeMillis() - flushStart;
        if (onlineCount > 0) {
            sender.sendMessage("§7Flushed §f" + flushed + "§7/§f" + onlineCount
                    + " §7online player(s) to disk in §f" + flushElapsed + "ms§7.");
        }

        log.info("[Backup] " + sender.getName() + " starting backup '" + id + "' (online flushed: " + flushed + ")");
        sender.sendMessage("§a[Backup] §7Starting backup §f" + id + "§7...");

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                MongoDatabase mainDb    = databaseManager.getDatabase();
                MongoDatabase indexDb   = databaseManager.getDatabase(BACKUPS_INDEX_DB);
                MongoDatabase backupDb  = databaseManager.getDatabase(BACKUP_DB_PREFIX + id);
                MongoCollection<Document> index = indexDb.getCollection(BACKUPS_INDEX_COLL);

                // Reject duplicate IDs (either in the index or a leftover orphan DB)
                if (index.find(eq("_id", id)).first() != null) {
                    backupMsg(sender, "§cBackup id §f" + id + " §calready exists. Aborting.");
                    return;
                }
                // Defensive: if a previous failed run left collections behind, drop them.
                backupDb.drop();

                // Mark in_progress so a partial backup is never restorable
                index.insertOne(new Document("_id", id)
                        .append("created_at", new Date())
                        .append("created_by", sender.getName())
                        .append("status", "in_progress"));

                Document sourceCounts = new Document();

                // Copy each main collection into the per-backup DB
                for (String coll : BACKUP_COLLECTIONS) {
                    MongoCollection<Document> src = mainDb.getCollection(coll);
                    MongoCollection<Document> dst = backupDb.getCollection(coll);

                    int copied = 0;
                    List<Document> batch = new ArrayList<>(INSERT_BATCH_SIZE);
                    for (Document doc : src.find()) {
                        batch.add(doc);
                        if (batch.size() >= INSERT_BATCH_SIZE) {
                            dst.insertMany(batch);
                            copied += batch.size();
                            batch.clear();
                        }
                    }
                    if (!batch.isEmpty()) { dst.insertMany(batch); copied += batch.size(); }
                    sourceCounts.append(coll, copied);
                    backupMsg(sender, "§7  · " + coll + " §8→ " + copied + " doc(s)");
                }

                // Backup .dat files for every player on disk (entire file, raw bytes)
                MongoCollection<Document> pdColl = backupDb.getCollection(PLAYERDATA_COLL);
                File playerDataFolder = new File(Bukkit.getWorlds().get(0).getWorldFolder(), "playerdata");
                int datCount = 0;
                if (playerDataFolder.isDirectory()) {
                    File[] datFiles = playerDataFolder.listFiles(
                            (dir, name) -> name.endsWith(".dat") && !name.endsWith(".dat_old"));
                    if (datFiles != null) {
                        List<Document> batch = new ArrayList<>(INSERT_BATCH_SIZE);
                        for (File datFile : datFiles) {
                            String raw = datFile.getName();
                            String uuidStr = raw.substring(0, raw.length() - 4);
                            try { UUID.fromString(uuidStr); }
                            catch (IllegalArgumentException e) { continue; }

                            try {
                                byte[] bytes = Files.readAllBytes(datFile.toPath());
                                batch.add(new Document("_id", uuidStr).append("data", bytes));
                                if (batch.size() >= INSERT_BATCH_SIZE) {
                                    pdColl.insertMany(batch);
                                    datCount += batch.size();
                                    batch.clear();
                                }
                            } catch (IOException e) {
                                log.warning("[Backup] Failed to read " + raw + ": " + e.getMessage());
                            }
                        }
                        if (!batch.isEmpty()) { pdColl.insertMany(batch); datCount += batch.size(); }
                    }
                }
                backupMsg(sender, "§7  · playerdata §8→ " + datCount + " .dat file(s)");

                // Mark complete
                index.updateOne(eq("_id", id),
                        new Document("$set", new Document()
                                .append("status", "complete")
                                .append("source_counts", sourceCounts)
                                .append("playerdata_count", datCount)));

                knownBackupIds.add(id);

                Bukkit.getScheduler().runTask(plugin, () ->
                        sender.sendMessage("§a§l[Backup] §r§aComplete: §f" + id));
                log.info("[Backup] '" + id + "' complete by " + sender.getName());
            } catch (Exception e) {
                backupMsg(sender, "§cBackup failed: " + e.getMessage());
                log.severe("[Backup] " + e.getMessage());
                e.printStackTrace();
                // Clean up the partial backup so it never appears as restorable
                try {
                    databaseManager.getDatabase(BACKUP_DB_PREFIX + id).drop();
                    databaseManager.getDatabase(BACKUPS_INDEX_DB)
                            .getCollection(BACKUPS_INDEX_COLL)
                            .deleteOne(eq("_id", id));
                } catch (Exception cleanupErr) {
                    log.warning("[Backup] Failed to clean up partial backup '" + id + "': " + cleanupErr.getMessage());
                }
            } finally {
                running.set(false);
            }
        });
    }

    // ── Restore ──────────────────────────────────────────────────────────────

    private void handleRestore(CommandSender sender, String[] args) {
        if (sender instanceof Player p && !WIPE_ALLOWED.contains(p.getName().toLowerCase())) {
            sender.sendMessage("§cYou do not have permission to run this command.");
            return;
        }

        if (args.length < 2) {
            sender.sendMessage("§cUsage: §f/database restore <id> confirm");
            return;
        }

        final String id = args[1];
        if (!ID_PATTERN.matcher(id).matches()) {
            sender.sendMessage("§cInvalid backup id format.");
            return;
        }

        if (args.length < 3 || !args[2].equalsIgnoreCase("confirm")) {
            sender.sendMessage("§c§lWARNING §r§c— this will OVERWRITE the current main database");
            sender.sendMessage("§7  with backup §f" + id + "§7.");
            sender.sendMessage("§eRun §f/database restore " + id + " confirm §eto proceed.");
            return;
        }

        // Refuse if anyone is online — restore overwrites .dat files and resets caches
        if (!Bukkit.getOnlinePlayers().isEmpty()) {
            sender.sendMessage("§cAll players must be offline to restore. Currently online: §f"
                    + Bukkit.getOnlinePlayers().size());
            return;
        }

        if (running.getAndSet(true)) {
            sender.sendMessage("§eA database task is already running.");
            return;
        }

        log.warning("[Restore] " + sender.getName() + " starting restore '" + id + "'");
        sender.sendMessage("§c[Restore] §7Restoring backup §f" + id + "§7...");

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                MongoDatabase indexDb  = databaseManager.getDatabase(BACKUPS_INDEX_DB);
                MongoDatabase backupDb = databaseManager.getDatabase(BACKUP_DB_PREFIX + id);
                MongoCollection<Document> index = indexDb.getCollection(BACKUPS_INDEX_COLL);

                Document meta = index.find(eq("_id", id)).first();
                if (meta == null) {
                    backupMsg(sender, "§cBackup §f" + id + " §cdoes not exist.");
                    return;
                }
                if (!"complete".equals(meta.getString("status"))) {
                    backupMsg(sender, "§cBackup §f" + id + " §cis not marked complete (status=" + meta.getString("status") + ").");
                    return;
                }

                MongoDatabase mainDb = databaseManager.getDatabase();

                // Restore each source collection: drop main, copy from backup
                for (String coll : BACKUP_COLLECTIONS) {
                    MongoCollection<Document> src = backupDb.getCollection(coll);
                    MongoCollection<Document> dst = mainDb.getCollection(coll);
                    dst.drop();
                    int copied = 0;
                    List<Document> batch = new ArrayList<>(INSERT_BATCH_SIZE);
                    for (Document doc : src.find()) {
                        batch.add(doc);
                        if (batch.size() >= INSERT_BATCH_SIZE) {
                            dst.insertMany(batch);
                            copied += batch.size();
                            batch.clear();
                        }
                    }
                    if (!batch.isEmpty()) { dst.insertMany(batch); copied += batch.size(); }
                    backupMsg(sender, "§7  · " + coll + " §8← " + copied + " doc(s)");
                }

                // Restore .dat files: write backed-up ones, then delete any orphans
                // (players who joined after the backup was taken) so disk state matches DB.
                File playerDataFolder = new File(Bukkit.getWorlds().get(0).getWorldFolder(), "playerdata");
                if (!playerDataFolder.isDirectory()) playerDataFolder.mkdirs();

                MongoCollection<Document> pdColl = backupDb.getCollection(PLAYERDATA_COLL);
                Set<String> backedUpUuids = new HashSet<>();
                int datCount = 0, datFailed = 0;
                for (Document doc : pdColl.find()) {
                    String uuidStr = doc.getString("_id");
                    Object raw = doc.get("data");
                    byte[] bytes = (raw instanceof org.bson.types.Binary b) ? b.getData()
                                  : (raw instanceof byte[] ba)              ? ba
                                  : null;
                    if (uuidStr == null || bytes == null) { datFailed++; continue; }
                    try {
                        Files.write(new File(playerDataFolder, uuidStr + ".dat").toPath(), bytes);
                        backedUpUuids.add(uuidStr);
                        datCount++;
                    } catch (IOException e) {
                        datFailed++;
                        log.warning("[Restore] Failed write " + uuidStr + ".dat: " + e.getMessage());
                    }
                }

                // Delete .dat files (and their .dat_old siblings) for UUIDs not present in the backup
                int orphans = 0;
                File[] existing = playerDataFolder.listFiles(
                        (dir, name) -> name.endsWith(".dat") || name.endsWith(".dat_old"));
                if (existing != null) {
                    for (File f : existing) {
                        String name = f.getName();
                        int dot = name.indexOf(".dat");
                        String uuidStr = name.substring(0, dot);
                        try { UUID.fromString(uuidStr); }
                        catch (IllegalArgumentException e) { continue; }
                        if (!backedUpUuids.contains(uuidStr)) {
                            if (f.delete()) orphans++;
                            else log.warning("[Restore] Failed to delete orphan " + name);
                        }
                    }
                }
                backupMsg(sender, "§7  · playerdata §8← " + datCount + " .dat file(s)"
                        + (orphans > 0 ? " (§e" + orphans + " orphan(s) removed§7)" : "")
                        + (datFailed > 0 ? " (§c" + datFailed + " failed§7)" : ""));

                // Recreate indexes (drop() removed them)
                databaseManager.ensureIndexes();

                // Reset in-memory caches — managers will reload from the restored DB on next access
                Bukkit.getScheduler().runTask(plugin, () -> {
                    bankManager.wipeAllMemory();
                    prestigeManager.wipeAllMemory();
                    statsManager.wipeAllMemory();
                    teamManager.wipeAllMemory();
                    devilFruitManager.wipeAllMemory();
                    fruitRollManager.wipeAllMemory();
                    sender.sendMessage("§a§l[Restore] §r§aComplete: §f" + id);
                });
                log.warning("[Restore] '" + id + "' complete by " + sender.getName());
            } catch (Exception e) {
                backupMsg(sender, "§cRestore failed: " + e.getMessage());
                log.severe("[Restore] " + e.getMessage());
                e.printStackTrace();
            } finally {
                running.set(false);
            }
        });
    }

    private void reloadBackupIds() {
        try {
            MongoDatabase indexDb = databaseManager.getDatabase(BACKUPS_INDEX_DB);
            if (indexDb == null) return;
            MongoCollection<Document> index = indexDb.getCollection(BACKUPS_INDEX_COLL);
            Set<String> fresh = new HashSet<>();
            for (Document doc : index.find(eq("status", "complete"))) {
                Object id = doc.get("_id");
                if (id instanceof String s) fresh.add(s);
            }
            knownBackupIds.clear();
            knownBackupIds.addAll(fresh);
            log.info("[Backup] Loaded " + fresh.size() + " backup id(s) into cache.");
        } catch (Exception e) {
            log.warning("[Backup] Failed to load backup ids: " + e.getMessage());
        }
    }

    private void backupMsg(CommandSender sender, String text) {
        log.info("[Backup] " + text.replaceAll("§[0-9a-fklmnorA-FKLMNOR]", ""));
        Bukkit.getScheduler().runTask(plugin, () -> sender.sendMessage(text));
    }

    private void sendUsage(CommandSender sender) {
        sender.sendMessage("§cUsage:");
        sender.sendMessage("§7  /database optimize mongo");
        sender.sendMessage("§7  /database fruit_sync");
        sender.sendMessage("§7  /database backup [id]");
        sender.sendMessage("§7  /database restore <id> confirm");
        sender.sendMessage("§7  /database wipe_release confirm");
    }

    // ── Wipe Release ─────────────────────────────────────────────────────────

    private void handleWipeRelease(CommandSender sender, String[] args) {
        // IGN gate — only javarev or revqz can run this
        if (sender instanceof Player p && !WIPE_ALLOWED.contains(p.getName().toLowerCase())) {
            sender.sendMessage("§cYou do not have permission to run this command.");
            return;
        }

        // Safety confirmation argument
        if (args.length < 2 || !args[1].equalsIgnoreCase("confirm")) {
            sender.sendMessage("§c§lWARNING §r§c— this will permanently wipe ALL player data:");
            sender.sendMessage("§7  XP · Level · Prestige · Kills · Deaths · Balance · Gold · Shards");
            sender.sendMessage("§7  Teams · Devil Fruits · Rolls · Inventories · Ender Chests");
            sender.sendMessage("§eType §f/database wipe_release confirm §eto proceed.");
            return;
        }

        if (running.getAndSet(true)) {
            sender.sendMessage("§eA database task is already running.");
            return;
        }

        log.warning("[WipeRelease] INITIATED by " + sender.getName());
        sender.sendMessage("§c§l[WIPE] §r§cStarting release wipe — check console for progress.");

        // ── Step 1 (main thread): clear online player inventories + ender chests
        int onlineCount = Bukkit.getOnlinePlayers().size();
        // Capture UUIDs now (main thread) so the async step can skip them
        Set<UUID> onlineUuids = new HashSet<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            onlineUuids.add(p.getUniqueId());
            p.getInventory().clear();
            p.getEnderChest().clear();
            p.sendMessage("§c§l[SERVER] §r§cA release wipe has been performed. All player data has been reset.");
        }
        wipeMsg(sender, "Cleared inventories + ender chests for " + onlineCount + " online player(s).");

        // ── Step 2 (main thread): clear all in-memory caches
        bankManager.wipeAllMemory();
        prestigeManager.wipeAllMemory();
        statsManager.wipeAllMemory();
        teamManager.wipeAllMemory();
        devilFruitManager.wipeAllMemory();
        fruitRollManager.wipeAllMemory();
        wipeMsg(sender, "Cleared all in-memory caches.");

        // ── Step 3 (async): offline player .dat files + deleteMany on all collections
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                // Wipe offline player inventory + ender chest in .dat files
                int offlineCount = wipeOfflinePlayerFiles(sender, onlineUuids);
                wipeMsg(sender, "Cleared inventories + ender chests for " + offlineCount + " offline player(s).");

                MongoDatabase db = databaseManager.getDatabase();

                wipeCollection(db, "players",      sender);  // xp, level, prestige, kills, deaths, balance, gold, shards
                wipeCollection(db, "devil_fruits",  sender);  // owned + equipped fruits
                wipeCollection(db, "fruit_rolls",   sender);  // roll tokens
                wipeCollection(db, "teams",         sender);  // team documents
                wipeCollection(db, "team_members",  sender);  // team membership
                wipeCollection(db, "enderchest",    sender);  // persisted ender chest contents

                Bukkit.getScheduler().runTask(plugin, () ->
                    sender.sendMessage("§a§l[WIPE] §r§aRelease wipe complete. All collections cleared."));
                log.warning("[WipeRelease] COMPLETE — all data wiped by " + sender.getName());
            } catch (Exception e) {
                sender.sendMessage("§c[WIPE] Failed: " + e.getMessage());
                log.severe("[WipeRelease] " + e.getMessage());
                e.printStackTrace();
            } finally {
                running.set(false);
            }
        });
    }

    private void wipeCollection(MongoDatabase db, String name, CommandSender sender) {
        long deleted = db.getCollection(name).deleteMany(new Document()).getDeletedCount();
        wipeMsg(sender, "Wiped §f" + name + " §7— " + deleted + " document(s) removed.");
    }

    /**
     * Clears the Inventory and EnderItems NBT tags from every offline player's
     * .dat file found in the default world's playerdata folder.
     * Uses Paper/NMS reflection — specific to Mojang-mapped Paper 1.21.
     *
     * @return number of .dat files successfully wiped
     */
    private int wipeOfflinePlayerFiles(CommandSender sender, Set<UUID> skipUuids) {
        File playerDataFolder = new File(
                Bukkit.getWorlds().get(0).getWorldFolder(), "playerdata");
        if (!playerDataFolder.exists()) return 0;

        File[] datFiles = playerDataFolder.listFiles(
                (dir, name) -> name.endsWith(".dat") && !name.endsWith(".dat_old"));
        if (datFiles == null || datFiles.length == 0) return 0;

        // Resolve reflection handles once — fail fast if NMS is inaccessible
        java.lang.reflect.Method readMethod, writeMethod, putMethod;
        Object unlimitedHeap;
        Class<?> listTagClass, compoundTagClass, tagInterface;
        try {
            Class<?> nbtIoClass      = Class.forName("net.minecraft.nbt.NbtIo");
            compoundTagClass         = Class.forName("net.minecraft.nbt.CompoundTag");
            listTagClass             = Class.forName("net.minecraft.nbt.ListTag");
            tagInterface             = Class.forName("net.minecraft.nbt.Tag");
            Class<?> accounterClass  = Class.forName("net.minecraft.nbt.NbtAccounter");

            unlimitedHeap = accounterClass.getMethod("unlimitedHeap").invoke(null);
            readMethod    = nbtIoClass.getMethod("readCompressed",
                    java.nio.file.Path.class, accounterClass);
            writeMethod   = nbtIoClass.getMethod("writeCompressed",
                    compoundTagClass, java.nio.file.Path.class);
            putMethod     = compoundTagClass.getMethod("put", String.class, tagInterface);
        } catch (Exception e) {
            wipeMsg(sender, "§eOffline .dat wipe skipped — NMS unavailable: " + e.getMessage());
            log.warning("[WipeRelease] NMS reflection failed: " + e);
            return 0;
        }

        int wiped = 0;
        int failed = 0;
        for (File datFile : datFiles) {
            String rawName = datFile.getName();
            String uuidStr = rawName.substring(0, rawName.length() - 4);
            UUID uuid;
            try { uuid = UUID.fromString(uuidStr); }
            catch (IllegalArgumentException ignored) { continue; }

            if (skipUuids.contains(uuid)) continue; // online players already handled

            try {
                Object compound = readMethod.invoke(null, datFile.toPath(), unlimitedHeap);
                // Replace Inventory and EnderItems with fresh empty ListTags
                putMethod.invoke(compound, "Inventory",  listTagClass.getDeclaredConstructor().newInstance());
                putMethod.invoke(compound, "EnderItems", listTagClass.getDeclaredConstructor().newInstance());
                writeMethod.invoke(null, compound, datFile.toPath());
                wiped++;
            } catch (Exception e) {
                failed++;
                log.warning("[WipeRelease] Failed .dat wipe for " + uuid + ": " + e.getMessage());
            }
        }
        if (failed > 0) wipeMsg(sender, "§e" + failed + " offline .dat file(s) could not be wiped — see console.");
        return wiped;
    }

    private void wipeMsg(CommandSender sender, String text) {
        log.warning("[WipeRelease] " + text.replaceAll("§[0-9a-fklmnorA-FKLMNOR]", ""));
        sender.sendMessage("§c[WIPE] §7" + text);
    }

    // ── Devil Fruits ─────────────────────────────────────────────────────────
    // Old: many docs { uuid: "...", fruit_name: "...", equipped: bool }
    // New: one doc per player { _id: uuid, owned: [...], equipped: "..." }

    private void optimizeDevilFruits(MongoDatabase db, CommandSender sender) {
        MongoCollection<Document> coll = db.getCollection("devil_fruits");
        msg(sender, "§7devil_fruits — scanning...");

        // Detect if already in new format (has _id that looks like a UUID and has "owned" array)
        Document sample = coll.find().first();
        if (sample == null) {
            msg(sender, "§e  ~ devil_fruits: empty, nothing to do.");
            return;
        }
        boolean alreadyNew = sample.containsKey("owned");
        if (alreadyNew) {
            msg(sender, "§e  ~ devil_fruits: already in compact format, skipping.");
            return;
        }

        // Drop old compound unique index so new-format docs can be inserted
        dropIndex(coll, "uuid_1_fruit_name_1", sender);

        // Group old docs by uuid, collect _id values for batched deletion
        Map<String, List<String>> owned    = new LinkedHashMap<>();
        Map<String, String>       equipped = new LinkedHashMap<>();
        List<Object>              oldIds   = new ArrayList<>();
        int oldCount = 0;

        for (Document doc : coll.find()) {
            String uuid      = doc.getString("uuid");
            String fruitName = doc.getString("fruit_name");
            if (uuid == null || fruitName == null) continue;
            owned.computeIfAbsent(uuid, k -> new ArrayList<>()).add(fruitName);
            if (Boolean.TRUE.equals(doc.getBoolean("equipped", false))) {
                equipped.put(uuid, fruitName);
            }
            oldIds.add(doc.get("_id"));
            oldCount++;
        }

        log.info("[DBOptimize] devil_fruits: " + oldCount + " old docs → " + owned.size() + " player docs");

        // Write new format
        for (Map.Entry<String, List<String>> entry : owned.entrySet()) {
            String uuid = entry.getKey();
            Document doc = new Document("_id", uuid)
                    .append("owned", entry.getValue());
            String eq = equipped.get(uuid);
            if (eq != null) doc.append("equipped", eq);
            coll.replaceOne(
                    new Document("_id", uuid),
                    doc,
                    new com.mongodb.client.model.ReplaceOptions().upsert(true));
        }

        // Batch-delete old docs
        batchDelete(coll, oldIds);
        msg(sender, "§a  ✓ devil_fruits: " + oldCount + " docs → " + owned.size() + " player docs");
    }

    // ── Kit Cooldowns ─────────────────────────────────────────────────────────
    // Old: many docs { uuid: "...", kit: "...", expires: long }
    // New: one doc per player { _id: uuid, cooldowns: { kitId: expires, ... } }

    private void optimizeKitCooldowns(MongoDatabase db, CommandSender sender) {
        MongoCollection<Document> coll = db.getCollection("kit_cooldowns");
        msg(sender, "§7kit_cooldowns — scanning...");

        Document sample = coll.find().first();
        if (sample == null) {
            msg(sender, "§e  ~ kit_cooldowns: empty, nothing to do.");
            return;
        }
        boolean alreadyNew = sample.containsKey("cooldowns");
        if (alreadyNew) {
            msg(sender, "§e  ~ kit_cooldowns: already in compact format, skipping.");
            return;
        }

        // Drop old indexes — uuid+kit compound unique and expires TTL/sort index
        dropIndex(coll, "uuid_1_kit_1", sender);
        dropIndex(coll, "expires_1", sender);

        long now = System.currentTimeMillis();
        Map<String, Map<String, Long>> playerCooldowns = new LinkedHashMap<>();
        int oldCount = 0;

        List<Object> oldIds = new ArrayList<>();

        for (Document doc : coll.find()) {
            String uuid    = doc.getString("uuid");
            String kit     = doc.getString("kit");
            Long   expires = doc.getLong("expires");
            if (uuid == null || kit == null || expires == null) continue;
            oldIds.add(doc.get("_id"));
            if (expires <= now) { oldCount++; continue; } // collect id but skip expired values
            playerCooldowns.computeIfAbsent(uuid, k -> new LinkedHashMap<>()).put(kit, expires);
            oldCount++;
        }

        log.info("[DBOptimize] kit_cooldowns: " + oldCount + " old docs → " + playerCooldowns.size() + " player docs");

        for (Map.Entry<String, Map<String, Long>> entry : playerCooldowns.entrySet()) {
            Document cooldownsDoc = new Document();
            entry.getValue().forEach(cooldownsDoc::append);
            coll.replaceOne(
                    new Document("_id", entry.getKey()),
                    new Document("_id", entry.getKey()).append("cooldowns", cooldownsDoc),
                    new com.mongodb.client.model.ReplaceOptions().upsert(true));
        }

        // Batch-delete old docs
        batchDelete(coll, oldIds);
        msg(sender, "§a  ✓ kit_cooldowns: " + oldCount + " docs → " + playerCooldowns.size() + " player docs (expired purged)");
    }

    // ── Protected Blocks ──────────────────────────────────────────────────────
    // Old: one doc per block { world: "...", x: int, y: int, z: int }
    // New: one doc per chunk { _id: "world:chunkX:chunkZ", b: [[x,y,z], ...] }

    private void optimizeProtectedBlocks(MongoDatabase db, CommandSender sender) {
        MongoCollection<Document> coll = db.getCollection("protected_blocks");
        msg(sender, "§7protected_blocks — scanning...");

        Document sample = coll.find().first();
        if (sample == null) {
            msg(sender, "§e  ~ protected_blocks: empty, nothing to do.");
            return;
        }
        // New format has _id = "world:cx:cz" (contains colons)
        Object id = sample.get("_id");
        boolean alreadyNew = id instanceof String s && s.contains(":");
        if (alreadyNew) {
            msg(sender, "§e  ~ protected_blocks: already in chunk format, skipping.");
            return;
        }

        // Drop old compound unique index — this is what caused the duplicate key error
        dropIndex(coll, "world_1_x_1_y_1_z_1", sender);

        // Group old blocks by chunk, collecting their _id values for batched deletion
        Map<String, List<List<Integer>>> byChunk  = new LinkedHashMap<>();
        List<Object>                     oldIds   = new ArrayList<>();
        int oldCount = 0;

        for (Document doc : coll.find()) {
            Object  rawId = doc.get("_id");
            String  world = doc.getString("world");
            Integer x     = doc.getInteger("x");
            Integer y     = doc.getInteger("y");
            Integer z     = doc.getInteger("z");
            if (world == null || x == null || y == null || z == null) continue;
            String key = world + ":" + (x >> 4) + ":" + (z >> 4);
            byChunk.computeIfAbsent(key, k -> new ArrayList<>()).add(List.of(x, y, z));
            oldIds.add(rawId);
            oldCount++;
        }

        log.info("[DBOptimize] protected_blocks: " + oldCount + " block docs → " + byChunk.size() + " chunk docs");
        msg(sender, "§7  → writing " + byChunk.size() + " chunk docs...");

        for (Map.Entry<String, List<List<Integer>>> entry : byChunk.entrySet()) {
            coll.replaceOne(
                    eq("_id", entry.getKey()),
                    new Document("_id", entry.getKey()).append("b", entry.getValue()),
                    new com.mongodb.client.model.ReplaceOptions().upsert(true));
        }

        // Delete old single-block docs in batches to avoid socket timeout
        msg(sender, "§7  → removing " + oldIds.size() + " old block docs in batches...");
        batchDelete(coll, oldIds);

        msg(sender, "§a  ✓ protected_blocks: " + oldCount + " block docs → " + byChunk.size() + " chunk docs");
    }

    // ── Fruit Sync ────────────────────────────────────────────────────────────
    // Scans the players collection for all UUIDs. For every UUID that does NOT
    // have a document in devil_fruits, creates one: { _id: uuid, owned: [] }

    private void syncFruitDocs(MongoDatabase db, CommandSender sender) {
        MongoCollection<Document> players = db.getCollection("players");
        MongoCollection<Document> fruits  = db.getCollection("devil_fruits");

        // Drop legacy indexes that block new-format inserts
        dropIndex(fruits, "uuid_1_fruit_name_1", sender);

        // Collect all known player UUIDs
        Set<String> allUuids = new HashSet<>();
        for (Document doc : players.find()) {
            Object id = doc.get("_id");
            if (id instanceof String s) allUuids.add(s);
        }
        msg(sender, "§7Found " + allUuids.size() + " players in database.");

        // Collect existing devil_fruits UUIDs
        Set<String> existingFruitUuids = new HashSet<>();
        for (Document doc : fruits.find()) {
            Object id = doc.get("_id");
            if (id instanceof String s) existingFruitUuids.add(s);
        }

        // Find missing
        Set<String> missing = new HashSet<>(allUuids);
        missing.removeAll(existingFruitUuids);

        if (missing.isEmpty()) {
            msg(sender, "§a✓ All " + allUuids.size() + " players already have devil_fruits documents.");
            return;
        }

        msg(sender, "§e" + missing.size() + " players missing devil_fruits documents — creating...");

        // Batch insert missing documents
        List<Document> toInsert = new ArrayList<>();
        for (String uuid : missing) {
            toInsert.add(new Document("_id", uuid).append("owned", Collections.emptyList()));
        }
        fruits.insertMany(toInsert);

        msg(sender, "§a✓ Created " + missing.size() + " devil_fruits documents.");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void batchDelete(MongoCollection<Document> coll, List<Object> ids) {
        if (ids.isEmpty()) return;
        int batchSize = 500;
        int deleted = 0;
        for (int i = 0; i < ids.size(); i += batchSize) {
            List<Object> batch = ids.subList(i, Math.min(i + batchSize, ids.size()));
            coll.deleteMany(new Document("_id", new Document("$in", batch)));
            deleted += batch.size();
            if (deleted % 10000 == 0 || deleted == ids.size()) {
                log.info("[DBOptimize] batch-delete: " + deleted + " / " + ids.size() + " removed");
            }
        }
    }

    private void dropIndex(MongoCollection<Document> coll, String indexName, CommandSender sender) {
        try {
            coll.dropIndex(indexName);
            log.info("[DBOptimize] Dropped index: " + indexName);
        } catch (Exception e) {
            // Index may not exist (already dropped or never created) — that's fine
            log.info("[DBOptimize] Index not found (skipping drop): " + indexName);
        }
    }

    private void msg(CommandSender sender, String text) {
        log.info("[DBOptimize] " + text.replaceAll("§[0-9a-fklmnorA-FKLMNOR]", ""));
        sender.sendMessage("§7[DB Optimize] " + text);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            String prefix = args[0].toLowerCase();
            return Stream.of("optimize", "fruit_sync", "wipe_release", "backup", "restore")
                    .filter(s -> s.startsWith(prefix))
                    .toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("optimize"))     return List.of("mongo");
        if (args.length == 2 && args[0].equalsIgnoreCase("wipe_release")) return List.of("confirm");
        if (args.length == 2 && args[0].equalsIgnoreCase("restore")) {
            String prefix = args[1].toLowerCase();
            return knownBackupIds.stream()
                    .filter(s -> s.toLowerCase().startsWith(prefix))
                    .sorted()
                    .toList();
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("restore"))      return List.of("confirm");
        return Collections.emptyList();
    }
}
