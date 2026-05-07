package me.revqz.genPvP.Shop;

import me.revqz.genPvP.Bank.BankManager;
import me.revqz.genPvP.DevilFruits.DevilFruitShardListener;
import me.revqz.genPvP.GenPvP;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.*;

import java.io.File;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

public class ItemShopManager implements Listener, CommandExecutor, TabCompleter {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.builder()
            .character('&').hexColors().build();

    private static final Set<Integer> BORDER = Set.of(
            0,1,2,3,4,5,6,7,8,
            9, 17,
            18, 26,
            27,28,29,30,31,32,33,34,35
    );

    private record Entry(
            int slot,
            Material material,
            String name,
            String subtitle,
            String rarity,
            double price,
            Map<Enchantment, Integer> enchants,
            List<String> customLore   
    ) {}

    private record Shop(
            String id,
            String title,
            String currency,
            String currencyLabel,
            List<Entry> items
    ) {}

    private final GenPvP      plugin;
    private final BankManager bankManager;

    private final Map<String, Shop>   shops    = new ConcurrentHashMap<>();
    private final Map<String, String> rarities = new ConcurrentHashMap<>();
    private volatile String defaultShop = "";

    private volatile DevilFruitShardListener devilShardListener;

    private final Set<UUID>         openMenus  = ConcurrentHashMap.newKeySet();
    
    private final Map<UUID, Long>   actionCooldown = new ConcurrentHashMap<>();
    private static final long       ACTION_COOLDOWN_MS = 300;
    
    private final Map<UUID, String> openShopId = new ConcurrentHashMap<>();

    public ItemShopManager(GenPvP plugin, BankManager bankManager) {
        this.plugin       = plugin;
        this.bankManager  = bankManager;
        load();
    }

    public void reload() { load(); }

    public void setDevilShardListener(DevilFruitShardListener listener) {
        this.devilShardListener = listener;
    }

    private void load() {
        
        Map<String, Shop>   freshShops   = new ConcurrentHashMap<>();
        Map<String, String> freshRarities = new ConcurrentHashMap<>();

        File file = new File(plugin.getDataFolder(), "shops2.yml");
        if (!file.exists()) plugin.saveResource("shops2.yml", false);
        FileConfiguration cfg = YamlConfiguration.loadConfiguration(file);

        ConfigurationSection rarSec = cfg.getConfigurationSection("rarities");
        if (rarSec != null) {
            rarSec.getKeys(false).forEach(k ->
                    freshRarities.put(k.toUpperCase(), rarSec.getString(k, "&f")));
        }

        ConfigurationSection shopsSec = cfg.getConfigurationSection("shops");
        if (shopsSec == null) {
            plugin.getLogger().warning("[ItemShop] No 'shops' section in shops2.yml");
            
            shops.clear(); shops.putAll(freshShops);
            rarities.clear(); rarities.putAll(freshRarities);
            return;
        }

        for (String shopId : shopsSec.getKeys(false)) {
            ConfigurationSection sec = shopsSec.getConfigurationSection(shopId);
            if (sec == null) continue;

            String title    = sec.getString("title", "&8Item Shop");
            String currency = sec.getString("currency", "money").toLowerCase();
            String label    = sec.getString("currency-label", "Shard");

            List<Entry> entries = new ArrayList<>();
            for (Map<?, ?> map : sec.getMapList("items")) {
                try {
                    int      slot     = numOf(map, "slot", 10);
                    Material mat      = Material.matchMaterial(strOf(map, "material", "STONE"));
                    if (mat == null) continue;
                    String   name     = strOf(map, "name", "Item");
                    String   subtitle = strOf(map, "subtitle", "");
                    String   rarity   = strOf(map, "rarity", "");
                    double   price    = dblOf(map, "price", 1.0);

                    Map<Enchantment, Integer> enchants = new LinkedHashMap<>();
                    for (Object e : listOf(map, "enchants")) {
                        if (!(e instanceof String s)) continue;
                        String[] parts = s.split(":", 2);
                        if (parts.length != 2) continue;
                        String enchKey = parts[0].trim().toLowerCase();
                        Enchantment ench = Registry.ENCHANTMENT.get(
                                NamespacedKey.minecraft(enchKey));
                        if (ench != null) {
                            enchants.put(ench, Integer.parseInt(parts[1].trim()));
                        } else {
                            plugin.getLogger().warning("[ItemShop] Unknown enchantment key: '" + enchKey + "' in shop '" + shopId + "'");
                        }
                    }

                    List<String> customLore = new ArrayList<>();
                    for (Object l : listOf(map, "lore")) customLore.add(String.valueOf(l));

                    entries.add(new Entry(slot, mat, name, subtitle, rarity, price, enchants, customLore));
                } catch (Exception ex) {
                    plugin.getLogger().warning("[ItemShop] Bad item in shop '" + shopId + "': " + ex.getMessage());
                }
            }

            freshShops.put(shopId, new Shop(shopId, title, currency, label, entries));
        }

        String newDefault = cfg.getString("default-shop", "");
        if (newDefault.isBlank() && !freshShops.isEmpty()) {
            newDefault = freshShops.keySet().iterator().next();
        }

        shops.clear();   shops.putAll(freshShops);
        rarities.clear(); rarities.putAll(freshRarities);
        defaultShop = newDefault;

        plugin.getLogger().info("[ItemShop] Loaded " + shops.size() + " shop(s) from shops2.yml.");
    }

    public boolean hasShop(String id) {
        return shops.containsKey(id);
    }

    public void openShop(Player player, String id) {
        Shop shop = shops.get(id);
        if (shop == null) return;
        if (!bankManager.isLoaded(player.getUniqueId())) {
            player.sendMessage(LEGACY.deserialize("&#A7A7A7Your data is still loading, please wait."));
            return;
        }
        openMenus.add(player.getUniqueId());
        openShopId.put(player.getUniqueId(), id);
        player.openInventory(buildInventory(shop));
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players can use /itemshop.");
            return true;
        }
        String id   = args.length > 0 ? args[0].toLowerCase() : defaultShop;
        Shop   shop = shops.get(id);
        if (shop == null) {
            player.sendMessage(LEGACY.deserialize(
                    "&cShop &e" + id + " &cdoes not exist. Available: &e" + String.join(", ", shops.keySet())));
            return true;
        }
        if (!bankManager.isLoaded(player.getUniqueId())) {
            player.sendMessage(LEGACY.deserialize("&#A7A7A7Your data is still loading, please wait."));
            return true;
        }
        openMenus.add(player.getUniqueId());
        openShopId.put(player.getUniqueId(), id);
        player.openInventory(buildInventory(shop));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 1) {
            String p = args[0].toLowerCase();
            return shops.keySet().stream().filter(s -> s.startsWith(p)).toList();
        }
        return List.of();
    }

    private Inventory buildInventory(Shop shop) {
        Inventory inv = Bukkit.createInventory(null, 36, LEGACY.deserialize(hex(shop.title())));

        ItemStack glass = borderPane();
        BORDER.forEach(s -> inv.setItem(s, glass));

        for (Entry e : shop.items()) {
            if (BORDER.contains(e.slot()) || e.slot() < 0 || e.slot() >= 36) continue;
            inv.setItem(e.slot(), buildDisplayItem(e, shop.currencyLabel()));
        }

        return inv;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (!openMenus.contains(player.getUniqueId())) return;
        event.setCancelled(true);
        
        Bukkit.getScheduler().runTask(plugin, player::updateInventory);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        UUID uuid = player.getUniqueId();
        if (!openMenus.contains(uuid)) return;

        event.setCancelled(true);

        switch (event.getClick()) {
            case DOUBLE_CLICK, NUMBER_KEY, DROP, CONTROL_DROP, SWAP_OFFHAND, CREATIVE, UNKNOWN -> {
                Bukkit.getScheduler().runTask(plugin, player::updateInventory);
                return;
            }
            default -> {}
        }

        if (event.getClickedInventory() == null
                || !event.getClickedInventory().equals(event.getView().getTopInventory())) return;

        long now = System.currentTimeMillis();
        Long last = actionCooldown.put(uuid, now);
        if (last != null && now - last < ACTION_COOLDOWN_MS) return;

        int  slot    = event.getSlot();
        String shopId = openShopId.get(uuid);
        if (shopId == null) return;   
        Shop shop   = shops.get(shopId);
        if (shop == null) return;

        shop.items().stream()
                .filter(e -> e.slot() == slot)
                .findFirst()
                .ifPresent(e -> handlePurchase(player, uuid, shop, e));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        openMenus.remove(uuid);
        openShopId.remove(uuid);
        actionCooldown.remove(uuid);
        
        if (event.getPlayer() instanceof Player player) {
            ItemStack cursor = player.getItemOnCursor();
            if (cursor != null && !cursor.getType().isAir()) {
                player.setItemOnCursor(null);
            }
        }
    }

    private void handlePurchase(Player player, UUID uuid, Shop shop, Entry e) {
        if (!bankManager.isLoaded(uuid)) {
            player.sendMessage(LEGACY.deserialize("&#A7A7A7Your data is still loading."));
            return;
        }

        boolean ok = switch (shop.currency()) {
            case "devil-shards" -> {
                if (devilShardListener == null) yield false;
                int have = devilShardListener.countShards(player);
                if (have < (int) e.price()) yield false;
                devilShardListener.removeShards(player, (int) e.price());
                yield true;
            }
            case "shards" -> bankManager.removeShards(uuid,  player.getName(), e.price());
            case "gold"   -> bankManager.removeGold(uuid,    player.getName(), e.price());
            default       -> bankManager.removeBalance(uuid, player.getName(), e.price());
        };

        if (!ok) {
            double bal = switch (shop.currency()) {
                case "devil-shards" -> devilShardListener != null ? devilShardListener.countShards(player) : 0;
                case "shards" -> bankManager.getShards(uuid);
                case "gold"   -> bankManager.getGold(uuid);
                default       -> bankManager.getBalance(uuid);
            };
            player.sendMessage(LEGACY.deserialize(
                    "&cInsufficient funds. &#A7A7A7Need &#00A4FB" + fmt(e.price()) + " " + shop.currencyLabel()
                    + " &#A7A7A7| Have &#00A4FB" + fmt(bal)));
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.6f, 0.9f);
            return;
        }

        ItemStack give = buildGiveItem(e);
        player.getInventory().addItem(give).values()
                .forEach(lo -> player.getWorld().dropItemNaturally(player.getLocation(), lo));

        String label = e.subtitle().isBlank() ? e.name() : e.name() + " &d(" + e.subtitle() + ")";
        player.sendMessage(LEGACY.deserialize(hex(
                "&#A7A7A7Purchased &f" + label + " &#A7A7A7for &#00A4FB" + fmt(e.price())
                + " " + shop.currencyLabel() + "&#A7A7A7.")));
        player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.6f, 1.2f);
    }

    private ItemStack buildDisplayItem(Entry e, String currencyLabel) {
        ItemStack stack = new ItemStack(e.material());
        ItemMeta  meta  = stack.getItemMeta();
        if (meta == null) return stack;

        meta.displayName(noItalic(LEGACY.deserialize(hex(e.name()))));
        meta.lore(buildLore(e, currencyLabel));
        applyEnchants(meta, e.enchants());
        meta.addItemFlags(ItemFlag.HIDE_ENCHANTS, ItemFlag.HIDE_STORED_ENCHANTS,
                ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
        stack.setItemMeta(meta);
        return stack;
    }

    private ItemStack buildGiveItem(Entry e) {
        ItemStack stack = new ItemStack(e.material());
        if (e.enchants().isEmpty()) return stack;

        if (e.material() == Material.ENCHANTED_BOOK) {
            stack.editMeta(EnchantmentStorageMeta.class, book -> {
                e.enchants().forEach((ench, lvl) -> book.addStoredEnchant(ench, lvl, true));
            });
        } else {
            stack.editMeta(meta -> {
                e.enchants().forEach((ench, lvl) -> meta.addEnchant(ench, lvl, true));
            });
        }

        return stack;
    }

    private List<Component> buildLore(Entry e, String currencyLabel) {
        
        if (!e.customLore().isEmpty()) {
            String rarityColor = rarities.getOrDefault(e.rarity().toUpperCase(), "&f");
            List<Component> lore = new ArrayList<>();
            for (String line : e.customLore()) {
                String resolved = line
                        .replace("%price%",    fmt(e.price()))
                        .replace("%currency%", currencyLabel)
                        .replace("%rarity%",   e.rarity().toUpperCase())
                        .replace("%rarity_color%", rarityColor)
                        .replace("%subtitle%", e.subtitle());
                lore.add(resolved.isEmpty()
                        ? Component.empty()
                        : noItalic(LEGACY.deserialize(hex(resolved))));
            }
            return lore;
        }

        List<Component> lore = new ArrayList<>();

        if (!e.subtitle().isBlank()) {
            lore.add(noItalic(LEGACY.deserialize(hex("&d" + e.subtitle()))));
        }

        if (!e.rarity().isBlank()) {
            lore.add(Component.empty());
            String color = rarities.getOrDefault(e.rarity().toUpperCase(), "&f");
            lore.add(noItalic(LEGACY.deserialize(hex(color + "&l" + e.rarity().toUpperCase()))));
        }

        lore.add(Component.empty());
        lore.add(noItalic(LEGACY.deserialize("&7Cost:")));
        lore.add(noItalic(LEGACY.deserialize("&d  - " + fmt(e.price()) + " " + currencyLabel)));

        return lore;
    }

    private static void applyEnchants(ItemMeta meta, Map<Enchantment, Integer> enchants) {
        if (meta instanceof EnchantmentStorageMeta book) {
            enchants.forEach((ench, lvl) -> book.addStoredEnchant(ench, lvl, true));
        } else {
            enchants.forEach((ench, lvl) -> meta.addEnchant(ench, lvl, true));
        }
    }

    private static ItemStack borderPane() {
        ItemStack stack = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta  meta  = stack.getItemMeta();
        if (meta != null) { meta.setHideTooltip(true); stack.setItemMeta(meta); }
        return stack;
    }

    private static Component noItalic(Component c) {
        return c.decoration(TextDecoration.ITALIC, false);
    }

    private static final Pattern HEX_PATTERN = Pattern.compile("(?<!&)#([0-9A-Fa-f]{6})");
    private static String hex(String s) {
        return HEX_PATTERN.matcher(s).replaceAll("&#$1");
    }

    private static String fmt(double v) {
        return v == Math.floor(v) && !Double.isInfinite(v)
                ? String.valueOf((long) v) : String.valueOf(v);
    }

    @SuppressWarnings("unchecked")
    private static List<Object> listOf(Map<?, ?> map, String key) {
        Object o = map.get(key);
        return o instanceof List<?> l ? (List<Object>) l : List.of();
    }

    private static String strOf(Map<?, ?> map, String key, String def) {
        Object o = map.get(key);
        return o != null ? o.toString() : def;
    }

    private static int numOf(Map<?, ?> map, String key, int def) {
        Object o = map.get(key);
        if (o instanceof Number n) return n.intValue();
        try { return Integer.parseInt(String.valueOf(o)); } catch (Exception e) { return def; }
    }

    private static double dblOf(Map<?, ?> map, String key, double def) {
        Object o = map.get(key);
        if (o instanceof Number n) return n.doubleValue();
        try { return Double.parseDouble(String.valueOf(o)); } catch (Exception e) { return def; }
    }
}
