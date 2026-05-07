package me.revqz.genPvP.AntiDupe;

import me.revqz.genPvP.GenPvP;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.UUID;

public class PDCManager {

    private final NamespacedKey keyItemUUID;

    public PDCManager(GenPvP plugin) {
        this.keyItemUUID = new NamespacedKey(plugin, "item_uuid");
    }

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

    public String getIfStamped(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        return item.getItemMeta().getPersistentDataContainer()
                .get(keyItemUUID, PersistentDataType.STRING);
    }

    public boolean isStamped(ItemStack item) {
        return getIfStamped(item) != null;
    }
}
