package me.revqz.genPvP.PvPRooms;

import me.revqz.genPvP.Protect.ProtectRegion;
import me.revqz.genPvP.Protect.RegionManager;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

import java.util.UUID;

public class PvPRoomListener implements Listener {

    private final PvPRoomManager pvpRoomManager;
    private final RegionManager regionManager;

    public PvPRoomListener(PvPRoomManager pvpRoomManager, RegionManager regionManager) {
        this.pvpRoomManager = pvpRoomManager;
        this.regionManager = regionManager;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        if (event.getTo() == null) return;

        Player player  = event.getPlayer();
        UUID   uuid    = player.getUniqueId();

        PvPRoomState[] rooms = { pvpRoomManager.getRoom1(), pvpRoomManager.getRoom2() };

        for (PvPRoomState room : rooms) {
            if (room.getCurrentPhase() == PvPPhase.WAITING) continue; 

            ProtectRegion region = regionManager.getRegion(room.getRoomIdentifier());
            if (region == null) continue;

            boolean landingInside = region.contains(event.getTo());

            if (room.isParticipant(uuid) && !landingInside) {
                
                if (event.getCause() == PlayerTeleportEvent.TeleportCause.ENDER_PEARL
                        || event.getCause() == PlayerTeleportEvent.TeleportCause.CHORUS_FRUIT) {
                    event.setCancelled(true);
                    player.sendMessage(ChatColor.RED + "You cannot pearl out of the PvP room!");
                    return;
                }

                player.sendTitle(" ", " ", 0, 1, 0);
                pvpRoomManager.handleParticipantLeave(player);
                return;
            }

            if (!landingInside) continue;
            if (room.isParticipant(uuid)) continue;

            event.setCancelled(true);
            player.sendMessage(ChatColor.RED + "You cannot enter this PvP room while a fight is in progress!");
            return;
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerMove(PlayerMoveEvent event) {
        if (!event.hasChangedBlock()) return; 

        Player player = event.getPlayer();

        PvPRoomState room = pvpRoomManager.getRoomByParticipant(player.getUniqueId());
        if (room != null && room.getCurrentPhase() == PvPPhase.FIGHTING) {
            ProtectRegion region = regionManager.getRegion(room.getRoomIdentifier());
            if (region != null && region.contains(event.getFrom()) && !region.contains(event.getTo())) {
                event.setCancelled(true);
                return;
            }
        }

        pvpRoomManager.onPlayerBlockChange(player, event.getFrom(), event.getTo());

        room = pvpRoomManager.getRoomByParticipant(player.getUniqueId());
        if (room != null && room.getCurrentPhase() == PvPPhase.LOOTING) {
            ProtectRegion region = regionManager.getRegion(room.getRoomIdentifier());
            if (region != null && region.contains(event.getFrom()) && !region.contains(event.getTo())) {
                
                player.sendTitle(" ", " ", 0, 1, 0);
                
                pvpRoomManager.resetRoom(room);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerDeath(PlayerDeathEvent event) {
        pvpRoomManager.handleParticipantDeathOrQuit(event.getEntity());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        pvpRoomManager.handleParticipantDeathOrQuit(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCommandPreprocess(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        if (player.isOp()) return;

        PvPRoomState room = pvpRoomManager.getRoomByParticipant(player.getUniqueId());
        if (room != null && room.getCurrentPhase() != PvPPhase.WAITING) {
            event.setCancelled(true);
            player.sendMessage(ChatColor.RED + "You cannot run commands while inside a PvP Room!");
        }
    }
}
