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

/**
 * Listens for item pickups and, if the player holds an AutoCompressor anywhere
 * in their inventory, compresses stacks of 9 ore items into their block forms.
 *
 * Thread / tick safety:
 *  - The event fires on the main thread (Bukkit guarantee).
 *  - We only schedule one compression task per player per tick via {@code pending}.
 *  - All inventory mutations happen inside a runTask callback (main thread).
 */
public class AutoCompressorListener implements Listener {

    /** 9-to-1 compression table: raw/ingot → block form. */
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
    /** PDC key used to authenticate the AutoCompressor item. */
    private final NamespacedKey pdcKey;
    /**
     * UUIDs of players who already have a compression task queued this tick.
     * Prevents stacking multiple tasks when several pickups fire in one tick.
     * Accessed only on the main thread — plain HashSet is safe.
     */
    private final Set<UUID> pending = new HashSet<>();

    public AutoCompressorListener(GenPvP plugin) {
        this.plugin = plugin;
        this.pdcKey = new NamespacedKey(plugin, AutoCompressor.PDC_KEY_NAME);
    }

    /** Exposes the PDC key so {@link AutoCompressor#isAutoCompressor} can be called externally. */
    public NamespacedKey getPdcKey() {
        return pdcKey;
    }

    // ── Events ────────────────────────────────────────────────────────────────

    /**
     * Handles natural item pickup (items on the ground picked up by walking over them).
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        scheduleCompression(player);
    }

    /**
     * Handles auto-pickup regions (GENS, OPMINES, etc.) where AutoPickupListener
     * intercepts BlockBreakEvent at MONITOR and adds drops directly to inventory —
     * no item entity is ever spawned, so EntityPickupItemEvent never fires.
     *
     * Because AutoPickupListener is registered before AutoCompressorListener in
     * GenPvP.onEnable(), its MONITOR handler runs first and the items are already
     * in the inventory by the time this handler executes.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        scheduleCompression(player);
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    /**
     * Schedules a single compression pass for the next tick.
     * Multiple pickups in the same tick collapse into one task (debounce).
     */
    private void scheduleCompression(Player player) {
        UUID uuid = player.getUniqueId();
        // pending.add returns false if already present — skip scheduling
        if (!pending.add(uuid)) return;

        plugin.getServer().getScheduler().runTask(plugin, () -> {
            pending.remove(uuid);

            // Guard: player may have disconnected between pickup and next tick
            if (!player.isOnline()) return;

            // Guard: verify compressor is still in the inventory
            if (!hasAutoCompressor(player)) return;

            compressInventory(player);
        });
    }

    /** Iterates every inventory slot looking for an AutoCompressor PDC stamp. */
    private boolean hasAutoCompressor(Player player) {
        for (ItemStack item : player.getInventory().getContents()) {
            if (AutoCompressor.isAutoCompressor(item, pdcKey)) return true;
        }
        return false;
    }

    /**
     * For each compressible material, counts how many sets of 9 the player has,
     * removes that many items, and adds the equivalent blocks.
     *
     * If the blocks don't fit (full inventory) they are dropped naturally — items
     * are never silently lost or duplicated.
     */
    private void compressInventory(Player player) {
        PlayerInventory inv = player.getInventory();
        boolean changed = false;

        for (Map.Entry<Material, Material> entry : COMPRESS_MAP.entrySet()) {
            Material raw   = entry.getKey();
            Material block = entry.getValue();

            int total = countMaterial(inv, raw);
            int sets  = total / 9;
            if (sets == 0) continue;

            // Remove exactly sets*9 items before adding blocks (atomic on main thread)
            removeMaterial(inv, raw, sets * 9);

            // Try to fit blocks into inventory
            Map<Integer, ItemStack> overflow = inv.addItem(new ItemStack(block, sets));

            // Drop any blocks that didn't fit — prevents silent item loss
            for (ItemStack leftover : overflow.values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), leftover);
            }

            changed = true;
        }

        if (changed) player.updateInventory();
    }

    /** Counts total amount of {@code mat} across all inventory slots. */
    private int countMaterial(PlayerInventory inv, Material mat) {
        int count = 0;
        for (ItemStack item : inv.getContents()) {
            if (item != null && item.getType() == mat) count += item.getAmount();
        }
        return count;
    }

    /**
     * Removes up to {@code amount} units of {@code mat} from the inventory by
     * iterating slots directly — more reliable than {@code removeItem()} for
     * split stacks and exactly-the-right-count removal.
     */
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
