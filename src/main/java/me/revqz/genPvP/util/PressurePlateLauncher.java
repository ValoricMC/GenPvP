package me.revqz.genPvP.util;

import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.block.Action;
import org.bukkit.util.Vector;

/**
 * Launches players ~12 blocks north when they step on a crimson pressure plate.
 */
public class PressurePlateLauncher implements Listener {

    @EventHandler
    public void onStep(PlayerInteractEvent event) {
        if (event.getAction() != Action.PHYSICAL) return;
        if (event.getClickedBlock() == null) return;
        if (event.getClickedBlock().getType() != Material.CRIMSON_PRESSURE_PLATE) return;

        var player = event.getPlayer();
        // North = negative Z. ~1.1 Z gives ~12 blocks; slight Y pop for bounce feel.
        player.setVelocity(new Vector(0, 0.455, -1.43));
        player.getWorld().playSound(player.getLocation(), Sound.ENTITY_SLIME_JUMP, 1.0f, 0.8f);
    }
}
