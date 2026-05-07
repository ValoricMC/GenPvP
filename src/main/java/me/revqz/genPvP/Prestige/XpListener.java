package me.revqz.genPvP.Prestige;

import me.revqz.genPvP.GenPvP;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.PlayerDeathEvent;

import java.util.concurrent.ConcurrentHashMap;
import java.util.UUID;

public class XpListener implements Listener {

    private final GenPvP plugin;
    private final PrestigeManager prestigeManager;

    private final ConcurrentHashMap<UUID, Integer> blockCounter = new ConcurrentHashMap<>();

    private double killXp;
    private int    blocksPerXp;

    public XpListener(GenPvP plugin, PrestigeManager prestigeManager) {
        this.plugin           = plugin;
        this.prestigeManager  = prestigeManager;
        reloadConfig();
    }

    public void reloadConfig() {
        killXp      = plugin.getConfig().getDouble("prestige.xp-sources.kill-xp",       5.0);
        blocksPerXp = Math.max(1, plugin.getConfig().getInt("prestige.xp-sources.blocks-per-xp", 10));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        Entity killerEntity = victim.getKiller();

        if (!(killerEntity instanceof Player killer)) return;  
        if (killer.getUniqueId().equals(victim.getUniqueId())) return; 

        prestigeManager.addXp(killer.getUniqueId(), killer.getName(), killXp);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        UUID   uuid   = player.getUniqueId();

        int newCount = blockCounter.merge(uuid, 1, Integer::sum);

        if (newCount % blocksPerXp == 0) {
            prestigeManager.addXp(uuid, player.getName(), 1.0);
        }
    }
}
