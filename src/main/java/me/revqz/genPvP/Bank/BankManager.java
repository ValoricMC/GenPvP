package me.revqz.genPvP.Bank;

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
import org.bukkit.scheduler.BukkitRunnable;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.mongodb.client.model.Filters.eq;

public class BankManager implements Listener {

    private final GenPvP plugin;
    private final MongoDatabase db;
    private final boolean dbConnected;

    private final ConcurrentHashMap<UUID, Double> balances = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Double> shards   = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Double> gold     = new ConcurrentHashMap<>();
    private final Set<UUID> disabledAccounts        = ConcurrentHashMap.newKeySet();
    private final Set<UUID> receivePaymentsDisabled = ConcurrentHashMap.newKeySet();
    private final Set<UUID> loadedPlayers           = ConcurrentHashMap.newKeySet();

    private final Map<Integer, LeaderboardEntry> moneyTopCache  = new ConcurrentHashMap<>();
    private final Map<Integer, LeaderboardEntry> shardsTopCache = new ConcurrentHashMap<>();
    private final Map<Integer, LeaderboardEntry> goldTopCache   = new ConcurrentHashMap<>();

    public BankManager(GenPvP plugin, DatabaseManager dbManager) {
        this.plugin      = plugin;
        this.db          = dbManager.isMongoConnected() ? dbManager.getDatabase() : null;
        this.dbConnected = dbManager.isMongoConnected();

        for (int i = 1; i <= 10; i++) {
            moneyTopCache.put(i,  new LeaderboardEntry("None", 0));
            shardsTopCache.put(i, new LeaderboardEntry("None", 0));
            goldTopCache.put(i,   new LeaderboardEntry("None", 0));
        }
        startLeaderboardTask();
    }

    public void inject(UUID uuid, double balance, double shardsVal, double goldVal,
                       boolean disabled, boolean receivePayments) {
        balances.put(uuid, balance);
        shards.put(uuid, shardsVal);
        gold.put(uuid, goldVal);
        if (disabled) disabledAccounts.add(uuid);
        if (!receivePayments) receivePaymentsDisabled.add(uuid);
        loadedPlayers.add(uuid);
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        UUID   uuid = event.getPlayer().getUniqueId();
        String name = event.getPlayer().getName();

        if (loadedPlayers.contains(uuid)) return;

        if (!dbConnected) {
            inject(uuid, 0, 0, 0, false, true);
            return;
        }

        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            PlayerData data = loadOrCreate(uuid, name);
            inject(uuid, data.balance(), data.shards(), data.gold(),
                   data.disabled(), data.receivePayments());
        });
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        balances.remove(uuid);
        shards.remove(uuid);
        gold.remove(uuid);
        disabledAccounts.remove(uuid);
        receivePaymentsDisabled.remove(uuid);
        loadedPlayers.remove(uuid);
    }

    public boolean isDisabled(UUID uuid) { return disabledAccounts.contains(uuid); }
    public boolean isLoaded(UUID uuid)   { return loadedPlayers.contains(uuid); }

    public double getBalance(UUID uuid) { return balances.getOrDefault(uuid, 0.0); }

    public boolean addBalance(UUID uuid, String playerName, double amount) {
        if (disabledAccounts.contains(uuid)) return false;
        double next = balances.merge(uuid, Math.max(0, amount), Double::sum);
        if (dbConnected) asyncSave(uuid, playerName, "balance", next);
        return true;
    }

    public boolean removeBalance(UUID uuid, String playerName, double amount) {
        if (disabledAccounts.contains(uuid)) return false;
        AtomicBoolean success = new AtomicBoolean(false);
        
        balances.compute(uuid, (k, cur) -> {
            double c = cur != null ? cur : 0.0;
            if (c >= amount) { success.set(true); return c - amount; }
            return cur;
        });
        if (success.get() && dbConnected)
            asyncSave(uuid, playerName, "balance", balances.getOrDefault(uuid, 0.0));
        return success.get();
    }

    public boolean setBalance(UUID uuid, String playerName, double amount) {
        if (disabledAccounts.contains(uuid)) return false;
        double clamped = Math.max(0, amount);
        balances.put(uuid, clamped);
        if (dbConnected) asyncSave(uuid, playerName, "balance", clamped);
        return true;
    }

    public void adminAdd(UUID uuid, String playerName, double amount) {
        balances.merge(uuid, Math.max(0, amount), Double::sum);
        if (dbConnected) adminAddDB(uuid, playerName, "balance", amount);
    }

    public void adminRemove(UUID uuid, String playerName, double amount) {
        double clamped = Math.max(0, balances.getOrDefault(uuid, 0.0) - amount);
        balances.put(uuid, clamped);
        if (dbConnected) asyncSave(uuid, playerName, "balance", clamped);
    }

    public void adminWipe(UUID uuid, String playerName) {
        balances.put(uuid, 0.0);
        if (dbConnected) asyncSave(uuid, playerName, "balance", 0.0);
    }

    public double getShards(UUID uuid) { return shards.getOrDefault(uuid, 0.0); }

    public boolean addShards(UUID uuid, String playerName, double amount) {
        if (disabledAccounts.contains(uuid)) return false;
        double next = shards.merge(uuid, Math.max(0, amount), Double::sum);
        if (dbConnected) asyncSave(uuid, playerName, "shards", next);
        return true;
    }

    public boolean removeShards(UUID uuid, String playerName, double amount) {
        if (disabledAccounts.contains(uuid)) return false;
        AtomicBoolean success = new AtomicBoolean(false);
        shards.compute(uuid, (k, cur) -> {
            double c = cur != null ? cur : 0.0;
            if (c >= amount) { success.set(true); return c - amount; }
            return cur;
        });
        if (success.get() && dbConnected)
            asyncSave(uuid, playerName, "shards", shards.getOrDefault(uuid, 0.0));
        return success.get();
    }

    public boolean setShards(UUID uuid, String playerName, double amount) {
        if (disabledAccounts.contains(uuid)) return false;
        double clamped = Math.max(0, amount);
        shards.put(uuid, clamped);
        if (dbConnected) asyncSave(uuid, playerName, "shards", clamped);
        return true;
    }

    public void adminAddShards(UUID uuid, String playerName, double amount) {
        shards.merge(uuid, Math.max(0, amount), Double::sum);
        if (dbConnected) adminAddDB(uuid, playerName, "shards", amount);
    }

    public void adminRemoveShards(UUID uuid, String playerName, double amount) {
        double clamped = Math.max(0, shards.getOrDefault(uuid, 0.0) - amount);
        shards.put(uuid, clamped);
        if (dbConnected) asyncSave(uuid, playerName, "shards", clamped);
    }

    public void adminWipeShards(UUID uuid, String playerName) {
        shards.put(uuid, 0.0);
        if (dbConnected) asyncSave(uuid, playerName, "shards", 0.0);
    }

    public double getGold(UUID uuid) { return gold.getOrDefault(uuid, 0.0); }

    public boolean addGold(UUID uuid, String playerName, double amount) {
        if (disabledAccounts.contains(uuid)) return false;
        double next = gold.merge(uuid, Math.max(0, amount), Double::sum);
        if (dbConnected) asyncSave(uuid, playerName, "gold", next);
        return true;
    }

    public boolean removeGold(UUID uuid, String playerName, double amount) {
        if (disabledAccounts.contains(uuid)) return false;
        AtomicBoolean success = new AtomicBoolean(false);
        gold.compute(uuid, (k, cur) -> {
            double c = cur != null ? cur : 0.0;
            if (c >= amount) { success.set(true); return c - amount; }
            return cur;
        });
        if (success.get() && dbConnected)
            asyncSave(uuid, playerName, "gold", gold.getOrDefault(uuid, 0.0));
        return success.get();
    }

    public boolean setGold(UUID uuid, String playerName, double amount) {
        if (disabledAccounts.contains(uuid)) return false;
        double clamped = Math.max(0, amount);
        gold.put(uuid, clamped);
        if (dbConnected) asyncSave(uuid, playerName, "gold", clamped);
        return true;
    }

    public void adminAddGold(UUID uuid, String playerName, double amount) {
        gold.merge(uuid, Math.max(0, amount), Double::sum);
        if (dbConnected) adminAddDB(uuid, playerName, "gold", amount);
    }

    public void adminRemoveGold(UUID uuid, String playerName, double amount) {
        double clamped = Math.max(0, gold.getOrDefault(uuid, 0.0) - amount);
        gold.put(uuid, clamped);
        if (dbConnected) asyncSave(uuid, playerName, "gold", clamped);
    }

    public void adminWipeGold(UUID uuid, String playerName) {
        gold.put(uuid, 0.0);
        if (dbConnected) asyncSave(uuid, playerName, "gold", 0.0);
    }

    public boolean toggleDisabled(UUID uuid, String playerName) {
        boolean disabled;
        if (disabledAccounts.contains(uuid)) {
            disabledAccounts.remove(uuid);
            disabled = false;
        } else {
            disabledAccounts.add(uuid);
            disabled = true;
        }
        if (!dbConnected) return disabled;
        final boolean d = disabled;
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                MongoCollection<Document> players = db.getCollection("players");
                players.updateOne(
                        eq("_id", uuid.toString()),
                        new Document("$set", new Document("disabled", d).append("player_name", playerName)),
                        new UpdateOptions().upsert(true));
            } catch (Exception e) {
                plugin.getLogger().warning("[BankManager] toggleDisabled failed: " + e.getMessage());
            }
        });
        return disabled;
    }

    public boolean isReceivePaymentsDisabled(UUID uuid) {
        return receivePaymentsDisabled.contains(uuid);
    }

    public boolean toggleReceivePayments(UUID uuid) {
        boolean nowDisabled;
        if (receivePaymentsDisabled.contains(uuid)) {
            receivePaymentsDisabled.remove(uuid);
            nowDisabled = false;
        } else {
            receivePaymentsDisabled.add(uuid);
            nowDisabled = true;
        }
        if (!dbConnected) return nowDisabled;
        final boolean rp = !nowDisabled;
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                db.getCollection("players").updateOne(
                        eq("_id", uuid.toString()),
                        new Document("$set", new Document("receive_payments", rp)));
            } catch (Exception e) {
                plugin.getLogger().warning("[BankManager] toggleReceivePayments failed: " + e.getMessage());
            }
        });
        return nowDisabled;
    }

    private record PlayerData(double balance, boolean disabled, double shards, double gold,
                              boolean receivePayments) {}

    private PlayerData loadOrCreate(UUID uuid, String playerName) {
        try {
            MongoCollection<Document> players = db.getCollection("players");
            String uuidStr = uuid.toString();

            players.updateOne(
                    eq("_id", uuidStr),
                    new Document("$set", new Document("player_name", playerName))
                            .append("$setOnInsert", new Document("balance", 0.0)
                                    .append("disabled", false)
                                    .append("shards", 0.0)
                                    .append("gold", 0.0)
                                    .append("receive_payments", true)
                                    .append("prestige", 0).append("level", 0).append("xp", 0.0)
                                    .append("prestige_blacklisted", false)
                                    .append("level_blacklisted", false)
                                    .append("xp_blacklisted", false)
                                    .append("kills", 0).append("deaths", 0)),
                    new UpdateOptions().upsert(true));

            Document doc = players.find(eq("_id", uuidStr)).first();
            if (doc != null) {
                
                Double bal    = doc.getDouble("balance");
                Double sh     = doc.getDouble("shards");
                Double go     = doc.getDouble("gold");
                return new PlayerData(
                        bal != null ? bal : 0.0,
                        doc.getBoolean("disabled", false),
                        sh  != null ? sh  : 0.0,
                        go  != null ? go  : 0.0,
                        doc.getBoolean("receive_payments", true));
            }
        } catch (Exception e) {
            plugin.getLogger().warning("[BankManager] Failed to load data for " + uuid + ": " + e.getMessage());
        }
        return new PlayerData(0.0, false, 0.0, 0.0, true);
    }

    private void asyncSave(UUID uuid, String playerName, String field, double value) {
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                db.getCollection("players").updateOne(
                        eq("_id", uuid.toString()),
                        new Document("$set", new Document(field, value).append("player_name", playerName)),
                        new UpdateOptions().upsert(true));
            } catch (Exception e) {
                plugin.getLogger().warning("[BankManager] Failed to save " + field + ": " + e.getMessage());
            }
        });
    }

    private void adminAddDB(UUID uuid, String playerName, String field, double amount) {
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                db.getCollection("players").updateOne(
                        eq("_id", uuid.toString()),
                        new Document("$inc", new Document(field, Math.max(0, amount)))
                                .append("$set", new Document("player_name", playerName)),
                        new UpdateOptions().upsert(true));
            } catch (Exception e) {
                plugin.getLogger().warning("[BankManager] adminAdd(" + field + ") failed: " + e.getMessage());
            }
        });
    }

    private void startLeaderboardTask() {
        new BukkitRunnable() {
            @Override public void run() { updateAllLeaderboards(); }
        }.runTaskTimerAsynchronously(plugin, 100L, 6000L);
    }

    public void forceLeaderboardUpdate() {
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, this::updateAllLeaderboards);
    }

    private void updateAllLeaderboards() {
        updateLeaderboard("balance", moneyTopCache);
        updateLeaderboard("shards",  shardsTopCache);
        updateLeaderboard("gold",    goldTopCache);
    }

    private void updateLeaderboard(String field, Map<Integer, LeaderboardEntry> cache) {
        if (!dbConnected) return;
        Map<Integer, LeaderboardEntry> fresh = new ConcurrentHashMap<>();
        int rank = 1;
        try {
            for (Document doc : db.getCollection("players")
                    .find()
                    .sort(new Document(field, -1))
                    .limit(10)) {
                String name = doc.getString("player_name");
                double val  = doc.getDouble(field) != null ? doc.getDouble(field) : 0.0;
                fresh.put(rank++, new LeaderboardEntry(name != null ? name : "Unknown", val));
            }
        } catch (Exception e) {
            plugin.getLogger().warning("[BankManager] Failed to update " + field + " leaderboard: " + e.getMessage());
            return;
        }
        while (rank <= 10) fresh.put(rank++, new LeaderboardEntry("None", 0));
        cache.putAll(fresh);
    }

    public LeaderboardEntry getTopEntry(int rank)       { return moneyTopCache.getOrDefault(rank,  new LeaderboardEntry("None", 0)); }
    public LeaderboardEntry getTopShardsEntry(int rank) { return shardsTopCache.getOrDefault(rank, new LeaderboardEntry("None", 0)); }
    public LeaderboardEntry getTopGoldEntry(int rank)   { return goldTopCache.getOrDefault(rank,   new LeaderboardEntry("None", 0)); }

    public static String formatBalance(double balance) {
        if (balance >= 1_000_000_000) return compact(balance / 1_000_000_000) + "b";
        if (balance >= 1_000_000)     return compact(balance / 1_000_000)     + "m";
        if (balance >= 1_000)         return compact(balance / 1_000)         + "k";
        return compact(balance);
    }

    private static String compact(double value) {
        if (value == Math.floor(value)) return String.format("%.0f", value);
        return String.format("%.2f", value).replaceAll("0+$", "").replaceAll("\\.$", "");
    }

    public void wipeAllMemory() {
        balances.clear();
        shards.clear();
        gold.clear();
        disabledAccounts.clear();
        receivePaymentsDisabled.clear();
        loadedPlayers.clear();
        moneyTopCache.clear();
        shardsTopCache.clear();
        goldTopCache.clear();
    }

    public record LeaderboardEntry(String name, double balance) {}
}
