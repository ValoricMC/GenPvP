package me.revqz.genPvP.Database;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import org.bson.Document;
import org.bukkit.plugin.java.JavaPlugin;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

public class DatabaseManager {

    private final JavaPlugin plugin;

    // MongoDB
    private MongoClient mongoClient;
    private MongoDatabase database;
    private boolean mongoConnected = false;

    // Redis (kept for CrossServerMessenger pub/sub)
    private JedisPool jedisPool;
    private boolean redisConnected = false;

    public DatabaseManager(JavaPlugin plugin) {
        this.plugin = plugin;
        // Fire both connections in parallel — cuts startup time roughly in half
        // compared to the old sequential approach.
        CompletableFuture<Void> mongoFuture  = CompletableFuture.runAsync(this::connectMongo);
        CompletableFuture<Void> redisFuture  = CompletableFuture.runAsync(this::connectRedis);
        CompletableFuture.allOf(mongoFuture, redisFuture).join(); // wait for both before returning
    }

    // ── MongoDB ──────────────────────────────────────────────────────────────

    private void connectMongo() {
        String uri    = plugin.getConfig().getString("mongodb.uri", "mongodb://localhost:27017");
        String dbName = plugin.getConfig().getString("mongodb.database", "genpvp");

        try {
            MongoClientSettings settings = MongoClientSettings.builder()
                    .applyConnectionString(new ConnectionString(uri))
                    .applyToClusterSettings(cluster -> cluster
                            .serverSelectionTimeout(5, TimeUnit.SECONDS))
                    .applyToConnectionPoolSettings(pool -> pool
                            .maxSize(20)
                            .minSize(5)
                            .maxWaitTime(5, TimeUnit.SECONDS)
                            .maxConnectionIdleTime(10, TimeUnit.MINUTES))
                    .applyToSocketSettings(socket -> socket
                            .connectTimeout(3, TimeUnit.SECONDS)
                            .readTimeout(10, TimeUnit.SECONDS))
                    .build();

            mongoClient = MongoClients.create(settings);
            database = mongoClient.getDatabase(dbName);

            // Verify connection with a real command
            database.runCommand(new Document("ping", 1));

            mongoConnected = true;
            plugin.getLogger().info("[DatabaseManager] Successfully connected to MongoDB.");

            createIndexes();

        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "[DatabaseManager] Failed to connect to MongoDB: " + e.getMessage());
        }
    }

    /**
     * Creates indexes for all collections. MongoDB createIndex is idempotent —
     * existing indexes are left untouched, so this is safe to call on every startup.
     */
    private void createIndexes() {
        try {
            // logs
            MongoCollection<Document> logs = database.getCollection("logs");
            logs.createIndex(Indexes.compoundIndex(Indexes.ascending("type"), Indexes.descending("timestamp")));
            logs.createIndex(Indexes.compoundIndex(Indexes.ascending("player"), Indexes.descending("timestamp")));
            logs.createIndex(Indexes.ascending("winner"), new IndexOptions().sparse(true));

            // players (merged bank + stats)
            MongoCollection<Document> players = database.getCollection("players");
            players.createIndex(Indexes.descending("balance"));
            players.createIndex(Indexes.descending("shards"));
            players.createIndex(Indexes.descending("gold"));
            players.createIndex(Indexes.descending("kills"));
            players.createIndex(Indexes.descending("deaths"));

            // regions
            // _id = name, no extra indexes needed

            // protected_blocks — _id = "world:chunkX:chunkZ", no extra indexes needed

            // kit_cooldowns — _id = uuid, cooldowns embedded as sub-document
            // No extra indexes needed (loaded by _id on join)
            // Drop stale compound index from the old per-row format (uuid+kit)
            // that causes E11000 duplicate key errors on new-format documents.
            try {
                database.getCollection("kit_cooldowns").dropIndex("uuid_1_kit_1");
                plugin.getLogger().info("[DatabaseManager] Dropped stale index 'uuid_1_kit_1' from kit_cooldowns.");
            } catch (Exception ignored) {
                // Index doesn't exist — nothing to drop
            }

            // enderchest
            database.getCollection("enderchest").createIndex(
                    Indexes.compoundIndex(Indexes.ascending("uuid"), Indexes.ascending("slot")),
                    new IndexOptions().unique(true));

            // teams — _id = name, no extra indexes

            // team_members
            database.getCollection("team_members").createIndex(Indexes.ascending("team_name"));

            // devil_fruits — _id = uuid, owned stored as array, no extra indexes needed
            // fruit_blacklist — _id = uuid, no extra indexes

            // custom_items — _id = name, no extra indexes

            plugin.getLogger().info("[DatabaseManager] All indexes verified / created.");

        } catch (Exception e) {
            plugin.getLogger().warning("[DatabaseManager] Index creation warning: " + e.getMessage());
        }
    }

    // ── Redis ──────────────────────────────────────────────────────────────────

    private void connectRedis() {
        String host     = plugin.getConfig().getString("redis.host",     "localhost");
        int    port     = plugin.getConfig().getInt   ("redis.port",     6379);
        String password = plugin.getConfig().getString("redis.password", "");

        try {
            JedisPoolConfig poolConfig = new JedisPoolConfig();
            poolConfig.setMaxTotal(128);
            poolConfig.setMaxIdle(128);
            poolConfig.setMinIdle(16);
            poolConfig.setTestOnBorrow(true);
            poolConfig.setTestOnReturn(true);
            poolConfig.setTestWhileIdle(true);
            poolConfig.setMinEvictableIdleTimeMillis(Duration.ofSeconds(60).toMillis());
            poolConfig.setTimeBetweenEvictionRunsMillis(Duration.ofSeconds(30).toMillis());
            poolConfig.setNumTestsPerEvictionRun(3);

            if (password != null && !password.isEmpty()) {
                jedisPool = new JedisPool(poolConfig, host, port, 2000, password);
            } else {
                jedisPool = new JedisPool(poolConfig, host, port, 2000);
            }

            try (var jedis = jedisPool.getResource()) {
                jedis.ping();
            }

            redisConnected = true;
            plugin.getLogger().info("[DatabaseManager] Successfully connected to Redis.");
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "[DatabaseManager] Failed to connect to Redis (cross-server pub/sub disabled): " + e.getMessage());
        }
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    public void close() {
        // Mark as disconnected FIRST so in-flight async tasks stop using the client
        mongoConnected = false;
        redisConnected = false;

        if (mongoClient != null) {
            try {
                mongoClient.close();
            } catch (Exception e) {
                plugin.getLogger().warning("[DatabaseManager] Error closing MongoDB: " + e.getMessage());
            }
            plugin.getLogger().info("[DatabaseManager] MongoDB connection closed.");
        }
        if (jedisPool != null && !jedisPool.isClosed()) {
            try {
                jedisPool.close();
            } catch (Exception e) {
                plugin.getLogger().warning("[DatabaseManager] Error closing Redis: " + e.getMessage());
            }
            plugin.getLogger().info("[DatabaseManager] Redis connection pool closed.");
        }
    }

    // ── Getters ────────────────────────────────────────────────────────────────

    public MongoDatabase getDatabase()       { return database; }
    public MongoDatabase getDatabase(String name) {
        return mongoClient == null ? null : mongoClient.getDatabase(name);
    }
    public JedisPool     getJedisPool()      { return jedisPool; }
    public boolean       isMongoConnected()  { return mongoConnected; }
    public boolean       isRedisConnected()  { return redisConnected; }

    /** Re-create / verify all main-DB indexes. Idempotent — safe to call after a restore. */
    public void ensureIndexes() { createIndexes(); }
}
