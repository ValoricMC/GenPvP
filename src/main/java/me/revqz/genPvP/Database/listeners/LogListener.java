package me.revqz.genPvP.Database.listeners;

import me.revqz.genPvP.Database.LogManager;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.UUID;

public class LogListener implements Listener {

    private final LogManager logManager;

    public LogListener(LogManager logManager) {
        this.logManager = logManager;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        logManager.logConnection(event.getPlayer().getUniqueId(), "JOIN");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        logManager.logConnection(event.getPlayer().getUniqueId(), "QUIT");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        Entity killerEntity = victim.getKiller(); 
        UUID killerUUID = (killerEntity instanceof Player killer) ? killer.getUniqueId() : null;
        logManager.logPvP(killerUUID, victim.getUniqueId());
    }
}
