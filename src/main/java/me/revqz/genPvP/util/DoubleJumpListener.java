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

public class DoubleJumpListener implements Listener {

    private static final String PERMISSION    = "genpvp.doublejump";
    private static final long   COOLDOWN_MS   = 3_000L;

    private static final double BOOST_H       = 1.2;
    
    private static final double BOOST_V       = 0.7;

    private final GenPvP         plugin;
    private final RegionManager  regionManager;

    private final Map<UUID, Long> cooldowns = new ConcurrentHashMap<>();

    private final java.util.Set<UUID> managedFlight = ConcurrentHashMap.newKeySet();

    public DoubleJumpListener(GenPvP plugin, RegionManager regionManager) {
        this.plugin        = plugin;
        this.regionManager = regionManager;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        
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
            
            if (!player.isFlying()) {
                player.setAllowFlight(true);
                managedFlight.add(player.getUniqueId());
            }
        } else if (!inSpawn && managedFlight.contains(player.getUniqueId())) {
            
            revokeFlightManagement(player);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onToggleFlight(PlayerToggleFlightEvent event) {
        Player player = event.getPlayer();

        if (!managedFlight.contains(player.getUniqueId())) return;
        
        if (!event.isFlying()) return;

        event.setCancelled(true); 

        long now = System.currentTimeMillis();
        Long last = cooldowns.get(player.getUniqueId());
        if (last != null && now - last < COOLDOWN_MS) {

            return;
        }

        if (!isInSpawn(player.getLocation())) {
            revokeFlightManagement(player);
            return;
        }

        Vector dir = player.getLocation().getDirection().normalize();
        Vector boost = new Vector(dir.getX() * BOOST_H, BOOST_V, dir.getZ() * BOOST_H);
        player.setVelocity(boost);

        player.playSound(player.getLocation(), Sound.ENTITY_SLIME_JUMP, 1.0f, 1.2f);

        cooldowns.put(player.getUniqueId(), now);

        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline() && managedFlight.contains(player.getUniqueId())
                    && isInSpawn(player.getLocation())) {
                player.setAllowFlight(true);
            }
        }, 10L); 
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        cooldowns.remove(player.getUniqueId());
        revokeFlightManagement(player);
    }

    private boolean isInSpawn(Location loc) {
        for (ProtectRegion r : regionManager.getRegionsAt(loc)) {
            if (r.getType() == RegionType.SPAWN) return true;
        }
        return false;
    }

    private void revokeFlightManagement(Player player) {
        if (!managedFlight.remove(player.getUniqueId())) return;
        
        if (player.getGameMode() != org.bukkit.GameMode.CREATIVE
                && player.getGameMode() != org.bukkit.GameMode.SPECTATOR
                && !player.isFlying()) {
            player.setAllowFlight(false);
        }
    }
}
