package me.revqz.genPvP.Combat;

import me.revqz.genPvP.GenPvP;
import me.revqz.genPvP.util.ColorUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.EnderPearl;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
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

import java.util.Iterator;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Handles all combat-tagging events and the action-bar countdown task.
 *
 * <h3>Kill-on-logout</h3>
 * {@link PlayerKickEvent} fires before {@link PlayerQuitEvent} for kicked/banned players.
 * We record their UUID in {@code kickedPlayers} so the quit handler can skip the death.
 * The set is cleared immediately after the quit handler runs, preventing leaks.
 *
 * <h3>Action bar</h3>
 * A repeating task runs every 2 ticks (0.1 s) and updates the action bar for every
 * combatant. Players whose timer expires mid-task receive the "untagged" message and
 * are removed from the combat map. Two-tick resolution gives smooth 1-decimal display.
 *
 * <h3>Ender-pearl cooldown</h3>
 * {@link ProjectileLaunchEvent} intercepts pearl throws. If the thrower is in combat
 * and their per-pearl cooldown has not expired, the launch is cancelled and an
 * action-bar message shows the remaining wait.
 */
public class CombatListener implements Listener {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();

    private final GenPvP       plugin;
    private final CombatManager combat;

    /**
     * Players currently being kicked or banned — their quit event must NOT trigger a death.
     * Written in {@link #onKick} (MONITOR), read and cleared in {@link #onQuit} (MONITOR).
     * Both events fire on the main thread so a plain HashSet would be fine, but
     * ConcurrentHashMap-backed set is used defensively.
     */
    private final Set<UUID> kickedPlayers = ConcurrentHashMap.newKeySet();

    private BukkitTask actionBarTask;

    public CombatListener(GenPvP plugin, CombatManager combat) {
        this.plugin = plugin;
        this.combat = combat;
        startActionBarTask();
    }
    // actionbar
    private void startActionBarTask() {
        actionBarTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickActionBars, 2L, 2L);
    }

    /**
     * Called every 2 ticks on the main thread.
     * Iterates the live combatant set and updates each player's action bar.
     * Expired entries are removed and the player is sent the "untagged" message.
     */
    private void tickActionBars() {
        Iterator<UUID> it = combat.getCombatants().iterator();
        while (it.hasNext()) {
            UUID uuid = it.next();
            Player player = Bukkit.getPlayer(uuid);

            double remaining = combat.getRemaining(uuid);

            if (remaining <= 0) {
                it.remove();  // remove from ConcurrentHashMap.keySet() is safe
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

    // combat tag
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        // Resolve actual attacker — handles projectiles from players
        Player attacker = resolveAttacker(event);
        if (attacker == null) return;
        if (!(event.getEntity() instanceof Player victim)) return;
        if (attacker.equals(victim)) return;

        combat.tag(attacker.getUniqueId(), victim.getUniqueId());
    }

    // command block
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        if (!combat.isInCombat(player.getUniqueId())) return;

        // Extract the base label: "/foo bar baz" → "foo"
        String message = event.getMessage(); // always starts with '/'
        String label   = message.substring(1).split(" ")[0].toLowerCase();

        // Strip plugin-prefix namespace if present ("pluginname:command" → "command")
        int colon = label.indexOf(':');
        if (colon != -1) label = label.substring(colon + 1);

        if (combat.isCommandAllowed(label)) return;

        event.setCancelled(true);
        player.sendActionBar(component(combat.getMsgCommandBlocked()));
    }

    // ender pearl cooldown
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPearlLaunch(ProjectileLaunchEvent event) {
        if (!(event.getEntity() instanceof EnderPearl pearl)) return;
        if (!(pearl.getShooter() instanceof Player player)) return;
        if (!combat.isInCombat(player.getUniqueId())) return;

        if (!combat.tryThrowPearl(player.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    // ── death: clear combat tag ──────────────────────────────────────────────

    /**
     * Removes the dying player from combat immediately.
     * <ul>
     *   <li>Fixes players staying "in combat" after respawning.</li>
     *   <li>Fixes double-death: when a combatant dies naturally, their UUID
     *       is removed here. If the client then disconnects on the death
     *       screen, {@link #onQuit} sees {@code isInCombat == false} and
     *       skips the {@code setHealth(0)} punishment.</li>
     * </ul>
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onDeath(PlayerDeathEvent event) {
        combat.remove(event.getEntity().getUniqueId());
    }

    // kill on combat log
    /**
     * Records ANY player removal initiated by the server (kick, ban, anticheat, etc.)
     * so the quit handler knows the player did NOT voluntarily disconnect.
     *
     * <ul>
     *   <li><b>ignoreCancelled = false</b> — catches kicks even if another plugin
     *       cancelled the event (some anticheats cancel-then-reconnect).</li>
     *   <li><b>LOWEST priority</b> — fires before any other listener can mutate state.</li>
     * </ul>
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onKick(PlayerKickEvent event) {
        kickedPlayers.add(event.getPlayer().getUniqueId());
    }

    /**
     * Runs at MONITOR priority so all other quit handlers (scoreboard cleanup, etc.)
     * finish first. Only kills the player if:
     *   1. They are in combat
     *   2. They were NOT kicked/banned (voluntary disconnect only)
     *   3. They are not already dead (prevents double PlayerDeathEvent)
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID   uuid   = player.getUniqueId();

        boolean wasKicked = kickedPlayers.remove(uuid); // also clears the entry

        if (!combat.isInCombat(uuid)) return;

        combat.remove(uuid);

        // Server-initiated removal (kick / ban / anticheat) — never punish
        if (wasKicked) return;

        // Already dead from a lethal hit on the same tick — skip to avoid double death
        if (player.isDead() || player.getHealth() <= 0) return;

        // Voluntary combat-log: kill the player. setHealth(0) fires PlayerDeathEvent
        // normally so drops, death message, and logging all work as expected.
        player.setHealth(0.0);
    }

    /**
     * Resolves the attacking {@link Player} from a damage event, unwrapping
     * projectile shooters so arrow/fireball kills are attributed correctly.
     */
    private static Player resolveAttacker(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player p) return p;
        if (event.getDamager() instanceof Projectile proj
                && proj.getShooter() instanceof Player p) return p;
        return null;
    }

    private static Component component(String raw) {
        return LEGACY.deserialize(ColorUtil.colorize(raw));
    }

    /** Formats a double to one decimal place without String.format overhead. */
    private static String formatOneDecimal(double value) {
        int tenths = (int) Math.round(value * 10.0);
        if (tenths < 0) tenths = 0;
        return (tenths / 10) + "." + (tenths % 10);
    }
}
