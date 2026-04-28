package me.revqz.genPvP.AntiDupe;

import me.revqz.genPvP.GenPvP;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.UUID;

/**
 * Stamps unstackable items with a unique PDC UUID so they can be tracked
 * across server restarts and player sessions.
 */
public class PDCManager {

    private final NamespacedKey keyItemUUID;

    public PDCManager(GenPvP plugin) {
        this.keyItemUUID = new NamespacedKey(plugin, "item_uuid");
    }

    /**
     * Returns the UUID for this item, assigning a new one if it has none.
     * Returns null if the item has no meta (shouldn't happen on real items).
     */
    public String getOrAssignUUID(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        ItemMeta meta = item.getItemMeta();
        PersistentDataContainer pdc = meta.getPersistentDataContainer();

        String existing = pdc.get(keyItemUUID, PersistentDataType.STRING);
        if (existing != null) return existing;

        String newId = UUID.randomUUID().toString();
        pdc.set(keyItemUUID, PersistentDataType.STRING, newId);
        item.setItemMeta(meta);
        return newId;
    }

    /**
     * Returns the UUID only if the item is already stamped, without assigning one.
     * Use this when you want to check without modifying the item.
     */
    public String getIfStamped(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        return item.getItemMeta().getPersistentDataContainer()
                .get(keyItemUUID, PersistentDataType.STRING);
    }

    /** Returns true if this item already has a UUID stamp. */
    public boolean isStamped(ItemStack item) {
        return getIfStamped(item) != null;
    }
}
