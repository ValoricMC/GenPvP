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

public class SpawnHandler implements Listener {

    private final GenPvP plugin;

    private Location spawnLocation;
    private boolean onFirstJoin;
    private boolean onJoin;
    private boolean onRespawn;

    public SpawnHandler(GenPvP plugin) {
        this.plugin = plugin;
        reload();
    }

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

    public Location getSpawnLocation() { return spawnLocation; }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerJoin(PlayerJoinEvent e) {
        if (spawnLocation == null) return;
        Player player = e.getPlayer();

        boolean firstJoin = !player.hasPlayedBefore();

        if (firstJoin && onFirstJoin) {
            
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
