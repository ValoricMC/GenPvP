package me.revqz.genPvP.Stats;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;

public class StatsListener implements Listener {

    private final StatsManager stats;

    public StatsListener(StatsManager stats) {
        this.stats = stats;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        stats.addDeath(victim.getUniqueId());

        Player killer = victim.getKiller();
        if (killer != null && !killer.equals(victim)) {
            stats.addKill(killer.getUniqueId());
        }
    }
}
