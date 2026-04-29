package me.revqz.genPvP.SpawnDisplays;

import me.revqz.genPvP.GenPvP;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.persistence.PersistentDataType;

import java.util.UUID;

public class DisplaysListener implements Listener {

    private final GenPvP plugin;
    private final DisplaysManager manager;

    public DisplaysListener(GenPvP plugin, DisplaysManager manager) {
        this.plugin  = plugin;
        this.manager = manager;
    }

    @EventHandler
    public void onInteract(PlayerInteractAtEntityEvent event) {
        if (!(event.getRightClicked() instanceof Interaction interaction)) return;
        var pdc = interaction.getPersistentDataContainer();
        if (!pdc.has(manager.KEY_SPAWN_ENTITY, PersistentDataType.BOOLEAN)) return;

        event.setCancelled(true);
        Player player = event.getPlayer();

        if (pdc.has(manager.KEY_DISCORD, PersistentDataType.STRING)) {
            player.performCommand("discord");
            player.playSound(player.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 1.0f, 1.0f);
            glowTemporarily(pdc.get(manager.KEY_DISCORD, PersistentDataType.STRING));
        }

        if (pdc.has(manager.KEY_STORE, PersistentDataType.STRING)) {
            player.performCommand("store");
            player.playSound(player.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 1.0f, 1.0f);
            glowTemporarily(pdc.get(manager.KEY_STORE, PersistentDataType.STRING));
        }
    }

    private void glowTemporarily(String uuidStr) {
        try {
            Entity entity = Bukkit.getEntity(UUID.fromString(uuidStr));
            if (entity == null || !entity.isValid()) return;
            entity.setGlowing(true);
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> entity.setGlowing(false), 20L);
        } catch (IllegalArgumentException ignored) {}
    }
}
