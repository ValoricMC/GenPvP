package me.revqz.genPvP.DevilFruits;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import me.revqz.genPvP.Database.DatabaseManager;
import me.revqz.genPvP.GenPvP;
import org.bson.Document;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import static com.mongodb.client.model.Filters.eq;

public class DevilFruitManager implements Listener {

    private final GenPvP plugin;
    private final MongoDatabase db;
    private final boolean dbConnected;

    private final ConcurrentHashMap<UUID, Set<String>> ownedFruits = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, String> equippedFruit = new ConcurrentHashMap<>();
    private final Set<UUID> blacklisted = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<UUID, Long> fruitDisabledUntil = new ConcurrentHashMap<>();
    private final Set<UUID> loadedPlayers = ConcurrentHashMap.newKeySet();

    // Fruits disabled server-wide by an OP via /fruit disable <key>
    private final Set<String> globallyDisabledFruits = ConcurrentHashMap.newKeySet();

    private volatile Consumer<UUID> onEquipChange;

    public DevilFruitManager(GenPvP plugin, DatabaseManager dbManager) {
        this.plugin      = plugin;
        this.db          = dbManager.isMongoConnected() ? dbManager.getDatabase() : null;
        this.dbConnected = dbManager.isMongoConnected();
    }

    public void setOnEquipChange(Consumer<UUID> callback) {
        this.onEquipChange = callback;
    }

    private void fireEquipChange(UUID uuid) {
        Consumer<UUID> cb = onEquipChange;
        if (cb == null) return;
        if (plugin.getServer().isPrimaryThread()) {
            cb.accept(uuid);
        } else {
            plugin.getServer().getScheduler().runTask(plugin, () -> cb.accept(uuid));
        }
    }

    public void inject(UUID uuid, Set<String> owned, String equipped, boolean isBlacklisted) {
        // MERGE instead of replace — avoids race condition where giveFruit adds
        // a fruit to the old set, then a late inject call replaces it.
        Set<String> existing = ownedFruits.get(uuid);
        if (existing != null) {
            existing.addAll(owned);
        } else {
            Set<String> newSet = ConcurrentHashMap.newKeySet();
            newSet.addAll(owned);
            ownedFruits.put(uuid, newSet);
        }
        if (equipped != null) {
            equippedFruit.put(uuid, equipped);
        }
        if (isBlacklisted) {
            blacklisted.add(uuid);
        }
        loadedPlayers.add(uuid);
        fireEquipChange(uuid);
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        if (loadedPlayers.contains(uuid)) return;
        inject(uuid, Collections.emptySet(), null, false);
        if (!dbConnected) return;
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> loadFromDB(uuid));
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();

        // Synchronously save this player's fruit data BEFORE clearing memory.
        // Async writes from giveFruit may still be queued, so this guarantees
        // the current in-memory state reaches the database.
        if (dbConnected && db != null) {
            Set<String> owned = ownedFruits.get(uuid);
            if (owned != null && !owned.isEmpty()) {
                try {
                    MongoCollection<Document> coll = db.getCollection("devil_fruits");
                    List<String> ownedList = new java.util.ArrayList<>(owned);
                    String eq = equippedFruit.get(uuid);
                    Document setDoc = new Document("owned", ownedList);
                    if (eq != null) setDoc.append("equipped", eq);
                    coll.updateOne(eq("_id", uuid.toString()),
                            new Document("$set", setDoc),
                            new com.mongodb.client.model.UpdateOptions().upsert(true));
                } catch (Exception e) {
                    plugin.getLogger().warning("[DevilFruitManager] save-on-quit failed for " + uuid + ": " + e.getMessage());
                }
            }
        }

        ownedFruits.remove(uuid);
        equippedFruit.remove(uuid);
        blacklisted.remove(uuid);
        fruitDisabledUntil.remove(uuid);
        loadedPlayers.remove(uuid);
    }

    //api
    public boolean isLoaded(UUID uuid) { return loadedPlayers.contains(uuid); }
    public boolean isBlacklisted(UUID uuid) { return blacklisted.contains(uuid); }

    public void disableFruit(UUID uuid, long durationMs) {
        fruitDisabledUntil.put(uuid, System.currentTimeMillis() + durationMs);
    }

    public boolean isFruitDisabled(UUID uuid) {
        Long expiry = fruitDisabledUntil.get(uuid);
        if (expiry == null) return false;
        if (System.currentTimeMillis() >= expiry) {
            fruitDisabledUntil.remove(uuid);
            return false;
        }
        return true;
    }

    // ── Global fruit disable (OP command) ─────────────────────────────────────

    /** Disables the fruit ability server-wide. Returns false if already disabled. */
    public boolean disableFruitKey(String key) {
        return globallyDisabledFruits.add(key.toLowerCase());
    }

    /** Re-enables the fruit ability server-wide. Returns false if it wasn't disabled. */
    public boolean enableFruitKey(String key) {
        return globallyDisabledFruits.remove(key.toLowerCase());
    }

    public boolean isFruitKeyDisabled(String key) {
        return globallyDisabledFruits.contains(key.toLowerCase());
    }

    public Set<String> getDisabledFruitKeys() {
        return Collections.unmodifiableSet(globallyDisabledFruits);
    }

    public Set<String> getOwnedFruits(UUID uuid) {
        Set<String> owned = ownedFruits.get(uuid);
        return owned != null ? Collections.unmodifiableSet(owned) : Collections.emptySet();
    }

    public String getEquippedFruit(UUID uuid) { return equippedFruit.get(uuid); }

    // storage format
    //   { _id: "uuid", owned: ["mera_mera", "magu_magu"], equipped: "magu_magu" }
    // fruit_blacklist remains separate: { _id: "uuid" }
    public boolean giveFruit(UUID uuid, DevilFruit fruit) {
        Set<String> owned = ownedFruits.computeIfAbsent(uuid, k -> ConcurrentHashMap.newKeySet());
        if (!owned.add(fruit.getKey())) return false;

        if (dbConnected) {
            final String key = fruit.getKey();
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
                try {
                    db.getCollection("devil_fruits").updateOne(
                            eq("_id", uuid.toString()),
                            new Document("$addToSet", new Document("owned", key)),
                            new com.mongodb.client.model.UpdateOptions().upsert(true));
                } catch (Exception e) {
                    plugin.getLogger().warning("[DevilFruitManager] giveFruit failed: " + e.getMessage());
                }
            });
        }
        return true;
    }

    public boolean removeFruit(UUID uuid, DevilFruit fruit) {
        Set<String> owned = ownedFruits.get(uuid);
        if (owned == null || !owned.remove(fruit.getKey())) return false;
        boolean wasEquipped = fruit.getKey().equals(equippedFruit.remove(uuid));
        if (wasEquipped) fireEquipChange(uuid);

        if (dbConnected) {
            final String key = fruit.getKey();
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
                try {
                    MongoCollection<Document> coll = db.getCollection("devil_fruits");
                    Document update = new Document("$pull", new Document("owned", key));
                    if (wasEquipped) update.append("$unset", new Document("equipped", ""));
                    coll.updateOne(eq("_id", uuid.toString()), update);
                } catch (Exception e) {
                    plugin.getLogger().warning("[DevilFruitManager] removeFruit failed: " + e.getMessage());
                }
            });
        }
        return true;
    }

    /**
     * sets the equipped fruit directly by key,
     * used by GUI
     */
    public void setEquipped(UUID uuid, String key) {
        equippedFruit.put(uuid, key);
        fireEquipChange(uuid);
        if (dbConnected) {
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
                try {
                    db.getCollection("devil_fruits").updateOne(
                            eq("_id", uuid.toString()),
                            new Document("$set", new Document("equipped", key)),
                            new com.mongodb.client.model.UpdateOptions().upsert(true));
                } catch (Exception e) {
                    plugin.getLogger().warning("[DevilFruitManager] setEquipped failed: " + e.getMessage());
                }
            });
        }
    }

    /**
     * Gives a fruit to an offline player directly via MongoDB.
     * If the player is online their in-memory state is NOT updated; only use this when offline.
     * Callback fires on the main thread: true = success, false = already owned.
     */
    public void giveOffline(UUID uuid, DevilFruit fruit, Consumer<Boolean> callback) {
        if (!dbConnected) { plugin.getServer().getScheduler().runTask(plugin, () -> callback.accept(false)); return; }
        final String key = fruit.getKey();
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            boolean success = false;
            try {
                com.mongodb.client.result.UpdateResult r = db.getCollection("devil_fruits").updateOne(
                        eq("_id", uuid.toString()),
                        new Document("$addToSet", new Document("owned", key)),
                        new com.mongodb.client.model.UpdateOptions().upsert(true));
                success = r.getModifiedCount() > 0 || r.getUpsertedId() != null;
            } catch (Exception e) {
                plugin.getLogger().warning("[DevilFruitManager] giveOffline failed: " + e.getMessage());
            }
            final boolean result = success;
            plugin.getServer().getScheduler().runTask(plugin, () -> callback.accept(result));
        });
    }

    /**
     * Removes a fruit from an offline player directly via MongoDB.
     * If the player is online their in-memory state is NOT updated; only use this when offline.
     * Callback fires on the main thread: true = success, false = not owned.
     */
    public void removeOffline(UUID uuid, DevilFruit fruit, Consumer<Boolean> callback) {
        if (!dbConnected) { plugin.getServer().getScheduler().runTask(plugin, () -> callback.accept(false)); return; }
        final String key = fruit.getKey();
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            boolean success = false;
            try {
                MongoCollection<Document> coll = db.getCollection("devil_fruits");
                com.mongodb.client.result.UpdateResult r = coll.updateOne(
                        new Document("_id", uuid.toString()).append("owned", key),
                        new Document("$pull", new Document("owned", key)));
                success = r.getModifiedCount() > 0;
                if (success) {
                    coll.updateOne(
                            new Document("_id", uuid.toString()).append("equipped", key),
                            new Document("$unset", new Document("equipped", "")));
                }
            } catch (Exception e) {
                plugin.getLogger().warning("[DevilFruitManager] removeOffline failed: " + e.getMessage());
            }
            final boolean result = success;
            plugin.getServer().getScheduler().runTask(plugin, () -> callback.accept(result));
        });
    }

    public boolean equipFruit(UUID uuid, DevilFruit fruit) {
        Set<String> owned = ownedFruits.get(uuid);
        if (owned == null || !owned.contains(fruit.getKey())) return false;

        equippedFruit.put(uuid, fruit.getKey());
        fireEquipChange(uuid);

        if (dbConnected) {
            final String key = fruit.getKey();
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
                try {
                    db.getCollection("devil_fruits").updateOne(
                            eq("_id", uuid.toString()),
                            new Document("$set", new Document("equipped", key)));
                } catch (Exception e) {
                    plugin.getLogger().warning("[DevilFruitManager] equipFruit failed: " + e.getMessage());
                }
            });
        }
        return true;
    }

    /**
     * Unequips the currently equipped fruit. The hotbar slot will revert
     * to the "no fruit" paper item via the equip-change callback.
     */
    public void unequip(UUID uuid) {
        equippedFruit.remove(uuid);
        fireEquipChange(uuid);
        if (dbConnected) {
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
                try {
                    db.getCollection("devil_fruits").updateOne(
                            eq("_id", uuid.toString()),
                            new Document("$unset", new Document("equipped", "")));
                } catch (Exception e) {
                    plugin.getLogger().warning("[DevilFruitManager] unequip failed: " + e.getMessage());
                }
            });
        }
    }

    public boolean toggleBlacklist(UUID uuid) {
        boolean nowBlacklisted;
        if (blacklisted.contains(uuid)) {
            blacklisted.remove(uuid);
            nowBlacklisted = false;
        } else {
            blacklisted.add(uuid);
            nowBlacklisted = true;
        }

        if (dbConnected) {
            final boolean bl = nowBlacklisted;
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
                try {
                    MongoCollection<Document> coll = db.getCollection("fruit_blacklist");
                    if (bl) {
                        try { coll.insertOne(new Document("_id", uuid.toString())); }
                        catch (Exception dup) { /* already blacklisted */ }
                    } else {
                        coll.deleteOne(eq("_id", uuid.toString()));
                    }
                } catch (Exception e) {
                    plugin.getLogger().warning("[DevilFruitManager] toggleBlacklist failed: " + e.getMessage());
                }
            });
        }
        return nowBlacklisted;
    }

    // database load balancer
    public void loadFromDB(UUID uuid) {
        try {
            FruitLoadResult r = loadFruitData(db, uuid);
            inject(uuid, r.owned(), r.equipped(), r.blacklisted());
        } catch (Exception e) {
            plugin.getLogger().warning("[DevilFruitManager] loadFromDB failed for " + uuid + ": " + e.getMessage());
        }
    }

    /** Loads fruit data from DB — used by PlayerDataLoader. */
    public FruitLoadResult loadFruitData(MongoDatabase database, UUID uuid) {
        Set<String> owned = new HashSet<>();
        String equipped = null;

        MongoCollection<Document> coll = database.getCollection("devil_fruits");
        Document doc = coll.find(eq("_id", uuid.toString())).first();
        if (doc != null) {
            List<?> ownedRaw = doc.get("owned", List.class);
            if (ownedRaw != null) {
                for (Object o : ownedRaw) if (o instanceof String s) owned.add(s);
            }
            equipped = doc.getString("equipped");
        } else {
            // First-time player — create the document immediately
            coll.insertOne(new Document("_id", uuid.toString())
                    .append("owned", Collections.emptyList()));
        }

        boolean bl = database.getCollection("fruit_blacklist")
                .find(eq("_id", uuid.toString())).first() != null;

        return new FruitLoadResult(owned, equipped, bl);
    }

    /**
     * Synchronously saves ALL in-memory fruit data to MongoDB.
     * Called during server shutdown to ensure no data is lost when
     * async tasks are cancelled by the scheduler.
     */
    // ── Wipe ──────────────────────────────────────────────────────────────────

    public void wipeAllMemory() {
        ownedFruits.clear();
        equippedFruit.clear();
        fruitDisabledUntil.clear();
        loadedPlayers.clear();
    }

    public void saveAll() {
        if (!dbConnected || db == null) return;
        try {
            MongoCollection<Document> coll = db.getCollection("devil_fruits");
            for (var entry : ownedFruits.entrySet()) {
                UUID uuid = entry.getKey();
                Set<String> owned = entry.getValue();
                if (owned == null) continue;
                String uuidStr = uuid.toString();
                List<String> ownedList = new ArrayList<>(owned);
                String equipped = equippedFruit.get(uuid);

                Document update = new Document("$set", new Document("owned", ownedList));
                if (equipped != null) {
                    update.get("$set", Document.class).append("equipped", equipped);
                }
                coll.updateOne(eq("_id", uuidStr), update,
                        new com.mongodb.client.model.UpdateOptions().upsert(true));
            }
            plugin.getLogger().info("[DevilFruitManager] Saved " + ownedFruits.size() + " player fruit records.");
        } catch (Exception e) {
            plugin.getLogger().warning("[DevilFruitManager] saveAll failed: " + e.getMessage());
        }
    }

    public record FruitLoadResult(Set<String> owned, String equipped, boolean blacklisted) {}
}
