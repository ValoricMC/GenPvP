package me.revqz.genPvP.util;

import me.revqz.genPvP.GenPvP;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class SpawnCommand implements CommandExecutor {

    private static final LegacyComponentSerializer LEGACY =
            LegacyComponentSerializer.legacySection();

    private static final int    TICK_INTERVAL = 2;
    private static final double TICK_SECONDS  = TICK_INTERVAL / 20.0; 

    private final GenPvP     plugin;
    private final SpawnHandler spawnHandler;

    private final Map<UUID, BukkitTask> pending = new ConcurrentHashMap<>();

    public SpawnCommand(GenPvP plugin, SpawnHandler spawnHandler) {
        this.plugin       = plugin;
        this.spawnHandler = spawnHandler;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) return true;

        cancelPending(player.getUniqueId());

        Location spawn = spawnHandler.getSpawnLocation();
        if (spawn == null) {
            player.sendActionBar(component("&cSpawn is not configured."));
            return true;
        }

        double totalSeconds = plugin.getConfig().getDouble("spawn.warp-countdown", 5.0);
        double threshold    = plugin.getConfig().getDouble("spawn.move-threshold",  0.15);
        String countdownFmt = plugin.getConfig()
                .getString("spawn.messages.countdown",
                        "&#4CC2FA&lS&#80D0FD&lP&#B4DEFF&lA&#80D0FD&lW&#4CC2FA&lN &8» &7 %time%s");
        String cancelledMsg = plugin.getConfig()
                .getString("spawn.messages.cancelled",
                        "&cOops you moved, Warp cancelled!");

        double originX = player.getLocation().getX();
        double originZ = player.getLocation().getZ();
        double threshold2 = threshold * threshold; 

        double[] remaining = {totalSeconds};

        BukkitTask task = new BukkitRunnable() {
            @Override
            public void run() {
                
                if (!player.isOnline()) {
                    pending.remove(player.getUniqueId());
                    return;
                }

                Location loc = player.getLocation();
                double dx = loc.getX() - originX;
                double dz = loc.getZ() - originZ;
                if (dx * dx + dz * dz > threshold2) {
                    player.sendActionBar(component(cancelledMsg));
                    player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.7f, 1.0f);
                    pending.remove(player.getUniqueId());
                    cancel();
                    return;
                }

                if (remaining[0] <= 0.0) {
                    player.teleport(spawn);
                    player.playSound(player.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 0.4f, 1.5f);
                    player.sendActionBar(Component.empty());
                    pending.remove(player.getUniqueId());
                    cancel();
                    return;
                }

                String text = countdownFmt.replace("%time%",
                        String.format("%.1f", remaining[0]));
                player.sendActionBar(component(text));

                remaining[0] -= TICK_SECONDS;
                if (remaining[0] < 0.0) remaining[0] = 0.0;
            }
        }.runTaskTimer(plugin, 0L, TICK_INTERVAL);

        pending.put(player.getUniqueId(), task);
        return true;
    }

    private void cancelPending(UUID id) {
        BukkitTask old = pending.remove(id);
        if (old != null) old.cancel();
    }

    public void shutdown() {
        pending.values().forEach(BukkitTask::cancel);
        pending.clear();
    }

    private static Component component(String raw) {
        
        return LEGACY.deserialize(me.revqz.genPvP.util.ColorUtil.colorize(raw));
    }
}
