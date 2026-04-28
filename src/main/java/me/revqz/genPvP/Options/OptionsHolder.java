package me.revqz.genPvP.Options;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

/** Marker holder so InventoryClickEvent knows this is the Options menu. */
public class OptionsHolder implements InventoryHolder {
    @Override
    public Inventory getInventory() { return null; }
}
