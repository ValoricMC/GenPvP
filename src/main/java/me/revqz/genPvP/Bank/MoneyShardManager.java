package me.revqz.genPvP.Bank;

import me.revqz.genPvP.GenPvP;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;

public class MoneyShardManager {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.builder()
            .character('&')
            .hexColors()
            .build();

    private final GenPvP        plugin;
    private final NamespacedKey SHARD_KEY;

    private String       shardName;
    private List<String> shardLore;
    
    private double       valuePerShard;

    public MoneyShardManager(GenPvP plugin) {
        this.plugin    = plugin;
        this.SHARD_KEY = new NamespacedKey(plugin, "money_shard");
        reload();
    }

    public void reload() {
        var cfg       = plugin.getConfig();
        shardName     = cfg.getString("money-shard.name", "&b&lMoney Shard");
        shardLore     = cfg.getStringList("money-shard.lore");
        if (shardLore.isEmpty()) {
            shardLore = List.of(
                    "&7Worth &a$1 &7each.",
                    "&7Use &b/deposit &7to redeem."
            );
        }
        valuePerShard = cfg.getDouble("money-shard.value", 1.0);
        if (valuePerShard <= 0) valuePerShard = 1.0;
    }

    public double getValuePerShard() { return valuePerShard; }

    public ItemStack createShard(int count) {
        count = Math.max(1, Math.min(64, count));
        ItemStack stack = new ItemStack(Material.PRISMARINE_SHARD, count);
        ItemMeta  meta  = stack.getItemMeta();
        if (meta == null) return stack;

        meta.displayName(noItalic(LEGACY.deserialize(shardName)));

        List<Component> lore = new ArrayList<>();
        for (String line : shardLore)
            lore.add(noItalic(LEGACY.deserialize(line)));
        meta.lore(lore);

        meta.getPersistentDataContainer().set(SHARD_KEY, PersistentDataType.BYTE, (byte) 1);
        stack.setItemMeta(meta);
        return stack;
    }

    public boolean isLegitShard(ItemStack stack) {
        if (stack == null || stack.getType() != Material.PRISMARINE_SHARD) return false;
        ItemMeta meta = stack.getItemMeta();
        return meta != null
                && meta.getPersistentDataContainer().has(SHARD_KEY, PersistentDataType.BYTE);
    }

    public int countShards(Player player) {
        int total = 0;
        for (ItemStack stack : player.getInventory().getStorageContents()) {
            if (isLegitShard(stack)) total += stack.getAmount();
        }
        return total;
    }

    public int availableCapacity(Player player) {
        int cap = 0;
        for (ItemStack stack : player.getInventory().getStorageContents()) {
            if (stack == null || stack.getType() == Material.AIR) {
                cap += 64;
            } else if (isLegitShard(stack) && stack.getAmount() < 64) {
                cap += 64 - stack.getAmount();
            }
        }
        return cap;
    }

    public void removeShards(Player player, int amount) {
        ItemStack[] contents  = player.getInventory().getStorageContents();
        int         remaining = amount;
        for (int i = 0; i < contents.length && remaining > 0; i++) {
            if (!isLegitShard(contents[i])) continue;
            int inSlot = contents[i].getAmount();
            if (inSlot <= remaining) {
                remaining   -= inSlot;
                contents[i]  = null;
            } else {
                contents[i].setAmount(inSlot - remaining);
                remaining = 0;
            }
        }
        player.getInventory().setStorageContents(contents);
    }

    public void giveShards(Player player, int amount) {
        int remaining = amount;
        while (remaining > 0) {
            int       batch = Math.min(remaining, 64);
            ItemStack shard = createShard(batch);
            player.getInventory().addItem(shard)
                  .values()
                  .forEach(lo -> player.getWorld().dropItemNaturally(player.getLocation(), lo));
            remaining -= batch;
        }
    }

    private static Component noItalic(Component c) {
        return c.decoration(TextDecoration.ITALIC, false);
    }
}
