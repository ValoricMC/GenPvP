package me.revqz.genPvP.Database;

import com.mongodb.MongoBulkWriteException;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.InsertManyOptions;
import com.mongodb.client.model.ReplaceOptions;
import com.mongodb.client.model.UpdateOptions;
import me.revqz.genPvP.GenPvP;
import org.bson.Document;
import org.bson.types.Binary;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.sql.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import static com.mongodb.client.model.Filters.eq;

/**
 * /mysql convert mongo  — migrates all legacy MySQL data into MongoDB.
 * /mysql convert status — shows the current progress.
 *
 * Runs fully async. All progress is logged to console AND sent to the sender.
 */
public class MysqlConvertCommand implements CommandExecutor, TabCompleter {

    // ── Status tracking ──────────────────────────────────────────────────────

    private static class MigrationStatus {
        final AtomicBoolean  running      = new AtomicBoolean(false);
        final AtomicBoolean  done         = new AtomicBoolean(false);
        final AtomicReference<String> currentStep = new AtomicReference<>("idle");
        final AtomicInteger  stepDone    = new AtomicInteger(0);
        final StringBuilder  log         = new StringBuilder();
        final AtomicReference<String> lastError = new AtomicReference<>(null);

        void step(String name) {
            currentStep.set(name);
            stepDone.set(0);
            log(name + " — starting...");
        }

        void finish(String name, int count) {
            String msg = "  ✓ " + name + ": " + count + " row(s) migrated";
            log(msg);
            stepDone.set(count);
        }

        void skip(String name, String reason) {
            String msg = "  ~ " + name + ": skipped (" + reason + ")";
            log(msg);
        }

        void error(String name, String err) {
            String msg = "  ✗ " + name + " ERROR: " + err;
            log(msg);
            lastError.set(msg);
        }

        synchronized void log(String msg) {
            log.append(msg).append("\n");
        }

        String[] getLogLines() {
            return log.toString().split("\n");
        }
    }

    private final MigrationStatus status = new MigrationStatus();

    // ────────────────────────────────────────────────────────────────────────

    private final GenPvP         plugin;
    private final DatabaseManager databaseManager;
    private final Logger          log;

    public MysqlConvertCommand(GenPvP plugin, DatabaseManager databaseManager) {
        this.plugin           = plugin;
        this.databaseManager  = databaseManager;
        this.log              = plugin.getLogger();
    }

    // ── Command dispatch ─────────────────────────────────────────────────────

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§cUsage: /mysql convert mongo  |  /mysql convert status");
            return true;
        }

        if (!args[0].equalsIgnoreCase("convert")) {
            sender.sendMessage("§cUsage: /mysql convert mongo  |  /mysql convert status");
            return true;
        }

        if (args[1].equalsIgnoreCase("status")) {
            sendStatus(sender);
            return true;
        }

        if (!args[1].equalsIgnoreCase("mongo")) {
            sender.sendMessage("§cUsage: /mysql convert mongo  |  /mysql convert status");
            return true;
        }

        if (!databaseManager.isMongoConnected()) {
            sender.sendMessage("§cMongoDB is not connected. Cannot migrate.");
            return true;
        }

        if (status.running.get()) {
            sender.sendMessage("§eA migration is already running. Use §f/mysql convert status §eto check progress.");
            return true;
        }

        // Reset status for new run
        status.running.set(true);
        status.done.set(false);
        status.currentStep.set("connecting");
        status.stepDone.set(0);
        status.lastError.set(null);
        synchronized (status) { status.log.setLength(0); }

        sender.sendMessage("§a[MySQL→Mongo] Starting migration — use §f/mysql convert status §afor live progress.");
        log.info("[MysqlConvert] Migration started by " + sender.getName());

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                migrate(sender);
                status.done.set(true);
                String done = "[MysqlConvert] Migration COMPLETE.";
                log.info(done);
                status.log("§a§lDone! Migration complete.");
                sender.sendMessage("§a§l[MySQL→Mongo] Migration complete!");
            } catch (Exception e) {
                String err = "[MysqlConvert] FAILED: " + e.getMessage();
                log.severe(err);
                status.log("§c§lFailed: " + e.getMessage());
                status.lastError.set(e.getMessage());
                sender.sendMessage("§c[MySQL→Mongo] Migration failed: " + e.getMessage());
                e.printStackTrace();
            } finally {
                status.running.set(false);
            }
        });

        return true;
    }

    private void sendStatus(CommandSender sender) {
        sender.sendMessage("§8§m------------------------------");
        sender.sendMessage("§e§lMySQL → MongoDB Migration Status");
        if (status.running.get()) {
            sender.sendMessage("§aStatus: §fRunning  |  Step: §f" + status.currentStep.get()
                    + "  |  Rows so far: §f" + status.stepDone.get());
        } else if (status.done.get()) {
            sender.sendMessage("§aStatus: §fComplete");
        } else {
            sender.sendMessage("§7Status: §fIdle / not started");
        }
        String[] lines = status.getLogLines();
        // Show last 15 lines so chat doesn't flood
        int start = Math.max(0, lines.length - 15);
        for (int i = start; i < lines.length; i++) {
            if (!lines[i].isBlank()) sender.sendMessage("§7" + lines[i]);
        }
        if (status.lastError.get() != null) {
            sender.sendMessage("§cLast error: " + status.lastError.get());
        }
        sender.sendMessage("§8§m------------------------------");
    }

    // ── JDBC connection ──────────────────────────────────────────────────────

    private Connection openMysql() throws SQLException {
        String host     = plugin.getConfig().getString("mysql.host", "localhost");
        int    port     = plugin.getConfig().getInt("mysql.port", 3306);
        String database = plugin.getConfig().getString("mysql.database", "genpvp");
        String username = plugin.getConfig().getString("mysql.username", "root");
        String password = plugin.getConfig().getString("mysql.password", "");

        String url = "jdbc:mysql://" + host + ":" + port + "/" + database
                + "?useSSL=false&allowPublicKeyRetrieval=true&connectTimeout=15000&socketTimeout=30000";
        return DriverManager.getConnection(url, username, password);
    }

    // ── Migration orchestrator ───────────────────────────────────────────────

    private void migrate(CommandSender sender) throws Exception {
        try (Connection conn = openMysql()) {
            consoleAndSender(sender, "§aConnected to MySQL.");

            MongoDatabase db = databaseManager.getDatabase();

            // ─── Kits FIRST (highest priority) ───────────────────────────────
            runStep(sender, conn, db, "kit_cooldowns", () -> migrateKitCooldowns(conn, db, sender));
            runStep(sender, conn, db, "genpvp_kits",   () -> migrateKitItems(conn, db, sender));

            // ─── Everything else ──────────────────────────────────────────────
            runStep(sender, conn, db, "bank+stats",        () -> migrateBankAndStats(conn, db, sender));
            runStep(sender, conn, db, "logs",              () -> migrateLogs(conn, db, sender));
            runStep(sender, conn, db, "regions",           () -> migrateRegions(conn, db, sender));
            runStep(sender, conn, db, "protected_blocks",  () -> migrateProtectedBlocks(conn, db, sender));
            runStep(sender, conn, db, "enderchest",        () -> migrateEnderChest(conn, db, sender));
            runStep(sender, conn, db, "teams",             () -> migrateTeams(conn, db, sender));
            runStep(sender, conn, db, "team_members",      () -> migrateTeamMembers(conn, db, sender));
            runStep(sender, conn, db, "devil_fruits",      () -> migrateDevilFruits(conn, db, sender));
            runStep(sender, conn, db, "fruit_blacklist",   () -> migrateFruitBlacklist(conn, db, sender));
            runStep(sender, conn, db, "custom_items",      () -> migrateCustomItems(conn, db, sender));
        }
    }

    /** Wraps each step so one table failure doesn't abort the entire migration. */
    private void runStep(CommandSender sender, Connection conn, MongoDatabase db,
                         String stepName, StepTask task) {
        status.currentStep.set(stepName);
        log.info("[MysqlConvert] Starting step: " + stepName);
        try {
            task.run();
        } catch (Exception e) {
            String msg = "Step '" + stepName + "' failed: " + e.getMessage();
            log.warning("[MysqlConvert] " + msg);
            status.error(stepName, e.getMessage());
            sender.sendMessage("§c[MySQL→Mongo] " + msg);
        }
    }

    @FunctionalInterface
    private interface StepTask { void run() throws Exception; }

    // ── Logging helpers ───────────────────────────────────────────────────────

    private void consoleAndSender(CommandSender sender, String msg) {
        log.info("[MysqlConvert] " + stripColor(msg));
        sender.sendMessage("§7[MySQL→Mongo] " + msg);
        status.log(msg);
    }

    private static String stripColor(String s) {
        return s.replaceAll("§[0-9a-fklmnorA-FKLMNOR]", "");
    }

    // ── genpvp_kit_cooldowns → kit_cooldowns  (FIRST) ────────────────────────

    private void migrateKitCooldowns(Connection conn, MongoDatabase db, CommandSender sender) throws SQLException {
        status.step("kit_cooldowns");
        if (!tableExists(conn, "genpvp_kit_cooldowns")) {
            status.skip("kit_cooldowns", "table not found");
            consoleAndSender(sender, "§e  ~ kit_cooldowns: table not found, skipping.");
            return;
        }

        MongoCollection<Document> coll = db.getCollection("kit_cooldowns");
        int count = 0;

        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT * FROM genpvp_kit_cooldowns")) {
            while (rs.next()) {
                String uuid    = rs.getString("uuid");
                String kit     = rs.getString("kit");
                long   expires = rs.getLong("expires");

                coll.updateOne(
                        new Document("uuid", uuid).append("kit", kit),
                        new Document("$set", new Document("uuid", uuid)
                                .append("kit", kit)
                                .append("expires", expires)),
                        new UpdateOptions().upsert(true));
                count++;

                if (count % 100 == 0) {
                    status.stepDone.set(count);
                    log.info("[MysqlConvert] kit_cooldowns — " + count + " processed...");
                }
            }
        }

        status.finish("kit_cooldowns", count);
        consoleAndSender(sender, "§a  ✓ kit_cooldowns: §f" + count + " §arow(s) migrated.");
    }

    // ── genpvp_kits → kits  (kit item definitions if stored in DB) ───────────

    private void migrateKitItems(Connection conn, MongoDatabase db, CommandSender sender) throws SQLException {
        // Try common table names for kit item storage
        String table = null;
        for (String candidate : new String[]{"genpvp_kits", "genpvp_kit_items", "genpvp_kit_data"}) {
            if (tableExists(conn, candidate)) { table = candidate; break; }
        }

        if (table == null) {
            status.skip("kit_items", "no kit item table found (kits stored in kits.yml)");
            consoleAndSender(sender, "§e  ~ kit_items: no DB table found — kits are in kits.yml, nothing to migrate.");
            return;
        }

        status.step("kit_items");
        MongoCollection<Document> coll = db.getCollection("kits");
        int count = 0;

        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT * FROM " + table)) {
            ResultSetMetaData meta = rs.getMetaData();
            List<Document> batch = new ArrayList<>(50);

            while (rs.next()) {
                Document doc = new Document();
                for (int i = 1; i <= meta.getColumnCount(); i++) {
                    String col = meta.getColumnName(i);
                    Object val = rs.getObject(i);
                    if (val instanceof byte[] bytes) {
                        doc.append(col, new Binary(bytes));
                    } else {
                        doc.append(col, val);
                    }
                }
                batch.add(doc);
                if (batch.size() >= 50) {
                    insertIgnoreDuplicates(coll, batch);
                    count += batch.size();
                    batch.clear();
                    status.stepDone.set(count);
                }
            }
            if (!batch.isEmpty()) {
                insertIgnoreDuplicates(coll, batch);
                count += batch.size();
            }
        }

        status.finish("kit_items", count);
        consoleAndSender(sender, "§a  ✓ kit_items: §f" + count + " §arow(s) migrated.");
    }

    // ── genpvp_bank + genpvp_stats → players ─────────────────────────────────

    private void migrateBankAndStats(Connection conn, MongoDatabase db, CommandSender sender) throws SQLException {
        boolean hasBank  = tableExists(conn, "genpvp_bank");
        boolean hasStats = tableExists(conn, "genpvp_stats");

        if (!hasBank && !hasStats) {
            status.skip("players", "no bank/stats tables found");
            consoleAndSender(sender, "§e  ~ players: no bank/stats tables found, skipping.");
            return;
        }

        status.step("players");
        MongoCollection<Document> coll = db.getCollection("players");
        int count = 0;

        if (hasBank) {
            consoleAndSender(sender, "§7  → Migrating genpvp_bank...");
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT * FROM genpvp_bank")) {
                ResultSetMetaData meta = rs.getMetaData();
                while (rs.next()) {
                    String uuid = rs.getString("uuid");
                    Document update = new Document()
                            .append("balance", getDoubleCol(rs, meta, "balance"))
                            .append("shards",  getDoubleCol(rs, meta, "shards"))
                            .append("gold",    getDoubleCol(rs, meta, "gold"))
                            .append("disabled",         getBoolCol(rs, meta, "disabled"))
                            .append("receive_payments", getBoolCol(rs, meta, "receive_payments", true));
                    if (hasColumn(meta, "player_name"))
                        update.append("player_name", rs.getString("player_name"));

                    coll.updateOne(eq("_id", uuid),
                            new Document("$set", update),
                            new UpdateOptions().upsert(true));
                    count++;
                    if (count % 200 == 0) {
                        status.stepDone.set(count);
                        log.info("[MysqlConvert] players (bank) — " + count + " processed...");
                    }
                }
            }
            consoleAndSender(sender, "§7  → bank rows: " + count);
        }

        if (hasStats) {
            consoleAndSender(sender, "§7  → Migrating genpvp_stats...");
            int statCount = 0;
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT * FROM genpvp_stats")) {
                ResultSetMetaData meta = rs.getMetaData();
                while (rs.next()) {
                    String uuid = rs.getString("uuid");
                    Document update = new Document()
                            .append("kills",  getIntCol(rs, meta, "kills"))
                            .append("deaths", getIntCol(rs, meta, "deaths"));
                    if (hasColumn(meta, "player_name")) update.append("player_name", rs.getString("player_name"));
                    if (hasColumn(meta, "prestige"))    update.append("prestige", rs.getInt("prestige"));
                    if (hasColumn(meta, "level"))       update.append("level", rs.getInt("level"));
                    if (hasColumn(meta, "xp"))          update.append("xp", rs.getDouble("xp"));
                    if (hasColumn(meta, "prestige_blacklisted")) update.append("prestige_blacklisted", rs.getBoolean("prestige_blacklisted"));
                    if (hasColumn(meta, "level_blacklisted"))    update.append("level_blacklisted",    rs.getBoolean("level_blacklisted"));
                    if (hasColumn(meta, "xp_blacklisted"))       update.append("xp_blacklisted",       rs.getBoolean("xp_blacklisted"));

                    coll.updateOne(eq("_id", uuid),
                            new Document("$set", update),
                            new UpdateOptions().upsert(true));
                    statCount++;
                    if (statCount % 200 == 0) {
                        status.stepDone.set(count + statCount);
                        log.info("[MysqlConvert] players (stats) — " + statCount + " processed...");
                    }
                }
            }
            count += statCount;
            consoleAndSender(sender, "§7  → stats rows: " + statCount);
        }

        status.finish("players", count);
        consoleAndSender(sender, "§a  ✓ players: §f" + count + " §atotal row(s) migrated.");
    }

    // ── genpvp_logs → logs ───────────────────────────────────────────────────

    private void migrateLogs(Connection conn, MongoDatabase db, CommandSender sender) throws SQLException {
        status.step("logs");
        if (!tableExists(conn, "genpvp_logs")) {
            status.skip("logs", "table not found");
            consoleAndSender(sender, "§e  ~ logs: table not found, skipping.");
            return;
        }

        MongoCollection<Document> coll = db.getCollection("logs");
        int count = 0;
        int batchSize = 500;
        List<Document> batch = new ArrayList<>(batchSize);

        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT * FROM genpvp_logs")) {
            ResultSetMetaData meta = rs.getMetaData();

            while (rs.next()) {
                Document doc = new Document();
                for (int i = 1; i <= meta.getColumnCount(); i++) {
                    String col = meta.getColumnName(i);
                    if (col.equalsIgnoreCase("id")) continue;
                    Object val = rs.getObject(i);
                    if (val instanceof java.sql.Timestamp ts) doc.append(col, ts.getTime());
                    else doc.append(col, val);
                }
                batch.add(doc);

                if (batch.size() >= batchSize) {
                    coll.insertMany(batch);
                    count += batch.size();
                    batch.clear();
                    status.stepDone.set(count);
                    log.info("[MysqlConvert] logs — " + count + " inserted...");
                }
            }
            if (!batch.isEmpty()) {
                coll.insertMany(batch);
                count += batch.size();
            }
        }

        status.finish("logs", count);
        consoleAndSender(sender, "§a  ✓ logs: §f" + count + " §arow(s) migrated.");
    }

    // ── genpvp_regions → regions ─────────────────────────────────────────────

    private void migrateRegions(Connection conn, MongoDatabase db, CommandSender sender) throws SQLException {
        status.step("regions");
        if (!tableExists(conn, "genpvp_regions")) {
            status.skip("regions", "table not found");
            consoleAndSender(sender, "§e  ~ regions: table not found, skipping.");
            return;
        }

        MongoCollection<Document> coll = db.getCollection("regions");
        int count = 0;

        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT * FROM genpvp_regions")) {
            while (rs.next()) {
                String name = rs.getString("name");
                Document doc = new Document("_id", name)
                        .append("type",     rs.getString("type"))
                        .append("world",    rs.getString("world"))
                        .append("min_x",    rs.getInt("min_x"))
                        .append("min_y",    rs.getInt("min_y"))
                        .append("min_z",    rs.getInt("min_z"))
                        .append("max_x",    rs.getInt("max_x"))
                        .append("max_y",    rs.getInt("max_y"))
                        .append("max_z",    rs.getInt("max_z"))
                        .append("priority", rs.getDouble("priority"));

                coll.replaceOne(eq("_id", name), doc, new ReplaceOptions().upsert(true));
                count++;
                log.info("[MysqlConvert] regions — inserted: " + name);
            }
        }

        status.finish("regions", count);
        consoleAndSender(sender, "§a  ✓ regions: §f" + count + " §arow(s) migrated.");
    }

    // ── genpvp_protected_blocks → protected_blocks ───────────────────────────

    private void migrateProtectedBlocks(Connection conn, MongoDatabase db, CommandSender sender) throws SQLException {
        status.step("protected_blocks");
        if (!tableExists(conn, "genpvp_protected_blocks")) {
            status.skip("protected_blocks", "table not found");
            consoleAndSender(sender, "§e  ~ protected_blocks: table not found, skipping.");
            return;
        }

        MongoCollection<Document> coll = db.getCollection("protected_blocks");
        int count = 0;
        List<Document> batch = new ArrayList<>(500);

        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT * FROM genpvp_protected_blocks")) {
            while (rs.next()) {
                batch.add(new Document()
                        .append("world", rs.getString("world"))
                        .append("x", rs.getInt("x"))
                        .append("y", rs.getInt("y"))
                        .append("z", rs.getInt("z")));

                if (batch.size() >= 500) {
                    insertIgnoreDuplicates(coll, batch);
                    count += batch.size();
                    batch.clear();
                    status.stepDone.set(count);
                    log.info("[MysqlConvert] protected_blocks — " + count + " inserted...");
                }
            }
            if (!batch.isEmpty()) {
                insertIgnoreDuplicates(coll, batch);
                count += batch.size();
            }
        }

        status.finish("protected_blocks", count);
        consoleAndSender(sender, "§a  ✓ protected_blocks: §f" + count + " §arow(s) migrated.");
    }

    // ── genpvp_enderchest → enderchest ───────────────────────────────────────

    private void migrateEnderChest(Connection conn, MongoDatabase db, CommandSender sender) throws SQLException {
        status.step("enderchest");
        if (!tableExists(conn, "genpvp_enderchest")) {
            status.skip("enderchest", "table not found");
            consoleAndSender(sender, "§e  ~ enderchest: table not found, skipping.");
            return;
        }

        MongoCollection<Document> coll = db.getCollection("enderchest");
        int count = 0;

        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT * FROM genpvp_enderchest")) {
            while (rs.next()) {
                String uuid  = rs.getString("uuid");
                int    slot  = rs.getInt("slot");
                byte[] item  = rs.getBytes("item");
                if (item == null || item.length == 0) continue;

                coll.updateOne(
                        new Document("uuid", uuid).append("slot", slot),
                        new Document("$set", new Document("uuid", uuid)
                                .append("slot", slot)
                                .append("item", new Binary(item))),
                        new UpdateOptions().upsert(true));
                count++;

                if (count % 200 == 0) {
                    status.stepDone.set(count);
                    log.info("[MysqlConvert] enderchest — " + count + " processed...");
                }
            }
        }

        status.finish("enderchest", count);
        consoleAndSender(sender, "§a  ✓ enderchest: §f" + count + " §arow(s) migrated.");
    }

    // ── genpvp_teams → teams ─────────────────────────────────────────────────

    private void migrateTeams(Connection conn, MongoDatabase db, CommandSender sender) throws SQLException {
        status.step("teams");
        if (!tableExists(conn, "genpvp_teams")) {
            status.skip("teams", "table not found");
            consoleAndSender(sender, "§e  ~ teams: table not found, skipping.");
            return;
        }

        MongoCollection<Document> coll = db.getCollection("teams");
        int count = 0;

        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT * FROM genpvp_teams")) {
            ResultSetMetaData meta = rs.getMetaData();
            while (rs.next()) {
                String name = rs.getString("name");
                Document doc = new Document("_id", name)
                        .append("owner_uuid", rs.getString("owner_uuid"))
                        .append("owner_name", rs.getString("owner_name"))
                        .append("created_at", rs.getLong("created_at"))
                        .append("pvp_enabled", getBoolCol(rs, meta, "pvp_enabled"))
                        .append("points",      getIntCol(rs, meta, "points"));

                coll.replaceOne(eq("_id", name), doc, new ReplaceOptions().upsert(true));
                count++;
                log.info("[MysqlConvert] teams — inserted: " + name);
            }
        }

        status.finish("teams", count);
        consoleAndSender(sender, "§a  ✓ teams: §f" + count + " §arow(s) migrated.");
    }

    // ── genpvp_team_members → team_members ───────────────────────────────────

    private void migrateTeamMembers(Connection conn, MongoDatabase db, CommandSender sender) throws SQLException {
        status.step("team_members");
        if (!tableExists(conn, "genpvp_team_members")) {
            status.skip("team_members", "table not found");
            consoleAndSender(sender, "§e  ~ team_members: table not found, skipping.");
            return;
        }

        MongoCollection<Document> coll = db.getCollection("team_members");
        int count = 0;

        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT * FROM genpvp_team_members")) {
            while (rs.next()) {
                String uuid = rs.getString("uuid");
                Document doc = new Document("_id", uuid)
                        .append("player_name", rs.getString("player_name"))
                        .append("team_name",   rs.getString("team_name"))
                        .append("role",        rs.getString("role"))
                        .append("joined_at",   rs.getLong("joined_at"));

                coll.replaceOne(eq("_id", uuid), doc, new ReplaceOptions().upsert(true));
                count++;
                log.info("[MysqlConvert] team_members — inserted: " + uuid);
            }
        }

        status.finish("team_members", count);
        consoleAndSender(sender, "§a  ✓ team_members: §f" + count + " §arow(s) migrated.");
    }

    // ── genpvp_devil_fruits → devil_fruits ───────────────────────────────────

    private void migrateDevilFruits(Connection conn, MongoDatabase db, CommandSender sender) throws SQLException {
        status.step("devil_fruits");
        if (!tableExists(conn, "genpvp_devil_fruits")) {
            status.skip("devil_fruits", "table not found");
            consoleAndSender(sender, "§e  ~ devil_fruits: table not found, skipping.");
            return;
        }

        MongoCollection<Document> coll = db.getCollection("devil_fruits");
        int count = 0;
        List<Document> batch = new ArrayList<>(500);

        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT * FROM genpvp_devil_fruits")) {
            ResultSetMetaData meta = rs.getMetaData();
            while (rs.next()) {
                batch.add(new Document()
                        .append("uuid",       rs.getString("uuid"))
                        .append("fruit_name", rs.getString("fruit_name"))
                        .append("equipped",   getBoolCol(rs, meta, "equipped")));

                if (batch.size() >= 500) {
                    insertIgnoreDuplicates(coll, batch);
                    count += batch.size();
                    batch.clear();
                    status.stepDone.set(count);
                    log.info("[MysqlConvert] devil_fruits — " + count + " inserted...");
                }
            }
            if (!batch.isEmpty()) {
                insertIgnoreDuplicates(coll, batch);
                count += batch.size();
            }
        }

        status.finish("devil_fruits", count);
        consoleAndSender(sender, "§a  ✓ devil_fruits: §f" + count + " §arow(s) migrated.");
    }

    // ── genpvp_fruit_blacklist → fruit_blacklist ─────────────────────────────

    private void migrateFruitBlacklist(Connection conn, MongoDatabase db, CommandSender sender) throws SQLException {
        status.step("fruit_blacklist");
        if (!tableExists(conn, "genpvp_fruit_blacklist")) {
            status.skip("fruit_blacklist", "table not found");
            consoleAndSender(sender, "§e  ~ fruit_blacklist: table not found, skipping.");
            return;
        }

        MongoCollection<Document> coll = db.getCollection("fruit_blacklist");
        int count = 0;

        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT * FROM genpvp_fruit_blacklist")) {
            while (rs.next()) {
                String uuid = rs.getString("uuid");
                coll.replaceOne(eq("_id", uuid),
                        new Document("_id", uuid),
                        new ReplaceOptions().upsert(true));
                count++;
            }
        }

        status.finish("fruit_blacklist", count);
        consoleAndSender(sender, "§a  ✓ fruit_blacklist: §f" + count + " §arow(s) migrated.");
    }

    // ── genpvp_custom_items → custom_items ───────────────────────────────────

    private void migrateCustomItems(Connection conn, MongoDatabase db, CommandSender sender) throws SQLException {
        status.step("custom_items");
        if (!tableExists(conn, "genpvp_custom_items")) {
            status.skip("custom_items", "table not found");
            consoleAndSender(sender, "§e  ~ custom_items: table not found, skipping.");
            return;
        }

        MongoCollection<Document> coll = db.getCollection("custom_items");
        int count = 0;

        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT * FROM genpvp_custom_items")) {
            while (rs.next()) {
                String name  = rs.getString("name");
                byte[] data  = rs.getBytes("item_data");
                long created = rs.getLong("created_at");
                if (data == null) continue;

                coll.replaceOne(eq("_id", name),
                        new Document("_id", name)
                                .append("item_data", new Binary(data))
                                .append("created_at", created),
                        new ReplaceOptions().upsert(true));
                count++;
                log.info("[MysqlConvert] custom_items — inserted: " + name);
            }
        }

        status.finish("custom_items", count);
        consoleAndSender(sender, "§a  ✓ custom_items: §f" + count + " §arow(s) migrated.");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private boolean tableExists(Connection conn, String table) {
        try (ResultSet rs = conn.getMetaData().getTables(null, null, table, null)) {
            return rs.next();
        } catch (SQLException e) {
            return false;
        }
    }

    private boolean hasColumn(ResultSetMetaData meta, String col) throws SQLException {
        for (int i = 1; i <= meta.getColumnCount(); i++)
            if (meta.getColumnName(i).equalsIgnoreCase(col)) return true;
        return false;
    }

    private double getDoubleCol(ResultSet rs, ResultSetMetaData meta, String col) throws SQLException {
        return hasColumn(meta, col) ? rs.getDouble(col) : 0.0;
    }

    private int getIntCol(ResultSet rs, ResultSetMetaData meta, String col) throws SQLException {
        return hasColumn(meta, col) ? rs.getInt(col) : 0;
    }

    private boolean getBoolCol(ResultSet rs, ResultSetMetaData meta, String col) throws SQLException {
        return getBoolCol(rs, meta, col, false);
    }

    private boolean getBoolCol(ResultSet rs, ResultSetMetaData meta, String col, boolean def) throws SQLException {
        return hasColumn(meta, col) ? rs.getBoolean(col) : def;
    }

    private void insertIgnoreDuplicates(MongoCollection<Document> coll, List<Document> docs) {
        try {
            coll.insertMany(docs, new InsertManyOptions().ordered(false));
        } catch (MongoBulkWriteException ignored) {
            // Duplicate keys on re-run — safe to skip
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) return List.of("convert");
        if (args.length == 2 && args[0].equalsIgnoreCase("convert")) return List.of("mongo", "status");
        return Collections.emptyList();
    }
}
