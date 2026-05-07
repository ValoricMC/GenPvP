package me.revqz.genPvP.DevilFruits;

import me.revqz.genPvP.GenPvP;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class ManaManager implements Listener {

    private final GenPvP plugin;
    private final YamlConfiguration fruitConfig;
    private final ConcurrentHashMap<UUID, Integer> mana = new ConcurrentHashMap<>();
    private volatile long nextRegenAt = 0;

    public ManaManager(GenPvP plugin, YamlConfiguration fruitConfig) {
        this.plugin      = plugin;
        this.fruitConfig = fruitConfig;
        startRegenTask();
    }

    private void startRegenTask() {
        long intervalTicks = (long) fruitConfig.getInt("mana.regen-interval-seconds", 10) * 20L;
        long intervalMs    = intervalTicks * 50L;
        int  regenAmount   = fruitConfig.getInt("mana.regen-amount", 1);
        nextRegenAt = System.currentTimeMillis() + intervalMs;
        plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            int max = getMax();
            mana.replaceAll((uuid, current) -> Math.min(current + regenAmount, max));
            nextRegenAt = System.currentTimeMillis() + intervalMs;
        }, intervalTicks, intervalTicks);
    }

    public long getMillisUntilRegen() {
        return Math.max(0, nextRegenAt - System.currentTimeMillis());
    }

    public int getMax() {
        return fruitConfig.getInt("mana.max", 10);
    }

    public int getMana(UUID uuid) {
        return mana.getOrDefault(uuid, getMax());
    }

    public boolean spend(UUID uuid, int cost) {
        int current = mana.getOrDefault(uuid, getMax());
        if (current < cost) return false;
        mana.put(uuid, current - cost);
        return true;
    }

    public void add(UUID uuid, int amount) {
        int max = getMax();
        mana.merge(uuid, amount, (cur, add) -> Math.min(cur + add, max));
    }

    public void set(UUID uuid, int value) {
        mana.put(uuid, Math.max(0, Math.min(value, getMax())));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        mana.put(event.getPlayer().getUniqueId(), getMax());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        mana.remove(event.getPlayer().getUniqueId());
    }
}
