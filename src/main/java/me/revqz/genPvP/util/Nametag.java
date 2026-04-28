package me.revqz.genPvP.util;

import me.revqz.genPvP.GenPvP;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

/**
 * Adds a ❤ hearts line below the vanilla nametag by setting a two-line
 * custom name directly on the Player entity.
 *
 * <p>Line 1: LuckPerms prefix + white IGN  (the existing default line)
 * <p>Line 2: red ❤ heart count             (the new line below)
 *
 * <p>No TextDisplay entity, no scoreboard hide team — the vanilla nametag
 * itself is repurposed.
 */
public class Nametag implements Listener {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();

    private final GenPvP plugin;

    public Nametag(GenPvP plugin) {
        this.plugin = plugin;
        // Periodic refresh — picks up prefix changes and health drift
        Bukkit.getScheduler().runTaskTimer(plugin, this::updateAll, 20L, 40L);
    }

    // ── Events ───────────────────────────────────────────────────────────────

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        // Small delay so LuckPerms user data is loaded before we read the prefix
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline()) apply(player);
        }, 2L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        // Reset so the player entity isn't left with a stale custom name
        event.getPlayer().customName(null);
        event.getPlayer().setCustomNameVisible(false);
    }

    @EventHandler
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (player.isOnline()) apply(player);
            }, 1L);
        }
    }

    @EventHandler
    public void onRegen(EntityRegainHealthEvent event) {
        if (event.getEntity() instanceof Player player) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (player.isOnline()) apply(player);
            }, 1L);
        }
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        event.getEntity().customName(null);
        event.getEntity().setCustomNameVisible(false);
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline()) apply(player);
        }, 2L);
    }

    // ── Core ─────────────────────────────────────────────────────────────────

    private void apply(Player player) {
        player.customName(buildText(player));
        player.setCustomNameVisible(true);
    }

    private void updateAll() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            apply(player);
        }
    }

    // ── Text builder ─────────────────────────────────────────────────────────

    private Component buildText(Player player) {
        // Line 1 — LuckPerms prefix + white IGN
        String prefix = getLuckPermsPrefix(player);
        Component line1 = LEGACY.deserialize(ColorUtil.colorize(prefix + "&f" + player.getName()));

        // Line 2 — hearts: health (0–20) ÷ 2, rounded to nearest 0.5
        double hearts = Math.round(player.getHealth()) / 2.0;
        hearts = Math.max(0, Math.min(10, hearts));
        String heartsStr = hearts == (long) hearts
                ? String.valueOf((long) hearts)
                : String.valueOf(hearts);
        Component line2 = Component.text("❤ " + heartsStr).color(TextColor.color(0xFF5555));

        return line1.append(Component.newline()).append(line2);
    }

    private String getLuckPermsPrefix(Player player) {
        try {
            net.luckperms.api.LuckPerms lp = net.luckperms.api.LuckPermsProvider.get();
            net.luckperms.api.model.user.User user = lp.getUserManager().getUser(player.getUniqueId());
            if (user != null) {
                String prefix = user.getCachedData().getMetaData().getPrefix();
                if (prefix != null) return prefix;
            }
        } catch (Exception ignored) {}
        return "";
    }

    // ── Shutdown ─────────────────────────────────────────────────────────────

    public void shutdown() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.customName(null);
            player.setCustomNameVisible(false);
        }
    }
}
