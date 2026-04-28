package me.revqz.genPvP.Combat;

import me.revqz.genPvP.GenPvP;
import me.revqz.genPvP.Protect.ProtectRegion;
import me.revqz.genPvP.Protect.RegionManager;
import me.revqz.genPvP.Protect.flags.RegionType;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Prevents combat-tagged players from entering SPAWN-type regions and
 * renders a ghost wall of Black Stained Glass on the region boundary.
 *
 * <h3>Key design decisions</h3>
 * <ul>
 *   <li>Entry check is XZ-only — the region's Y bounds are irrelevant for
 *       a horizontal boundary and should never silently allow entry.</li>
 *   <li>Ghost wall Y is anchored to the player's feet, not clamped to the
 *       region's Y range, so it always appears at eye level.</li>
 *   <li>Spawn regions are cached and refreshed every second so the
 *       per-move-event path is allocation-free.</li>
 * </ul>
 */
public class SpawnBorderGuard implements Listener {

    private static final BlockData GLASS = Material.BLACK_STAINED_GLASS.createBlockData();

    private final GenPvP        plugin;
    private final CombatManager combat;
    private final RegionManager regions;

    private int borderDistance;

    /** Cached list of SPAWN-type regions — refreshed every 20 ticks. */
    private volatile List<ProtectRegion> spawnCache = new ArrayList<>();

    /**
     * Fake block positions currently visible to each player, mapped to the real
     * {@link BlockData} that was there when the fake block was first sent.
     * Caching here means we never need {@code world.getBlockAt()} to restore.
     */
    private final Map<UUID, Map<BlockKey, BlockData>> sentBlocks = new HashMap<>();

    /**
     * Scratch set reused every tick to avoid allocating a new HashSet per player.
     * Only safe because tickBorders runs exclusively on the main thread and never
     * recurses — clear at the start of each player's iteration.
     */
    private final Set<BlockKey> scratchDesired = new HashSet<>();

    /**
     * Scratch map reused when computing which new blocks to send, keyed by
     * BlockKey → real BlockData captured at send time.
     */
    private final Map<BlockKey, BlockData> scratchCapture = new LinkedHashMap<>();

    private BukkitTask borderTask;
    private BukkitTask cacheTask;

    public SpawnBorderGuard(GenPvP plugin, CombatManager combat, RegionManager regions) {
        this.plugin  = plugin;
        this.combat  = combat;
        this.regions = regions;
        loadConfig();
        refreshCache();
        startTasks();
    }

    public void loadConfig() {
        borderDistance = plugin.getConfig().getInt("combat.spawn-border.distance", 5);
    }

    public void shutdown() {
        if (borderTask != null) borderTask.cancel();
        if (cacheTask  != null) cacheTask.cancel();
        for (Map.Entry<UUID, Map<BlockKey, BlockData>> entry : sentBlocks.entrySet()) {
            Player p = Bukkit.getPlayer(entry.getKey());
            if (p != null) restoreBlocks(p, entry.getValue());
        }
        sentBlocks.clear();
    }

    // ── Entry blocking ────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Location to = event.getTo();
        if (to == null) return;

        // Skip if no block boundary was crossed (head rotation only)
        Location from = event.getFrom();
        if (from.getBlockX() == to.getBlockX()
                && from.getBlockZ() == to.getBlockZ()) return;

        if (!combat.isInCombat(event.getPlayer().getUniqueId())) return;
        if (!isInsideSpawnXZ(to)) return;

        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        Location to = event.getTo();
        if (to == null) return;
        if (!combat.isInCombat(event.getPlayer().getUniqueId())) return;
        if (!isInsideSpawnXZ(to)) return;

        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        clearFakeBlocks(event.getPlayer());
    }

    // ── Ghost wall task ───────────────────────────────────────────────────────

    private void startTasks() {
        // Border visual — every 10 ticks (0.5s); was 4 ticks, reduced to cut main-thread cost
        borderTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickBorders, 10L, 10L);
        // Cache refresh — every 20 ticks (1 second)
        cacheTask  = Bukkit.getScheduler().runTaskTimer(plugin, this::refreshCache,  20L, 20L);
    }

    private void refreshCache() {
        List<ProtectRegion> fresh = new ArrayList<>();
        for (String name : regions.getRegionNames()) {
            ProtectRegion r = regions.getRegion(name);
            if (r != null && r.getType() == RegionType.SPAWN) fresh.add(r);
        }
        spawnCache = fresh;
    }

    private void tickBorders() {
        List<ProtectRegion> snap = spawnCache; // single read — no lock needed
        if (snap.isEmpty()) return;

        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID uuid = player.getUniqueId();

            if (!combat.isInCombat(uuid)) {
                if (sentBlocks.containsKey(uuid)) clearFakeBlocks(player);
                continue;
            }

            // Reuse scratch set — clear before filling to avoid per-tick allocation
            scratchDesired.clear();
            computeWallBlocks(player, snap, scratchDesired);

            Map<BlockKey, BlockData> current = sentBlocks.getOrDefault(uuid, Map.of());

            // Nothing to do — no desired blocks and nothing currently shown
            if (scratchDesired.isEmpty() && current.isEmpty()) continue;

            World world = player.getWorld();

            // nowShowing is allocated lazily — most players won't be near the border
            Map<BlockKey, BlockData> nowShowing = null;

            // Keep existing shown blocks that are still desired; restore the rest.
            // Real BlockData was cached at send-time — no world.getBlockAt() needed.
            for (Map.Entry<BlockKey, BlockData> entry : current.entrySet()) {
                BlockKey key = entry.getKey();
                if (scratchDesired.contains(key)) {
                    if (nowShowing == null) nowShowing = new HashMap<>();
                    nowShowing.put(key, entry.getValue());
                } else {
                    // No longer in range — restore from cached real BlockData
                    player.sendBlockChange(key.toLocation(world), entry.getValue());
                }
            }

            // Show newly desired positions — only if the block there is air.
            // Capture the real BlockData once here so we can restore it later
            // without touching the world again.
            scratchCapture.clear();
            for (BlockKey key : scratchDesired) {
                if (!current.containsKey(key)) {
                    Location loc = key.toLocation(world);
                    BlockData real = world.getBlockAt(loc).getBlockData(); // one-time read
                    if (real.getMaterial().isAir()) {
                        scratchCapture.put(key, real);
                    }
                }
            }
            if (!scratchCapture.isEmpty()) {
                if (nowShowing == null) nowShowing = new HashMap<>();
                for (Map.Entry<BlockKey, BlockData> entry : scratchCapture.entrySet()) {
                    player.sendBlockChange(entry.getKey().toLocation(world), GLASS);
                    nowShowing.put(entry.getKey(), entry.getValue());
                }
            }

            if (nowShowing == null || nowShowing.isEmpty()) sentBlocks.remove(uuid);
            else                                             sentBlocks.put(uuid, nowShowing);
        }
    }

    /**
     * Fills {@code result} with boundary block positions that should show as glass for this player.
     * Caller must clear {@code result} before calling.
     *
     * <p>For each of the four SPAWN-region faces, if the player is within
     * {@code borderDistance} blocks (measured perpendicularly), a column of four
     * glass blocks is generated at the boundary — anchored to the player's feet Y,
     * NOT clamped to the region's Y bounds.
     */
    private void computeWallBlocks(Player player, List<ProtectRegion> snap, Set<BlockKey> result) {
        Location loc = player.getLocation();
        if (loc.getWorld() == null) return;

        String world = loc.getWorld().getName();
        int px = loc.getBlockX();
        int py = loc.getBlockY();
        int pz = loc.getBlockZ();

        for (ProtectRegion region : snap) {
            if (!region.getWorld().equals(world)) continue;

            int minX = region.getMinX(), maxX = region.getMaxX();
            int minZ = region.getMinZ(), maxZ = region.getMaxZ();

            // Perpendicular distance to each face (positive = player is outside)
            int dWest  = minX - px;
            int dEast  = px - maxX;
            int dNorth = minZ - pz;
            int dSouth = pz - maxZ;

            // West face  (x = minX, player is to the west)
            if (dWest > 0 && dWest <= borderDistance) {
                int zFrom = Math.max(minZ, pz - borderDistance);
                int zTo   = Math.min(maxZ, pz + borderDistance);
                for (int z = zFrom; z <= zTo; z++) {
                    addWallColumn(result, minX, py, z);
                }
            }

            // East face  (x = maxX, player is to the east)
            if (dEast > 0 && dEast <= borderDistance) {
                int zFrom = Math.max(minZ, pz - borderDistance);
                int zTo   = Math.min(maxZ, pz + borderDistance);
                for (int z = zFrom; z <= zTo; z++) {
                    addWallColumn(result, maxX, py, z);
                }
            }

            // North face (z = minZ, player is to the north)
            if (dNorth > 0 && dNorth <= borderDistance) {
                int xFrom = Math.max(minX, px - borderDistance);
                int xTo   = Math.min(maxX, px + borderDistance);
                for (int x = xFrom; x <= xTo; x++) {
                    addWallColumn(result, x, py, minZ);
                }
            }

            // South face (z = maxZ, player is to the south)
            if (dSouth > 0 && dSouth <= borderDistance) {
                int xFrom = Math.max(minX, px - borderDistance);
                int xTo   = Math.min(maxX, px + borderDistance);
                for (int x = xFrom; x <= xTo; x++) {
                    addWallColumn(result, x, py, maxZ);
                }
            }
        }
    }

    /**
     * Adds a 4-block-tall column: one below feet, feet, body, head.
     * The extra block below ensures no gap appears at the base when the
     * player jumps and footY shifts up by 1.
     */
    private static void addWallColumn(Set<BlockKey> set, int x, int footY, int z) {
        set.add(new BlockKey(x, footY - 1, z));
        set.add(new BlockKey(x, footY,     z));
        set.add(new BlockKey(x, footY + 1, z));
        set.add(new BlockKey(x, footY + 2, z));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * XZ-only containment check — Y is deliberately ignored.
     * The region's vertical bounds must not silently allow entry at ground level.
     */
    private boolean isInsideSpawnXZ(Location loc) {
        if (loc.getWorld() == null) return false;
        String world = loc.getWorld().getName();
        int bx = loc.getBlockX();
        int bz = loc.getBlockZ();
        for (ProtectRegion r : spawnCache) {
            if (r.getWorld().equals(world)
                    && bx >= r.getMinX() && bx <= r.getMaxX()
                    && bz >= r.getMinZ() && bz <= r.getMaxZ()) {
                return true;
            }
        }
        return false;
    }

    public void clearFakeBlocks(Player player) {
        Map<BlockKey, BlockData> entries = sentBlocks.remove(player.getUniqueId());
        if (entries != null && !entries.isEmpty()) restoreBlocks(player, entries);
    }

    /** Restores all fake blocks using the cached real BlockData — no world reads. */
    private static void restoreBlocks(Player player, Map<BlockKey, BlockData> entries) {
        World world = player.getWorld();
        for (Map.Entry<BlockKey, BlockData> entry : entries.entrySet()) {
            player.sendBlockChange(entry.getKey().toLocation(world), entry.getValue());
        }
    }

    // ── BlockKey ──────────────────────────────────────────────────────────────

    private record BlockKey(int x, int y, int z) {

        Location toLocation(World world) {
            return new Location(world, x, y, z);
        }

        @Override public boolean equals(Object o) {
            if (!(o instanceof BlockKey k)) return false;
            return x == k.x && y == k.y && z == k.z;
        }

        @Override public int hashCode() {
            return Objects.hash(x, y, z);
        }
    }
}
