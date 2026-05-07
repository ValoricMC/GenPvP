package me.revqz.genPvP.Items;

import me.clip.placeholderapi.PlaceholderAPI;
import me.revqz.genPvP.DevilFruits.DevilFruitManager;
import me.revqz.genPvP.GenPvP;
import me.revqz.genPvP.util.ColorUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

public class LuffyArmorManager implements Listener {

    private static final GsonComponentSerializer GSON = GsonComponentSerializer.gson();
    private static final net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer LEGACY =
            net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection();

    public static final String[] PIECE_IDS = {
            "luffy_helmet",
            "luffy_chestplate",
            "luffy_leggings",
            "luffy_boots"
    };

    public static final double HAKI_CHANCE_PER_PIECE = 0.10; 
    private static final double FULL_SET_DAMAGE_MULT  = 1.20; 

    private final GenPvP             plugin;
    private final CustomItemRegistry registry;
    private final DevilFruitManager  fruitManager;

    public LuffyArmorManager(GenPvP plugin, CustomItemRegistry registry, DevilFruitManager fruitManager) {
        this.plugin       = plugin;
        this.registry     = registry;
        this.fruitManager = fruitManager;

        if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            Bukkit.getScheduler().runTaskTimer(plugin, () -> {
                for (Player p : Bukkit.getOnlinePlayers()) refreshLore(p);
            }, 20L, 20L);
        }
    }

    public int getEquippedPieceCount(Player player) {
        EntityEquipment eq = player.getEquipment();
        if (eq == null) return 0;

        ItemStack[] slots = {
                eq.getHelmet(),
                eq.getChestplate(),
                eq.getLeggings(),
                eq.getBoots()
        };

        int count = 0;
        for (int i = 0; i < slots.length; i++) {
            if (registry.hasId(slots[i], PIECE_IDS[i])) count++;
        }
        return count;
    }

    public boolean hasFullSet(Player player) {
        return getEquippedPieceCount(player) == 4;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player victim)) return;

        int pieces = getEquippedPieceCount(victim);
        if (pieces == 0) return;

        double blockChance = pieces * HAKI_CHANCE_PER_PIECE;
        if (ThreadLocalRandom.current().nextDouble() >= blockChance) return;

        event.setCancelled(true);

        victim.playSound(victim.getLocation(), Sound.ITEM_SHIELD_BLOCK, 1.0f, 0.6f);
        String hakiMsg = plugin.getConfig().getString("onepiece.messages.haki-block",
                "&#FFAF89&lHAKI &8\u00bb &7Attack blocked by spirit armor.");
        victim.sendActionBar(LEGACY.deserialize(ColorUtil.colorize(hakiMsg)));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onOutgoingDamage(EntityDamageByEntityEvent event) {
        Player attacker = resolveAttacker(event.getDamager());
        if (attacker == null) return;
        if (!hasFullSet(attacker)) return;
        if (fruitManager.getEquippedFruit(attacker.getUniqueId()) == null) return;

        event.setDamage(event.getDamage() * FULL_SET_DAMAGE_MULT);
    }

    private Player resolveAttacker(Entity damager) {
        if (damager instanceof Player p) return p;
        if (damager instanceof Projectile proj && proj.getShooter() instanceof Player p) return p;
        return null;
    }

    private void refreshLore(Player player) {
        
        ItemStack[] armor = {
                player.getInventory().getHelmet(),
                player.getInventory().getChestplate(),
                player.getInventory().getLeggings(),
                player.getInventory().getBoots()
        };

        for (int i = 0; i < PIECE_IDS.length; i++) {
            if (applyLore(player, armor[i], PIECE_IDS[i])) {
                switch (i) {
                    case 0 -> player.getInventory().setHelmet(armor[i]);
                    case 1 -> player.getInventory().setChestplate(armor[i]);
                    case 2 -> player.getInventory().setLeggings(armor[i]);
                    case 3 -> player.getInventory().setBoots(armor[i]);
                }
            }
        }

        for (int slot = 0; slot < player.getInventory().getSize(); slot++) {
            ItemStack item = player.getInventory().getItem(slot);
            if (item == null || item.getType().isAir()) continue;
            for (String pieceId : PIECE_IDS) {
                if (applyLore(player, item, pieceId)) {
                    player.getInventory().setItem(slot, item);
                    break;
                }
            }
        }
    }

    private boolean applyLore(Player player, ItemStack item, String pieceId) {
        if (!registry.hasId(item, pieceId)) return false;

        List<String> templateLines = plugin.getConfig().getStringList("onepiece.armor-lore." + pieceId);
        if (templateLines == null || templateLines.isEmpty()) return false;

        List<Component> newLore = templateLines.stream()
                .map(line -> PlaceholderAPI.setPlaceholders(player, line))
                .map(line -> LEGACY.deserialize(
                        me.revqz.genPvP.util.ColorUtil.colorize(line))
                        .decoration(TextDecoration.ITALIC, false))
                .collect(Collectors.toList());

        ItemMeta meta = item.getItemMeta();
        if (meta == null || newLore.equals(meta.lore())) return false;

        meta.lore(newLore);
        item.setItemMeta(meta);
        return true;
    }
}
