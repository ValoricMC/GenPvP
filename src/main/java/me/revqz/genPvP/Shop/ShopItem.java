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
        String command,          
        List<PotionEffect> potions,  
        RequiredItem requiresItem,   
        String itemId,               
        String afterBoughtTitle,     
        List<String> afterBoughtLore, 
        boolean unbreakable          
) {}
