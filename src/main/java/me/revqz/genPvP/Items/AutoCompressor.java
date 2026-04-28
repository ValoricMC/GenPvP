package me.revqz.genPvP.Items;

import me.revqz.genPvP.util.ColorUtil;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Arrays;

/**
 * AutoCompressor — a custom item that, when held anywhere in the player's
 * inventory, automatically compresses 9 of any ore/ingot into its block form.
 *
 * ─── Anti-Dupe Notes ───────────────────────────────────────────────────────
 * 1. PDC identity — the item is authenticated by a Persistent Data Container
 *    key (genpvp:autocompressor). Renaming a NETHER_STAR or giving a vanilla
 *    one will NOT trigger compression; only items stamped by {@link #create}
 *    pass {@link #isAutoCompressor}.
 *
 * 2. Atomic compression — in AutoCompressorListener, we count → remove →
 *    addItem all on the main thread in a single tick.  No async access means
 *    no TOCTOU race.  If addItem returns overflow (full inventory) the blocks
 *    are dropped naturally — items are never silently lost or duplicated.
 *
 * 3. Single-tick debounce — the pending-UUID set in AutoCompressorListener
 *    guarantees at most one compression pass per player per tick even if many
 *    EntityPickupItemEvents fire at once (e.g. auto-clickers, lag bursts).
 *
 * 4. Online guard — the runTask callback verifies player.isOnline() before
 *    touching the inventory, preventing stale operations on disconnect.
 *
 * 5. No compression loop — only items in COMPRESS_MAP are candidates.
 *    NETHER_STAR (the compressor's own material) is not in that map, so the
 *    item can never compress itself into something else.
 *
 * 6. Creative / command exploit — the PDC stamp is write-once via server code;
 *    players cannot set it client-side.  Pair with AntiDupe's unstackable-item
 *    UUID scan so two compressors with different PDC stamps are still caught if
 *    a player somehow acquires duplicates through external means.
 * ───────────────────────────────────────────────────────────────────────────
 */
public final class AutoCompressor {

    /** Shared PDC key name — both create() and AutoCompressorListener use this. */
    static final String PDC_KEY_NAME = "autocompressor";

    private AutoCompressor() {}

    /**
     * Builds a freshly stamped AutoCompressor item stack (quantity 1).
     */
    public static ItemStack create(JavaPlugin plugin) {
        ItemStack item = new ItemStack(Material.NETHER_STAR);
        ItemMeta meta = item.getItemMeta();

        meta.setDisplayName(ColorUtil.colorize(
                "&#5FE2C5&lA&#6AE6CB&lU&#76EAD0&lT&#81EED6&lO " +
                "&#98F7E2&lC&#A4FBE7&lO&#AFFFED&lM&#A4FBE7&lP" +
                "&#98F7E2&lR&#8DF3DC&lE&#81EED6&lS&#76EAD0&lS&#6AE6CB&lO&#5FE2C5&lR"
        ));

        meta.setLore(Arrays.asList(
                ColorUtil.colorize("&8ᴀᴜᴛᴏ ᴄᴏᴍᴘʀᴇssᴏʀ"),
                "",
                ColorUtil.colorize("&7● &fHave in your inventory"),
                ColorUtil.colorize("&f   to auto compress any ores to blocks.")
        ));

        // Stamp with PDC so it cannot be faked by renaming a vanilla NETHER_STAR
        meta.getPersistentDataContainer().set(
                new NamespacedKey(plugin, PDC_KEY_NAME),
                PersistentDataType.BYTE,
                (byte) 1
        );

        item.setItemMeta(meta);
        return item;
    }

    /**
     * Returns true if {@code item} is a genuine (PDC-stamped) AutoCompressor.
     *
     * @param item the item to test (may be null or AIR)
     * @param key  the NamespacedKey from {@link AutoCompressorListener}
     */
    public static boolean isAutoCompressor(ItemStack item, NamespacedKey key) {
        if (item == null || item.getType() == Material.AIR || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer().has(key, PersistentDataType.BYTE);
    }
}
