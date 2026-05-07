package me.revqz.genPvP.Scoreboard;

import me.revqz.genPvP.GenPvP;
import me.revqz.genPvP.Koth.KothManager;
import me.revqz.genPvP.util.ColorUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Score;
import org.bukkit.scoreboard.Scoreboard;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class ScoreboardManager implements Listener {

    private static final LegacyComponentSerializer LEGACY =
            LegacyComponentSerializer.legacySection();
    private static final MiniMessage MM = MiniMessage.miniMessage();
    
    private static final Pattern MM_TAG = Pattern.compile("<[^>]+>");

    private record LineDef(List<String> frames) {
        String frame(int tick) { return frames.get(tick % frames.size()); }
    }

    private final GenPvP plugin;
    private final KothManager kothManager;
    private final boolean papiEnabled;

    private boolean enabled     = false;
    private String  titleRaw    = "&f&lSCOREBOARD";
    private int     updateTicks = 20;

    private final List<LineDef> lineDefs     = new ArrayList<>();
    
    private final List<LineDef> kothLineDefs = new ArrayList<>();
    private String kothTitleRaw = null;

    private int maxLines = 0;

    private final Map<UUID, int[]>     animTick      = new ConcurrentHashMap<>();
    private final Map<UUID, Scoreboard> boards        = new ConcurrentHashMap<>();
    private final Map<UUID, ScoreboardPanel> panels   = new ConcurrentHashMap<>();
    
    private final Map<UUID, Boolean>   lastKothState  = new ConcurrentHashMap<>();

    private BukkitTask task;

    public ScoreboardManager(GenPvP plugin, KothManager kothManager) {
        this.plugin       = plugin;
        this.kothManager  = kothManager;
        this.papiEnabled  = Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null;
        reload();
    }

    public void reload() {
        if (task != null) { task.cancel(); task = null; }
        lineDefs.clear();
        kothLineDefs.clear();
        kothTitleRaw = null;

        FileConfiguration cfg = plugin.getConfig();
        enabled = cfg.getBoolean("scoreboard.enabled", false);

        if (!enabled) {
            for (Player p : Bukkit.getOnlinePlayers())
                p.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
            boards.clear();
            panels.clear();
            animTick.clear();
            lastKothState.clear();
            return;
        }

        titleRaw    = cfg.getString("scoreboard.title", "&f&lSCOREBOARD");
        updateTicks = Math.max(1, cfg.getInt("scoreboard.update-ticks", 20));

        for (Map<?, ?> entry : cfg.getMapList("scoreboard.lines")) {
            Object text = entry.get("text");
            if (text != null) lineDefs.add(new LineDef(toStringList(text)));
        }

        if (cfg.contains("scoreboard.koth")) {
            kothTitleRaw = cfg.getString("scoreboard.koth.title", titleRaw);
            for (Map<?, ?> entry : cfg.getMapList("scoreboard.koth.lines")) {
                Object text = entry.get("text");
                if (text != null) kothLineDefs.add(new LineDef(toStringList(text)));
            }
        }

        maxLines = Math.max(lineDefs.size(), kothLineDefs.isEmpty() ? 0 : kothLineDefs.size());

        for (Player p : Bukkit.getOnlinePlayers()) setupBoard(p);

        task = new BukkitRunnable() {
            @Override public void run() {
                for (Player p : Bukkit.getOnlinePlayers()) updateBoard(p);
            }
        }.runTaskTimer(plugin, updateTicks, updateTicks);
    }

    public void shutdown() {
        if (task != null) task.cancel();
    }

    public void showBoard(Player player) {
        if (!enabled) return;
        animTick.put(player.getUniqueId(), new int[]{0});
        setupBoard(player);
    }

    public void hideBoard(Player player) {
        UUID id = player.getUniqueId();
        boards.remove(id);
        panels.remove(id);
        animTick.remove(id);
        lastKothState.remove(id);
        player.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        if (!enabled) return;
        animTick.put(e.getPlayer().getUniqueId(), new int[]{0});
        setupBoard(e.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        animTick.remove(id);
        boards.remove(id);
        panels.remove(id);
        lastKothState.remove(id);
    }

    private void setupBoard(Player player) {
        Scoreboard board = Bukkit.getScoreboardManager().getNewScoreboard();

        boolean kothActive = kothManager != null && kothManager.isKothActive() && !kothLineDefs.isEmpty();
        String activeTitle = (kothActive && kothTitleRaw != null) ? kothTitleRaw : titleRaw;

        Component title = LEGACY.deserialize(ColorUtil.colorize(activeTitle));
        Objective obj   = board.registerNewObjective("genpvp", "dummy", title);
        obj.setDisplaySlot(DisplaySlot.SIDEBAR);

        for (int i = 0; i < maxLines; i++) {
            Score score = obj.getScore(slotEntry(i));
            score.setScore(maxLines - i);
            score.customName(Component.empty());
        }

        FileConfiguration cfg = plugin.getConfig();
        ScoreboardPanel panel = new ScoreboardPanel(
                cfg.getInt("scoreboard.width",  200),
                cfg.getInt("scoreboard.height", 320));
        panel.setFontSize((float) cfg.getDouble("scoreboard.font-size", 14.0));
        panel.setLineHeight(cfg.getInt("scoreboard.line-height", 20));
        panel.setPadding(cfg.getInt("scoreboard.padding-x", 6),
                         cfg.getInt("scoreboard.padding-y", 20));
        String bgRaw = cfg.getString("scoreboard.background", "NONE");
        if (bgRaw != null && bgRaw.startsWith("&#") && bgRaw.length() == 8) {
            try { panel.setBackground(Color.decode("#" + bgRaw.substring(2))); }
            catch (NumberFormatException ignored) {}
        }

        boards.put(player.getUniqueId(), board);
        panels.put(player.getUniqueId(), panel);
        animTick.putIfAbsent(player.getUniqueId(), new int[]{0});
        lastKothState.put(player.getUniqueId(), kothActive);
        player.setScoreboard(board);
        updateBoard(player);
    }

    private void updateBoard(Player player) {
        UUID id = player.getUniqueId();
        Scoreboard board = boards.get(id);
        if (board == null) return;
        Objective obj = board.getObjective("genpvp");
        if (obj == null) return;

        boolean kothActive = kothManager != null && kothManager.isKothActive() && !kothLineDefs.isEmpty();

        Boolean prev = lastKothState.put(id, kothActive);
        if (prev == null || prev != kothActive) {
            String activeTitle = (kothActive && kothTitleRaw != null) ? kothTitleRaw : titleRaw;
            obj.displayName(LEGACY.deserialize(ColorUtil.colorize(activeTitle)));
        }

        List<LineDef> activeDefs = kothActive ? kothLineDefs : lineDefs;

        int[] tick = animTick.computeIfAbsent(id, k -> new int[]{0});
        tick[0]++;

        ScoreboardPanel panel = panels.get(id);
        List<String> rawResolved = panel != null ? new ArrayList<>(maxLines) : null;

        for (int i = 0; i < maxLines; i++) {
            String frame;
            if (i < activeDefs.size()) {
                frame = activeDefs.get(i).frame(tick[0]);
                
                if (papiEnabled && frame.indexOf('%') >= 0)
                    frame = me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(player, frame);
            } else {
                frame = "";
            }
            if (rawResolved != null) rawResolved.add(frame);

            Component display = frame.isEmpty() ? Component.empty() : deserializeFrame(frame);
            obj.getScore(slotEntry(i)).customName(display);
        }

        if (panel != null) panel.setLines(rawResolved);
    }

    private static String slotEntry(int i) {
        ChatColor[] c = ChatColor.values();
        int n = 16;
        return i < n
                ? c[i % n].toString()
                : c[i / n % n].toString() + c[i % n].toString();
    }

    @SuppressWarnings("unchecked")
    private static List<String> toStringList(Object obj) {
        if (obj instanceof List<?> list) {
            List<String> result = new ArrayList<>(list.size());
            for (Object item : list) result.add(item == null ? "" : item.toString());
            return result;
        }
        return List.of(obj.toString());
    }

    private static Component deserializeFrame(String frame) {
        if (!frame.contains("<")) {
            
            return LEGACY.deserialize(ColorUtil.colorize(frame));
        }

        Matcher m = MM_TAG.matcher(frame);
        if (!m.find()) {
            return LEGACY.deserialize(ColorUtil.colorize(frame));
        }

        Component result = Component.empty();
        int last = 0;
        m.reset();

        while (m.find()) {
            
            if (m.start() > last) {
                String before = frame.substring(last, m.start());
                if (!before.isEmpty())
                    result = result.append(LEGACY.deserialize(ColorUtil.colorize(before)));
            }
            
            result = result.append(MM.deserialize(m.group()));
            last = m.end();
        }

        if (last < frame.length()) {
            String after = frame.substring(last);
            if (!after.isEmpty())
                result = result.append(LEGACY.deserialize(ColorUtil.colorize(after)));
        }

        return result;
    }

    public ScoreboardPanel getPanel(UUID playerId) {
        return panels.get(playerId);
    }
}
