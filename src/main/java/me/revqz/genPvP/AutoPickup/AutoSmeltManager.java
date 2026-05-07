package me.revqz.genPvP.AutoPickup;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class AutoSmeltManager {

    private final Set<UUID> enabled = ConcurrentHashMap.newKeySet();

    private static final Map<Material, Material> SMELT_MAP = new EnumMap<>(Material.class);

    static {
        
        SMELT_MAP.put(Material.RAW_IRON,   Material.IRON_INGOT);
        SMELT_MAP.put(Material.RAW_GOLD,   Material.GOLD_INGOT);
        SMELT_MAP.put(Material.RAW_COPPER, Material.COPPER_INGOT);

        SMELT_MAP.put(Material.IRON_ORE,              Material.IRON_INGOT);
        SMELT_MAP.put(Material.DEEPSLATE_IRON_ORE,    Material.IRON_INGOT);
        SMELT_MAP.put(Material.GOLD_ORE,              Material.GOLD_INGOT);
        SMELT_MAP.put(Material.DEEPSLATE_GOLD_ORE,    Material.GOLD_INGOT);
        SMELT_MAP.put(Material.NETHER_GOLD_ORE,       Material.GOLD_INGOT);
        SMELT_MAP.put(Material.COPPER_ORE,            Material.COPPER_INGOT);
        SMELT_MAP.put(Material.DEEPSLATE_COPPER_ORE,  Material.COPPER_INGOT);

        SMELT_MAP.put(Material.ANCIENT_DEBRIS, Material.NETHERITE_SCRAP);
    }

    public boolean isEnabled(UUID uuid) {
        return enabled.contains(uuid);
    }

    public boolean toggle(UUID uuid) {
        if (enabled.remove(uuid)) return false;
        enabled.add(uuid);
        return true;
    }

    public ItemStack smelt(ItemStack item) {
        if (item == null) return null;
        Material output = SMELT_MAP.get(item.getType());
        if (output == null) return item;
        return new ItemStack(output, item.getAmount());
    }

    public boolean isSmeltable(Material material) {
        return SMELT_MAP.containsKey(material);
    }
}
