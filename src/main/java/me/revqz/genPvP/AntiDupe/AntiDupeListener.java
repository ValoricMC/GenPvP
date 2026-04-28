package me.revqz.genPvP.AntiDupe;

import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;

public class AntiDupeListener implements Listener {

    private final AntiDupeManager manager;

    public AntiDupeListener(AntiDupeManager manager) {
        this.manager = manager;
    }

    /** Stamp all existing items in the player's inventory when they log in. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        manager.stampInventory(event.getPlayer());
    }

    /** Stamp all items when any inventory is opened (catches items from chests, trades, etc.). */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (event.getPlayer() instanceof Player player) {
            manager.stampInventory(player);
        }
    }

    /** Check items as they're picked up off the ground — cancel if confirmed dupe. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        Item itemEntity = event.getItem();
        ItemStack item  = itemEntity.getItemStack();

        if (manager.checkItem(player, item)) {
            event.setCancelled(true);
        }
    }

    /** Clean up all tracking data when a player disconnects. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        manager.clearPlayer(event.getPlayer().getUniqueId());
    }
}
