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

/**
 * Awards XP to players for kills and block breaks.
 *
 * <h3>Rates (configurable in config.yml under prestige.xp-sources)</h3>
 * <ul>
 *   <li>{@code kill-xp}        — XP awarded per player kill  (default: 5)</li>
 *   <li>{@code blocks-per-xp}  — Blocks a player must break to earn 1 XP (default: 10)</li>
 * </ul>
 *
 * Both rates are read on construction and on {@link #reloadConfig()}.
 */
public class XpListener implements Listener {

    private final GenPvP plugin;
    private final PrestigeManager prestigeManager;

    /** Tracks cumulative blocks broken per player since last XP award. */
    private final ConcurrentHashMap<UUID, Integer> blockCounter = new ConcurrentHashMap<>();

    // ── Configurable rates ────────────────────────────────────────────────────
    private double killXp;
    private int    blocksPerXp;

    public XpListener(GenPvP plugin, PrestigeManager prestigeManager) {
        this.plugin           = plugin;
        this.prestigeManager  = prestigeManager;
        reloadConfig();
    }

    /** Reads XP rates from config.yml. Safe to call on /genpvp reload. */
    public void reloadConfig() {
        killXp      = plugin.getConfig().getDouble("prestige.xp-sources.kill-xp",       5.0);
        blocksPerXp = Math.max(1, plugin.getConfig().getInt("prestige.xp-sources.blocks-per-xp", 10));
    }

    // ── Kill handler ──────────────────────────────────────────────────────────

    /**
     * Awards XP to the killer when a player dies.
     * Uses MONITOR so all damage/death handling finishes first.
     * Ignores self-kills (e.g. fall damage, /kill).
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        Entity killerEntity = victim.getKiller();

        if (!(killerEntity instanceof Player killer)) return;  // no player killer
        if (killer.getUniqueId().equals(victim.getUniqueId())) return; // self-kill

        prestigeManager.addXp(killer.getUniqueId(), killer.getName(), killXp);
    }

    // ── Block break handler ───────────────────────────────────────────────────

    /**
     * Increments a per-player block counter. Every {@code blocksPerXp} blocks
     * broken earns the player 1 XP. Counter is never reset — it accumulates
     * indefinitely, so partial progress carries across sessions (in memory only;
     * counter resets on server restart, which is intentional).
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        UUID   uuid   = player.getUniqueId();

        int newCount = blockCounter.merge(uuid, 1, Integer::sum);

        // Award 1 XP for each completed batch of blocksPerXp blocks
        if (newCount % blocksPerXp == 0) {
            prestigeManager.addXp(uuid, player.getName(), 1.0);
        }
    }
}
