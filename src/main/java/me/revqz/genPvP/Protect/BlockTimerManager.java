package me.revqz.genPvP.Protect;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Map;

public class BlockTimerManager {

    private final Map<Location, BukkitTask> activeTimers = new HashMap<>();
    private final JavaPlugin plugin;
    private final long timerTicks;
    private final Sound decaySound;

    public BlockTimerManager(JavaPlugin plugin) {
        this.plugin = plugin;
        int seconds = plugin.getConfig().getInt("block-timer-seconds", 600);
        this.timerTicks = seconds * 20L;
        String soundName = plugin.getConfig().getString("block-decay-sound", "BLOCK_SAND_BREAK");
        Sound parsed;
        try {
            parsed = Sound.valueOf(soundName);
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("Invalid block-decay-sound '" + soundName + "', using BLOCK_SAND_BREAK");
            parsed = Sound.BLOCK_SAND_BREAK;
        }
        this.decaySound = parsed;
    }

    /**
     * Starts (or restarts) the decay timer for {@code blockLoc}.
     * Callers must pass a block-aligned Location (from {@code Block.getLocation()}).
     */
    public void trackBlock(Location blockLoc) {
        // Cancel any existing timer for this location before creating a new one
        BukkitTask existing = activeTimers.remove(blockLoc);
        if (existing != null) existing.cancel();

        BukkitTask task = plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            blockLoc.getBlock().setType(Material.AIR);
            blockLoc.getWorld().playSound(blockLoc, decaySound, 1.0f, 1.0f);
            activeTimers.remove(blockLoc);
        }, timerTicks);
        activeTimers.put(blockLoc, task);
    }

    /** Callers must pass a block-aligned Location (from {@code Block.getLocation()}). */
    public void cancelTimer(Location blockLoc) {
        BukkitTask task = activeTimers.remove(blockLoc);
        if (task != null) task.cancel();
    }

    public void cancelAll() {
        activeTimers.values().forEach(BukkitTask::cancel);
        activeTimers.clear();
    }

    public boolean isTracking(Location loc) {
        return activeTimers.containsKey(loc.getBlock().getLocation());
    }
}
