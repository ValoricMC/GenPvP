package me.revqz.genPvP.util;

import me.revqz.genPvP.GenPvP;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.util.ArrayList;
import java.util.List;

public class WelcomeMessageListener implements Listener {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();

    private final GenPvP plugin;
    private final SkinCache skinCache;

    private boolean enabled;
    private int delayTicks;
    private List<String> lines;

    public WelcomeMessageListener(GenPvP plugin, SkinCache skinCache) {
        this.plugin = plugin;
        this.skinCache = skinCache;
        reload();
    }

    public void reload() {
        var cfg = plugin.getConfig();
        enabled    = cfg.getBoolean("welcome-message.enabled", true);
        delayTicks = cfg.getInt("welcome-message.delay-ticks", 40);
        List<String> raw = cfg.getStringList("welcome-message.lines");
        lines = new ArrayList<>(raw);
        while (lines.size() < 8) lines.add("");
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onJoin(PlayerJoinEvent event) {
        if (!enabled) return;
        Player player = event.getPlayer();
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) return;
            send(player);
        }, delayTicks);
    }

    private void send(Player player) {
        for (int row = 0; row < 8; row++) {
            String head = skinCache.renderRow(player.getUniqueId(), row);
            String line = row < lines.size() ? lines.get(row) : "<head>";
            String combined = line.replace("<head>", head);
            Component msg = LEGACY.deserialize(ColorUtil.colorize(combined));
            player.sendMessage(msg);
        }
    }
}
