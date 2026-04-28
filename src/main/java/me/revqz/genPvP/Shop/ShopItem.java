package me.revqz.genPvP.Shop;

import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.potion.PotionEffect;

import java.util.List;
import java.util.Map;

public record ShopItem(
        int slot,
        Material material,
        String name,
        double basePrice,
        String currencyType,
        boolean allowMultiple,
        List<String> lore,
        Map<Enchantment, Integer> enchants,
        int requiresPrestige,
        int amount,
        String command,          // null = give item directly; non-null = run this console command instead
        List<PotionEffect> potions,  // applied when material is POTION / SPLASH_POTION / LINGERING_POTION / TIPPED_ARROW
        RequiredItem requiresItem,   // null = no item requirement
        String itemId,               // non-empty = stamp PDC tag on purchased item (for tier progression)
        String afterBoughtTitle,     // custom display name applied after purchase (null = no rename)
        List<String> afterBoughtLore, // custom lore applied after purchase (empty = no lore)
        boolean unbreakable          // if true, item is set unbreakable on purchase
) {}
