package me.revqz.genPvP.Database;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mongodb.client.MongoCollection;
import me.revqz.genPvP.GenPvP;
import org.bson.Document;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPubSub;

import java.util.logging.Level;

/**
 * Cross-server log forwarding framework via Redis pub/sub.
 *
 * ── Architecture ────────────────────────────────────────────────────────────
 *
 *   Server A (this)                   Server B (remote)
 *   ─────────────                     ─────────────────
 *   publish(json)                     publish(json)
 *        │                                   │
 *        └──────────┐          ┌─────────────┘
 *                   ▼          ▼
 *              Redis channel "genpvp:cross:events"
 *                        │
 *                        ▼
 *             CrossServerSubscriber.onMessage()
 *             (runs on a dedicated daemon thread)
 *                        │
 *                        ▼  (only if originServer != this server's ID)
 *             MongoDB logs ← remote events written here
 */
public class CrossServerMessenger {

    private final GenPvP plugin;
    private final DatabaseManager databaseManager;

    private final String serverId;
    private final String channel;

    private final CrossServerSubscriber subscriber = new CrossServerSubscriber();
    private Thread subscriberThread;
    private volatile boolean running = false;

    public CrossServerMessenger(GenPvP plugin, DatabaseManager databaseManager) {
        this.plugin          = plugin;
        this.databaseManager = databaseManager;
        this.serverId        = plugin.getConfig().getString("cross-server.server-id", "server-1");
        this.channel         = plugin.getConfig().getString("cross-server.channel", "genpvp:cross:events");
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    public void start() {
        if (running) return;
        if (!databaseManager.isRedisConnected()) {
            plugin.getLogger().warning("[CrossServer] Redis is not connected — cross-server messaging disabled.");
            return;
        }

        running = true;
        subscriberThread = new Thread(this::subscribeLoop, "genpvp-cross-server-sub");
        subscriberThread.setDaemon(true);
        subscriberThread.start();
        plugin.getLogger().info("[CrossServer] Subscriber started on channel '" + channel + "' (server-id: " + serverId + ").");
    }

    public void shutdown() {
        if (!running) return;
        running = false;
        try { subscriber.unsubscribe(); } catch (Exception ignored) {}
        if (subscriberThread != null) subscriberThread.interrupt();
        plugin.getLogger().info("[CrossServer] Subscriber shut down.");
    }

    // ── Subscriber loop ───────────────────────────────────────────────────────

    private void subscribeLoop() {
        JedisPool pool = databaseManager.getJedisPool();
        if (pool == null) return;

        while (running) {
            try (Jedis jedis = pool.getResource()) {
                jedis.subscribe(subscriber, channel);
            } catch (Exception e) {
                if (!running) break;
                plugin.getLogger().log(Level.WARNING,
                        "[CrossServer] Subscriber connection lost, reconnecting in 5s...", e);
                try { Thread.sleep(5_000); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    // ── Publisher ─────────────────────────────────────────────────────────────

    public void publish(JsonObject json) {
        if (!running || !databaseManager.isRedisConnected()) return;

        JedisPool pool = databaseManager.getJedisPool();
        if (pool == null) return;

        json.addProperty("originServer", serverId);

        try (Jedis jedis = pool.getResource()) {
            jedis.publish(channel, json.toString());
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "[CrossServer] Failed to publish event: " + e.getMessage());
        }
    }

    // ── Inner subscriber ──────────────────────────────────────────────────────

    private class CrossServerSubscriber extends JedisPubSub {

        @Override
        public void onMessage(String channel, String message) {
            try {
                JsonObject json = JsonParser.parseString(message).getAsJsonObject();

                String origin = getString(json, "originServer");
                if (serverId.equals(origin)) return; // skip own events

                if (!databaseManager.isMongoConnected()) {
                    plugin.getLogger().warning("[CrossServer] MongoDB unavailable — dropping remote event from " + origin);
                    return;
                }

                insertToMongo(json, origin);

            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "[CrossServer] Failed to handle remote event: " + e.getMessage());
            }
        }

        @Override
        public void onSubscribe(String channel, int subscribedChannels) {
            plugin.getLogger().info("[CrossServer] Subscribed to channel: " + channel);
        }

        @Override
        public void onUnsubscribe(String channel, int subscribedChannels) {
            plugin.getLogger().info("[CrossServer] Unsubscribed from channel: " + channel);
        }
    }

    // ── MongoDB insert ───────────────────────────────────────────────────────

    private void insertToMongo(JsonObject json, String originServer) {
        try {
            MongoCollection<Document> collection = databaseManager.getDatabase().getCollection("logs");
            Document doc = new Document()
                    .append("type",          getString(json, "type"))
                    .append("player",        getString(json, "player"))
                    .append("player_name",   getString(json, "playerName"))
                    .append("action",        getString(json, "action"))
                    .append("event_type",    getString(json, "eventType"))
                    .append("koth_name",     getString(json, "kothName"))
                    .append("winner",        getString(json, "winner"))
                    .append("winner_name",   getString(json, "winnerName"))
                    .append("killer",        getString(json, "killer"))
                    .append("killer_name",   getString(json, "killerName"))
                    .append("victim",        getString(json, "victim"))
                    .append("victim_name",   getString(json, "victimName"))
                    .append("currency_type", getString(json, "currencyType"))
                    .append("amount",        getDouble(json, "amount"))
                    .append("item",          getString(json, "item"))
                    .append("price",         getDouble(json, "price"))
                    .append("reason",        getString(json, "reason"))
                    .append("timestamp",     json.has("timestamp") ? json.get("timestamp").getAsLong() : System.currentTimeMillis())
                    .append("origin_server", originServer);
            collection.insertOne(doc);
        } catch (Exception e) {
            plugin.getLogger().warning("[CrossServer] Failed to insert remote event into MongoDB: " + e.getMessage());
        }
    }

    // ── JSON helpers ──────────────────────────────────────────────────────────

    private static String getString(JsonObject json, String key) {
        JsonElement el = json.get(key);
        return (el != null && !el.isJsonNull()) ? el.getAsString() : null;
    }

    private static Double getDouble(JsonObject json, String key) {
        JsonElement el = json.get(key);
        return (el != null && !el.isJsonNull()) ? el.getAsDouble() : null;
    }
}
