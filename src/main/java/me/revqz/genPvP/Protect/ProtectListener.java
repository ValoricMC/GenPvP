package me.revqz.genPvP.Protect;

import me.revqz.genPvP.Protect.flags.RegionRule;
import me.revqz.genPvP.Protect.flags.RegionType;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.Tag;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.WindCharge;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityKnockbackEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

public class ProtectListener implements Listener {

    private static final Set<Material> INTERACT_BLOCKS = Set.of(
            Material.ANVIL, Material.CHIPPED_ANVIL, Material.DAMAGED_ANVIL,
            Material.ENCHANTING_TABLE, Material.GRINDSTONE, Material.CRAFTING_TABLE,
            Material.FURNACE, Material.BLAST_FURNACE, Material.SMOKER
    );

    private final JavaPlugin plugin;
    private final RegionManager regionManager;
    private final BlockTimerManager blockTimerManager;
    private final ProtectCommand protectCommand;

    // Blocks placed by a creative/admin or via FAWE schematic.
    // ConcurrentHashMap.newKeySet() so WorldEditHook can add from FAWE async threads safely.
    private final Set<Location> creativePlacedBlocks = ConcurrentHashMap.newKeySet();

    public Set<Location> getCreativePlacedBlocks() {
        return creativePlacedBlocks;
    }

    // Pending block restorations for survival players — processed once per tick
    // by a single repeating task instead of scheduling N individual tasks.
    private record RestoreEntry(Player player, Location loc, BlockData data) {}
    private final ConcurrentLinkedQueue<RestoreEntry> restoreQueue = new ConcurrentLinkedQueue<>();

    public ProtectListener(JavaPlugin plugin, RegionManager regionManager,
                           BlockTimerManager blockTimerManager, ProtectCommand protectCommand) {
        this.plugin = plugin;
        this.regionManager = regionManager;
        this.blockTimerManager = blockTimerManager;
        this.protectCommand = protectCommand;

        // Single 1-tick repeating task drains the queue — avoids creating a new
        // scheduler task for every cancelled break (spam-clicking would flood the scheduler).
        plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            RestoreEntry entry;
            while ((entry = restoreQueue.poll()) != null) {
                if (entry.player().isOnline()) {
                    entry.player().sendBlockChange(entry.loc(), entry.data());
                }
            }
        }, 1L, 1L);
    }

    /**
     * Queues a block restore packet for a survival player.
     * The packet is sent on the next tick so it arrives after Paper's own
     * cancelled-break handling, preventing ghost blocks.
     * Creative mode is handled natively by Paper — no packet needed.
     */
    private void restoreBlock(Player player, Location loc, BlockData data) {
        if (player.getGameMode() == GameMode.SURVIVAL) {
            restoreQueue.offer(new RestoreEntry(player, loc, data));
        }
    }

    /**
     * Returns true if the region rules at {@code loc} deny {@code rule}.
     * Uses the zero-allocation anyDenies() path — no List created.
     */
    private boolean isRegionDenying(Location loc, Player bypassPlayer, RegionRule rule) {
        if (bypassPlayer != null && protectCommand.isBypassing(bypassPlayer)) return false;
        return regionManager.anyDenies(loc, rule);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onEntityDamage(EntityDamageEvent event) {
        Entity entity = event.getEntity();
        Player bypassCheck = entity instanceof Player p ? p : null;
        if (isRegionDenying(entity.getLocation(), bypassCheck, RegionRule.ALLOW_DAMAGE)) {
            event.setCancelled(true);
        }
    }

    /**
     * Cancels damage when the ATTACKER is standing in a no-PvP region.
     * This prevents players inside SPAWN / GENS (etc.) from punching out
     * and hitting players who are standing in a region that allows combat.
     *
     * <p>Runs at HIGH priority with ignoreCancelled=true so it only fires
     * when the victim-side check (above) has not already cancelled the event.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        // Resolve the actual attacker (handles arrows / projectiles)
        Player attacker = resolveAttacker(event);
        if (attacker == null) return;

        if (protectCommand.isBypassing(attacker)) return;

        if (regionManager.anyDenies(attacker.getLocation(), RegionRule.ALLOW_DAMAGE)) {
            event.setCancelled(true);
        }
    }

    private static Player resolveAttacker(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player p) return p;
        if (event.getDamager() instanceof Projectile proj
                && proj.getShooter() instanceof Player p) return p;
        return null;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        Location blockLoc = event.getBlock().getLocation();
        Material blockType = event.getBlock().getType();

        // Globally block breaking smooth stone slabs unless bypassing
        if (blockType == Material.SMOOTH_STONE_SLAB) {
            if (!protectCommand.isBypassing(player)) {
                BlockData data = event.getBlock().getBlockData();
                event.setCancelled(true);
                restoreBlock(player, blockLoc, data);
                return;
            }
        }

        // ── SHULKERROOMS: only shulker boxes may be broken ──────────────────
        if (!protectCommand.isBypassing(player)
                && regionManager.insideType(blockLoc, RegionType.SHULKERROOMS)) {
            if (!Tag.SHULKER_BOXES.isTagged(blockType)) {
                BlockData data = event.getBlock().getBlockData();
                event.setCancelled(true);
                restoreBlock(player, blockLoc, data);
                player.sendMessage("§cYou can only mine shulkerboxes in this area.");
                player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
                return;
            }
            // It IS a shulker box — allow mining and skip remaining deny checks
            return;
        }

        // /region protect snapshot — block was present when an admin ran the command.
        // Evict from the set whenever the block is actually broken so replacements
        // placed there are not incorrectly treated as protected.
        if (protectCommand.isProtected(blockLoc)) {
            if (protectCommand.isBypassing(player)) {
                // Bypass player — always allow, evict from snapshot
                protectCommand.removeProtected(blockLoc);
            } else if (regionManager.insideAnyType(blockLoc, RegionType.GENS, RegionType.OPMINESGENS)) {
                // GENS / OPMINESGENS regions regenerate blocks — allow mining and evict the
                // snapshot so the regenerated block isn't treated as protected.
                // GLOBAL and other permissive types do NOT override the protect snapshot.
                protectCommand.removeProtected(blockLoc);
            } else {
                // No region overrides protection — block is unbreakable
                BlockData data = event.getBlock().getBlockData();
                event.setCancelled(true);
                restoreBlock(player, blockLoc, data);
                return;
            }
        }

        // Creative/FAWE-placed blocks can only be broken if the region explicitly allows it
        if (creativePlacedBlocks.contains(blockLoc) && !protectCommand.isBypassing(player)) {
            // inRegionAndAllAllow: true only if there IS a region here AND all of them allow ALLOW_BREAK
            if (!regionManager.inRegionAndAllAllow(blockLoc, RegionRule.ALLOW_BREAK)) {
                BlockData data = event.getBlock().getBlockData();
                event.setCancelled(true);
                restoreBlock(player, blockLoc, data);
                return;
            }
        }

        if (isRegionDenying(blockLoc, player, RegionRule.ALLOW_BREAK)) {
            BlockData data = event.getBlock().getBlockData();
            event.setCancelled(true);
            restoreBlock(player, blockLoc, data);
            return;
        }

        blockTimerManager.cancelTimer(blockLoc);
        creativePlacedBlocks.remove(blockLoc);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onBlockPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        Location blockLoc = event.getBlock().getLocation();
        Material placedType = event.getBlock().getType();

        // ── SHULKERROOMS: only shulker boxes may be placed ──────────────────
        if (!protectCommand.isBypassing(player)
                && regionManager.insideType(blockLoc, RegionType.SHULKERROOMS)) {
            if (!Tag.SHULKER_BOXES.isTagged(placedType)) {
                event.setCancelled(true);
                player.sendMessage("§cYou can only place shulkerboxes in this area.");
                player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
                return;
            }
            // It IS a shulker box — allow placement and skip remaining deny checks
            return;
        }

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
    public void onProjectileLaunch(ProjectileLaunchEvent event) {
        if (!(event.getEntity() instanceof WindCharge)) return;
        if (!(event.getEntity().getShooter() instanceof Player player)) return;
        if (protectCommand.isBypassing(player)) return;
        if (regionManager.anyDenies(player.getLocation(), RegionRule.ALLOW_WIND_CHARGE)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onEnderPearlTeleport(PlayerTeleportEvent event) {
        if (event.getCause() != PlayerTeleportEvent.TeleportCause.ENDER_PEARL) return;
        Player player = event.getPlayer();
        if (protectCommand.isBypassing(player)) return;

        if (regionManager.anyDenies(event.getFrom(), RegionRule.ALLOW_PEARL)) {
            event.setCancelled(true);
            if (regionManager.insideType(event.getFrom(), RegionType.PIT)) {
                player.sendMessage("§cYou cannot ender pearl out of the PIT!");
            }
            return;
        }

        if (regionManager.anyDenies(event.getTo(), RegionRule.ALLOW_PEARL)) {
            event.setCancelled(true);
            if (regionManager.insideType(event.getTo(), RegionType.SPAWN)) {
                player.sendMessage("§cYou cannot ender pearl into Spawn!");
            }
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
            if (isRegionDenying(event.getClickedBlock().getLocation(), player, RegionRule.ALLOW_INTERACT)) {
                event.setCancelled(true);
            }
        }
    }
}
