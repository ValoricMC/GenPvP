package me.revqz.genPvP.util;

import me.revqz.genPvP.GenPvP;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Handles all player join, leave, kill, and death broadcast messages.
 *
 * <p>All messages are configurable under {@code chat:} in config.yml.
 * Set a message to an empty string to suppress it entirely.
 *
 * <pre>
 * chat:
 *   join:       "&7[&a+&f] &f%player% &fhas joined!"
 *   join-first: "&7[&a+&7] &f%player% &7has joined for the first time, Welcome him!"
 *   leave:      ""          # empty = no leave message shown
 *   kill:       "&7[&c☠&7] &f%killer% &7has slain &f%victim%&7!"
 *   death:      "&7[&c☠&7] &f%player% &7has died!"
 * </pre>
 */
public class ChatListener implements Listener {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();

    private final GenPvP plugin;

    // Cached config strings — rebuilt on reload()
    private String joinMsg;
    private String joinFirstMsg;
    private String leaveMsg;
    private String killMsg;
    private String deathMsg;

    public ChatListener(GenPvP plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        var cfg = plugin.getConfig();
        joinMsg      = cfg.getString("chat.join",       "&7[&a+&f] &f%player% &fhas joined!");
        joinFirstMsg = cfg.getString("chat.join-first", "&7[&a+&7] &f%player% &7has joined for the first time, Welcome him!");
        leaveMsg     = cfg.getString("chat.leave",      "");
        killMsg      = cfg.getString("chat.kill",       "&7[&c\u2620&7] &f%killer% &7has slain &f%victim%&7!");
        deathMsg     = cfg.getString("chat.death",      "&7[&c\u2620&7] &f%player% &7has died!");
    }

    // ── Join ──────────────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.HIGH)
    public void onJoin(PlayerJoinEvent event) {
        // Suppress Bukkit's default join message
        event.joinMessage(null);

        Player player    = event.getPlayer();
        boolean firstJoin = !player.hasPlayedBefore();

        if (firstJoin) {
            broadcast(joinFirstMsg.replace("%player%", player.getName()));
            // Soft chime for everyone online to notice a new face
            for (Player online : Bukkit.getOnlinePlayers()) {
                online.playSound(online.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 0.4f, 1.5f);
            }
        } else {
            broadcast(joinMsg.replace("%player%", player.getName()));
        }
    }

    // ── Leave ─────────────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.HIGH)
    public void onQuit(PlayerQuitEvent event) {
        // Suppress Bukkit's default quit message
        event.quitMessage(null);

        if (!leaveMsg.isBlank()) {
            broadcast(leaveMsg.replace("%player%", event.getPlayer().getName()));
        }
    }

    // ── Kill / Death ──────────────────────────────────────────────────────────

    /**
     * Runs at HIGH priority so we override the default death message before
     * MONITOR listeners (e.g. LogListener) read the already-nulled value.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent event) {
        // Suppress vanilla death message
        event.deathMessage(null);

        Player victim = event.getEntity();
        Entity killerEntity = victim.getKiller();

        if (killerEntity instanceof Player killer) {
            if (!killMsg.isBlank()) {
                broadcast(killMsg
                        .replace("%killer%", killer.getName())
                        .replace("%victim%",  victim.getName()));
            }
        } else {
            if (!deathMsg.isBlank()) {
                broadcast(deathMsg.replace("%player%", victim.getName()));
            }
        }
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private static void broadcast(String raw) {
        Component msg = LEGACY.deserialize(ColorUtil.colorize(raw));
        Bukkit.broadcast(msg);
    }
}
