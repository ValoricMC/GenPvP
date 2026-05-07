package me.revqz.genPvP.Items;

import me.revqz.genPvP.util.ColorUtil;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Arrays;

public final class AutoCompressor {

    static final String PDC_KEY_NAME = "autocompressor";

    private AutoCompressor() {}

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

        meta.getPersistentDataContainer().set(
                new NamespacedKey(plugin, PDC_KEY_NAME),
                PersistentDataType.BYTE,
                (byte) 1
        );

        item.setItemMeta(meta);
        return item;
    }

    public static boolean isAutoCompressor(ItemStack item, NamespacedKey key) {
        if (item == null || item.getType() == Material.AIR || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer().has(key, PersistentDataType.BYTE);
    }
}
