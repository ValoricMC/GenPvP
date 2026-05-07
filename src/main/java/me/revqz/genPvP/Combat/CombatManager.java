package me.revqz.genPvP.Combat;

import me.revqz.genPvP.GenPvP;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class CombatManager {

    private final GenPvP plugin;

    private final Map<UUID, Long> combatExpiry      = new ConcurrentHashMap<>();
    
    private final Map<UUID, Long> pearlCooldownExpiry = new ConcurrentHashMap<>();

    private int    combatDuration;   
    private double pearlCooldown;    

    private final Set<String> allowedCommands = new HashSet<>();

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

    public double getRemaining(UUID uuid) {
        Long exp = combatExpiry.get(uuid);
        if (exp == null) return 0;
        double remaining = (exp - System.currentTimeMillis()) / 1000.0;
        return Math.max(0, remaining);
    }

    public void tagFor(UUID uuid, long seconds) {
        combatExpiry.put(uuid, System.currentTimeMillis() + seconds * 1000L);
    }

    public void remove(UUID uuid) {
        combatExpiry.remove(uuid);
        pearlCooldownExpiry.remove(uuid);
    }

    public boolean tryThrowPearl(UUID uuid) {
        Long exp = pearlCooldownExpiry.get(uuid);
        if (exp != null && System.currentTimeMillis() < exp) return false;
        pearlCooldownExpiry.put(uuid, System.currentTimeMillis() + (long) (pearlCooldown * 1000.0));
        return true;
    }

    public int getCombatDuration()       { return combatDuration; }
    public String getMsgTagged()         { return msgTagged; }
    public String getMsgUntagged()       { return msgUntagged; }
    public String getMsgCommandBlocked() { return msgCommandBlocked; }

    public boolean isCommandAllowed(String label) { return allowedCommands.contains(label); }

    public Set<UUID> getCombatants() { return combatExpiry.keySet(); }
}
