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

    // Thread-safe set tracking block locations that already have a regen task queued.
    // Prevents duplicate tasks when multiple players break (or the server processes multiple
    // packets for) the same block within the same tick window.
    private final Set<Location> pendingRegen = ConcurrentHashMap.newKeySet();

    // Define globally protected blocks that can never be mined (even in gens regions)
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

        // 1. Global hard-protection — these blocks are never mineable regardless of region
        if (UNMINEABLE_BLOCKS.contains(material)) {
            if (!protectCommand.isBypassing(player)) {
                event.setCancelled(true);
                return;
            }
        }

        // 2. Only apply regen logic inside GENS / OPMINESGENS regions.
        //    insideAnyType() uses the chunk spatial index — O(1) hash + no List allocation.
        Location blockLoc = block.getLocation();
        if (!regionManager.insideAnyType(blockLoc, RegionType.GENS, RegionType.OPMINESGENS)) return;

        // Snapshot data before Bukkit converts the block to AIR (happens after event processing)
        BlockData originalData = block.getBlockData().clone();

        // Guard: if a regen is already scheduled for this location, cancel the break.
        // The block is currently AIR (already broken this regen cycle); accepting another
        // break while AIR causes the client to receive no sound/drops and Paper may begin
        // ignoring rapid same-location packets as spam. Cancelling here sends the client a
        // clean "nope" signal so it stops sending dig packets until the regen restores the ore.
        if (!pendingRegen.add(blockLoc)) {
            event.setCancelled(true);
            return;
        }

        // Let the event proceed normally so drops and XP fire as usual.
        // Schedule block restoration on the main thread (Bukkit requires it).
        // runTask (delay 0) queues the restore to run at the START of the next tick,
        // before network packets for that tick are processed — ensuring the ore is
        // always ORE again before the player's next dig packet reaches the server.
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            block.setBlockData(originalData, false); // false = skip physics update, avoids cascading drops
            pendingRegen.remove(blockLoc);
        });
    }
}
