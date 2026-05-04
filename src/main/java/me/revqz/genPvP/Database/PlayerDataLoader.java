package me.revqz.genPvP.Database;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.result.UpdateResult;
import me.revqz.genPvP.Bank.BankManager;
import me.revqz.genPvP.DevilFruits.DevilFruitManager;
import me.revqz.genPvP.GenPvP;
import me.revqz.genPvP.Kit.KitManager;
import me.revqz.genPvP.Prestige.PrestigeManager;
import me.revqz.genPvP.Stats.StatsManager;
import org.bson.Document;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.util.Collections;
import java.util.UUID;

import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Updates.combine;
import static com.mongodb.client.model.Updates.set;
import static com.mongodb.client.model.Updates.setOnInsert;

/**
 * Loads ALL per-player data in one async task on join:
 *
 *   1. One find() on the merged "players" collection — covers 100% of
 *      the data that BankManager, PrestigeManager, and StatsManager need.
 *   2. Upsert with $setOnInsert for new players — only one round trip.
 *   3. Injects results directly into each manager's in-memory cache.
 *
 * Net result: 1 async task + 1-2 round trips.
 */
public class PlayerDataLoader implements Listener {

    private final GenPvP             plugin;
    private final MongoDatabase      db;
    private final BankManager        bankManager;
    private final PrestigeManager    prestigeManager;
    private final StatsManager       statsManager;
    private final DevilFruitManager  fruitManager;
    private final KitManager         kitManager;
    private final me.revqz.genPvP.DevilFruits.FruitRollManager rollManager;

    public PlayerDataLoader(GenPvP plugin, DatabaseManager dbManager,
                            BankManager bankManager,
                            PrestigeManager prestigeManager,
                            StatsManager statsManager,
                            DevilFruitManager fruitManager,
                            KitManager kitManager,
                            me.revqz.genPvP.DevilFruits.FruitRollManager rollManager) {
        this.plugin          = plugin;
        this.db              = dbManager.isMongoConnected() ? dbManager.getDatabase() : null;
        this.bankManager     = bankManager;
        this.prestigeManager = prestigeManager;
        this.statsManager    = statsManager;
        this.fruitManager    = fruitManager;
        this.kitManager      = kitManager;
        this.rollManager     = rollManager;
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onJoin(PlayerJoinEvent event) {
        UUID   uuid = event.getPlayer().getUniqueId();
        String name = event.getPlayer().getName();

        if (db == null) return;

        // Synchronous: block individual managers from spawning their own tasks
        injectDefaults(uuid);

        // Async: load real values and overwrite
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> load(uuid, name));
    }

    /**
     * Called after a PlugMan reload to re-load data for every player that is
     * already online.  PlugMan does NOT fire PlayerJoinEvent for existing
     * players, so without this their caches would stay empty (0 balance, etc.).
     */
    public void reloadOnlinePlayers() {
        var online = plugin.getServer().getOnlinePlayers();
        if (online.isEmpty()) return;

        // Inject safe defaults immediately so placeholders never return null
        for (var p : online) injectDefaults(p.getUniqueId());

        if (db == null) {
            plugin.getLogger().warning("[PlayerDataLoader] MongoDB unavailable — online players have default values.");
            return;
        }

        // Async: load real values from MongoDB and overwrite the defaults
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            int count = 0;
            for (var p : online) {
                try {
                    loadReload(p.getUniqueId(), p.getName());
                    count++;
                } catch (Exception e) {
                    plugin.getLogger().warning("[PlayerDataLoader] Reload-load failed for "
                            + p.getName() + ": " + e.getMessage());
                }
            }
            plugin.getLogger().info("[PlayerDataLoader] Reloaded data for " + count + " online player(s).");
        });
    }

    private void load(UUID uuid, String name) {
        try {
            MongoCollection<Document> players = db.getCollection("players");
            String uuidStr = uuid.toString();

            // Upsert: create player doc if it doesn't exist (defaults via $setOnInsert).
            // getUpsertedId() != null means this was an INSERT — i.e., a brand-new player.
            UpdateResult upsertResult = players.updateOne(
                    eq("_id", uuidStr),
                    combine(
                            set("player_name", name),
                            setOnInsert("balance", 0.0),
                            setOnInsert("disabled", false),
                            setOnInsert("shards", 0.0),
                            setOnInsert("gold", 0.0),
                            setOnInsert("prestige", 0),
                            setOnInsert("level", 0),
                            setOnInsert("xp", 0.0),
                            setOnInsert("prestige_blacklisted", false),
                            setOnInsert("level_blacklisted", false),
                            setOnInsert("xp_blacklisted", false),
                            setOnInsert("receive_payments", true),
                            setOnInsert("kills", 0),
                            setOnInsert("deaths", 0)
                    ),
                    new UpdateOptions().upsert(true));

            boolean isNewPlayer = upsertResult.getUpsertedId() != null;

            // Now read the full document
            Document doc = players.find(eq("_id", uuidStr)).first();
            if (doc != null) {
                try {
                    injectAll(uuid, doc);
                } catch (Exception e) {
                    plugin.getLogger().warning("[PlayerDataLoader] injectAll failed for " + uuid + ": " + e.getMessage());
                    injectDefaults(uuid);
                }
            } else {
                injectDefaults(uuid);
            }

            // Load fruit data independently — never skip this even if injectAll failed
            loadFruitData(uuid);

            // First-join reward: give starter kit + 50 money on the main thread,
            // AFTER injectAll so the bank balance is already initialised in memory.
            if (isNewPlayer) {
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    Player player = plugin.getServer().getPlayer(uuid);
                    if (player == null || !player.isOnline()) return;
                    bankManager.addBalance(uuid, name, 50);
                    kitManager.giveStarterKitBypassing(player);
                    plugin.getServer().dispatchCommand(plugin.getServer().getConsoleSender(), "fruit roll_give " + name + " paramecia 1");
                });
            }

        } catch (Exception e) {
            plugin.getLogger().warning("[PlayerDataLoader] Failed to load data for " + uuid + ": " + e.getMessage());
            injectDefaults(uuid);
        }
    }

    /**
     * Same as {@link #load} but skips the first-join reward. Used during
     * PlugMan reloads where players are already online.
     */
    private void loadReload(UUID uuid, String name) {
        try {
            MongoCollection<Document> players = db.getCollection("players");
            String uuidStr = uuid.toString();

            // Read the full document (no upsert needed — player already exists)
            Document doc = players.find(eq("_id", uuidStr)).first();
            if (doc != null) {
                try {
                    injectAll(uuid, doc);
                } catch (Exception e) {
                    plugin.getLogger().warning("[PlayerDataLoader] injectAll failed for " + uuid + ": " + e.getMessage());
                    injectDefaults(uuid);
                }
            } else {
                injectDefaults(uuid);
            }

            // Load fruit data independently
            loadFruitData(uuid);

        } catch (Exception e) {
            plugin.getLogger().warning("[PlayerDataLoader] Reload-load failed for " + uuid + ": " + e.getMessage());
            injectDefaults(uuid);
        }
    }

    private void loadFruitData(UUID uuid) {
        try {
            DevilFruitManager.FruitLoadResult result = fruitManager.loadFruitData(db, uuid);
            fruitManager.inject(uuid, result.owned(), result.equipped(), result.blacklisted());
        } catch (Exception e) {
            plugin.getLogger().warning("[PlayerDataLoader] Failed to load fruit data for " + uuid + ": " + e.getMessage());
            // Do NOT overwrite with empty — leave whatever was already loaded intact
        }

        // Load roll tokens in the same async batch
        try {
            me.revqz.genPvP.DevilFruits.FruitRollManager.RollLoadResult rollResult =
                    rollManager.loadRollData(db, uuid);
            rollManager.inject(uuid, rollResult.paramecia(), rollResult.logia(), rollResult.zoan());
        } catch (Exception e) {
            plugin.getLogger().warning("[PlayerDataLoader] Failed to load roll data for " + uuid + ": " + e.getMessage());
            // Do NOT overwrite with 0 — leave whatever was already loaded intact
        }
    }

    private void injectAll(UUID uuid, Document doc) {
        Double bal = doc.getDouble("balance");
        Double sh  = doc.getDouble("shards");
        Double go  = doc.getDouble("gold");
        Double xp  = doc.getDouble("xp");
        bankManager.inject(uuid,
                bal != null ? bal : 0.0,
                sh  != null ? sh  : 0.0,
                go  != null ? go  : 0.0,
                doc.getBoolean("disabled", false),
                doc.getBoolean("receive_payments", true));

        prestigeManager.inject(uuid,
                doc.getInteger("prestige", 0),
                doc.getInteger("level", 0),
                xp != null ? xp : 0.0,
                doc.getBoolean("prestige_blacklisted", false),
                doc.getBoolean("level_blacklisted", false),
                doc.getBoolean("xp_blacklisted", false));

        statsManager.inject(uuid,
                doc.getInteger("kills", 0),
                doc.getInteger("deaths", 0));
    }

    private void injectDefaults(UUID uuid) {
        bankManager.inject(uuid, 0, 0, 0, false, true);
        prestigeManager.inject(uuid, 0, 0, 0, false, false, false);
        statsManager.inject(uuid, 0, 0);
        fruitManager.inject(uuid, Collections.emptySet(), null, false);
        rollManager.inject(uuid, 0, 0, 0);
    }
}
