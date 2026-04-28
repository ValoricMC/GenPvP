package me.revqz.genPvP.AntiDupe;

import org.bukkit.configuration.file.FileConfiguration;

/**
 * All configurable anti-dupe flags, loaded from the plugin's config.yml.
 * Call {@link #reload(FileConfiguration)} to refresh after a config reload.
 */
public class AntiDupeConfig {

    // ── Detection ─────────────────────────────────────────────────────────────
    public boolean enabled;
    public boolean checkUnstackables;
    public long    scanIntervalTicks;

    // ── Actions ───────────────────────────────────────────────────────────────
    public boolean deleteItem;
    public boolean notifyOps;
    public boolean logConsole;
    public String  punishCommand;

    // ── Anti-spam ─────────────────────────────────────────────────────────────
    public long alertCooldownSeconds;

    public AntiDupeConfig(FileConfiguration cfg) {
        reload(cfg);
    }

    public void reload(FileConfiguration cfg) {
        enabled              = cfg.getBoolean("anti-dupe.enabled",               true);
        checkUnstackables    = cfg.getBoolean("anti-dupe.check-unstackables",    true);
        scanIntervalTicks    = cfg.getLong   ("anti-dupe.scan-interval-ticks",   40L);
        deleteItem           = cfg.getBoolean("anti-dupe.delete-item",           true);
        notifyOps            = cfg.getBoolean("anti-dupe.notify-ops",            true);
        logConsole           = cfg.getBoolean("anti-dupe.log-console",           true);
        punishCommand        = cfg.getString ("anti-dupe.punish-command",        "");
        alertCooldownSeconds = cfg.getLong   ("anti-dupe.alert-cooldown-seconds", 30L);
    }
}
