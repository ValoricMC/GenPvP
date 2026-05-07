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

public class DevilFruitShardListener implements Listener {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.builder()
            .character('&').hexColors().build();

    private final GenPvP plugin;
    private final NamespacedKey SHARD_KEY;

    private double dropChance;
    private long   cooldownMs;
    private String itemName;
    private List<String> itemLore;

    private String msgReceived;
    private String msgDropped;
    private String msgCooldown;

    private final Map<UUID, Map<UUID, Long>> cooldowns = new ConcurrentHashMap<>();

    public DevilFruitShardListener(GenPvP plugin, YamlConfiguration messagesConfig) {
        this.plugin    = plugin;
        this.SHARD_KEY = new NamespacedKey(plugin, "devil_fruit_shard");
        reload(messagesConfig);
    }

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

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        Player killer = victim.getKiller();

        if (killer == null || killer.equals(victim)) return;

        UUID killerUuid = killer.getUniqueId();
        UUID victimUuid = victim.getUniqueId();

        Map<UUID, Long> victimCooldowns = cooldowns.get(killerUuid);
        if (victimCooldowns != null) {
            Long lastDrop = victimCooldowns.get(victimUuid);
            if (lastDrop != null && System.currentTimeMillis() - lastDrop < cooldownMs) {
                
                if (msgCooldown != null && !msgCooldown.isEmpty()) {
                    killer.sendMessage(LEGACY.deserialize(ColorUtil.colorize(
                            msgCooldown.replace("%victim%", victim.getName()))));
                }
                return;
            }
        }

        if (ThreadLocalRandom.current().nextDouble() >= dropChance) return;

        cooldowns.computeIfAbsent(killerUuid, k -> new ConcurrentHashMap<>())
                 .put(victimUuid, System.currentTimeMillis());

        ItemStack shard = createShard();

        Map<Integer, ItemStack> overflow = killer.getInventory().addItem(shard);
        if (overflow.isEmpty()) {
            
            sendMessage(killer, msgReceived.replace("%victim%", victim.getName()));
        } else {
            
            overflow.values().forEach(leftover ->
                    killer.getWorld().dropItemNaturally(killer.getLocation(), leftover));
            sendMessage(killer, msgDropped);
        }

        killer.playSound(killer.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.8f, 1.4f);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        cooldowns.remove(event.getPlayer().getUniqueId());
    }

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

    public NamespacedKey getShardKey() { return SHARD_KEY; }

    public boolean isDevilShard(ItemStack stack) {
        if (stack == null || stack.getType() != Material.AMETHYST_SHARD) return false;
        ItemMeta meta = stack.getItemMeta();
        return meta != null
                && meta.getPersistentDataContainer().has(SHARD_KEY, PersistentDataType.BYTE);
    }

    public int countShards(Player player) {
        int total = 0;
        for (ItemStack stack : player.getInventory().getStorageContents()) {
            if (isDevilShard(stack)) total += stack.getAmount();
        }
        return total;
    }

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
