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

    /**
     * Blocks ALL forms of teleportation into an active room by non-participants.
     * This covers: ender pearls, /tp commands, plugin-sourced teleports, chorus fruit, etc.
     * Participants can always teleport (they are the two combatants).
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        if (event.getTo() == null) return;

        Player player  = event.getPlayer();
        UUID   uuid    = player.getUniqueId();

        PvPRoomState[] rooms = { pvpRoomManager.getRoom1(), pvpRoomManager.getRoom2() };

        for (PvPRoomState room : rooms) {
            if (room.getCurrentPhase() == PvPPhase.WAITING) continue; // gate is open — anyone may enter

            ProtectRegion region = regionManager.getRegion(room.getRoomIdentifier());
            if (region == null) continue;

            boolean landingInside = region.contains(event.getTo());

            // ── Block participants from pearling OUT of the room ─────────────
            if (room.isParticipant(uuid) && !landingInside) {
                // Only block pearls / chorus fruit — allow admin /tp
                if (event.getCause() == PlayerTeleportEvent.TeleportCause.ENDER_PEARL
                        || event.getCause() == PlayerTeleportEvent.TeleportCause.CHORUS_FRUIT) {
                    event.setCancelled(true);
                    player.sendMessage(ChatColor.RED + "You cannot pearl out of the PvP room!");
                    return;
                }
                continue; // allow other teleport types (admin /tp)
            }

            // ── Block non-participants from entering ─────────────────────────
            if (!landingInside) continue;
            if (room.isParticipant(uuid)) continue;

            event.setCancelled(true);
            player.sendMessage(ChatColor.RED + "You cannot enter this PvP room while a fight is in progress!");
            return;
        }
    }

    /**
     * Event-driven room detection: fires when a player crosses a block boundary.
     * Delegates to PvPRoomManager which compares from/to against both room regions
     * and triggers an immediate gate-seal check if the player entered or exited.
     * This replaces the 10-tick polling delay for the 2-player trigger.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        if (!event.hasChangedBlock()) return; // skip sub-block movements

        Player player = event.getPlayer();
        pvpRoomManager.onPlayerBlockChange(player, event.getFrom(), event.getTo());

        // ── Clear "WIN!" title when the winner walks out of the room region ─
        PvPRoomState room = pvpRoomManager.getRoomByParticipant(player.getUniqueId());
        if (room != null && room.getCurrentPhase() == PvPPhase.LOOTING) {
            ProtectRegion region = regionManager.getRegion(room.getRoomIdentifier());
            if (region != null && region.contains(event.getFrom()) && !region.contains(event.getTo())) {
                // Player just left the room — clear the persistent WIN! title
                player.sendTitle(" ", " ", 0, 1, 0);
                // Reset the room since the winner has left
                pvpRoomManager.resetRoom(room);
            }
        }
    }

    /**
     * Triggers when a participant dies inside the room.
     * Uses MONITOR priority so the death is fully processed before we act on it.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerDeath(PlayerDeathEvent event) {
        pvpRoomManager.handleParticipantDeathOrQuit(event.getEntity());
    }

    /**
     * Treats a disconnect the same as a death so the room does not lock forever.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        pvpRoomManager.handleParticipantDeathOrQuit(event.getPlayer());
    }

    /**
     * Prevents participants from running commands during an active fight or loot phase.
     * Operators are exempt so admins can intervene if needed.
     */
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
