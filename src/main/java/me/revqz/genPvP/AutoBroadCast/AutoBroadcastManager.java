package me.revqz.genPvP.AutoBroadcast;

import me.revqz.genPvP.GenPvP;
import me.revqz.genPvP.util.ColorUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class AutoBroadcastManager {

    private static final LegacyComponentSerializer LEGACY =
            LegacyComponentSerializer.legacySection();

    private final GenPvP plugin;
    
    private final List<List<Component>> messages = new ArrayList<>();
    private int index = 0;
    private BukkitTask task;

    public AutoBroadcastManager(GenPvP plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        messages.clear();
        index = 0;

        List<Map<?, ?>> entries = plugin.getConfig().getMapList("autobroadcast.messages");
        for (Map<?, ?> entry : entries) {
            Object textObj = entry.get("text");
            if (textObj == null) continue;

            ClickEvent click = null;
            Object urlObj = entry.get("url");
            if (urlObj != null) {
                String url = urlObj.toString().strip();
                if (!url.isEmpty()) click = ClickEvent.openUrl(url);
            }

            List<String> rawLines = toStringList(textObj);
            List<Component> lines = new ArrayList<>(rawLines.size());
            for (String raw : rawLines) {
                Component c = LEGACY.deserialize(ColorUtil.colorize(raw));
                if (click != null) c = c.clickEvent(click);
                lines.add(c);
            }

            if (!lines.isEmpty()) messages.add(lines);
        }

        if (messages.isEmpty()) {
            plugin.getLogger().info("[AutoBroadcast] No messages configured — disabled.");
            return;
        }

        int intervalSeconds = plugin.getConfig().getInt("autobroadcast.interval", 60);
        long intervalTicks  = Math.max(1, intervalSeconds) * 20L;

        task = new BukkitRunnable() {
            @Override
            public void run() {
                List<Component> lines = messages.get(index % messages.size());
                index = (index + 1) % messages.size();
                for (Component line : lines) Bukkit.broadcast(line);
            }
        }.runTaskTimer(plugin, intervalTicks, intervalTicks);

        plugin.getLogger().info("[AutoBroadcast] Started — " + messages.size() +
                " message(s), interval " + intervalSeconds + "s.");
    }

    public void shutdown() {
        if (task != null) task.cancel();
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
}
