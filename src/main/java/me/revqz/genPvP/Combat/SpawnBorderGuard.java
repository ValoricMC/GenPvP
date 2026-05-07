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

public class SpawnBorderGuard implements Listener {

    private static final BlockData GLASS = Material.BLACK_STAINED_GLASS.createBlockData();

    private final GenPvP        plugin;
    private final CombatManager combat;
    private final RegionManager regions;

    private int borderDistance;

    private volatile List<ProtectRegion> spawnCache = new ArrayList<>();

    private final Map<UUID, Map<BlockKey, BlockData>> sentBlocks = new HashMap<>();

    private final Set<BlockKey> scratchDesired = new HashSet<>();

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

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Location to = event.getTo();
        if (to == null) return;

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

    private void startTasks() {
        
        borderTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickBorders, 10L, 10L);
        
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
        List<ProtectRegion> snap = spawnCache; 
        if (snap.isEmpty()) return;

        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID uuid = player.getUniqueId();

            if (!combat.isInCombat(uuid)) {
                if (sentBlocks.containsKey(uuid)) clearFakeBlocks(player);
                continue;
            }

            scratchDesired.clear();
            computeWallBlocks(player, snap, scratchDesired);

            Map<BlockKey, BlockData> current = sentBlocks.getOrDefault(uuid, Map.of());

            if (scratchDesired.isEmpty() && current.isEmpty()) continue;

            World world = player.getWorld();

            Map<BlockKey, BlockData> nowShowing = null;

            for (Map.Entry<BlockKey, BlockData> entry : current.entrySet()) {
                BlockKey key = entry.getKey();
                if (scratchDesired.contains(key)) {
                    if (nowShowing == null) nowShowing = new HashMap<>();
                    nowShowing.put(key, entry.getValue());
                } else {
                    
                    player.sendBlockChange(key.toLocation(world), entry.getValue());
                }
            }

            scratchCapture.clear();
            for (BlockKey key : scratchDesired) {
                if (!current.containsKey(key)) {
                    Location loc = key.toLocation(world);
                    BlockData real = world.getBlockAt(loc).getBlockData(); 
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

            int dWest  = minX - px;
            int dEast  = px - maxX;
            int dNorth = minZ - pz;
            int dSouth = pz - maxZ;

            if (dWest > 0 && dWest <= borderDistance) {
                int zFrom = Math.max(minZ, pz - borderDistance);
                int zTo   = Math.min(maxZ, pz + borderDistance);
                for (int z = zFrom; z <= zTo; z++) {
                    addWallColumn(result, minX, py, z);
                }
            }

            if (dEast > 0 && dEast <= borderDistance) {
                int zFrom = Math.max(minZ, pz - borderDistance);
                int zTo   = Math.min(maxZ, pz + borderDistance);
                for (int z = zFrom; z <= zTo; z++) {
                    addWallColumn(result, maxX, py, z);
                }
            }

            if (dNorth > 0 && dNorth <= borderDistance) {
                int xFrom = Math.max(minX, px - borderDistance);
                int xTo   = Math.min(maxX, px + borderDistance);
                for (int x = xFrom; x <= xTo; x++) {
                    addWallColumn(result, x, py, minZ);
                }
            }

            if (dSouth > 0 && dSouth <= borderDistance) {
                int xFrom = Math.max(minX, px - borderDistance);
                int xTo   = Math.min(maxX, px + borderDistance);
                for (int x = xFrom; x <= xTo; x++) {
                    addWallColumn(result, x, py, maxZ);
                }
            }
        }
    }

    private static void addWallColumn(Set<BlockKey> set, int x, int footY, int z) {
        set.add(new BlockKey(x, footY - 1, z));
        set.add(new BlockKey(x, footY,     z));
        set.add(new BlockKey(x, footY + 1, z));
        set.add(new BlockKey(x, footY + 2, z));
    }

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

    private static void restoreBlocks(Player player, Map<BlockKey, BlockData> entries) {
        World world = player.getWorld();
        for (Map.Entry<BlockKey, BlockData> entry : entries.entrySet()) {
            player.sendBlockChange(entry.getKey().toLocation(world), entry.getValue());
        }
    }

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
