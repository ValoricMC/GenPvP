package me.revqz.genPvP.util;

import me.revqz.genPvP.GenPvP;
import org.bukkit.Location;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntitySpawnEvent;

/**
 * Intercepts every {@link ExperienceOrb} spawn and redirects the XP directly
 * to the nearest player within range instead of letting it drop on the floor.
 *
 * <p>Covers <em>all</em> XP sources with a single listener:
 * mining, mob/player kills, furnaces, fishing, trading, breeding, etc.
 *
 * <p>{@code giveExp(amount, true)} is used so that Mending enchantments are
 * applied before any surplus goes to the XP bar.
 *
 * <p>If no player is within {@code xp-pickup.range} blocks the orb spawns
 * normally — XP is never silently deleted.
 *
 * Config:
 * <pre>
 * xp-pickup:
 *   enabled: true
 *   range: 16.0
 * </pre>
 */
public class XpPickupListener implements Listener {

    private final GenPvP plugin;
    private boolean enabled;
    private double  rangeSquared; // store squared to avoid sqrt each event

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
        if (nearest == null) return; // nobody nearby — let the orb drop normally

        e.setCancelled(true);
        // true → apply Mending repair before adding to the XP bar
        nearest.giveExp(orb.getExperience(), true);
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /** Returns the nearest online player within range, or {@code null} if none. */
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
