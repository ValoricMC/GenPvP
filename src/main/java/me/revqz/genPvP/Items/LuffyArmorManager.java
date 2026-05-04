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

/**
 * Luffy Armor — Haki & Full-Set bonuses:
 *
 *  ── Haki (per piece, stacks) ─────────────────────────────────────────────
 *  Each piece gives a 10% independent chance to block ANY incoming damage.
 *   1 piece → 10%    2 pieces → 20%    3 pieces → 30%    4 pieces → 40%
 *  When triggered: damage is cancelled, shield-block sound + action-bar shown.
 *
 *  ── Full Set (all 4 pieces) ──────────────────────────────────────────────
 *  While wearing all 4 pieces AND a devil fruit is equipped:
 *   All outgoing damage (melee + projectile/fruit abilities) × 1.20 (+20%)
 *
 * Piece custom-item IDs (registered via /onepiece set from_hand <id>):
 *   luffy_helmet, luffy_chestplate, luffy_leggings, luffy_boots
 */
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

    public static final double HAKI_CHANCE_PER_PIECE = 0.10; // 10% per piece
    private static final double FULL_SET_DAMAGE_MULT  = 1.20; // +20% outgoing damage

    private final GenPvP             plugin;
    private final CustomItemRegistry registry;
    private final DevilFruitManager  fruitManager;

    public LuffyArmorManager(GenPvP plugin, CustomItemRegistry registry, DevilFruitManager fruitManager) {
        this.plugin       = plugin;
        this.registry     = registry;
        this.fruitManager = fruitManager;

        // Refresh placeholder lore on all worn Luffy armor pieces every second.
        // Only runs when PlaceholderAPI is present.
        if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            Bukkit.getScheduler().runTaskTimer(plugin, () -> {
                for (Player p : Bukkit.getOnlinePlayers()) refreshLore(p);
            }, 20L, 20L);
        }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /** Returns how many Luffy armor pieces the player is currently wearing (0–4). */
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

    /** Returns true only when all 4 pieces are worn. */
    public boolean hasFullSet(Player player) {
        return getEquippedPieceCount(player) == 4;
    }

    // ── Haki block ────────────────────────────────────────────────────────────

    /**
     * 10% chance per equipped Luffy armor piece to block any incoming damage.
     * Runs at HIGH so it fires after most damage-modification listeners but
     * before LOW/MONITOR listeners that just observe the final value.
     */
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

    // ── Full-set: +20% outgoing fruit damage ─────────────────────────────────

    /**
     * Applies the full-set +20% damage multiplier to the attacker's outgoing
     * damage when they have all 4 pieces equipped and a devil fruit active.
     * Covers direct melee and projectile/ability damage (Mera Mera fireballs,
     * Zushi Zushi rocks, etc.) via the shooter chain.
     */
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

    // ── Placeholder lore refresh ───────────────────────────────────────────────

    /**
     * Scans both armor slots and the main inventory for any Luffy armor pieces,
     * resolves PAPI placeholders against the stored template lore, and writes the
     * result back only when the lore actually changed.
     *
     * Scanning the full inventory is required so that pieces the player has just
     * taken off (now sitting in a hotbar/storage slot) reflect the correct current
     * values rather than staying frozen at whatever was last resolved while worn.
     */
    private void refreshLore(Player player) {
        // ── Armor slots ───────────────────────────────────────────────────────
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

        // ── Main inventory (slots 0–35) ───────────────────────────────────────
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

    /**
     * Reads the lore template from config.yml (onepiece.armor-lore.{pieceId}),
     * resolves PAPI placeholders, colorizes, and writes to the item.
     * Config-driven templates bypass the Paper 1.21+ Component serialization
     * issue where meta.lore() returns empty Components.
     *
     * @return true if the item's lore was actually updated (caller must write back)
     */
    private boolean applyLore(Player player, ItemStack item, String pieceId) {
        if (!registry.hasId(item, pieceId)) return false;

        // Read template from config — plain strings with & color codes + PAPI placeholders
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
