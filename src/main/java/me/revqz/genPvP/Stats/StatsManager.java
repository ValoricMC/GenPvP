package me.revqz.genPvP.Stats;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.UpdateOptions;
import me.revqz.genPvP.Database.DatabaseManager;
import me.revqz.genPvP.GenPvP;
import org.bson.Document;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static com.mongodb.client.model.Filters.eq;

public class StatsManager implements Listener {

    public record StatsEntry(String name, String uuid, int value) {}

    private final GenPvP        plugin;
    private final MongoDatabase db;
    private final boolean       dbConnected;

    private final ConcurrentHashMap<UUID, int[]> cache = new ConcurrentHashMap<>();

    private final Map<Integer, StatsEntry> killsBoard  = new HashMap<>();
    private final Map<Integer, StatsEntry> deathsBoard = new HashMap<>();

    private static final StatsEntry EMPTY = new StatsEntry("None", null, 0);

    public StatsManager(GenPvP plugin, DatabaseManager dbManager) {
        this.plugin      = plugin;
        this.db          = dbManager.isMongoConnected() ? dbManager.getDatabase() : null;
        this.dbConnected = dbManager.isMongoConnected();

        for (int i = 1; i <= 10; i++) {
            killsBoard.put(i,  EMPTY);
            deathsBoard.put(i, EMPTY);
        }

        if (dbConnected) startLeaderboardTask();
    }

    // ── Events ────────────────────────────────────────────────────────────────

    public void inject(UUID uuid, int kills, int deaths) {
        cache.put(uuid, new int[]{kills, deaths});
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        if (cache.containsKey(uuid)) return;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> loadPlayer(uuid));
    }

    // ── Mutation ──────────────────────────────────────────────────────────────

    public void addKill(UUID uuid) {
        int[] stats = cache.computeIfAbsent(uuid, k -> new int[2]);
        stats[0]++;
        if (dbConnected) persistAsync(uuid, stats[0], stats[1]);
    }

    public void addDeath(UUID uuid) {
        int[] stats = cache.computeIfAbsent(uuid, k -> new int[2]);
        stats[1]++;
        if (dbConnected) persistAsync(uuid, stats[0], stats[1]);
    }

    // ── Reads ─────────────────────────────────────────────────────────────────

    public int getKills(UUID uuid) {
        int[] s = cache.get(uuid);
        return s != null ? s[0] : 0;
    }

    public int getDeaths(UUID uuid) {
        int[] s = cache.get(uuid);
        return s != null ? s[1] : 0;
    }

    public StatsEntry getTopKills(int rank)  { return killsBoard.getOrDefault(rank, EMPTY); }
    public StatsEntry getTopDeaths(int rank) { return deathsBoard.getOrDefault(rank, EMPTY); }

    // ── DB ────────────────────────────────────────────────────────────────────

    private void loadPlayer(UUID uuid) {
        try {
            Document doc = db.getCollection("players").find(eq("_id", uuid.toString())).first();
            if (doc != null) {
                cache.put(uuid, new int[]{
                        doc.getInteger("kills", 0),
                        doc.getInteger("deaths", 0)
                });
            } else {
                cache.put(uuid, new int[2]);
            }
        } catch (Exception e) {
            plugin.getLogger().warning("[StatsManager] Failed to load stats for " + uuid + ": " + e.getMessage());
        }
    }

    private void persistAsync(UUID uuid, int kills, int deaths) {
        Player p = Bukkit.getPlayer(uuid);
        String name = p != null ? p.getName() : null;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                Document update = new Document("kills", kills).append("deaths", deaths);
                if (name != null) update.append("player_name", name);
                db.getCollection("players").updateOne(
                        eq("_id", uuid.toString()),
                        new Document("$set", update),
                        new UpdateOptions().upsert(true));
            } catch (Exception e) {
                plugin.getLogger().warning("[StatsManager] Persist failed for " + uuid + ": " + e.getMessage());
            }
        });
    }

    private void startLeaderboardTask() {
        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::refreshLeaderboards, 100L, 6000L);
    }

    private void refreshLeaderboards() {
        refreshBoard("kills",  killsBoard);
        refreshBoard("deaths", deathsBoard);
    }

    private void refreshBoard(String field, Map<Integer, StatsEntry> target) {
        Map<Integer, StatsEntry> fresh = new HashMap<>();
        int rank = 1;
        try {
            for (Document doc : db.getCollection("players")
                    .find()
                    .sort(new Document(field, -1))
                    .limit(10)) {
                String name  = doc.getString("player_name");
                String uuid  = doc.getString("_id");
                int    value = doc.getInteger(field, 0);
                fresh.put(rank++, new StatsEntry(name != null ? name : "Unknown", uuid, value));
            }
        } catch (Exception e) {
            plugin.getLogger().warning("[StatsManager] Leaderboard refresh failed (" + field + "): " + e.getMessage());
        }
        while (rank <= 10) fresh.put(rank++, EMPTY);
        Bukkit.getScheduler().runTask(plugin, () -> {
            target.clear();
            target.putAll(fresh);
        });
    }

    // ── Wipe ──────────────────────────────────────────────────────────────────

    public void wipeAllMemory() {
        cache.clear();
        killsBoard.clear();
        deathsBoard.clear();
    }
}
