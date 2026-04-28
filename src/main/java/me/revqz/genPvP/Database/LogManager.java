package me.revqz.genPvP.Database;

import com.mongodb.client.MongoCollection;
import me.revqz.genPvP.GenPvP;
import org.bson.Document;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Async log pipeline:
 *
 *   Main thread (enqueue — instant, non-blocking)
 *        │
 *        ▼
 *   writeQueue (ConcurrentLinkedQueue<Document>)
 *        │
 *   Async writer every 1 s (batch insertMany to MongoDB)
 *        │
 *   On failure → requeue (bounded to MAX_MEM_QUEUE)
 */
public class LogManager {

    private final GenPvP plugin;
    private final DatabaseManager databaseManager;

    private static final int BATCH_SIZE    = 500;
    private static final int MAX_MEM_QUEUE = 10_000;

    private final ConcurrentLinkedQueue<Document> writeQueue = new ConcurrentLinkedQueue<>();

    public LogManager(GenPvP plugin, DatabaseManager databaseManager) {
        this.plugin          = plugin;
        this.databaseManager = databaseManager;

        plugin.getLogger().info("[LogManager] MongoDB connected: " + databaseManager.isMongoConnected());
        if (!databaseManager.isMongoConnected()) {
            plugin.getLogger().warning("[LogManager] MongoDB is NOT connected — logs will be held in memory until connection is restored.");
        }

        startWriterTask();
    }

    // ── Background writer ─────────────────────────────────────────────────────

    private void startWriterTask() {
        plugin.getServer().getScheduler().runTaskTimerAsynchronously(plugin, () -> {
            List<Document> batch = drain(BATCH_SIZE);
            if (batch.isEmpty()) return;

            if (!databaseManager.isMongoConnected()) {
                requeue(batch);
                return;
            }

            if (!insertBatch(batch)) {
                requeue(batch);
            }
        }, 20L, 20L); // every 1 second
    }

    // ── I/O ───────────────────────────────────────────────────────────────────

    private boolean insertBatch(List<Document> batch) {
        try {
            MongoCollection<Document> collection = databaseManager.getDatabase().getCollection("logs");
            collection.insertMany(batch);
            return true;
        } catch (Exception e) {
            plugin.getLogger().warning("[LogManager] MongoDB batch insert failed (" + batch.size() + " docs): " + e.getMessage());
            return false;
        }
    }

    // ── Queue helpers ─────────────────────────────────────────────────────────

    private List<Document> drain(int limit) {
        List<Document> batch = new ArrayList<>(limit);
        Document entry;
        while (batch.size() < limit && (entry = writeQueue.poll()) != null) {
            batch.add(entry);
        }
        return batch;
    }

    private void requeue(List<Document> batch) {
        for (Document entry : batch) {
            if (writeQueue.size() >= MAX_MEM_QUEUE) {
                plugin.getLogger().warning("[LogManager] Memory queue full (" + MAX_MEM_QUEUE + ") — dropping log entries.");
                break;
            }
            writeQueue.offer(entry);
        }
    }

    /** Enqueues a log document on the main thread — instant, never blocks. */
    private void enqueue(Document doc) {
        if (writeQueue.size() < MAX_MEM_QUEUE) {
            writeQueue.offer(doc);
        }
    }

    // ── Name helper ───────────────────────────────────────────────────────────

    private static String playerName(UUID uuid) {
        if (uuid == null) return null;
        Player p = Bukkit.getPlayer(uuid);
        return p != null ? p.getName() : null;
    }

    // ── Public log API ────────────────────────────────────────────────────────

    public void logEconomy(UUID player, String currencyType, String action, double amount, String reason) {
        Document doc = new Document("type", "ECONOMY")
                .append("player", player.toString())
                .append("player_name", playerName(player))
                .append("action", action)
                .append("currency_type", currencyType)
                .append("amount", amount)
                .append("reason", reason)
                .append("timestamp", System.currentTimeMillis());
        enqueue(doc);
    }

    public void logShop(UUID player, String action, String item, int amount, double price) {
        Document doc = new Document("type", "SHOP")
                .append("player", player.toString())
                .append("player_name", playerName(player))
                .append("action", action)
                .append("amount", (double) amount)
                .append("item", item)
                .append("price", price)
                .append("timestamp", System.currentTimeMillis());
        enqueue(doc);
    }

    public void logKoth(String eventType, String kothName, UUID winner) {
        logKoth(eventType, kothName, winner, playerName(winner));
    }

    public void logKoth(String eventType, String kothName, UUID winner, String winnerName) {
        Document doc = new Document("type", "KOTH")
                .append("event_type", eventType)
                .append("koth_name", kothName)
                .append("timestamp", System.currentTimeMillis());
        // Only store winner fields on WIN events — keeps START/STOP docs tiny
        if (winner != null) {
            doc.append("winner", winner.toString());
            if (winnerName != null) doc.append("winner_name", winnerName);
        }
        enqueue(doc);
    }

    public void logPvP(UUID killer, UUID victim) {
        String killerStr  = killer != null ? killer.toString() : "ENVIRONMENT";
        String killerName = killer != null ? playerName(killer) : null;
        Document doc = new Document("type", "PVP")
                .append("killer", killerStr)
                .append("victim", victim.toString())
                .append("victim_name", playerName(victim))
                .append("timestamp", System.currentTimeMillis());
        if (killerName != null) doc.append("killer_name", killerName);
        enqueue(doc);
    }

    public void logConnection(UUID player, String action) {
        Document doc = new Document("type", "CONNECTION")
                .append("player", player.toString())
                .append("player_name", playerName(player))
                .append("action", action)
                .append("timestamp", System.currentTimeMillis());
        enqueue(doc);
    }

    public void logDupe(UUID player, String reason) {
        Document doc = new Document("type", "DUPE_SUSPICION")
                .append("player", player.toString())
                .append("player_name", playerName(player))
                .append("reason", reason)
                .append("timestamp", System.currentTimeMillis());
        enqueue(doc);
    }

    public void logAbility(UUID player, String ability) {
        Document doc = new Document("type", "ABILITY")
                .append("player", player.toString())
                .append("player_name", playerName(player))
                .append("ability", ability)
                .append("timestamp", System.currentTimeMillis());
        enqueue(doc);
    }
}
