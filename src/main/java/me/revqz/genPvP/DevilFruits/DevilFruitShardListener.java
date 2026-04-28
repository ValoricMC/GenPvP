package me.revqz.genPvP.DevilFruits;

import me.revqz.genPvP.GenPvP;
import me.revqz.genPvP.util.ColorUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Handles the Devil Fruit Shard drop mechanic on PvP kills.
 *
 * <h3>Behaviour</h3>
 * <ul>
 *   <li>When a player kills another player, there is a configurable chance
 *       (default 10%) to receive a PDC-stamped {@link Material#AMETHYST_SHARD}.</li>
 *   <li>A per-victim cooldown (default 3 hours) prevents farming the same
 *       player repeatedly.</li>
 *   <li>If the killer's inventory is full the shard is dropped naturally at
 *       their feet and a separate message is sent.</li>
 * </ul>
 *
 * <h3>Concurrency</h3>
 * All maps use {@link ConcurrentHashMap} defensively, even though Bukkit events
 * fire on the main thread.  {@link #onQuit} clears killer-side entries to
 * prevent unbounded memory growth.
 */
public class DevilFruitShardListener implements Listener {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.builder()
            .character('&').hexColors().build();

    private final GenPvP plugin;
    private final NamespacedKey SHARD_KEY;

    // ── Config values (hot-reloadable) ───────────────────────────────────────
    private double dropChance;
    private long   cooldownMs;
    private String itemName;
    private List<String> itemLore;

    // ── Messages (from FruitGUI/GeneralMessages.yml) ─────────────────────────
    private String msgReceived;
    private String msgDropped;
    private String msgCooldown;

    /**
     * Killer UUID → (Victim UUID → timestamp of last shard drop).
     * Entries for offline killers are removed in {@link #onQuit}.
     */
    private final Map<UUID, Map<UUID, Long>> cooldowns = new ConcurrentHashMap<>();

    // ── Init ──────────────────────────────────────────────────────────────────

    public DevilFruitShardListener(GenPvP plugin, YamlConfiguration messagesConfig) {
        this.plugin    = plugin;
        this.SHARD_KEY = new NamespacedKey(plugin, "devil_fruit_shard");
        reload(messagesConfig);
    }

    /** Re-reads config + messages.  Call after /genpvp reload. */
    public void reload(YamlConfiguration messagesConfig) {
        var cfg = plugin.getConfig();
        dropChance = cfg.getDouble("devil-fruit-shard.drop-chance", 0.10);
        cooldownMs = (long) (cfg.getDouble("devil-fruit-shard.cooldown-hours", 3.0) * 3_600_000L);
        itemName   = cfg.getString("devil-fruit-shard.item.name", "&d&lDevil Fruit Shard");
        itemLore   = cfg.getStringList("devil-fruit-shard.item.lore");
        if (itemLore.isEmpty()) {
            itemLore = List.of(
                    "&7A shard of power obtained",
                    "&7through combat.",
                    "",
                    "&8Used in the &dDevil Fruit Shop"
            );
        }

        msgReceived = messagesConfig.getString("shard-received",
                "&d&lSHARD &8» &7You obtained a &dDevil Fruit Shard &7from &f%victim%&7!");
        msgDropped  = messagesConfig.getString("shard-dropped",
                "&d&lSHARD &8» &cInventory full! &7Shard dropped at your feet.");
        msgCooldown = messagesConfig.getString("shard-cooldown-active", "");
    }

    // ── Events ────────────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        Player killer = victim.getKiller();

        // Only PvP kills — skip environment / self kills
        if (killer == null || killer.equals(victim)) return;

        UUID killerUuid = killer.getUniqueId();
        UUID victimUuid = victim.getUniqueId();

        // ── Cooldown check ───────────────────────────────────────────────────
        Map<UUID, Long> victimCooldowns = cooldowns.get(killerUuid);
        if (victimCooldowns != null) {
            Long lastDrop = victimCooldowns.get(victimUuid);
            if (lastDrop != null && System.currentTimeMillis() - lastDrop < cooldownMs) {
                // Still on cooldown — optionally notify
                if (msgCooldown != null && !msgCooldown.isEmpty()) {
                    killer.sendMessage(LEGACY.deserialize(ColorUtil.colorize(
                            msgCooldown.replace("%victim%", victim.getName()))));
                }
                return;
            }
        }

        // ── Drop chance ──────────────────────────────────────────────────────
        if (ThreadLocalRandom.current().nextDouble() >= dropChance) return;

        // ── Record cooldown ──────────────────────────────────────────────────
        cooldowns.computeIfAbsent(killerUuid, k -> new ConcurrentHashMap<>())
                 .put(victimUuid, System.currentTimeMillis());

        // ── Create the shard ─────────────────────────────────────────────────
        ItemStack shard = createShard();

        // ── Give to killer ───────────────────────────────────────────────────
        Map<Integer, ItemStack> overflow = killer.getInventory().addItem(shard);
        if (overflow.isEmpty()) {
            // Shard fitted into inventory
            sendMessage(killer, msgReceived.replace("%victim%", victim.getName()));
        } else {
            // Inventory full — drop at killer's feet
            overflow.values().forEach(leftover ->
                    killer.getWorld().dropItemNaturally(killer.getLocation(), leftover));
            sendMessage(killer, msgDropped);
        }

        killer.playSound(killer.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.8f, 1.4f);
    }

    /**
     * Cleans up cooldown entries when the killer goes offline.
     * Victim-side entries expire naturally via timestamp check.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        cooldowns.remove(event.getPlayer().getUniqueId());
    }

    /**
     * Opens the devil fruit shop when a player right-clicks with a shard.
     */
    @EventHandler
    public void onInteract(org.bukkit.event.player.PlayerInteractEvent event) {
        if (event.getAction().name().contains("RIGHT_CLICK")) {
            ItemStack item = event.getItem();
            if (isDevilShard(item)) {
                event.setCancelled(true);
                org.bukkit.Bukkit.dispatchCommand(event.getPlayer(), "shop devilfruit");
            }
        }
    }

    // ── Public API (used by ShopManager for currency checks) ──────────────────

    /** Returns the {@link NamespacedKey} used to stamp devil fruit shards. */
    public NamespacedKey getShardKey() { return SHARD_KEY; }

    /** Returns true only if the stack is a server-issued devil fruit shard. */
    public boolean isDevilShard(ItemStack stack) {
        if (stack == null || stack.getType() != Material.AMETHYST_SHARD) return false;
        ItemMeta meta = stack.getItemMeta();
        return meta != null
                && meta.getPersistentDataContainer().has(SHARD_KEY, PersistentDataType.BYTE);
    }

    /** Counts all legitimate devil fruit shards in the player's storage inventory. */
    public int countShards(Player player) {
        int total = 0;
        for (ItemStack stack : player.getInventory().getStorageContents()) {
            if (isDevilShard(stack)) total += stack.getAmount();
        }
        return total;
    }

    /**
     * Removes exactly {@code amount} legitimate devil fruit shards from inventory.
     * Caller must verify sufficient quantity first.
     */
    public void removeShards(Player player, int amount) {
        ItemStack[] contents  = player.getInventory().getStorageContents();
        int         remaining = amount;
        for (int i = 0; i < contents.length && remaining > 0; i++) {
            if (!isDevilShard(contents[i])) continue;
            int inSlot = contents[i].getAmount();
            if (inSlot <= remaining) {
                remaining  -= inSlot;
                contents[i] = null;
            } else {
                contents[i].setAmount(inSlot - remaining);
                remaining = 0;
            }
        }
        player.getInventory().setStorageContents(contents);
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    /** Creates a single PDC-stamped devil fruit shard item. */
    private ItemStack createShard() {
        ItemStack stack = new ItemStack(Material.AMETHYST_SHARD, 1);
        ItemMeta  meta  = stack.getItemMeta();
        if (meta == null) return stack;

        meta.displayName(noItalic(LEGACY.deserialize(itemName)));

        List<Component> lore = new ArrayList<>();
        for (String line : itemLore) {
            lore.add(line.isEmpty()
                    ? Component.empty()
                    : noItalic(LEGACY.deserialize(ColorUtil.colorize(line))));
        }
        meta.lore(lore);

        meta.getPersistentDataContainer().set(SHARD_KEY, PersistentDataType.BYTE, (byte) 1);
        stack.setItemMeta(meta);
        return stack;
    }

    private static void sendMessage(Player player, String raw) {
        if (raw == null || raw.isEmpty()) return;
        player.sendMessage(LEGACY.deserialize(ColorUtil.colorize(raw)));
    }

    private static Component noItalic(Component c) {
        return c.decoration(TextDecoration.ITALIC, false);
    }
}
