package me.revqz.genPvP.Shop;

import me.revqz.genPvP.Bank.BankManager;
import me.revqz.genPvP.DevilFruits.DevilFruitShardListener;
import me.revqz.genPvP.GenPvP;
import me.revqz.genPvP.Prestige.PrestigeManager;
import me.revqz.genPvP.util.ColorUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.potion.PotionType;

import java.io.File;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

public class ShopManager implements Listener {

    /** Bottom-row control slots. */
    private static final int SLOT_DECREASE = 52;
    private static final int SLOT_INCREASE = 53;
    /** Cooldown (ms) for the +/- quantity spinner buttons. */
    private static final long CLICK_COOLDOWN_MS  = 250;
    /** Cooldown (ms) between buy/sell actions — prevents spam-click double-purchase. */
    private static final long ACTION_COOLDOWN_MS  = 300;
    /** Max clicks allowed within RATE_WINDOW_MS before the player is rate-limited. */
    private static final int  RATE_LIMIT_MAX      = 3;
    private static final long RATE_WINDOW_MS      = 1000;
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();

    private final GenPvP plugin;
    private final BankManager bankManager;
    private final PrestigeManager prestigeManager;
    private ShopMessages messages;
    /** PDC key used to stamp a unique ID onto shop-issued items (e.g. pickaxe tiers). */
    private final NamespacedKey ITEM_ID_KEY;
    /** Set after construction — provides physical devil-shard counting/removal. */
    private volatile DevilFruitShardListener devilShardListener;

    /** The menu ID to open when /shop is run with no args. */
    private String defaultMenuId = "equipment";

    /** All configured shop menus, insertion-ordered. */
    private final Map<String, ShopMenu> menus = new LinkedHashMap<>();

    /**
     * Parsed shared-nav entries — one per nav slot.
     * Each entry holds both the "active" and "inactive" presentation.
     */
    private final List<NavEntry> sharedNav = new ArrayList<>();

    // ── Per-player session state ───────────────────────────────────────────────
    /** UUID → current menu ID */
    private final Map<UUID, String>  playerMenu     = new ConcurrentHashMap<>();
    /** UUID → current quantity (shared across the whole open session) */
    private final Map<UUID, Integer> playerQty      = new ConcurrentHashMap<>();
    /** UUID → last +/- spinner click millis */
    private final Map<UUID, Long>    clickCooldown  = new ConcurrentHashMap<>();
    /** UUID → last buy/sell action millis — prevents double-purchase on button spam / lag */
    private final Map<UUID, Long>        actionCooldown = new ConcurrentHashMap<>();
    /** UUID → sliding window of recent click timestamps (for rate-limiting). */
    private final Map<UUID, Deque<Long>> clickHistory   = new ConcurrentHashMap<>();
    /** Players who currently have a shop GUI open */
    private final Set<UUID>          openShops      = ConcurrentHashMap.newKeySet();
    /** Players in the middle of switching menus — suppresses close-event cleanup */
    private final Set<UUID>          navigating     = ConcurrentHashMap.newKeySet();

    public ShopManager(GenPvP plugin, BankManager bankManager, PrestigeManager prestigeManager) {
        this.plugin           = plugin;
        this.bankManager      = bankManager;
        this.prestigeManager  = prestigeManager;
        this.messages         = new ShopMessages(plugin.getConfig());
        this.ITEM_ID_KEY      = new NamespacedKey(plugin, "item_id");
        loadShops();
    }

    /** Injected after construction to avoid circular dependency. */
    public void setDevilShardListener(DevilFruitShardListener listener) {
        this.devilShardListener = listener;
    }

    // ── Config loading ────────────────────────────────────────────────────────

    public void loadShops() {
        menus.clear();
        sharedNav.clear();
        messages = new ShopMessages(plugin.getConfig());

        File file = new File(plugin.getDataFolder(), "shops.yml");
        if (!file.exists()) plugin.saveResource("shops.yml", false);

        FileConfiguration config = YamlConfiguration.loadConfiguration(file);

        // Default menu
        defaultMenuId = config.getString("default-menu", "equipment");

        // Shared navigation bar
        List<Map<?, ?>> navRaw = config.getMapList("shared-nav");
        for (Map<?, ?> map : navRaw) {
            try {
                int      slot       = num(map, "slot", 0);
                Material mat        = Material.matchMaterial(str(map, "material", "STONE"));
                if (mat == null) continue;
                String   name       = str(map, "name", "");
                String   activeName = str(map, "active-name", name);
                List<String> lore       = strList(map, "lore");
                List<String> activeLore = strList(map, "active-lore");
                if (activeLore.isEmpty()) activeLore = lore;
                String   targetMenu = str(map, "menu", "");
                sharedNav.add(new NavEntry(slot, mat, name, activeName, lore, activeLore, targetMenu));
            } catch (Exception ex) {
                plugin.getLogger().warning("[ShopManager] Skipping bad shared-nav entry: " + ex.getMessage());
            }
        }

        // Shop menus
        ConfigurationSection shopsSection = config.getConfigurationSection("shops");
        if (shopsSection == null) {
            plugin.getLogger().warning("[ShopManager] No 'shops' section found in shops.yml");
            return;
        }

        for (String menuId : shopsSection.getKeys(false)) {
            ConfigurationSection sec = shopsSection.getConfigurationSection(menuId);
            if (sec == null) continue;
            String displayName        = sec.getString("name", menuId);
            List<ShopItem>   items      = parseItems(sec.getMapList("items"));
            List<ShopButton> buttons    = parseButtons(sec.getMapList("buttons"));
            List<SlotButton> slotButtons = parseSlotButtons(sec.getMapList("slot-buttons"));
            menus.put(menuId, new ShopMenu(menuId, displayName, items, buttons, slotButtons));
        }
        plugin.getLogger().info("[ShopManager] Loaded " + menus.size() + " shop menu(s) with "
                + sharedNav.size() + " shared-nav button(s). Default menu: " + defaultMenuId);
    }

    private List<ShopItem> parseItems(List<Map<?, ?>> raw) {
        List<ShopItem> out = new ArrayList<>();
        if (raw == null) return out;
        for (Map<?, ?> map : raw) {
            try {
                int      slot          = num(map, "slot", 0);
                Material mat           = Material.matchMaterial(str(map, "material", "STONE"));
                if (mat == null) continue;
                String   name          = str(map, "name", "&fItem");
                double   price         = dbl(map, "price", 0);
                String   currency      = str(map, "currency-type", "money").toLowerCase();
                boolean  allowMultiple = bool(map, "allow-multiple", true);
                int      reqPrestige   = num(map, "requires-prestige", 0);
                int      amount        = Math.max(1, Math.min(64, num(map, "amount", 1)));
                String   command       = map.containsKey("command") ? str(map, "command", null) : null;
                List<String> lore      = strList(map, "lore");

                List<PotionEffect> potions = new ArrayList<>();
                for (String p : strList(map, "potions")) {
                    String[] parts = p.split(":", 3);
                    if (parts.length < 2) continue;
                    PotionEffectType effectType = PotionEffectType.getByKey(
                            NamespacedKey.minecraft(parts[0].trim().toLowerCase()));
                    if (effectType == null) {
                        plugin.getLogger().warning("[ShopManager] Unknown potion effect: " + parts[0]);
                        continue;
                    }
                    int level           = Math.max(1, Integer.parseInt(parts[1].trim())); // 1-indexed
                    int durationSeconds = parts.length == 3 ? Math.max(1, Integer.parseInt(parts[2].trim())) : 180;
                    potions.add(new PotionEffect(effectType, durationSeconds * 20, level - 1, false, true, true));
                }

                Map<Enchantment, Integer> enchants = new LinkedHashMap<>();
                for (String e : strList(map, "enchants")) {
                    String[] parts = e.split(":", 2);
                    if (parts.length != 2) continue;
                    Enchantment ench = Registry.ENCHANTMENT.get(
                            NamespacedKey.minecraft(parts[0].trim().toLowerCase()));
                    if (ench == null) continue;
                    enchants.put(ench, Integer.parseInt(parts[1].trim()));
                }

                // ── Optional item-id (PDC tag stamped on purchase) ────────────
                String itemId = str(map, "item-id", "");

                // ── After-purchase display name and lore ──────────────────────
                String afterBoughtTitle = str(map, "after-store-bought-title", null);
                List<String> afterBoughtLore = strList(map, "after-store-bought-lore");

                // ── Unbreakable flag ───────────────────────────────────────
                boolean unbreakable = bool(map, "unbreakable", false);

                // ── Optional required-item block ──────────────────────────────
                RequiredItem reqItem = null;
                Object rawReqItem = map.get("requires-item");
                if (rawReqItem instanceof Map<?, ?> reqMap) {
                    Material reqMat = Material.matchMaterial(str(reqMap, "material", "AIR"));
                    if (reqMat != null && reqMat != Material.AIR) {
                        String  reqItemId    = str(reqMap, "item-id", "");
                        String  nameContains = str(reqMap, "name-contains", "");
                        boolean consume      = bool(reqMap, "consume", true);
                        reqItem = new RequiredItem(reqMat, reqItemId, nameContains, consume);
                    }
                }

                out.add(new ShopItem(slot, mat, name, price, currency, allowMultiple, lore, enchants, reqPrestige, amount, command, potions, reqItem, itemId, afterBoughtTitle, afterBoughtLore, unbreakable));
            } catch (Exception ex) {
                plugin.getLogger().warning("[ShopManager] Skipping bad item entry: " + ex.getMessage());
            }
        }
        return out;
    }

    private List<ShopButton> parseButtons(List<Map<?, ?>> raw) {
        List<ShopButton> out = new ArrayList<>();
        if (raw == null) return out;
        for (Map<?, ?> map : raw) {
            try {
                int      slot     = num(map, "slot", 0);
                Material mat      = Material.matchMaterial(str(map, "material", "STONE"));
                if (mat == null) continue;
                String   name     = str(map, "name", "");
                List<String> lore = strList(map, "lore");
                String   target   = str(map, "menu", "");
                out.add(new ShopButton(slot, mat, name, lore, target));
            } catch (Exception ex) {
                plugin.getLogger().warning("[ShopManager] Skipping bad button entry: " + ex.getMessage());
            }
        }
        return out;
    }

    private List<SlotButton> parseSlotButtons(List<Map<?, ?>> raw) {
        List<SlotButton> out = new ArrayList<>();
        if (raw == null) return out;
        for (Map<?, ?> map : raw) {
            try {
                int slot          = num(map, "slot", 0);
                String name       = str(map, "name", "");
                List<String> lore = strList(map, "lore");
                out.add(new SlotButton(slot, name, lore));
            } catch (Exception ex) {
                plugin.getLogger().warning("[ShopManager] Skipping bad slot-button entry: " + ex.getMessage());
            }
        }
        return out;
    }

    // ── Open / navigate ───────────────────────────────────────────────────────

    /** Opens the default menu configured in shops.yml (falls back to first menu). */
    public void openDefault(Player player) {
        if (menus.isEmpty()) {
            messages.sendNoMenus(player);
            return;
        }
        String target = menus.containsKey(defaultMenuId) ? defaultMenuId : menus.keySet().iterator().next();
        openMenu(player, target);
    }

    /** Opens the named menu. Resets quantity to 1. */
    public void openMenu(Player player, String menuId) {
        ShopMenu menu = menus.get(menuId);
        if (menu == null) {
            messages.sendUnknownShop(player);
            return;
        }
        UUID uuid = player.getUniqueId();
        playerMenu.put(uuid, menuId);
        playerQty.put(uuid, 1);
        openShops.add(uuid);
        player.openInventory(buildInventory(player, menu));
    }

    // ── Inventory building ────────────────────────────────────────────────────

    private Inventory buildInventory(Player player, ShopMenu menu) {
        Component title = noItalic(LEGACY.deserialize(ColorUtil.colorize("&8Shop Menu: " + menu.displayName())));
        Inventory inv   = Bukkit.createInventory(null, 54, title);

        int qty = playerQty.getOrDefault(player.getUniqueId(), 1);

        // ── Shared-nav buttons ───────────────────────────────────────────────
        for (NavEntry nav : sharedNav) {
            if (nav.slot >= 0 && nav.slot <= 53)
                inv.setItem(nav.slot, buildNavItem(nav, menu.id()));
        }

        // ── Shop items ────────────────────────────────────────────────────────
        for (ShopItem item : menu.items()) {
            if (item.slot() >= 0 && item.slot() <= 53)
                inv.setItem(item.slot(), buildShopItem(item, qty, player));
        }

        // ── Menu-specific nav buttons ─────────────────────────────────────────
        for (ShopButton btn : menu.buttons()) {
            if (btn.slot() >= 0 && btn.slot() <= 53)
                inv.setItem(btn.slot(), buildButtonItem(btn));
        }

        // ── Decorative stone-button slots ─────────────────────────────────────
        for (SlotButton sb : menu.slotButtons()) {
            if (sb.slot() >= 0 && sb.slot() <= 53)
                inv.setItem(sb.slot(), buildSlotButtonItem(sb));
        }

        // ── Controls ──────────────────────────────────────────────────────────
        inv.setItem(SLOT_DECREASE, controlItem(Material.GRAY_DYE, "&c- Decrease Quantity", qty));
        inv.setItem(SLOT_INCREASE, controlItem(Material.LIME_DYE, "&a+ Increase Quantity", qty));

        return inv;
    }

    private ItemStack buildShopItem(ShopItem item, int qty, Player player) {
        int    playerPrestige = prestigeManager.getPrestige(player.getUniqueId());
        int    effectiveQty   = item.allowMultiple() ? qty : 1;
        int    totalItems     = effectiveQty * item.amount();
        double totalPrice     = item.basePrice() * effectiveQty;
        boolean locked        = item.requiresPrestige() > 0 && playerPrestige < item.requiresPrestige();
        boolean missingReqItem = item.requiresItem() != null
                && findRequiredItem(player, item.requiresItem()) == null;

        int visualQty = Math.min(totalItems, item.material().getMaxStackSize());
        ItemStack stack = new ItemStack(item.material(), Math.max(1, visualQty));
        ItemMeta  meta  = stack.getItemMeta();
        if (meta == null) return stack;

        meta.displayName(noItalic(LEGACY.deserialize(ColorUtil.colorize(item.name()))));

        List<Component> loreComp = new ArrayList<>();
        for (String line : item.lore()) {
            String resolved = line
                    .replace("%price%",      BankManager.formatBalance(totalPrice))
                    .replace("%base_price%", BankManager.formatBalance(item.basePrice()))
                    .replace("%quantity%",   String.valueOf(totalItems))
                    .replace("%amount%",     String.valueOf(totalItems))
                    .replace("%currency%",   item.currencyType());

            // If locked behind prestige, replace the "Click to purchase" line
            if (locked && resolved.contains("Click to purchase")) {
                resolved = "&cRequires Prestige level " + item.requiresPrestige() + ".";
            }
            loreComp.add(noItalic(LEGACY.deserialize(ColorUtil.colorize(resolved))));
        }
        meta.lore(loreComp);

        item.enchants().forEach((ench, lvl) -> meta.addEnchant(ench, lvl, true));

        if (!item.potions().isEmpty() && meta instanceof PotionMeta potionMeta) {
            potionMeta.setBasePotionType(resolveBaseType(item.potions()));
            for (PotionEffect effect : item.potions()) {
                potionMeta.addCustomEffect(effect, true);
            }
        }

        meta.addItemFlags(ItemFlag.HIDE_ENCHANTS, ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
        stack.setItemMeta(meta);
        return stack;
    }

    /** Builds a shared-nav icon, highlighting it green if it matches the currently open menu. */
    private ItemStack buildNavItem(NavEntry nav, String currentMenuId) {
        boolean active = nav.targetMenu.equals(currentMenuId);
        ItemStack stack = new ItemStack(nav.material);
        ItemMeta  meta  = stack.getItemMeta();
        if (meta == null) return stack;

        String resolvedName = ColorUtil.colorize(active ? nav.activeName : nav.name);
        String stripped     = stripColors(resolvedName).trim();

        if (stripped.isEmpty()) {
            // Pure filler pane — hide the entire tooltip so no box appears at all
            meta.setHideTooltip(true);
        } else {
            meta.displayName(noItalic(LEGACY.deserialize(resolvedName)));
            List<Component> loreComp = new ArrayList<>();
            for (String line : active ? nav.activeLore : nav.lore)
                loreComp.add(noItalic(LEGACY.deserialize(ColorUtil.colorize(line))));
            meta.lore(loreComp);
        }

        meta.addItemFlags(ItemFlag.HIDE_ENCHANTS, ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
        stack.setItemMeta(meta);
        return stack;
    }

    private ItemStack buildButtonItem(ShopButton btn) {
        ItemStack stack = new ItemStack(btn.material());
        ItemMeta  meta  = stack.getItemMeta();
        if (meta == null) return stack;

        meta.displayName(noItalic(LEGACY.deserialize(ColorUtil.colorize(btn.name()))));
        List<Component> loreComp = new ArrayList<>();
        for (String line : btn.lore())
            loreComp.add(noItalic(LEGACY.deserialize(ColorUtil.colorize(line))));
        meta.lore(loreComp);
        meta.addItemFlags(ItemFlag.HIDE_ENCHANTS, ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
        stack.setItemMeta(meta);
        return stack;
    }

    /** Renders a decorative slot-button (always STONE_BUTTON, never clickable). */
    private ItemStack buildSlotButtonItem(SlotButton sb) {
        ItemStack stack = new ItemStack(Material.STONE_BUTTON);
        ItemMeta  meta  = stack.getItemMeta();
        if (meta == null) return stack;

        String displayName = sb.name().isEmpty() ? "&8[Button]" : sb.name();
        meta.displayName(noItalic(LEGACY.deserialize(ColorUtil.colorize(displayName))));
        List<Component> loreComp = new ArrayList<>();
        for (String line : sb.lore())
            loreComp.add(noItalic(LEGACY.deserialize(ColorUtil.colorize(line))));
        meta.lore(loreComp);
        meta.addItemFlags(ItemFlag.HIDE_ENCHANTS, ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
        stack.setItemMeta(meta);
        return stack;
    }

    private ItemStack controlItem(Material mat, String name, int qty) {
        ItemStack stack = new ItemStack(mat);
        ItemMeta  meta  = stack.getItemMeta();
        if (meta == null) return stack;
        meta.displayName(noItalic(LEGACY.deserialize(ColorUtil.colorize(name))));
        meta.lore(List.of(noItalic(LEGACY.deserialize(ColorUtil.colorize("&7Quantity: &e" + qty)))));
        meta.addItemFlags(ItemFlag.HIDE_ENCHANTS, ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
        stack.setItemMeta(meta);
        return stack;
    }

    // ── Refresh helper ────────────────────────────────────────────────────────

    /** Updates only the shop items and the two control slots in the open inventory. */
    private void refreshItems(Player player, ShopMenu menu) {
        Inventory inv = player.getOpenInventory().getTopInventory();
        int qty = playerQty.getOrDefault(player.getUniqueId(), 1);

        for (ShopItem item : menu.items()) {
            if (item.slot() >= 0 && item.slot() <= 53)
                inv.setItem(item.slot(), buildShopItem(item, qty, player));
        }
        inv.setItem(SLOT_DECREASE, controlItem(Material.GRAY_DYE, "&c- Decrease Quantity", qty));
        inv.setItem(SLOT_INCREASE, controlItem(Material.LIME_DYE, "&a+ Increase Quantity", qty));
    }

    // ── Event handlers ────────────────────────────────────────────────────────

    /**
     * Blocks ALL inventory drag interactions while a shop is open.
     * Without this, players (especially on Geyser/Bedrock) can drag items
     * from their hotbar into shop slots, creating ghost items.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = false)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (!openShops.contains(player.getUniqueId())) return;
        // Cancel if ANY dragged slot touches the top inventory
        int topSize = event.getView().getTopInventory().getSize();
        for (int rawSlot : event.getRawSlots()) {
            if (rawSlot < topSize) {
                event.setCancelled(true);
                // Force resync for Geyser/Bedrock clients
                Bukkit.getScheduler().runTask(plugin, player::updateInventory);
                return;
            }
        }
    }

    /**
     * Main click handler. Runs at HIGH priority so it wins over other plugins.
     * Explicitly cleans up every dangerous click type so Geyser/Bedrock clients
     * cannot smuggle items in or out via double-click collect, number-key swap, etc.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = false)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        UUID uuid = player.getUniqueId();
        if (!openShops.contains(uuid)) return;

        // Always cancel first — nothing should ever move in/out of the shop GUI
        event.setCancelled(true);

        // ── Rate limiter: max RATE_LIMIT_MAX clicks per RATE_WINDOW_MS ────────
        // Uses a sliding window — purges old timestamps, then checks the count.
        if (!rateLimitOk(uuid)) {
            messages.sendSlowDown(player);
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.7f, 1.0f);
            Bukkit.getScheduler().runTask(plugin, player::updateInventory);
            return;
        }

        // Dangerous click types: double-click (collects items into cursor),
        // number-key hotbar swap, drop, middle-click (creative pick), off-hand swap.
        // All are already cancelled, but we also resync the client inventory so
        // Geyser / Bedrock players don't see a ghost item stuck on their cursor.
        ClickType click = event.getClick();
        if (click == ClickType.DOUBLE_CLICK || click == ClickType.MIDDLE
                || click == ClickType.NUMBER_KEY
                || click == ClickType.DROP   || click == ClickType.CONTROL_DROP
                || click == ClickType.CREATIVE
                || click == ClickType.SWAP_OFFHAND
                || click == ClickType.UNKNOWN) {
            // Schedule resync on next tick (needed for Geyser cursor desync)
            Bukkit.getScheduler().runTask(plugin, player::updateInventory);
            return;
        }

        // Clicks in the player's own inventory — cancelled, but resync for Geyser
        if (event.getClickedInventory() == null
                || !event.getClickedInventory().equals(event.getView().getTopInventory())) {
            Bukkit.getScheduler().runTask(plugin, player::updateInventory);
            return;
        }

        int slot = event.getSlot();
        ShopMenu menu = getActiveMenu(uuid);
        if (menu == null) return;

        // Quantity controls
        if (slot == SLOT_INCREASE) { handleIncrease(player, menu); return; }
        if (slot == SLOT_DECREASE) { handleDecrease(player, menu); return; }

        // Shop item click — Left/Shift-Left = Buy only
        for (ShopItem item : menu.items()) {
            if (item.slot() == slot) {
                if (click == ClickType.LEFT || click == ClickType.SHIFT_LEFT) {
                    handlePurchase(player, item);
                }
                // Right-click and all other types are silently ignored
                return;
            }
        }

        // Shared-nav click
        for (NavEntry nav : sharedNav) {
            if (nav.slot == slot && !nav.targetMenu.isEmpty() && !nav.targetMenu.equals(menu.id())) {
                navigateTo(player, nav.targetMenu);
                return;
            }
        }

        // Menu-specific button click
        for (ShopButton btn : menu.buttons()) {
            if (btn.slot() == slot && !btn.targetMenu().isEmpty()) {
                navigateTo(player, btn.targetMenu());
                return;
            }
        }
        // Slot buttons and fillers — click already cancelled, nothing to do
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        UUID uuid = player.getUniqueId();
        if (navigating.contains(uuid)) return; // switching menus — keep state

        // Clear any ghost item sitting on the cursor (Geyser/Bedrock desync protection)
        ItemStack cursor = player.getItemOnCursor();
        if (cursor != null && cursor.getType() != Material.AIR) {
            player.setItemOnCursor(new ItemStack(Material.AIR));
            player.getWorld().dropItemNaturally(player.getLocation(), cursor);
        }

        clearPlayerState(uuid);
    }

    /** Cleans up all session state for a player (quit, close, or error path). */
    private void clearPlayerState(UUID uuid) {
        openShops.remove(uuid);
        playerMenu.remove(uuid);
        playerQty.remove(uuid);
        clickCooldown.remove(uuid);
        actionCooldown.remove(uuid);
        clickHistory.remove(uuid);
        navigating.remove(uuid);
    }

    /** Handles server-side disconnects — prevents map leaks when players quit mid-session. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        clearPlayerState(event.getPlayer().getUniqueId());
    }

    // ── Click actions ─────────────────────────────────────────────────────────

    private void handleIncrease(Player player, ShopMenu menu) {
        UUID uuid = player.getUniqueId();
        if (!cooldownOk(uuid)) return;

        boolean anyAllowMultiple = menu.items().stream().anyMatch(ShopItem::allowMultiple);
        if (!anyAllowMultiple) return;

        playerQty.merge(uuid, 1, Integer::sum);
        actionCooldown.put(uuid, System.currentTimeMillis()); // Enforce cooldown before buying
        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.3f, 1.5f);
        refreshItems(player, menu);
    }

    private void handleDecrease(Player player, ShopMenu menu) {
        UUID uuid = player.getUniqueId();
        if (!cooldownOk(uuid)) return;

        int current = playerQty.getOrDefault(uuid, 1);
        if (current <= 1) return; // floor at 1

        playerQty.put(uuid, current - 1);
        actionCooldown.put(uuid, System.currentTimeMillis()); // Enforce cooldown before buying
        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.3f, 1.5f);
        refreshItems(player, menu);
    }

    private void navigateTo(Player player, String menuId) {
        UUID uuid = player.getUniqueId();
        ShopMenu target = menus.get(menuId);
        if (target == null) {
            messages.sendUnknownShop(player);
            return;
        }
        navigating.add(uuid);
        playerMenu.put(uuid, menuId);
        playerQty.put(uuid, 1);
        player.playSound(player.getLocation(), Sound.ITEM_BOOK_PAGE_TURN, 0.5f, 1.2f);
        // Must open on the next tick — can't open inventory inside InventoryClickEvent.
        // IMPORTANT: navigating.remove() must come AFTER player.openInventory() — opening
        // the new inventory fires InventoryCloseEvent for the old one synchronously inside
        // openInventory(). If we remove from 'navigating' first, the close handler runs
        // before the flag is checked and wipes playerMenu/openShops, leaving the player stuck.
        Bukkit.getScheduler().runTask(plugin, () -> {
            openShops.add(uuid);
            player.openInventory(buildInventory(player, target)); // close event fires HERE
            navigating.remove(uuid); // safe to remove only after the close event has fired
        });
    }

    private void handlePurchase(Player player, ShopItem item) {
        UUID uuid = player.getUniqueId();

        // Per-action cooldown: prevents double-purchase on jitter / Bedrock tap spam
        if (!actionCooldownOk(uuid)) return;

        // ── Prestige gate ─────────────────────────────────────────────────────
        if (item.requiresPrestige() > 0 && prestigeManager.getPrestige(uuid) < item.requiresPrestige()) {
            messages.sendPrestigeRequired(player, item.requiresPrestige());
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.6f, 0.9f);
            return;
        }

        // ── Required item gate ────────────────────────────────────────────────
        if (item.requiresItem() != null && findRequiredItem(player, item.requiresItem()) == null) {
            messages.sendRequiresItem(player, item.requiresItem().label());
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.6f, 0.9f);
            return;
        }

        int    qty        = item.allowMultiple() ? playerQty.getOrDefault(uuid, 1) : 1;
        int    totalItems = qty * item.amount();
        double totalPrice = item.basePrice() * qty;

        if (bankManager.isDisabled(uuid)) {
            messages.sendBankDisabled(player);
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.6f, 0.9f);
            return;
        }

        if (!bankManager.isLoaded(uuid)) {
            messages.sendBankLoading(player);
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.6f, 0.9f);
            return;
        }

        String currency = item.currencyType();

        // ── Step 1: deduct money BEFORE giving any items ──────────────────────
        boolean deducted = switch (currency) {
            case "devil-shards" -> {
                if (devilShardListener == null) yield false;
                int have = devilShardListener.countShards(player);
                if (have < (int) totalPrice) yield false;
                devilShardListener.removeShards(player, (int) totalPrice);
                yield true;
            }
            case "shards" -> bankManager.removeShards(uuid,  player.getName(), totalPrice);
            case "gold"   -> bankManager.removeGold(uuid,    player.getName(), totalPrice);
            default       -> bankManager.removeBalance(uuid, player.getName(), totalPrice);
        };

        if (!deducted) {
            double balance = switch (currency) {
                case "devil-shards" -> devilShardListener != null ? devilShardListener.countShards(player) : 0;
                case "shards" -> bankManager.getShards(uuid);
                case "gold"   -> bankManager.getGold(uuid);
                default      -> bankManager.getBalance(uuid);
            };
            messages.sendInsufficientFunds(player, currency,
                    BankManager.formatBalance(totalPrice),
                    BankManager.formatBalance(balance));
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.6f, 0.9f);
            return;
        }

        // ── Step 2: consume required predecessor item (if configured) ─────────
        if (item.requiresItem() != null && item.requiresItem().consume()) {
            consumeRequiredItem(player, item.requiresItem());
        }

        // ── Step 3: give reward — money already taken ─────────────────────────
        if (item.command() != null && !item.command().isBlank()) {
            // Command mode: run the console command once per qty unit,
            // replacing %player_name% / %player% and %amount% in the command string.
            String baseCmd = item.command()
                    .replace("%player_name%", player.getName())
                    .replace("%player%",      player.getName())
                    .replace("%amount%",      String.valueOf(item.amount()));
            for (int i = 0; i < qty; i++) {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), baseCmd);
            }
        } else {
            // Item mode: give items directly, overflow drops at player's feet — never lost
            int remaining = totalItems;
            while (remaining > 0) {
                int batchSize = Math.min(remaining, item.material().getMaxStackSize());
                ItemStack give = new ItemStack(item.material(), batchSize);
                ItemMeta  meta = give.getItemMeta();
                if (meta != null) {
                    // Apply after-bought display name (if configured)
                    if (item.afterBoughtTitle() != null && !item.afterBoughtTitle().isBlank()) {
                        meta.displayName(noItalic(LEGACY.deserialize(ColorUtil.colorize(item.afterBoughtTitle()))));
                    }
                    // Apply after-bought lore (if configured)
                    if (!item.afterBoughtLore().isEmpty()) {
                        List<Component> boughtLore = new ArrayList<>();
                        for (String line : item.afterBoughtLore()) {
                            boughtLore.add(noItalic(LEGACY.deserialize(ColorUtil.colorize(line))));
                        }
                        meta.lore(boughtLore);
                    }
                    item.enchants().forEach((ench, lvl) -> meta.addEnchant(ench, lvl, true));
                    if (!item.potions().isEmpty() && meta instanceof PotionMeta potionMeta) {
                        potionMeta.setBasePotionType(PotionType.WATER);
                        for (PotionEffect effect : item.potions()) {
                            potionMeta.addCustomEffect(effect, true);
                        }
                    }
                    // Stamp item-id PDC tag so future tier gates can verify by ID, not name
                    if (item.itemId() != null && !item.itemId().isBlank()) {
                        meta.getPersistentDataContainer().set(
                                ITEM_ID_KEY, org.bukkit.persistence.PersistentDataType.STRING, item.itemId());
                    }
                    // Mark unbreakable if configured
                    if (item.unbreakable()) {
                        meta.setUnbreakable(true);
                    }
                    // Hide vanilla tooltips (enchant list, attribute modifiers, "Unbreakable", etc.)
                    meta.addItemFlags(ItemFlag.HIDE_ENCHANTS, ItemFlag.HIDE_ATTRIBUTES,
                            ItemFlag.HIDE_ADDITIONAL_TOOLTIP, ItemFlag.HIDE_UNBREAKABLE);
                    give.setItemMeta(meta);
                }
                player.getInventory().addItem(give).values()
                        .forEach(leftover -> player.getWorld().dropItemNaturally(player.getLocation(), leftover));
                remaining -= batchSize;
            }
        }

        messages.sendPurchase(player, totalItems, stripColors(item.name()),
                BankManager.formatBalance(totalPrice), currency);
        player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.6f, 1.2f);
    }

    /**
     * Finds the first inventory slot that satisfies the RequiredItem spec.
     * When {@code req.itemId()} is non-empty, matches by PDC tag (preferred).
     * Falls back to display-name contains check when only {@code nameContains} is set.
     */
    private ItemStack findRequiredItem(Player player, RequiredItem req) {
        boolean useId = req.itemId() != null && !req.itemId().isBlank();
        for (ItemStack stack : player.getInventory().getContents()) {
            if (stack == null || stack.getType() != req.material()) continue;
            ItemMeta meta = stack.getItemMeta();
            if (useId) {
                if (meta == null) continue;
                String tag = meta.getPersistentDataContainer()
                        .get(ITEM_ID_KEY, org.bukkit.persistence.PersistentDataType.STRING);
                if (!req.itemId().equals(tag)) continue;
            } else if (!req.nameContains().isBlank()) {
                if (meta == null || !meta.hasDisplayName()) continue;
                Component dn = meta.displayName();
                if (dn == null) continue;
                String displayName = LEGACY.serialize(dn);
                if (!displayName.toLowerCase().contains(req.nameContains().toLowerCase())) continue;
            }
            return stack;
        }
        return null;
    }

    /**
     * Removes one unit of the required item from the player's inventory.
     * Mirrors the search logic of {@link #findRequiredItem}.
     */
    private void consumeRequiredItem(Player player, RequiredItem req) {
        boolean useId = req.itemId() != null && !req.itemId().isBlank();
        ItemStack[] contents = player.getInventory().getContents();
        for (int i = 0; i < contents.length; i++) {
            ItemStack stack = contents[i];
            if (stack == null || stack.getType() != req.material()) continue;
            ItemMeta meta = stack.getItemMeta();
            if (useId) {
                if (meta == null) continue;
                String tag = meta.getPersistentDataContainer()
                        .get(ITEM_ID_KEY, org.bukkit.persistence.PersistentDataType.STRING);
                if (!req.itemId().equals(tag)) continue;
            } else if (!req.nameContains().isBlank()) {
                if (meta == null || !meta.hasDisplayName()) continue;
                Component dn = meta.displayName();
                if (dn == null) continue;
                String displayName = LEGACY.serialize(dn);
                if (!displayName.toLowerCase().contains(req.nameContains().toLowerCase())) continue;
            }
            if (stack.getAmount() > 1) {
                stack.setAmount(stack.getAmount() - 1);
            } else {
                player.getInventory().setItem(i, null);
            }
            return;
        }
    }

    /**
     * Checks whether a stack has AT LEAST the enchantments specified (may have more).
     * Used when giving enchanted items on purchase.
     */
    private boolean enchantmentsMatch(ItemStack stack, Map<Enchantment, Integer> required) {
        for (Map.Entry<Enchantment, Integer> entry : required.entrySet()) {
            if (stack.getEnchantmentLevel(entry.getKey()) < entry.getValue()) return false;
        }
        return true;
    }

    // ── Cooldowns ─────────────────────────────────────────────────────────────

    /** Cooldown for the +/- quantity spinner. */
    private boolean cooldownOk(UUID uuid) {
        long now  = System.currentTimeMillis();
        Long last = clickCooldown.get(uuid);
        if (last != null && now - last < CLICK_COOLDOWN_MS) return false;
        clickCooldown.put(uuid, now);
        return true;
    }

    /**
     * Cooldown for buy / sell actions.
     * Prevents double-purchase caused by Bedrock tap-spam, client lag, or rapid clicking.
     */
    private boolean actionCooldownOk(UUID uuid) {
        long now  = System.currentTimeMillis();
        Long last = actionCooldown.get(uuid);
        if (last != null && now - last < ACTION_COOLDOWN_MS) return false;
        actionCooldown.put(uuid, now);
        return true;
    }

    /**
     * Sliding-window rate limiter: allows at most RATE_LIMIT_MAX clicks
     * within any RATE_WINDOW_MS window.
     * Records the current click, prunes timestamps older than the window,
     * then checks the count.
     */
    private boolean rateLimitOk(UUID uuid) {
        long now     = System.currentTimeMillis();
        Deque<Long> history = clickHistory.computeIfAbsent(uuid, k -> new ConcurrentLinkedDeque<>());
        history.addLast(now);
        // Discard events older than the window
        while (!history.isEmpty() && now - history.peekFirst() > RATE_WINDOW_MS) {
            history.pollFirst();
        }
        return history.size() < RATE_LIMIT_MAX;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private ShopMenu getActiveMenu(UUID uuid) {
        String id = playerMenu.get(uuid);
        return id != null ? menus.get(id) : null;
    }

    public Map<String, ShopMenu> getMenus() { return Collections.unmodifiableMap(menus); }

    private static String stripColors(String s) {
        return s.replaceAll("(?i)&[0-9a-fk-or]|&#[0-9a-fA-F]{6}", "").trim();
    }

    /**
     * Finds the {@link PotionType} whose base effect matches the first entry in the list.
     * Iterates all known PotionType values and compares NamespacedKeys at runtime so it
     * works regardless of enum renames across Paper versions.
     * Falls back to AWKWARD (valid brewing base, no unwanted effects) if no match is found.
     */
    private static PotionType resolveBaseType(List<PotionEffect> effects) {
        if (effects.isEmpty()) return PotionType.AWKWARD;
        var targetKey = effects.get(0).getType().getKey();
        for (PotionType pt : PotionType.values()) {
            PotionEffectType et = pt.getEffectType();
            if (et != null && et.getKey().equals(targetKey)) return pt;
        }
        return PotionType.AWKWARD;
    }

    /** Strips the default Minecraft italic from any Adventure component. */
    private static Component noItalic(Component c) {
        return c.decoration(TextDecoration.ITALIC, false);
    }

    // Map helper methods — handle both Integer and String values from YAML
    private static int     num(Map<?, ?> m, String k, int def)       { Object v = m.get(k); return v instanceof Number n ? n.intValue() : def; }
    private static double  dbl(Map<?, ?> m, String k, double def)    { Object v = m.get(k); return v instanceof Number n ? n.doubleValue() : def; }
    private static boolean bool(Map<?, ?> m, String k, boolean def)  { Object v = m.get(k); return v instanceof Boolean b ? b : (v != null && Boolean.parseBoolean(v.toString())); }
    private static String  str(Map<?, ?> m, String k, String def)    { Object v = m.get(k); return v != null ? v.toString() : def; }

    @SuppressWarnings("unchecked")
    private static List<String> strList(Map<?, ?> m, String k) {
        Object v = m.get(k);
        if (v instanceof List<?> list) {
            List<String> out = new ArrayList<>();
            for (Object o : list) if (o != null) out.add(o.toString());
            return out;
        }
        return Collections.emptyList();
    }

    // ── Inner record for shared navigation entries ───────────────────────────

    private record NavEntry(
            int slot, Material material,
            String name, String activeName,
            List<String> lore, List<String> activeLore,
            String targetMenu
    ) {}
}
