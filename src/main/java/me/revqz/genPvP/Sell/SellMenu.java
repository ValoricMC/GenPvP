package me.revqz.genPvP.Sell;

import me.revqz.genPvP.GenPvP;
import me.revqz.genPvP.util.ColorUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class SellMenu implements Listener {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();

    private static final int SLOT_CAULDRON = 0;
    private static final int SLOT_FILLER_L = 1;
    private static final int SLOT_BELL     = 2;
    private static final int SLOT_FILLER_R = 3;
    private static final int SLOT_CLOCK    = 4;

    private final GenPvP plugin;
    private final Set<UUID> openMenus = ConcurrentHashMap.newKeySet();

    public SellMenu(GenPvP plugin) {
        this.plugin = plugin;
    }

    // ── Open ──────────────────────────────────────────────────────────────────

    public void open(Player player) {
        openMenus.add(player.getUniqueId());
        player.openInventory(buildInventory());
    }

    private Inventory buildInventory() {
        FileConfiguration cfg = plugin.getConfig();
        Component title = Component.text("Shop Menu: Sell");
        Inventory inv = Bukkit.createInventory(null, InventoryType.HOPPER, title);

        // Slot 0 — Cauldron (configurable)
        inv.setItem(SLOT_CAULDRON, buildItem(Material.CAULDRON,
                cfg.getString("sell.cauldron.name", "&7Sell Shop"),
                cfg.getStringList("sell.cauldron.lore")));

        // Slot 1 — Left filler
        inv.setItem(SLOT_FILLER_L, fillerPane());

        // Slot 2 — Bell: click runs /sell all
        inv.setItem(SLOT_BELL, buildItem(Material.BELL,
                cfg.getString("sell.bell.name", "&eSell All"),
                cfg.getStringList("sell.bell.lore")));

        // Slot 3 — Right filler
        inv.setItem(SLOT_FILLER_R, fillerPane());

        // Slot 4 — Clock: close menu and run /prestige
        inv.setItem(SLOT_CLOCK, buildItem(Material.CLOCK,
                cfg.getString("sell.clock.name", "&6Prestige"),
                cfg.getStringList("sell.clock.lore")));

        return inv;
    }

    // ── Events ────────────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = false)
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (!openMenus.contains(player.getUniqueId())) return;
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = false)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (!openMenus.contains(player.getUniqueId())) return;

        event.setCancelled(true);

        // Only react to clicks in the top inventory
        if (event.getClickedInventory() == null
                || !event.getClickedInventory().equals(event.getView().getTopInventory())) return;

        int slot = event.getSlot();

        if (slot == SLOT_BELL) {
            player.closeInventory();
            player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.0f);
            Bukkit.getScheduler().runTask(plugin, () -> player.performCommand("sell all"));

        } else if (slot == SLOT_CLOCK) {
            player.closeInventory();
            player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.2f);
            Bukkit.getScheduler().runTask(plugin, () -> player.performCommand("prestige"));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        openMenus.remove(event.getPlayer().getUniqueId());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private ItemStack buildItem(Material mat, String name, List<String> loreLines) {
        ItemStack stack = new ItemStack(mat);
        ItemMeta  meta  = stack.getItemMeta();
        if (meta == null) return stack;

        meta.displayName(noItalic(LEGACY.deserialize(ColorUtil.colorize(name))));

        if (!loreLines.isEmpty()) {
            List<Component> lore = new ArrayList<>();
            for (String line : loreLines)
                lore.add(noItalic(LEGACY.deserialize(ColorUtil.colorize(line))));
            meta.lore(lore);
        }

        meta.addItemFlags(ItemFlag.HIDE_ENCHANTS, ItemFlag.HIDE_ATTRIBUTES,
                          ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
        stack.setItemMeta(meta);
        return stack;
    }

    private static ItemStack fillerPane() {
        ItemStack stack = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta  meta  = stack.getItemMeta();
        if (meta != null) {
            meta.setHideTooltip(true);
            stack.setItemMeta(meta);
        }
        return stack;
    }

    private static Component noItalic(Component c) {
        return c.decoration(TextDecoration.ITALIC, false);
    }
}
