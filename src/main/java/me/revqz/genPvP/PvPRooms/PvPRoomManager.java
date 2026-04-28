package me.revqz.genPvP.PvPRooms;

import me.revqz.genPvP.GenPvP;
import me.revqz.genPvP.Protect.ProtectRegion;
import me.revqz.genPvP.Protect.RegionManager;
import me.revqz.genPvP.util.ColorUtil;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class PvPRoomManager {

    private final GenPvP plugin;
    private final RegionManager regionManager;

    private final PvPRoomState room1;
    private final PvPRoomState room2;

    // Loot tasks stored so they can be cancelled on early reset
    private BukkitTask lootTask1 = null;
    private BukkitTask lootTask2 = null;

    // Title text is constant — built once and reused every loot tick
    private static final String LOOT_TITLE = color("&#4498DB&lW&#70BAF5&lI&#4498DB&lN&#70BAF5&l!");

    public PvPRoomManager(GenPvP plugin, RegionManager regionManager) {
        this.plugin = plugin;
        this.regionManager = regionManager;

        this.room1 = new PvPRoomState("PVPROOM1", "PVPROOMGATE1");
        this.room2 = new PvPRoomState("PVPROOM2", "PVPROOMGATE2");

        startDetectionTask();
    }

    // ── Getters ───────────────────────────────────────────────────────────────

    public PvPRoomState getRoom1() { return room1; }
    public PvPRoomState getRoom2() { return room2; }
    public RegionManager getRegionManager() { return regionManager; }

    /** Returns the room this player is a registered participant of, or null. */
    public PvPRoomState getRoomByParticipant(UUID uuid) {
        if (room1.isParticipant(uuid)) return room1;
        if (room2.isParticipant(uuid)) return room2;
        return null;
    }

    // ── Detection task ────────────────────────────────────────────────────────

    /**
     * Polls both rooms every 10 ticks (0.5 s) for the two-player trigger.
     * Only active during the WAITING phase — once a fight starts the gate is
     * sealed and no new players can enter.
     */
    private void startDetectionTask() {
        new BukkitRunnable() {
            @Override
            public void run() {
                checkRoomState(room1);
                checkRoomState(room2);
            }
        }.runTaskTimer(plugin, 20L, 10L);
    }

    /**
     * Scans the room, updates the cached inside-count, and starts a fight if
     * exactly 2 players are present. Package-private so the move-event path in
     * {@link PvPRoomListener} can trigger an immediate check the moment a
     * player crosses a room boundary.
     */
    void checkRoomState(PvPRoomState room) {
        if (room.getCurrentPhase() != PvPPhase.WAITING) return;

        ProtectRegion region = regionManager.getRegion(room.getRoomIdentifier());
        if (region == null) return;

        // Collect up to 3 players — we only need to know if it's 0, 1, 2, or "more"
        List<Player> inside = new ArrayList<>(3);
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!region.contains(p.getLocation())) continue;
            inside.add(p);
            if (inside.size() == 3) break; // more than 2 — stop early
        }

        room.setCachedInsideCount(inside.size());

        if (inside.size() == 2) {
            startFight(room, inside);
        }
    }

    /**
     * Called by {@link PvPRoomListener} whenever a player crosses a block
     * boundary. If the player has entered or left a WAITING room region the
     * full room check is triggered immediately, closing the gate without
     * waiting for the next polling tick.
     */
    public void onPlayerBlockChange(Player player, Location from, Location to) {
        triggerIfBoundaryChanged(room1, from, to);
        triggerIfBoundaryChanged(room2, from, to);
    }

    private void triggerIfBoundaryChanged(PvPRoomState room, Location from, Location to) {
        if (room.getCurrentPhase() != PvPPhase.WAITING) return;
        ProtectRegion region = regionManager.getRegion(room.getRoomIdentifier());
        if (region == null) return;
        boolean wasIn = region.contains(from);
        boolean isIn  = region.contains(to);
        if (wasIn != isIn) checkRoomState(room); // boundary crossed — re-scan immediately
    }

    // ── Fight phase ───────────────────────────────────────────────────────────

    private void startFight(PvPRoomState room, List<Player> combatants) {
        room.clearParticipants();
        combatants.forEach(room::addParticipant);
        room.setCurrentPhase(PvPPhase.FIGHTING);

        plugin.getLogger().info("[PvPRooms] Fight started in " + room.getRoomIdentifier()
                + " — sealing gate: " + room.getGateIdentifier());
        setGate(room.getGateIdentifier(), Material.RED_STAINED_GLASS);
    }

    /**
     * Called on participant death OR quit. Idempotent — safe to call for the
     * same player twice (e.g., death event + quit event in the same tick).
     */
    public void handleParticipantDeathOrQuit(Player eliminated) {
        PvPRoomState room = getRoomByParticipant(eliminated.getUniqueId());
        if (room == null) return;

        // Only transition out of FIGHTING here; LOOTING is managed by the loot timer
        if (room.getCurrentPhase() != PvPPhase.FIGHTING) return;

        room.removeParticipant(eliminated);

        // Find the surviving participant
        Player winner = null;
        for (UUID uid : room.getParticipants()) {
            Player p = Bukkit.getPlayer(uid);
            if (p != null && p.isOnline()) { winner = p; break; }
        }

        if (winner != null) {
            startLootPhase(room, winner);
        } else {
            // Both players gone simultaneously (dual-quit or simultaneous death)
            resetRoom(room);
        }
    }

    // ── Loot phase ────────────────────────────────────────────────────────────

    private void startLootPhase(PvPRoomState room, Player winner) {
        room.setCurrentPhase(PvPPhase.LOOTING);
        room.setLootingCountdown(150); // 2.5 minutes = 150 seconds

        BukkitTask task = new BukkitRunnable() {
            @Override
            public void run() {
                // Guard: winner logged out or was removed from participants some other way
                if (room.getCurrentPhase() != PvPPhase.LOOTING
                        || !winner.isOnline()
                        || !room.isParticipant(winner.getUniqueId())) {
                    resetRoom(room);
                    this.cancel();
                    return;
                }

                int left = room.getLootingCountdown();

                if (left <= 0) {
                    resetRoom(room);
                    this.cancel();
                    return;
                }

                // Format as "1m30s" / "45s" — no space between minutes and seconds
                // stay=25 gives a 5-tick overlap buffer against minor server lag
                String subtitle = color("&#4498DB" + formatCountdown(left) + " &fleft to loot.");
                winner.sendTitle(LOOT_TITLE, subtitle, 0, 25, 0);

                room.decrementCountdown();
            }
        }.runTaskTimer(plugin, 0L, 20L);

        // Store reference so resetRoom can cancel it immediately if needed
        if (room == room1) { if (lootTask1 != null) lootTask1.cancel(); lootTask1 = task; }
        else               { if (lootTask2 != null) lootTask2.cancel(); lootTask2 = task; }
    }

    // ── Reset ─────────────────────────────────────────────────────────────────

    public void resetRoom(PvPRoomState room) {
        // Cancel any in-flight loot timer first
        if (room == room1 && lootTask1 != null) { lootTask1.cancel(); lootTask1 = null; }
        if (room == room2 && lootTask2 != null) { lootTask2.cancel(); lootTask2 = null; }

        room.setCurrentPhase(PvPPhase.WAITING);
        room.clearParticipants();
        room.setLootingCountdown(0);
        room.setCachedInsideCount(0); // will be refreshed by next polling tick or move event
        setGate(room.getGateIdentifier(), Material.AIR);
    }

    // ── Gate fill ─────────────────────────────────────────────────────────────

    /**
     * Fills every block in the gate region with {@code material}.
     * Gate regions are small by design (a doorway), so a synchronous fill is
     * negligible. Only changes blocks that are not already the target material.
     *
     * Logs a warning if the region or world cannot be found so the admin can see
     * exactly why the gate is not sealing.
     */
    private void setGate(String gateName, Material material) {
        ProtectRegion gate = regionManager.getRegion(gateName);
        if (gate == null) {
            plugin.getLogger().warning("[PvPRooms] Gate region '" + gateName + "' not found — "
                    + "define it with /region define " + gateName + " PVPROOMGATE1 (or PVPROOMGATE2). "
                    + "Gate will NOT be sealed.");
            return;
        }

        World world = Bukkit.getWorld(gate.getWorld());
        if (world == null) {
            plugin.getLogger().warning("[PvPRooms] World '" + gate.getWorld() + "' for gate region '"
                    + gateName + "' is not loaded. Gate will NOT be sealed.");
            return;
        }

        int filled = 0;
        for (int x = gate.getMinX(); x <= gate.getMaxX(); x++) {
            for (int y = gate.getMinY(); y <= gate.getMaxY(); y++) {
                for (int z = gate.getMinZ(); z <= gate.getMaxZ(); z++) {
                    Block block = world.getBlockAt(x, y, z);
                    if (block.getType() != material) {
                        block.setType(material, false); // false = skip physics, much faster
                        filled++;
                    }
                }
            }
        }
        plugin.getLogger().info("[PvPRooms] Gate '" + gateName + "' set to " + material.name()
                + " (" + filled + " block(s) changed).");
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    public static String color(String message) {
        return ColorUtil.colorize(message);
    }

    /**
     * Formats a second count for the loot-phase subtitle.
     * Examples: 150 → "2m30s", 60 → "1m", 45 → "45s"
     */
    private static String formatCountdown(int seconds) {
        if (seconds < 60) return seconds + "s";
        int m = seconds / 60, s = seconds % 60;
        return s == 0 ? m + "m" : m + "m" + s + "s";
    }
}
