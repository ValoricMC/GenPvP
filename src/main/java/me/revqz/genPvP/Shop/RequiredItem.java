package me.revqz.genPvP.Shop;

import org.bukkit.Material;

public record RequiredItem(
        Material material,
        String   itemId,         
        String   nameContains,   
        boolean  consume
) {
    
    public String label() {
        if (itemId != null && !itemId.isBlank())
            return itemId.replace('_', ' ');
        if (nameContains != null && !nameContains.isBlank())
            return nameContains;
        return material.name().replace('_', ' ');
    }
}
