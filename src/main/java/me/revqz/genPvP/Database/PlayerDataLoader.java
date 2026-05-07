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

        injectDefaults(uuid);

        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> load(uuid, name));
    }

    public void reloadOnlinePlayers() {
        var online = plugin.getServer().getOnlinePlayers();
        if (online.isEmpty()) return;

        for (var p : online) injectDefaults(p.getUniqueId());

        if (db == null) {
            plugin.getLogger().warning("[PlayerDataLoader] MongoDB unavailable — online players have default values.");
            return;
        }

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

            loadFruitData(uuid);

            if (isNewPlayer) {
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    Player player = plugin.getServer().getPlayer(uuid);
                    if (player == null || !player.isOnline()) return;
                    bankManager.addBalance(uuid, name, 50);
                    kitManager.giveStarterKitBypassing(player);
                    
                    rollManager.addRolls(uuid, me.revqz.genPvP.DevilFruits.FruitType.PARAMECIA, 1);
                });
            }

        } catch (Exception e) {
            plugin.getLogger().warning("[PlayerDataLoader] Failed to load data for " + uuid + ": " + e.getMessage());
            injectDefaults(uuid);
        }
    }

    private void loadReload(UUID uuid, String name) {
        try {
            MongoCollection<Document> players = db.getCollection("players");
            String uuidStr = uuid.toString();

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
            
        }

        try {
            me.revqz.genPvP.DevilFruits.FruitRollManager.RollLoadResult rollResult =
                    rollManager.loadRollData(db, uuid);
            rollManager.inject(uuid, rollResult.paramecia(), rollResult.logia(), rollResult.zoan());
        } catch (Exception e) {
            plugin.getLogger().warning("[PlayerDataLoader] Failed to load roll data for " + uuid + ": " + e.getMessage());
            
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
