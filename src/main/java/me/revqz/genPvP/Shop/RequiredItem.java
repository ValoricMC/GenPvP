package me.revqz.genPvP.Shop;

import org.bukkit.Material;

/**
 * Describes an item the player must hold in their inventory before a shop item
 * can be purchased.
 *
 * Config format (inside any item entry in shops.yml):
 *
 *   requires-item:
 *     material: DIAMOND_PICKAXE
 *     item-id: "pickaxe_tier_1"  # preferred — matches PDC tag stamped on purchase
 *     name-contains: "TIER 1"    # fallback when item-id is absent (case-insensitive)
 *     consume: true               # if true the item is removed from the player's inventory on purchase
 */
public record RequiredItem(
        Material material,
        String   itemId,         // non-empty = match by PDC tag (preferred)
        String   nameContains,   // non-empty = match by display name (fallback)
        boolean  consume
) {
    /** Display label used in lore and messages. */
    public String label() {
        if (itemId != null && !itemId.isBlank())
            return itemId.replace('_', ' ');
        if (nameContains != null && !nameContains.isBlank())
            return nameContains;
        return material.name().replace('_', ' ');
    }
}
