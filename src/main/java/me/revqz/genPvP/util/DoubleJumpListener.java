package me.revqz.genPvP.util;

import me.revqz.genPvP.GenPvP;
import me.revqz.genPvP.Protect.ProtectRegion;
import me.revqz.genPvP.Protect.RegionManager;
import me.revqz.genPvP.Protect.flags.RegionType;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerToggleFlightEvent;
import org.bukkit.util.Vector;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Grants players with {@code genpvp.doublejump} a double-jump ability while they are
 * inside a {@link RegionType#SPAWN} region.
 *
 * <h3>Mechanic</h3>
 * <ol>
 *   <li>While the player is in SPAWN, allow-flight is enabled so the client can detect
 *       a second space-bar press in the air (vanilla double-jump hook).</li>
 *   <li>When the player presses space mid-air ({@link PlayerToggleFlightEvent}), the event
 *       is cancelled, flight is disabled again, and the player receives a forward velocity
 *       boost + upward impulse, along with the slime-block bounce sound.</li>
 *   <li>A 3-second cooldown prevents spamming.</li>
 *   <li>allow-flight is revoked when the player leaves the SPAWN region or disconnects.</li>
 * </ol>
 */
public class DoubleJumpListener implements Listener {

    private static final String PERMISSION    = "genpvp.doublejump";
    private static final long   COOLDOWN_MS   = 3_000L;

    /** Horizontal forward boost velocity (blocks/tick). */
    private static final double BOOST_H       = 1.2;
    /** Upward launch velocity (blocks/tick). */
    private static final double BOOST_V       = 0.7;

    private final GenPvP         plugin;
    private final RegionManager  regionManager;

    /** uuid → last double-jump timestamp (epoch ms). */
    private final Map<UUID, Long> cooldowns = new ConcurrentHashMap<>();

    /** Players currently in SPAWN with allow-flight toggled on by us. */
    private final java.util.Set<UUID> managedFlight = ConcurrentHashMap.newKeySet();

    public DoubleJumpListener(GenPvP plugin, RegionManager regionManager) {
        this.plugin        = plugin;
        this.regionManager = regionManager;
    }

    // ── Region tracking — enable / disable allow-flight as players move ───────

    /**
     * Called every move tick. Enables allow-flight while in SPAWN (for eligible players),
     * and revokes it once they leave.
     *
     * Only checks when the block position changes to avoid running every micro-movement.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        // Only check on block changes to minimise overhead
        if (event.getFrom().getBlockX() == event.getTo().getBlockX()
                && event.getFrom().getBlockY() == event.getTo().getBlockY()
                && event.getFrom().getBlockZ() == event.getTo().getBlockZ()) {
            return;
        }

        Player player = event.getPlayer();
        if (!player.hasPermission(PERMISSION)) return;
        if (player.isOp() && player.getGameMode() == org.bukkit.GameMode.CREATIVE) return;

        boolean inSpawn = isInSpawn(player.getLocation());

        if (inSpawn && !managedFlight.contains(player.getUniqueId())) {
            // Entered spawn — grant allow-flight if not already flying
            if (!player.isFlying()) {
                player.setAllowFlight(true);
                managedFlight.add(player.getUniqueId());
            }
        } else if (!inSpawn && managedFlight.contains(player.getUniqueId())) {
            // Left spawn — revoke allow-flight (unless they already have it naturally)
            revokeFlightManagement(player);
        }
    }

    // ── Double-jump intercept ─────────────────────────────────────────────────

    /**
     * Fires when a player with allow-flight enabled presses space mid-air.
     * We use this as the double-jump trigger.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onToggleFlight(PlayerToggleFlightEvent event) {
        Player player = event.getPlayer();

        // Only handle players we granted flight to (i.e. in SPAWN via our system)
        if (!managedFlight.contains(player.getUniqueId())) return;
        // They are trying to turn flight ON — that's the double-jump press
        if (!event.isFlying()) return;

        event.setCancelled(true); // Do NOT actually enter fly mode

        // Cooldown check
        long now = System.currentTimeMillis();
        Long last = cooldowns.get(player.getUniqueId());
        if (last != null && now - last < COOLDOWN_MS) {

            return;
        }

        // Re-confirm still in spawn
        if (!isInSpawn(player.getLocation())) {
            revokeFlightManagement(player);
            return;
        }

        // Apply boost — forward direction from yaw, with upward component
        Vector dir = player.getLocation().getDirection().normalize();
        Vector boost = new Vector(dir.getX() * BOOST_H, BOOST_V, dir.getZ() * BOOST_H);
        player.setVelocity(boost);

        // Sound & feedback
        player.playSound(player.getLocation(), Sound.ENTITY_SLIME_JUMP, 1.0f, 1.2f);

        cooldowns.put(player.getUniqueId(), now);

        // Brief delay then re-enable allow-flight so they can double-jump again after next landing
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline() && managedFlight.contains(player.getUniqueId())
                    && isInSpawn(player.getLocation())) {
                player.setAllowFlight(true);
            }
        }, 10L); // 0.5 s — enough time for the boost to register; before they land
    }

    // ── Cleanup on quit ───────────────────────────────────────────────────────

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        cooldowns.remove(player.getUniqueId());
        revokeFlightManagement(player);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private boolean isInSpawn(Location loc) {
        for (ProtectRegion r : regionManager.getRegionsAt(loc)) {
            if (r.getType() == RegionType.SPAWN) return true;
        }
        return false;
    }

    /**
     * Revokes managed allow-flight from a player.
     * Never sets allow-flight=false for players who naturally have flight
     * (e.g. creative mode or operators using /fly).
     */
    private void revokeFlightManagement(Player player) {
        if (!managedFlight.remove(player.getUniqueId())) return;
        // Only revoke if not naturally flying / creative
        if (player.getGameMode() != org.bukkit.GameMode.CREATIVE
                && player.getGameMode() != org.bukkit.GameMode.SPECTATOR
                && !player.isFlying()) {
            player.setAllowFlight(false);
        }
    }
}
