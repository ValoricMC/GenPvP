package me.revqz.genPvP.Koth;

import com.mongodb.client.AggregateIterable;
import com.mongodb.client.MongoDatabase;
import me.revqz.genPvP.Database.DatabaseManager;
import me.revqz.genPvP.Database.LogManager;
import me.revqz.genPvP.GenPvP;
import me.revqz.genPvP.Protect.ProtectRegion;
import me.revqz.genPvP.Protect.RegionManager;
import me.revqz.genPvP.Teams.TeamManager;
import me.revqz.genPvP.Webhook.WebhookSender;
import me.revqz.genPvP.util.ColorUtil;
import org.bson.Document;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;
import java.util.stream.Collectors;

public class KothManager {

    private final GenPvP plugin;
    private final RegionManager regionManager;
    private final LogManager logManager;
    private final DatabaseManager databaseManager;
    private final TeamManager teamManager;

    private int intervalSetting;
    private int captureTimeSetting;
    private int maxDurationSetting;
    private String rewardCommand;

    private final Map<String, List<String>> msgCache = new HashMap<>();

    private boolean isKothActive          = false;
    private int     timeUntilNextKoth;
    private int     captureTimeRemaining;
    private int     kothElapsedSeconds   = 0;
    private UUID    capturingPlayer      = null;
    private String  capturingPlayerName  = null;  

    private ProtectRegion cachedKothRegion = null;

    private final Map<Integer, KothWinnerEntry> topWinnersCache = new HashMap<>();

    private static final String[] MESSAGE_KEYS = {
        "start", "stop", "win", "new_capturer", "capture_interrupted",
        "actionbar", "already_active", "not_active", "force_started", "force_stopped",
        "timeout"
    };

    public KothManager(GenPvP plugin, RegionManager regionManager,
                       LogManager logManager, DatabaseManager databaseManager,
                       TeamManager teamManager) {
        this.plugin          = plugin;
        this.regionManager   = regionManager;
        this.logManager      = logManager;
        this.databaseManager = databaseManager;
        this.teamManager     = teamManager;

        loadConfig();
        this.timeUntilNextKoth = intervalSetting;
        startKothLoop();
        startLeaderboardTask();
    }

    public void loadConfig() {
        intervalSetting    = plugin.getConfig().getInt("koth.interval", 1800);
        captureTimeSetting = plugin.getConfig().getInt("koth.capture_time", 120);
        maxDurationSetting = plugin.getConfig().getInt("koth.max_duration", 1800);
        rewardCommand      = plugin.getConfig().getString("koth.reward_command", "say %player% won KOTH!");

        msgCache.clear();
        for (String key : MESSAGE_KEYS) {
            msgCache.put(key, loadLines("koth.messages." + key));
        }
    }

    private List<String> loadLines(String path) {
        if (plugin.getConfig().isList(path)) {
            return plugin.getConfig().getStringList(path).stream()
                    .map(ColorUtil::colorize)
                    .collect(Collectors.toList());
        }
        String raw = plugin.getConfig().getString(path, "");
        if (raw.isBlank()) return Collections.emptyList();
        return List.of(ColorUtil.colorize(raw));
    }

    private void broadcast(String key, String... pairs) {
        List<String> lines = msgCache.getOrDefault(key, Collections.emptyList());
        for (String line : lines) {
            Bukkit.broadcastMessage(sub(line, pairs));
        }
    }

    public String msg(String key, String... pairs) {
        List<String> lines = msgCache.getOrDefault(key, Collections.emptyList());
        if (lines.isEmpty()) return "";
        return sub(String.join("\n", lines), pairs);
    }

    private static String sub(String text, String... pairs) {
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            text = text.replace(pairs[i], pairs[i + 1]);
        }
        return text;
    }

    private void startKothLoop() {
        new BukkitRunnable() {
            @Override
            public void run() {
                if (!isKothActive) {
                    if (--timeUntilNextKoth <= 0) startKoth(false);
                } else {
                    processActiveKoth();
                }
            }
        }.runTaskTimer(plugin, 20L, 20L);
    }

    public void startKoth(boolean force) {
        if (isKothActive) return;
        isKothActive         = true;
        capturingPlayer      = null;
        captureTimeRemaining = captureTimeSetting;
        kothElapsedSeconds   = 0;
        cachedKothRegion     = regionManager.getRegion("KOTHCAPTURE"); 

        logManager.logKoth("START", "MainKoth", null);
        broadcast("start");
        WebhookSender.sendKothStarted();
    }

    public void stopKoth(boolean force) {
        if (!isKothActive) return;
        isKothActive          = false;
        capturingPlayer       = null;
        capturingPlayerName   = null;
        cachedKothRegion      = null;
        timeUntilNextKoth     = intervalSetting;

        logManager.logKoth("STOP", "MainKoth", null);
        broadcast("stop");
        WebhookSender.sendKothStopped(force ? "Admin force-stopped" : "Stopped");
    }

    private void processActiveKoth() {
        if (++kothElapsedSeconds >= maxDurationSetting) {
            timeoutKoth();
            return;
        }

        ProtectRegion kothRegion = cachedKothRegion; 
        if (kothRegion == null) return;

        List<Player> inside = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (kothRegion.contains(p.getLocation())) inside.add(p);
        }

        if (inside.isEmpty()) {
            if (capturingPlayer != null) {
                broadcast("capture_interrupted");
                WebhookSender.sendKothCaptureStopped(
                        capturingPlayerName,
                        capturingPlayer.toString(),
                        teamManager.getTeamName(capturingPlayer));
            }
            capturingPlayer      = null;
            capturingPlayerName  = null;
            captureTimeRemaining = captureTimeSetting;
            return;
        }

        if (inside.size() >= 2) return;

        Player capper = inside.get(0);

        if (capturingPlayer != null && capturingPlayer.equals(capper.getUniqueId())) {
            if (--captureTimeRemaining <= 0) {
                winKoth(capper);
            } else {
                capper.sendActionBar(msg("actionbar", "%time%", String.valueOf(captureTimeRemaining)));
            }
        } else {
            capturingPlayer     = capper.getUniqueId();
            capturingPlayerName = capper.getName();
            captureTimeRemaining = captureTimeSetting;
            broadcast("new_capturer",
                    "%player%", capper.getName(),
                    "%time%",   String.valueOf(captureTimeRemaining));
            WebhookSender.sendKothCaptureStarted(
                    capper.getName(),
                    capper.getUniqueId().toString(),
                    teamManager.getTeamName(capper.getUniqueId()));
        }
    }

    private void timeoutKoth() {
        isKothActive          = false;
        capturingPlayer       = null;
        capturingPlayerName   = null;
        cachedKothRegion      = null;
        kothElapsedSeconds    = 0;
        timeUntilNextKoth     = intervalSetting;

        logManager.logKoth("STOP", "MainKoth", null);
        broadcast("timeout");
        WebhookSender.sendKothStopped("Ran out of time");
    }

    private void winKoth(Player winner) {
        isKothActive          = false;
        capturingPlayer       = null;
        capturingPlayerName   = null;
        cachedKothRegion      = null;
        timeUntilNextKoth     = intervalSetting;

        logManager.logKoth("WIN", "MainKoth", winner.getUniqueId(), winner.getName());
        broadcast("win", "%player%", winner.getName());
        WebhookSender.sendKothCaptured(
                winner.getName(),
                winner.getUniqueId().toString(),
                teamManager.getTeamName(winner.getUniqueId()));

        Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                rewardCommand.replace("%player%", winner.getName()));

        forceUpdateLeaderboardSync();
    }

    private void startLeaderboardTask() {
        new BukkitRunnable() {
            @Override
            public void run() { updateLeaderboard(); }
        }.runTaskTimerAsynchronously(plugin, 100L, 6000L);
    }

    private void forceUpdateLeaderboardSync() {
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, this::updateLeaderboard);
    }

    private void updateLeaderboard() {
        if (!databaseManager.isMongoConnected()) return;

        MongoDatabase db = databaseManager.getDatabase();
        int rank = 1;
        Map<Integer, KothWinnerEntry> fresh = new HashMap<>();

        try {
            
            List<Document> pipeline = List.of(
                    new Document("$match", new Document("type", "KOTH")
                            .append("event_type", "WIN")
                            .append("winner", new Document("$ne", null))),
                    new Document("$group", new Document("_id",
                            new Document("winner", "$winner").append("winner_name", "$winner_name"))
                            .append("wins", new Document("$sum", 1))),
                    new Document("$sort", new Document("wins", -1)),
                    new Document("$limit", 10)
            );

            AggregateIterable<Document> results = db.getCollection("logs").aggregate(pipeline);
            for (Document doc : results) {
                Document idDoc = doc.get("_id", Document.class);
                String name = idDoc != null ? idDoc.getString("winner_name") : "Unknown";
                int wins = doc.getInteger("wins", 0);
                fresh.put(rank++, new KothWinnerEntry(name != null ? name : "Unknown", wins));
            }
        } catch (Exception e) {
            plugin.getLogger().warning("[KothManager] Failed to update leaderboard: " + e.getMessage());
        }

        while (rank <= 10) {
            fresh.put(rank++, new KothWinnerEntry("None", 0));
        }

        topWinnersCache.putAll(fresh);
    }

    public boolean isKothActive()        { return isKothActive; }
    public int getTimeUntilNextKoth()    { return timeUntilNextKoth; }
    public int getCaptureTimeRemaining() { return captureTimeRemaining; }
    public int getCaptureTimeSetting()   { return captureTimeSetting; }

    public String getCapturingPlayerName() {
        if (capturingPlayer == null) return "None";
        Player p = Bukkit.getPlayer(capturingPlayer);
        return p != null ? p.getName() : "None";
    }

    public KothWinnerEntry getTopWinner(int rank) {
        return topWinnersCache.getOrDefault(rank, new KothWinnerEntry("None", 0));
    }

    public static class KothWinnerEntry {
        public final String name;
        public final int wins;

        KothWinnerEntry(String name, int wins) {
            this.name = name;
            this.wins = wins;
        }
    }
}
