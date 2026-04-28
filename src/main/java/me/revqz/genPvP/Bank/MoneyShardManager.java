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

/**
 * Manages the physical "Money Shard" item — a PDC-stamped Prismarine Shard
 * that represents withdrawable bank balance.
 *
 * Only shards created by this class carry the {@code genpvp:money_shard} PDC key.
 * Deposit checks this key before accepting any item, making non-legitimate
 * shards (crafted, obtained from Creative, etc.) impossible to deposit.
 */
public class MoneyShardManager {

    /** Serializer that reads raw & codes + &#RRGGBB hex directly from config strings. */
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.builder()
            .character('&')
            .hexColors()
            .build();

    private final GenPvP        plugin;
    private final NamespacedKey SHARD_KEY;

    // ── Config-loaded ─────────────────────────────────────────────────────────
    private String       shardName;
    private List<String> shardLore;
    /** Dollar value of one shard (default 1.0 → each shard = $1). */
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

    // ── Public API ────────────────────────────────────────────────────────────

    /** Dollar value represented by one shard. */
    public double getValuePerShard() { return valuePerShard; }

    /**
     * Creates a single ItemStack of up to 64 server-stamped money shards.
     * The PDC tag is what makes these legitimate — it cannot be faked by players.
     */
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

        // Stamp with PDC — this is what distinguishes legit from non-legit shards
        meta.getPersistentDataContainer().set(SHARD_KEY, PersistentDataType.BYTE, (byte) 1);
        stack.setItemMeta(meta);
        return stack;
    }

    /** Returns true only if the stack carries the server-issued PDC stamp. */
    public boolean isLegitShard(ItemStack stack) {
        if (stack == null || stack.getType() != Material.PRISMARINE_SHARD) return false;
        ItemMeta meta = stack.getItemMeta();
        return meta != null
                && meta.getPersistentDataContainer().has(SHARD_KEY, PersistentDataType.BYTE);
    }

    /** Counts all legit shards in the player's 36-slot storage inventory. */
    public int countShards(Player player) {
        int total = 0;
        for (ItemStack stack : player.getInventory().getStorageContents()) {
            if (isLegitShard(stack)) total += stack.getAmount();
        }
        return total;
    }

    /**
     * How many more shards fit in the player's inventory right now,
     * accounting for empty slots and partial legit-shard stacks.
     */
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

    /**
     * Removes exactly {@code amount} legit shards from the player's storage.
     * Caller must verify there are at least {@code amount} shards first.
     */
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

    /**
     * Gives {@code amount} shards to the player, split into stacks of 64.
     * Any overflow that does not fit is dropped at the player's feet.
     */
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

    // ── Internal ──────────────────────────────────────────────────────────────

    private static Component noItalic(Component c) {
        return c.decoration(TextDecoration.ITALIC, false);
    }
}
