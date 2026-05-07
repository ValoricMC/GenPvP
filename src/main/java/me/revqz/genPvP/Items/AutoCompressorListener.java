package me.revqz.genPvP.Items;

import me.revqz.genPvP.GenPvP;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataType;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class AutoCompressorListener implements Listener {

    private static final Map<Material, Material> COMPRESS_MAP = new LinkedHashMap<>();
    static {
        COMPRESS_MAP.put(Material.DIAMOND,          Material.DIAMOND_BLOCK);
        COMPRESS_MAP.put(Material.EMERALD,          Material.EMERALD_BLOCK);
        COMPRESS_MAP.put(Material.RAW_IRON,         Material.RAW_IRON_BLOCK);
        COMPRESS_MAP.put(Material.IRON_INGOT,       Material.IRON_BLOCK);
        COMPRESS_MAP.put(Material.RAW_GOLD,         Material.RAW_GOLD_BLOCK);
        COMPRESS_MAP.put(Material.GOLD_INGOT,       Material.GOLD_BLOCK);
        COMPRESS_MAP.put(Material.COAL,             Material.COAL_BLOCK);
        COMPRESS_MAP.put(Material.REDSTONE,         Material.REDSTONE_BLOCK);
        COMPRESS_MAP.put(Material.LAPIS_LAZULI,     Material.LAPIS_BLOCK);
        COMPRESS_MAP.put(Material.RAW_COPPER,       Material.RAW_COPPER_BLOCK);
        COMPRESS_MAP.put(Material.COPPER_INGOT,     Material.COPPER_BLOCK);
        COMPRESS_MAP.put(Material.AMETHYST_SHARD,   Material.AMETHYST_BLOCK);
        COMPRESS_MAP.put(Material.QUARTZ,           Material.QUARTZ_BLOCK);
        COMPRESS_MAP.put(Material.NETHERITE_INGOT,  Material.NETHERITE_BLOCK);
    }

    private final GenPvP plugin;
    
    private final NamespacedKey pdcKey;
    
    private final Set<UUID> pending = new HashSet<>();

    public AutoCompressorListener(GenPvP plugin) {
        this.plugin = plugin;
        this.pdcKey = new NamespacedKey(plugin, AutoCompressor.PDC_KEY_NAME);
    }

    public NamespacedKey getPdcKey() {
        return pdcKey;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        scheduleCompression(player);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        scheduleCompression(player);
    }

    private void scheduleCompression(Player player) {
        UUID uuid = player.getUniqueId();
        
        if (!pending.add(uuid)) return;

        plugin.getServer().getScheduler().runTask(plugin, () -> {
            pending.remove(uuid);

            if (!player.isOnline()) return;

            if (!hasAutoCompressor(player)) return;

            compressInventory(player);
        });
    }

    private boolean hasAutoCompressor(Player player) {
        for (ItemStack item : player.getInventory().getContents()) {
            if (AutoCompressor.isAutoCompressor(item, pdcKey)) return true;
        }
        return false;
    }

    private void compressInventory(Player player) {
        PlayerInventory inv = player.getInventory();
        boolean changed = false;

        for (Map.Entry<Material, Material> entry : COMPRESS_MAP.entrySet()) {
            Material raw   = entry.getKey();
            Material block = entry.getValue();

            int total = countMaterial(inv, raw);
            int sets  = total / 9;
            if (sets == 0) continue;

            removeMaterial(inv, raw, sets * 9);

            Map<Integer, ItemStack> overflow = inv.addItem(new ItemStack(block, sets));

            for (ItemStack leftover : overflow.values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), leftover);
            }

            changed = true;
        }

        if (changed) player.updateInventory();
    }

    private int countMaterial(PlayerInventory inv, Material mat) {
        int count = 0;
        for (ItemStack item : inv.getContents()) {
            if (item != null && item.getType() == mat) count += item.getAmount();
        }
        return count;
    }

    private void removeMaterial(PlayerInventory inv, Material mat, int amount) {
        ItemStack[] contents = inv.getContents();
        for (int i = 0; i < contents.length && amount > 0; i++) {
            ItemStack item = contents[i];
            if (item == null || item.getType() != mat) continue;
            int take = Math.min(item.getAmount(), amount);
            amount -= take;
            if (item.getAmount() == take) {
                contents[i] = null;
            } else {
                item.setAmount(item.getAmount() - take);
            }
        }
        inv.setContents(contents);
    }
}
