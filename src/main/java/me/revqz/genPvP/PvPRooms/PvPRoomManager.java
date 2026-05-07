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

    private BukkitTask lootTask1 = null;
    private BukkitTask lootTask2 = null;

    private static final String LOOT_TITLE = color("&#4498DB&lW&#70BAF5&lI&#4498DB&lN&#70BAF5&l!");

    public PvPRoomManager(GenPvP plugin, RegionManager regionManager) {
        this.plugin = plugin;
        this.regionManager = regionManager;

        this.room1 = new PvPRoomState("PVPROOM1", "PVPROOMGATE1");
        this.room2 = new PvPRoomState("PVPROOM2", "PVPROOMGATE2");

        startDetectionTask();
    }

    public PvPRoomState getRoom1() { return room1; }
    public PvPRoomState getRoom2() { return room2; }
    public RegionManager getRegionManager() { return regionManager; }

    public PvPRoomState getRoomByParticipant(UUID uuid) {
        if (room1.isParticipant(uuid)) return room1;
        if (room2.isParticipant(uuid)) return room2;
        return null;
    }

    private void startDetectionTask() {
        new BukkitRunnable() {
            @Override
            public void run() {
                checkRoomState(room1);
                checkRoomState(room2);
            }
        }.runTaskTimer(plugin, 20L, 10L);

        new BukkitRunnable() {
            @Override
            public void run() {
                updateInsideCount(room1);
                updateInsideCount(room2);
            }
        }.runTaskTimer(plugin, 20L, 20L);
    }

    private void updateInsideCount(PvPRoomState room) {
        ProtectRegion region = regionManager.getRegion(room.getRoomIdentifier());
        if (region == null) { room.setCachedInsideCount(0); return; }

        int count = 0;
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (region.contains(p.getLocation())) count++;
        }
        room.setCachedInsideCount(count);
    }

    void checkRoomState(PvPRoomState room) {
        if (room.getCurrentPhase() != PvPPhase.WAITING) return;

        ProtectRegion region = regionManager.getRegion(room.getRoomIdentifier());
        if (region == null) {
            plugin.getLogger().warning("[PvPRooms] Room region '" + room.getRoomIdentifier()
                    + "' NOT FOUND in RegionManager. Available regions: " + regionManager.getRegionNames());
            return;
        }

        List<Player> inside = new ArrayList<>(3);
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!region.contains(p.getLocation())) continue;
            inside.add(p);
            if (inside.size() == 3) break; 
        }

        room.setCachedInsideCount(inside.size());

        if (inside.size() == 2) {
            plugin.getLogger().info("[PvPRooms] 2 players detected in " + room.getRoomIdentifier()
                    + ": " + inside.get(0).getName() + ", " + inside.get(1).getName()
                    + " — triggering fight!");
            startFight(room, inside);
        }
    }

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
        if (wasIn != isIn) checkRoomState(room); 
    }

    private void startFight(PvPRoomState room, List<Player> combatants) {
        room.clearParticipants();
        combatants.forEach(room::addParticipant);
        room.setCurrentPhase(PvPPhase.FIGHTING);

        plugin.getLogger().info("[PvPRooms] Fight started in " + room.getRoomIdentifier()
                + " — sealing gate: " + room.getGateIdentifier());
        setGate(room.getGateIdentifier(), Material.RED_STAINED_GLASS);
    }

    public void handleParticipantDeathOrQuit(Player eliminated) {
        PvPRoomState room = getRoomByParticipant(eliminated.getUniqueId());
        if (room == null) return;

        if (room.getCurrentPhase() != PvPPhase.FIGHTING) return;

        room.removeParticipant(eliminated);

        Player winner = null;
        for (UUID uid : room.getParticipants()) {
            Player p = Bukkit.getPlayer(uid);
            if (p != null && p.isOnline()) { winner = p; break; }
        }

        if (winner != null) {
            startLootPhase(room, winner);
        } else {
            
            resetRoom(room);
        }
    }

    public void handleParticipantLeave(Player leaver) {
        PvPRoomState room = getRoomByParticipant(leaver.getUniqueId());
        if (room == null) return;

        if (room.getCurrentPhase() == PvPPhase.FIGHTING) {
            
            handleParticipantDeathOrQuit(leaver);
        } else if (room.getCurrentPhase() == PvPPhase.LOOTING) {
            
            leaver.sendTitle(" ", " ", 0, 1, 0);
            resetRoom(room);
        }
    }

    private void startLootPhase(PvPRoomState room, Player winner) {
        room.setCurrentPhase(PvPPhase.LOOTING);
        room.setLootingCountdown(150); 

        BukkitTask task = new BukkitRunnable() {
            @Override
            public void run() {
                
                if (room.getCurrentPhase() != PvPPhase.LOOTING
                        || !winner.isOnline()
                        || !room.isParticipant(winner.getUniqueId())) {
                    resetRoom(room);
                    this.cancel();
                    return;
                }

                ProtectRegion region = regionManager.getRegion(room.getRoomIdentifier());
                if (region != null && !region.contains(winner.getLocation())) {
                    winner.sendTitle(" ", " ", 0, 1, 0);
                    resetRoom(room);
                    this.cancel();
                    return;
                }

                int left = room.getLootingCountdown();

                if (left <= 0) {
                    winner.sendTitle(" ", " ", 0, 1, 0);
                    resetRoom(room);
                    this.cancel();
                    return;
                }

                String subtitle = color("&#4498DB" + formatCountdown(left) + " &fleft to loot.");
                winner.sendTitle(LOOT_TITLE, subtitle, 0, 25, 0);

                room.decrementCountdown();
            }
        }.runTaskTimer(plugin, 0L, 20L);

        if (room == room1) { if (lootTask1 != null) lootTask1.cancel(); lootTask1 = task; }
        else               { if (lootTask2 != null) lootTask2.cancel(); lootTask2 = task; }
    }

    public void resetRoom(PvPRoomState room) {
        
        if (room == room1 && lootTask1 != null) { lootTask1.cancel(); lootTask1 = null; }
        if (room == room2 && lootTask2 != null) { lootTask2.cancel(); lootTask2 = null; }

        for (UUID uid : room.getParticipants()) {
            Player p = Bukkit.getPlayer(uid);
            if (p != null && p.isOnline()) {
                p.sendTitle(" ", " ", 0, 1, 0);
            }
        }

        room.setCurrentPhase(PvPPhase.WAITING);
        room.clearParticipants();
        room.setLootingCountdown(0);
        room.setCachedInsideCount(0); 
        setGate(room.getGateIdentifier(), Material.AIR);
    }

    private void setGate(String gateName, Material material) {
        ProtectRegion gate = regionManager.getRegion(gateName);
        if (gate == null) {
            plugin.getLogger().severe("[PvPRooms] Gate region '" + gateName + "' NOT FOUND — "
                    + "define it with /region define " + gateName + " PVPROOMGATE1 (or PVPROOMGATE2). "
                    + "Gate will NOT be sealed. Available regions: " + regionManager.getRegionNames());
            return;
        }

        World world = Bukkit.getWorld(gate.getWorld());
        if (world == null) {
            plugin.getLogger().severe("[PvPRooms] World '" + gate.getWorld() + "' for gate region '"
                    + gateName + "' is not loaded. Gate will NOT be sealed.");
            return;
        }

        plugin.getLogger().info("[PvPRooms] Setting gate '" + gateName + "' to " + material.name()
                + " | bounds: (" + gate.getMinX() + "," + gate.getMinY() + "," + gate.getMinZ()
                + ") -> (" + gate.getMaxX() + "," + gate.getMaxY() + "," + gate.getMaxZ()
                + ") in world '" + gate.getWorld() + "'");

        Bukkit.getScheduler().runTask(plugin, () -> {
            int filled = 0;
            for (int x = gate.getMinX(); x <= gate.getMaxX(); x++) {
                for (int y = gate.getMinY(); y <= gate.getMaxY(); y++) {
                    for (int z = gate.getMinZ(); z <= gate.getMaxZ(); z++) {
                        Block block = world.getBlockAt(x, y, z);
                        block.setType(material); 
                        filled++;
                    }
                }
            }
            plugin.getLogger().info("[PvPRooms] Gate '" + gateName + "' fill complete — "
                    + filled + " block(s) set to " + material.name() + ".");
        });
    }

    public static String color(String message) {
        return ColorUtil.colorize(message);
    }

    private static String formatCountdown(int seconds) {
        if (seconds < 60) return seconds + "s";
        int m = seconds / 60, s = seconds % 60;
        return s == 0 ? m + "m" : m + "m" + s + "s";
    }
}
