package me.revqz.genPvP.util;

import me.revqz.genPvP.GenPvP;
import org.bukkit.Location;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntitySpawnEvent;

public class XpPickupListener implements Listener {

    private final GenPvP plugin;
    private boolean enabled;
    private double  rangeSquared; 

    public XpPickupListener(GenPvP plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        enabled      = plugin.getConfig().getBoolean("xp-pickup.enabled", true);
        double range = plugin.getConfig().getDouble("xp-pickup.range", 16.0);
        rangeSquared = range * range;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onOrbSpawn(EntitySpawnEvent e) {
        if (!enabled) return;
        if (!(e.getEntity() instanceof ExperienceOrb orb)) return;

        Player nearest = nearestPlayer(orb.getLocation());
        if (nearest == null) return; 

        e.setCancelled(true);
        
        nearest.giveExp(orb.getExperience(), true);
    }

    private Player nearestPlayer(Location loc) {
        Player best   = null;
        double bestD2 = rangeSquared;
        for (Player p : loc.getWorld().getPlayers()) {
            double d2 = p.getLocation().distanceSquared(loc);
            if (d2 < bestD2) { bestD2 = d2; best = p; }
        }
        return best;
    }
}
