package me.revqz.genPvP.Gens;

import me.revqz.genPvP.GenPvP;
import me.revqz.genPvP.Protect.ProtectCommand;
import me.revqz.genPvP.Protect.RegionManager;
import me.revqz.genPvP.Protect.flags.RegionType;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class GensListener implements Listener {

    private final GenPvP plugin;
    private final RegionManager regionManager;
    private final ProtectCommand protectCommand;

    private final Set<Location> pendingRegen = ConcurrentHashMap.newKeySet();

    private static final Set<Material> UNMINEABLE_BLOCKS = Set.of(
            Material.SMOOTH_STONE_SLAB,
            Material.CRAFTING_TABLE,
            Material.ENDER_CHEST
    );

    public GensListener(GenPvP plugin, RegionManager regionManager, ProtectCommand protectCommand) {
        this.plugin = plugin;
        this.regionManager = regionManager;
        this.protectCommand = protectCommand;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        Block block = event.getBlock();
        Material material = block.getType();

        if (UNMINEABLE_BLOCKS.contains(material)) {
            if (!protectCommand.isBypassing(player)) {
                event.setCancelled(true);
                return;
            }
        }

        Location blockLoc = block.getLocation();
        if (!regionManager.insideAnyType(blockLoc, RegionType.GENS, RegionType.OPMINESGENS)) return;

        BlockData originalData = block.getBlockData().clone();

        if (!pendingRegen.add(blockLoc)) {
            event.setCancelled(true);
            return;
        }

        plugin.getServer().getScheduler().runTask(plugin, () -> {
            block.setBlockData(originalData, false); 
            pendingRegen.remove(blockLoc);
        });
    }
}
