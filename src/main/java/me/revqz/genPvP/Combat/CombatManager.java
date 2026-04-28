package me.revqz.genPvP.Combat;

import me.revqz.genPvP.GenPvP;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks which players are currently in combat and manages ender-pearl cooldowns.
 *
 * <p>All state is stored in {@link ConcurrentHashMap}s so reads from the
 * action-bar task (main thread) and writes from event handlers (also main thread)
 * are safe. No cross-thread mutations happen here — Bukkit events and the
 * scheduler both run on the server thread.
 *
 * <pre>
 * combat:
 *   duration: 15               # seconds a player stays tagged after the last hit
 *   pearl-cooldown: 12         # seconds between ender-pearl throws while in combat
 *   messages:
 *     tagged:        "&#E24848&lC&#FF8383&lO&#F16666&lM&#E24848&lB&#FF8383&lA&#E24848&lT &8» &f%time%s"
 *     untagged:      "&aYou are no longer in combat."
 *     logged-out:    "&c%player% &7has logged out during combat!"
 *     pearl-blocked: "&cYou cannot throw an ender pearl for another &e%time%s&c."
 * </pre>
 */
public class CombatManager {

    private final GenPvP plugin;

    // UUID System.currentTimeMillis() when combat expires
    private final Map<UUID, Long> combatExpiry      = new ConcurrentHashMap<>();
    // UUID System.currentTimeMillis() when ender-pearl cooldown expires
    private final Map<UUID, Long> pearlCooldownExpiry = new ConcurrentHashMap<>();

    // Cached config values (reloaded via loadConfig)
    private int    combatDuration;   // seconds
    private double pearlCooldown;    // seconds (supports decimals, e.g. 2.5)

    // Lower-cased command labels that are permitted while in combat
    private final Set<String> allowedCommands = new HashSet<>();

    // Message templates (pre-cached, no config lookup per tick)
    private String msgTagged;
    private String msgUntagged;
    private String msgCommandBlocked;

    public CombatManager(GenPvP plugin) {
        this.plugin = plugin;
        loadConfig();
    }

    public void loadConfig() {
        var cfg = plugin.getConfig();
        combatDuration = cfg.getInt("combat.duration", 15);
        pearlCooldown  = cfg.getDouble("combat.pearl-cooldown", 2.5);
        msgTagged      = cfg.getString("combat.messages.tagged",
                "&#E24848&lC&#FF8383&lO&#F16666&lM&#E24848&lB&#FF8383&lA&#E24848&lT &8\u00bb &f%time%s");
        msgUntagged       = cfg.getString("combat.messages.untagged",        "&aYou are no longer in combat.");
        msgCommandBlocked = cfg.getString("combat.messages.command-blocked",  "&cYou cannot use commands while in combat.");

        allowedCommands.clear();
        List<String> whitelist = cfg.getStringList("combat.allowed-commands");
        for (String cmd : whitelist) allowedCommands.add(cmd.toLowerCase());
    }

    // combat tag
    /**
     * Tags both players in combat (or resets their timer if already tagged).
     * Always call with two distinct UUIDs — attacker and victim.
     */
    public void tag(UUID a, UUID b) {
        long expires = System.currentTimeMillis() + (long) combatDuration * 1000L;
        combatExpiry.put(a, expires);
        combatExpiry.put(b, expires);
    }

    public boolean isInCombat(UUID uuid) {
        Long exp = combatExpiry.get(uuid);
        if (exp == null) return false;
        if (System.currentTimeMillis() >= exp) {
            combatExpiry.remove(uuid);
            return false;
        }
        return true;
    }

    /**
     * Returns seconds remaining (with one decimal) or 0 if not in combat.
     */
    public double getRemaining(UUID uuid) {
        Long exp = combatExpiry.get(uuid);
        if (exp == null) return 0;
        double remaining = (exp - System.currentTimeMillis()) / 1000.0;
        return Math.max(0, remaining);
    }

    /**
     * Tags a single player for exactly {@code seconds} seconds (for admin testing).
     * Does not tag any second player.
     */
    public void tagFor(UUID uuid, long seconds) {
        combatExpiry.put(uuid, System.currentTimeMillis() + seconds * 1000L);
    }

    /** Removes a player from combat without killing them (logout-kill is handled externally). */
    public void remove(UUID uuid) {
        combatExpiry.remove(uuid);
        pearlCooldownExpiry.remove(uuid);
    }

    // ender pearl cooldown

    /**
     * Returns true if the player may throw an ender pearl right now.
     * If yes, records the throw so the cooldown starts.
     */
    public boolean tryThrowPearl(UUID uuid) {
        Long exp = pearlCooldownExpiry.get(uuid);
        if (exp != null && System.currentTimeMillis() < exp) return false;
        pearlCooldownExpiry.put(uuid, System.currentTimeMillis() + (long) (pearlCooldown * 1000.0));
        return true;
    }

    // combat listener

    public int getCombatDuration()       { return combatDuration; }
    public String getMsgTagged()         { return msgTagged; }
    public String getMsgUntagged()       { return msgUntagged; }
    public String getMsgCommandBlocked() { return msgCommandBlocked; }

    /** Returns true if the command label (no leading slash, lowercase) is whitelisted. */
    public boolean isCommandAllowed(String label) { return allowedCommands.contains(label); }

    /** Full expiry map — iterated by the action-bar task. */
    public Set<UUID> getCombatants() { return combatExpiry.keySet(); }
}
