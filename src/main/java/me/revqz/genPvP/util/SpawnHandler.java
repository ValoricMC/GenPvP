package me.revqz.genPvP.util;

import me.revqz.genPvP.GenPvP;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

/**
 * Teleports players to the configured spawn point on:
 * <ul>
 *   <li><b>First join</b> — player has never played on this server before.</li>
 *   <li><b>Every join</b> — any time a player connects.</li>
 *   <li><b>Respawn</b>    — after death, overrides the respawn location.</li>
 * </ul>
 *
 * Config (under {@code spawn:} in config.yml):
 * <pre>
 * spawn:
 *   world: "world"
 *   x: 135.5
 *   y: 80.0
 *   z: -126.5
 *   yaw: 180.0
 *   pitch: 0.0
 *   on-first-join: true
 *   on-join:       true
 *   on-respawn:    true
 * </pre>
 */
public class SpawnHandler implements Listener {

    private final GenPvP plugin;

    // Cached spawn location — rebuilt on reload()
    private Location spawnLocation;
    private boolean onFirstJoin;
    private boolean onJoin;
    private boolean onRespawn;

    public SpawnHandler(GenPvP plugin) {
        this.plugin = plugin;
        reload();
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    public void reload() {
        FileConfiguration cfg = plugin.getConfig();

        String worldName = cfg.getString("spawn.world", "world");
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            plugin.getLogger().warning("[SpawnHandler] World '" + worldName + "' not found — spawn disabled until reload.");
            spawnLocation = null;
        } else {
            spawnLocation = new Location(
                    world,
                    cfg.getDouble("spawn.x",     135.5),
                    cfg.getDouble("spawn.y",      80.0),
                    cfg.getDouble("spawn.z",    -126.5),
                    (float) cfg.getDouble("spawn.yaw",   180.0),
                    (float) cfg.getDouble("spawn.pitch",   0.0)
            );
        }

        onFirstJoin = cfg.getBoolean("spawn.on-first-join", true);
        onJoin      = cfg.getBoolean("spawn.on-join",       true);
        onRespawn   = cfg.getBoolean("spawn.on-respawn",    true);
    }

    /** Returns the cached spawn {@link Location}, or {@code null} if the world isn't loaded. */
    public Location getSpawnLocation() { return spawnLocation; }

    // ── Events ────────────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerJoin(PlayerJoinEvent e) {
        if (spawnLocation == null) return;
        Player player = e.getPlayer();

        boolean firstJoin = !player.hasPlayedBefore();

        if (firstJoin && onFirstJoin) {
            // Delay by 1 tick so the player fully loads before teleporting
            Bukkit.getScheduler().runTaskLater(plugin,
                    () -> player.teleport(spawnLocation), 1L);
            return;
        }

        if (!firstJoin && onJoin) {
            Bukkit.getScheduler().runTaskLater(plugin,
                    () -> player.teleport(spawnLocation), 1L);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerRespawn(PlayerRespawnEvent e) {
        if (spawnLocation == null || !onRespawn) return;
        e.setRespawnLocation(spawnLocation);
    }
}
