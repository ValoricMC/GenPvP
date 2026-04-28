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

/**
 * Handles the {@code /spawn} command with a configurable countdown.
 *
 * <ul>
 *   <li>Counts down from {@code spawn.warp-countdown} seconds (default 5) to 0,
 *       displayed in the player's action bar to one decimal place.</li>
 *   <li>If the player moves more than {@code spawn.move-threshold} blocks on
 *       the XZ plane during the countdown, the warp is cancelled with an
 *       action-bar error message.</li>
 *   <li>Re-running {@code /spawn} mid-countdown restarts the timer.</li>
 *   <li>Thread-safe: pending tasks are tracked in a {@link ConcurrentHashMap}
 *       and all scheduling/cancellation happens on Bukkit's main thread.</li>
 * </ul>
 *
 * Action-bar messages (both configurable via config.yml):
 * <pre>
 *   spawn.messages.countdown  — e.g. "&#4CC2FA&lS … &8» &7 %time%s"
 *   spawn.messages.cancelled  — shown when the player moves
 * </pre>
 */
public class SpawnCommand implements CommandExecutor {

    private static final LegacyComponentSerializer LEGACY =
            LegacyComponentSerializer.legacySection();

    // Interval between countdown ticks — 2 game ticks = 0.1 s, giving 1-decimal precision
    private static final int    TICK_INTERVAL = 2;
    private static final double TICK_SECONDS  = TICK_INTERVAL / 20.0; // 0.1

    private final GenPvP     plugin;
    private final SpawnHandler spawnHandler;

    /** One entry per player with an active countdown. Keyed by UUID. */
    private final Map<UUID, BukkitTask> pending = new ConcurrentHashMap<>();

    public SpawnCommand(GenPvP plugin, SpawnHandler spawnHandler) {
        this.plugin       = plugin;
        this.spawnHandler = spawnHandler;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) return true;

        // Cancel any warp already in progress for this player
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

        // Snapshot XZ origin — Y intentionally excluded to avoid gravity false-positives
        double originX = player.getLocation().getX();
        double originZ = player.getLocation().getZ();
        double threshold2 = threshold * threshold; // compare squared distance (no sqrt needed)

        // remaining[0] counts down on the main thread — safe because the runnable
        // is a main-thread BukkitRunnable; no cross-thread mutation occurs.
        double[] remaining = {totalSeconds};

        BukkitTask task = new BukkitRunnable() {
            @Override
            public void run() {
                // Player disconnected — clean up silently
                if (!player.isOnline()) {
                    pending.remove(player.getUniqueId());
                    return;
                }

                // Movement check on XZ plane
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

                // Countdown finished — teleport
                if (remaining[0] <= 0.0) {
                    player.teleport(spawn);
                    player.playSound(player.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 0.4f, 1.5f);
                    player.sendActionBar(Component.empty());
                    pending.remove(player.getUniqueId());
                    cancel();
                    return;
                }

                // Display current time to 1 decimal place
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

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Cancels and removes any pending countdown for {@code id}. */
    private void cancelPending(UUID id) {
        BukkitTask old = pending.remove(id);
        if (old != null) old.cancel();
    }

    /** Cancels all active countdowns — call from {@link GenPvP#onDisable()}. */
    public void shutdown() {
        pending.values().forEach(BukkitTask::cancel);
        pending.clear();
    }

    /** Converts a raw {@code &}-coded string to an Adventure {@link Component}. */
    private static Component component(String raw) {
        // ColorUtil.colorize handles both &#RRGGBB hex and &legacy codes → §-signs
        return LEGACY.deserialize(me.revqz.genPvP.util.ColorUtil.colorize(raw));
    }
}
