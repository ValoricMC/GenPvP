package me.revqz.genPvP.Items;

import me.revqz.genPvP.GenPvP;
import me.revqz.genPvP.Protect.ProtectRegion;
import me.revqz.genPvP.Protect.RegionManager;
import me.revqz.genPvP.Protect.flags.RegionType;
import me.revqz.genPvP.util.ColorUtil;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * box_sphere — OnePiece item ability.
 *
 * Right-click spawns a hollow radius-2 sphere of LIGHT_BLUE_STAINED_GLASS around
 * the player. Only non-solid blocks are replaced (e.g. air, water, tall grass).
 * After 5 s the remaining glass turns YELLOW; after another 5 s it turns GREEN
 * briefly, then is removed.
 *
 * Blocked in: SPAWN, GENS, OPMINESGENS, KOTH, KOTHCAPTURE, PIT, PVPROOM1, PVPROOM2.
 * Cooldown: configurable via onepiece.box-sphere.cooldown-seconds (default 120 s).
 */
public class BoxSphereListener implements Listener {

    private static final int  SPHERE_RADIUS      = 3;
    private static final long PHASE_TICKS        = 100L; // 5 seconds
    private static final long GREEN_LINGER_TICKS = 10L;  // 0.5 s green flash before removal

    private final GenPvP             plugin;
    private final CustomItemRegistry registry;
    private final RegionManager      regionManager;

    private final Map<UUID, Long>           cooldowns    = new ConcurrentHashMap<>();
    private final Map<UUID, List<Location>> activeBlocks = new ConcurrentHashMap<>();

    public BoxSphereListener(GenPvP plugin, CustomItemRegistry registry, RegionManager regionManager) {
        this.plugin        = plugin;
        this.registry      = registry;
        this.regionManager = regionManager;
    }

    // ── Region guard ──────────────────────────────────────────────────────────

    private boolean isRestrictedZone(Location loc) {
        for (ProtectRegion r : regionManager.getRegionsAt(loc)) {
            switch (r.getType()) {
                case SPAWN, GENS, OPMINESGENS, KOTH, KOTHCAPTURE, PIT, PVPROOM1, PVPROOM2 -> {
                    return true;
                }
                default -> { /* allowed */ }
            }
        }
        return false;
    }

    // ── Right-click dispatch ──────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = false)
    public void onRightClick(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) return;

        Player player = event.getPlayer();
        String id = registry.getItemId(player.getInventory().getItemInMainHand());
        if (!"box_sphere".equals(id)) return;

        event.setCancelled(true);

        UUID uuid = player.getUniqueId();
        long now  = System.currentTimeMillis();

        Long expiry = cooldowns.get(uuid);
        if (expiry != null && now < expiry) {
            long remaining = (expiry - now + 999) / 1000;
            String raw = plugin.getConfig().getString(
                    "onepiece.box-sphere.messages.cooldown",
                    "&cBox Sphere is on cooldown! &7%seconds%s remaining.");
            player.sendMessage(ColorUtil.colorize(raw.replace("%seconds%", String.valueOf(remaining))));
            return;
        }

        if (isRestrictedZone(player.getLocation())) {
            String raw = plugin.getConfig().getString(
                    "onepiece.box-sphere.messages.restricted-zone",
                    "&cBox Sphere cannot be used in this area.");
            player.sendMessage(ColorUtil.colorize(raw));
            return;
        }

        long cooldownMs = plugin.getConfig().getLong(
                "onepiece.box-sphere.cooldown-seconds", 120L) * 1000L;
        cooldowns.put(uuid, now + cooldownMs);

        activateSphere(player);
    }

    // ── Sphere activation ─────────────────────────────────────────────────────

    private void activateSphere(Player player) {
        UUID     uuid   = player.getUniqueId();
        Location center = player.getLocation();

        int    r       = SPHERE_RADIUS;
        double innerSq = (double)(r - 1) * (r - 1); // 1.0  — inner boundary (exclusive)
        double outerSq = (double)r * r;              // 4.0  — outer boundary (inclusive)

        List<Location> placed = new ArrayList<>();
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    double distSq = dx * dx + dy * dy + dz * dz;
                    if (distSq <= innerSq || distSq > outerSq) continue;

                    Block block = center.getWorld().getBlockAt(
                            center.getBlockX() + dx,
                            center.getBlockY() + dy,
                            center.getBlockZ() + dz);
                    if (block.getType().isSolid()) continue; // preserve andesite, stone, etc.
                    if (isRestrictedZone(block.getLocation())) continue;

                    block.setType(Material.LIGHT_BLUE_STAINED_GLASS);
                    placed.add(block.getLocation());
                }
            }
        }
        activeBlocks.put(uuid, placed);

        String raw = plugin.getConfig().getString(
                "onepiece.box-sphere.messages.activated",
                "&#73D7F7&lBOX SPHERE &8» &7Sphere deployed!");
        player.sendMessage(ColorUtil.colorize(raw));

        // Phase 2 — turn yellow at t = 5 s
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            List<Location> current = activeBlocks.get(uuid);
            if (current == null) return;
            List<Location> remaining = new ArrayList<>();
            for (Location loc : current) {
                Block b = loc.getBlock();
                if (b.getType() == Material.LIGHT_BLUE_STAINED_GLASS) {
                    b.setType(Material.YELLOW_STAINED_GLASS);
                    remaining.add(loc);
                }
            }
            activeBlocks.put(uuid, remaining);

            // Phase 3 — turn green at t = 10 s, then remove after brief flash
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                List<Location> current2 = activeBlocks.get(uuid);
                if (current2 == null) return;
                List<Location> remaining2 = new ArrayList<>();
                for (Location loc : current2) {
                    Block b = loc.getBlock();
                    if (b.getType() == Material.YELLOW_STAINED_GLASS) {
                        b.setType(Material.LIME_STAINED_GLASS);
                        remaining2.add(loc);
                    }
                }
                activeBlocks.put(uuid, remaining2);

                plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                    List<Location> finalLocs = activeBlocks.remove(uuid);
                    if (finalLocs == null) return;
                    for (Location loc : finalLocs) {
                        if (loc.getBlock().getType() == Material.LIME_STAINED_GLASS) {
                            loc.getBlock().setType(Material.AIR);
                        }
                    }
                }, GREEN_LINGER_TICKS);

            }, PHASE_TICKS);
        }, PHASE_TICKS);
    }
}
