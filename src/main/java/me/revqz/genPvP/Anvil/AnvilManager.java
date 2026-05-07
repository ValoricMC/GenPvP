package me.revqz.genPvP.Anvil;

import me.revqz.genPvP.GenPvP;
import me.revqz.genPvP.Protect.ProtectRegion;
import me.revqz.genPvP.Protect.RegionManager;
import me.revqz.genPvP.Protect.flags.RegionType;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;

public class AnvilManager {

    public AnvilManager(GenPvP plugin, RegionManager regionManager) {
        plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            for (String name : regionManager.getRegionNames()) {
                ProtectRegion region = regionManager.getRegion(name);
                if (region == null || region.getType() != RegionType.ANVIL) continue;
                fillAnvils(region);
            }
        }, 20L, 20L);
    }

    private void fillAnvils(ProtectRegion region) {
        World world = Bukkit.getWorld(region.getWorld());
        if (world == null) return;

        for (int x = region.getMinX(); x <= region.getMaxX(); x++) {
            for (int z = region.getMinZ(); z <= region.getMaxZ(); z++) {
                if (!world.isChunkLoaded(x >> 4, z >> 4)) continue;
                for (int y = region.getMinY(); y <= region.getMaxY(); y++) {
                    Block block = world.getBlockAt(x, y, z);
                    if (!Tag.ANVIL.isTagged(block.getType())) {
                        block.setType(Material.ANVIL, false);
                    }
                }
            }
        }
    }
}
