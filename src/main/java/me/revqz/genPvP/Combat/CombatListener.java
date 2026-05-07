package me.revqz.genPvP.Combat;

import me.revqz.genPvP.GenPvP;
import me.revqz.genPvP.util.ColorUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.EnderPearl;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.WindCharge;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class CombatListener implements Listener {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();

    private final GenPvP       plugin;
    private final CombatManager combat;

    private final Set<UUID> kickedPlayers = ConcurrentHashMap.newKeySet();

    private static final long PEARL_COOLDOWN_MS = 3_000L; 
    private static final long WIND_COOLDOWN_MS  = 3_000L; 
    private final Map<UUID, Long> pearlThrowTime = new HashMap<>();
    private final Map<UUID, Long> windThrowTime  = new HashMap<>();

    private BukkitTask actionBarTask;

    public CombatListener(GenPvP plugin, CombatManager combat) {
        this.plugin = plugin;
        this.combat = combat;
        startActionBarTask();
    }
    
    private void startActionBarTask() {
        actionBarTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickActionBars, 2L, 2L);
    }

    private void tickActionBars() {
        Iterator<UUID> it = combat.getCombatants().iterator();
        while (it.hasNext()) {
            UUID uuid = it.next();
            Player player = Bukkit.getPlayer(uuid);

            double remaining = combat.getRemaining(uuid);

            if (remaining <= 0) {
                it.remove();  
                combat.remove(uuid);
                if (player != null && player.isOnline()) {
                    player.sendActionBar(component(combat.getMsgUntagged()));
                }
                continue;
            }

            if (player == null || !player.isOnline()) continue;

            String text = combat.getMsgTagged()
                    .replace("%time%", formatOneDecimal(remaining));
            player.sendActionBar(component(text));
        }
    }

    public void shutdown() {
        if (actionBarTask != null) actionBarTask.cancel();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        
        Player attacker = resolveAttacker(event);
        if (attacker == null) return;
        if (!(event.getEntity() instanceof Player victim)) return;
        if (attacker.equals(victim)) return;

        combat.tag(attacker.getUniqueId(), victim.getUniqueId());
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        if (!combat.isInCombat(player.getUniqueId())) return;

        String message = event.getMessage(); 
        String label   = message.substring(1).split(" ")[0].toLowerCase();

        int colon = label.indexOf(':');
        if (colon != -1) label = label.substring(colon + 1);

        if (combat.isCommandAllowed(label)) return;

        event.setCancelled(true);
        player.sendActionBar(component(combat.getMsgCommandBlocked()));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPearlLaunch(ProjectileLaunchEvent event) {
        if (!(event.getEntity() instanceof EnderPearl pearl)) return;
        if (!(pearl.getShooter() instanceof Player player)) return;

        long now = System.currentTimeMillis();
        Long last = pearlThrowTime.get(player.getUniqueId());
        if (last != null && now - last < PEARL_COOLDOWN_MS) {
            event.setCancelled(true);
            return;
        }

        if (combat.isInCombat(player.getUniqueId()) && !combat.tryThrowPearl(player.getUniqueId())) {
            event.setCancelled(true);
            return;
        }

        pearlThrowTime.put(player.getUniqueId(), now);
        
        plugin.getServer().getScheduler().runTaskLater(plugin,
                () -> player.setCooldown(Material.ENDER_PEARL, 60), 1L);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onWindChargeLaunch(ProjectileLaunchEvent event) {
        if (!(event.getEntity() instanceof WindCharge)) return;
        if (!(event.getEntity().getShooter() instanceof Player player)) return;

        long now = System.currentTimeMillis();
        Long last = windThrowTime.get(player.getUniqueId());
        if (last != null && now - last < WIND_COOLDOWN_MS) {
            event.setCancelled(true);
            return;
        }

        windThrowTime.put(player.getUniqueId(), now);
        plugin.getServer().getScheduler().runTaskLater(plugin,
                () -> player.setCooldown(Material.WIND_CHARGE, 60), 1L);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDeath(PlayerDeathEvent event) {
        combat.remove(event.getEntity().getUniqueId());
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onKick(PlayerKickEvent event) {
        kickedPlayers.add(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID   uuid   = player.getUniqueId();

        pearlThrowTime.remove(uuid);
        windThrowTime.remove(uuid);

        boolean wasKicked = kickedPlayers.remove(uuid); 

        if (!combat.isInCombat(uuid)) return;

        combat.remove(uuid);

        if (wasKicked) return;

        if (player.isDead() || player.getHealth() <= 0) return;

        player.setHealth(0.0);
    }

    private static Player resolveAttacker(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player p) return p;
        if (event.getDamager() instanceof Projectile proj
                && proj.getShooter() instanceof Player p) return p;
        return null;
    }

    private static Component component(String raw) {
        return LEGACY.deserialize(ColorUtil.colorize(raw));
    }

    private static String formatOneDecimal(double value) {
        int tenths = (int) Math.round(value * 10.0);
        if (tenths < 0) tenths = 0;
        return (tenths / 10) + "." + (tenths % 10);
    }
}
