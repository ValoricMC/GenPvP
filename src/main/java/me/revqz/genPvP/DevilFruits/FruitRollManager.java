package me.revqz.genPvP.DevilFruits;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.UpdateOptions;
import me.revqz.genPvP.Database.DatabaseManager;
import me.revqz.genPvP.GenPvP;
import org.bson.Document;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static com.mongodb.client.model.Filters.eq;

/**
 * Manages per-player roll tokens (one balance per {@link FruitType}).
 * <p>
 * MongoDB collection: {@code fruit_rolls}
 * Document schema:  {@code { _id: "uuid", paramecia: int, logia: int, zoan: int }}
 */
public class FruitRollManager implements Listener {

    private final GenPvP plugin;
    private final MongoDatabase db;
    private final boolean dbConnected;

    private final ConcurrentHashMap<UUID, EnumMap<FruitType, Integer>> rolls = new ConcurrentHashMap<>();
    private final ConcurrentHashMap.KeySetView<UUID, Boolean> loadedPlayers = ConcurrentHashMap.newKeySet();

    public FruitRollManager(GenPvP plugin, DatabaseManager dbManager) {
        this.plugin      = plugin;
        this.db          = dbManager.isMongoConnected() ? dbManager.getDatabase() : null;
        this.dbConnected = dbManager.isMongoConnected();
    }

    // ── Inject (used by PlayerDataLoader) ─────────────────────────────────────

    public void inject(UUID uuid, int paramecia, int logia, int zoan) {
        EnumMap<FruitType, Integer> map = new EnumMap<>(FruitType.class);
        map.put(FruitType.PARAMECIA, Math.max(0, paramecia));
        map.put(FruitType.LOGIA,     Math.max(0, logia));
        map.put(FruitType.ZOAN,      Math.max(0, zoan));
        rolls.put(uuid, map);
        loadedPlayers.add(uuid);
    }

    // ── Join / Quit ───────────────────────────────────────────────────────────

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        if (loadedPlayers.contains(uuid)) return;
        // Default to 0 rolls; real values will be injected by PlayerDataLoader
        inject(uuid, 0, 0, 0);
        if (!dbConnected) return;
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> loadFromDB(uuid));
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();

        // Synchronously save roll data BEFORE clearing memory
        if (dbConnected && db != null) {
            EnumMap<FruitType, Integer> map = rolls.get(uuid);
            if (map != null) {
                try {
                    db.getCollection("fruit_rolls").updateOne(eq("_id", uuid.toString()),
                            new Document("$set", new Document()
                                    .append("paramecia", map.getOrDefault(FruitType.PARAMECIA, 0))
                                    .append("logia", map.getOrDefault(FruitType.LOGIA, 0))
                                    .append("zoan", map.getOrDefault(FruitType.ZOAN, 0))),
                            new UpdateOptions().upsert(true));
                } catch (Exception e) {
                    plugin.getLogger().warning("[FruitRollManager] save-on-quit failed for " + uuid + ": " + e.getMessage());
                }
            }
        }

        rolls.remove(uuid);
        loadedPlayers.remove(uuid);
    }

    // ── API ───────────────────────────────────────────────────────────────────

    public int getRolls(UUID uuid, FruitType type) {
        EnumMap<FruitType, Integer> map = rolls.get(uuid);
        return map != null ? map.getOrDefault(type, 0) : 0;
    }

    /**
     * Adds roll tokens for a given type. Persists to MongoDB async.
     */
    public void addRolls(UUID uuid, FruitType type, int amount) {
        if (amount <= 0) return;
        EnumMap<FruitType, Integer> map = rolls.computeIfAbsent(uuid, k -> {
            EnumMap<FruitType, Integer> m = new EnumMap<>(FruitType.class);
            for (FruitType ft : FruitType.values()) m.put(ft, 0);
            return m;
        });
        map.merge(type, amount, Integer::sum);

        if (dbConnected) {
            String field = type.name().toLowerCase();
            int newVal = map.getOrDefault(type, 0);
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
                try {
                    db.getCollection("fruit_rolls").updateOne(
                            eq("_id", uuid.toString()),
                            new Document("$set", new Document(field, newVal)),
                            new UpdateOptions().upsert(true));
                } catch (Exception e) {
                    plugin.getLogger().warning("[FruitRollManager] addRolls failed: " + e.getMessage());
                }
            });
        }
    }

    /**
     * Attempts to consume one roll token.
     *
     * @return {@code true} if the player had ≥1 token and it was deducted.
     */
    public boolean consumeRoll(UUID uuid, FruitType type) {
        EnumMap<FruitType, Integer> map = rolls.get(uuid);
        if (map == null) return false;
        int current = map.getOrDefault(type, 0);
        if (current <= 0) return false;
        map.put(type, current - 1);

        if (dbConnected) {
            String field = type.name().toLowerCase();
            int newVal = current - 1;
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
                try {
                    db.getCollection("fruit_rolls").updateOne(
                            eq("_id", uuid.toString()),
                            new Document("$set", new Document(field, newVal)),
                            new UpdateOptions().upsert(true));
                } catch (Exception e) {
                    plugin.getLogger().warning("[FruitRollManager] consumeRoll failed: " + e.getMessage());
                }
            });
        }
        return true;
    }

    // ── DB load ───────────────────────────────────────────────────────────────

    /** Loads roll data from MongoDB. Called by PlayerDataLoader. */
    public RollLoadResult loadRollData(MongoDatabase database, UUID uuid) {
        int paramecia = 0, logia = 0, zoan = 0;
        try {
            Document doc = database.getCollection("fruit_rolls").find(eq("_id", uuid.toString())).first();
            if (doc != null) {
                paramecia = doc.getInteger("paramecia", 0);
                logia     = doc.getInteger("logia", 0);
                zoan      = doc.getInteger("zoan", 0);
            }
        } catch (Exception e) {
            // Handled by caller
        }
        return new RollLoadResult(paramecia, logia, zoan);
    }

    private void loadFromDB(UUID uuid) {
        try {
            if (db == null) return;
            RollLoadResult r = loadRollData(db, uuid);
            inject(uuid, r.paramecia(), r.logia(), r.zoan());
        } catch (Exception e) {
            plugin.getLogger().warning("[FruitRollManager] loadFromDB failed: " + e.getMessage());
        }
    }

    /**
     * Synchronously saves ALL in-memory roll data to MongoDB.
     * Called during server shutdown.
     */
    // ── Wipe ──────────────────────────────────────────────────────────────────

    public void wipeAllMemory() {
        rolls.clear();
        loadedPlayers.clear();
    }

    public void saveAll() {
        if (!dbConnected || db == null) return;
        try {
            MongoCollection<Document> coll = db.getCollection("fruit_rolls");
            for (var entry : rolls.entrySet()) {
                UUID uuid = entry.getKey();
                EnumMap<FruitType, Integer> map = entry.getValue();
                if (map == null) continue;
                coll.updateOne(eq("_id", uuid.toString()),
                        new Document("$set", new Document()
                                .append("paramecia", map.getOrDefault(FruitType.PARAMECIA, 0))
                                .append("logia", map.getOrDefault(FruitType.LOGIA, 0))
                                .append("zoan", map.getOrDefault(FruitType.ZOAN, 0))),
                        new com.mongodb.client.model.UpdateOptions().upsert(true));
            }
            plugin.getLogger().info("[FruitRollManager] Saved " + rolls.size() + " player roll records.");
        } catch (Exception e) {
            plugin.getLogger().warning("[FruitRollManager] saveAll failed: " + e.getMessage());
        }
    }

    public record RollLoadResult(int paramecia, int logia, int zoan) {}
}
