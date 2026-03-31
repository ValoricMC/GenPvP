package me.revqz.genPvP.Protect;

import me.revqz.genPvP.Protect.flags.RegionRule;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityKnockbackEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class ProtectListener implements Listener {

    private static final Set<Material> INTERACT_BLOCKS = Set.of(
            Material.ANVIL, Material.CHIPPED_ANVIL, Material.DAMAGED_ANVIL,
            Material.ENCHANTING_TABLE, Material.GRINDSTONE, Material.CRAFTING_TABLE
    );

    private final RegionManager regionManager;
    private final BlockTimerManager blockTimerManager;
    private final ProtectCommand protectCommand;
    private final Set<Location> creativePlacedBlocks = new HashSet<>();

    public ProtectListener(RegionManager regionManager, BlockTimerManager blockTimerManager,
                           ProtectCommand protectCommand) {
        this.regionManager = regionManager;
        this.blockTimerManager = blockTimerManager;
        this.protectCommand = protectCommand;
    }

    private boolean isRegionDenying(Location loc, Player bypassPlayer, RegionRule rule) {
        if (bypassPlayer != null && protectCommand.isBypassing(bypassPlayer)) return false;
        List<ProtectRegion> regions = regionManager.getRegionsAt(loc);
        if (regions.isEmpty()) return false;
        return regions.stream().anyMatch(r -> !r.hasRule(rule));
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onEntityDamage(EntityDamageEvent event) {
        Entity entity = event.getEntity();
        Player bypassCheck = entity instanceof Player p ? p : null;
        if (isRegionDenying(entity.getLocation(), bypassCheck, RegionRule.ALLOW_DAMAGE)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        Location blockLoc = event.getBlock().getLocation();

        // Global rule: creative-placed blocks cannot be broken unless in an ALLOW_BREAK region
        if (creativePlacedBlocks.contains(blockLoc) && !protectCommand.isBypassing(player)) {
            boolean inAllowBreakRegion = regionManager.getRegionsAt(blockLoc)
                    .stream().anyMatch(r -> r.hasRule(RegionRule.ALLOW_BREAK));
            if (!inAllowBreakRegion) {
                event.setCancelled(true);
                return;
            }
        }

        if (isRegionDenying(blockLoc, player, RegionRule.ALLOW_BREAK)) {
            event.setCancelled(true);
            return;
        }

        blockTimerManager.cancelTimer(blockLoc);
        creativePlacedBlocks.remove(blockLoc);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onBlockPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        Location blockLoc = event.getBlock().getLocation();

        if (isRegionDenying(blockLoc, player, RegionRule.ALLOW_PLACE)) {
            event.setCancelled(true);
            return;
        }

        if (player.getGameMode() == GameMode.CREATIVE) {
            creativePlacedBlocks.add(blockLoc);
        } else if (player.getGameMode() == GameMode.SURVIVAL) {
            blockTimerManager.trackBlock(blockLoc);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onCreatureSpawn(CreatureSpawnEvent event) {
        if (event.getEntity() instanceof Player) return;
        if (isRegionDenying(event.getLocation(), null, RegionRule.ALLOW_MOB_SPAWN)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onKnockback(EntityKnockbackEvent event) {
        Entity entity = event.getEntity();
        Player bypassCheck = entity instanceof Player p ? p : null;
        if (isRegionDenying(entity.getLocation(), bypassCheck, RegionRule.ALLOW_KNOCKBACK)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        Player player = event.getPlayer();

        if (event.getMaterial() == Material.FLINT_AND_STEEL) {
            if (isRegionDenying(player.getLocation(), player, RegionRule.ALLOW_FLINT_STEEL)) {
                event.setCancelled(true);
                return;
            }
        }

        if (event.hasBlock() && INTERACT_BLOCKS.contains(event.getClickedBlock().getType())) {
            Location blockLoc = event.getClickedBlock().getLocation();
            if (isRegionDenying(blockLoc, player, RegionRule.ALLOW_INTERACT)) {
                event.setCancelled(true);
            }
        }
    }
}
