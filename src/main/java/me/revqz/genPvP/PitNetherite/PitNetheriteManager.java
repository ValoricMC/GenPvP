package me.revqz.genPvP.PitNetherite;

import me.revqz.genPvP.GenPvP;
import me.revqz.genPvP.Protect.ProtectRegion;
import me.revqz.genPvP.Protect.RegionManager;
import me.revqz.genPvP.Protect.flags.RegionType;
import me.revqz.genPvP.util.ColorUtil;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;

public class PitNetheriteManager {

    private static final long RESET_INTERVAL_TICKS = 15L * 60L * 20L; 

    private final GenPvP        plugin;
    private final RegionManager regionManager;

    public PitNetheriteManager(GenPvP plugin, RegionManager regionManager) {
        this.plugin        = plugin;
        this.regionManager = regionManager;

        plugin.getServer().getScheduler().runTaskTimer(
                plugin, this::reset, RESET_INTERVAL_TICKS, RESET_INTERVAL_TICKS);
    }

    public void forceReset() {
        reset();
    }

    private void reset() {
        boolean anyFilled = false;
        for (String name : regionManager.getRegionNames()) {
            ProtectRegion region = regionManager.getRegion(name);
            if (region == null || region.getType() != RegionType.PITNETHERITE) continue;
            fillDebris(region);
            anyFilled = true;
        }
        if (anyFilled) broadcastReset();
    }

    private void fillDebris(ProtectRegion region) {
        World world = Bukkit.getWorld(region.getWorld());
        if (world == null) return;

        for (int x = region.getMinX(); x <= region.getMaxX(); x++) {
            for (int z = region.getMinZ(); z <= region.getMaxZ(); z++) {
                if (!world.isChunkLoaded(x >> 4, z >> 4)) continue;
                for (int y = region.getMinY(); y <= region.getMaxY(); y++) {
                    world.getBlockAt(x, y, z).setType(Material.ANCIENT_DEBRIS, false);
                }
            }
        }
    }

    private void broadcastReset() {
        String raw = plugin.getConfig().getString(
                "pit-netherite.reset-message",
                "&#FCD05C&lPIT &8» &7The Ancient Debris has regenerated!");
        if (raw != null && !raw.isEmpty()) {
            Bukkit.broadcastMessage(ColorUtil.colorize(raw));
        }
    }
}
