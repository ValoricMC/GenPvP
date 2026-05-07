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

    private volatile MongoClient mongoClient;
    private volatile MongoDatabase database;
    private volatile boolean mongoConnected = false;

    private volatile JedisPool jedisPool;
    private volatile boolean redisConnected = false;

    public DatabaseManager(JavaPlugin plugin) {
        this.plugin = plugin;

        CompletableFuture.runAsync(this::connectRedis);

        initMongoClient();
    }

    private void initMongoClient() {
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
            database    = mongoClient.getDatabase(dbName); 
            mongoConnected = true;

            plugin.getLogger().info("[DatabaseManager] MongoClient created — connection will be established lazily.");

            CompletableFuture.runAsync(this::verifyAndIndex);

        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "[DatabaseManager] Failed to create MongoDB client: " + e.getMessage());
        }
    }

    private void verifyAndIndex() {
        try {
            database.runCommand(new Document("ping", 1));
            plugin.getLogger().info("[DatabaseManager] Successfully connected to MongoDB.");
            createIndexes();
        } catch (Exception e) {
            mongoConnected = false;
            plugin.getLogger().log(Level.SEVERE, "[DatabaseManager] MongoDB verification failed — "
                    + "operations will fail until next restart: " + e.getMessage());
        }
    }

    private void createIndexes() {
        try {
            
            MongoCollection<Document> logs = database.getCollection("logs");
            logs.createIndex(Indexes.compoundIndex(Indexes.ascending("type"), Indexes.descending("timestamp")));
            logs.createIndex(Indexes.compoundIndex(Indexes.ascending("player"), Indexes.descending("timestamp")));
            logs.createIndex(Indexes.ascending("winner"), new IndexOptions().sparse(true));

            MongoCollection<Document> players = database.getCollection("players");
            players.createIndex(Indexes.descending("balance"));
            players.createIndex(Indexes.descending("shards"));
            players.createIndex(Indexes.descending("gold"));
            players.createIndex(Indexes.descending("kills"));
            players.createIndex(Indexes.descending("deaths"));

            try {
                database.getCollection("kit_cooldowns").dropIndex("uuid_1_kit_1");
                plugin.getLogger().info("[DatabaseManager] Dropped stale index 'uuid_1_kit_1' from kit_cooldowns.");
            } catch (Exception ignored) {
                
            }

            database.getCollection("enderchest").createIndex(
                    Indexes.compoundIndex(Indexes.ascending("uuid"), Indexes.ascending("slot")),
                    new IndexOptions().unique(true));

            database.getCollection("team_members").createIndex(Indexes.ascending("team_name"));

            plugin.getLogger().info("[DatabaseManager] All indexes verified / created.");

        } catch (Exception e) {
            plugin.getLogger().warning("[DatabaseManager] Index creation warning: " + e.getMessage());
        }
    }

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

    public void close() {
        
        mongoConnected = false;
        redisConnected = false;

        final MongoClient mc = mongoClient;
        final JedisPool   jp = jedisPool;
        mongoClient = null;
        jedisPool   = null;

        Thread closer = new Thread(() -> {
            if (mc != null) {
                try {
                    mc.close();
                } catch (Exception e) {
                    plugin.getLogger().warning("[DatabaseManager] Error closing MongoDB: " + e.getMessage());
                }
            }
            if (jp != null && !jp.isClosed()) {
                try {
                    jp.close();
                } catch (Exception e) {
                    plugin.getLogger().warning("[DatabaseManager] Error closing Redis: " + e.getMessage());
                }
            }
            plugin.getLogger().info("[DatabaseManager] Connections closed.");
        }, "genpvp-db-closer");
        closer.setDaemon(true);
        closer.start();
        
    }

    public MongoDatabase getDatabase()       { return database; }
    public MongoDatabase getDatabase(String name) {
        MongoClient mc = mongoClient;
        return mc == null ? null : mc.getDatabase(name);
    }
    public JedisPool     getJedisPool()      { return jedisPool; }
    public boolean       isMongoConnected()  { return mongoConnected; }
    public boolean       isRedisConnected()  { return redisConnected; }

    public void ensureIndexes() { createIndexes(); }
}
