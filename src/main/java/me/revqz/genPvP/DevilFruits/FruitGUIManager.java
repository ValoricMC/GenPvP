package me.revqz.genPvP.DevilFruits;

import me.revqz.genPvP.GenPvP;
import me.revqz.genPvP.Protect.ProtectRegion;
import me.revqz.genPvP.Protect.RegionManager;
import me.revqz.genPvP.Protect.flags.RegionType;
import me.revqz.genPvP.util.ColorUtil;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.io.File;
import java.util.*;

public class FruitGUIManager implements Listener {

    private final GenPvP plugin;
    private final DevilFruitManager fruitManager;
    private final RegionManager regionManager;

    private YamlConfiguration generalConfig;
    private YamlConfiguration messagesConfig;
    private YamlConfiguration balanceConfig;
    private final Map<FruitType, YamlConfiguration> typeConfigs = new EnumMap<>(FruitType.class);

    private FruitSlotManager slotManager;

    // Rate limiting: max 3 clicks per second per player
    private final Map<UUID, ArrayDeque<Long>> clickTimestamps = new HashMap<>();

    public FruitGUIManager(GenPvP plugin, DevilFruitManager fruitManager, RegionManager regionManager) {
        this.plugin        = plugin;
        this.fruitManager  = fruitManager;
        this.regionManager = regionManager;
        loadConfigs();
    }

    public YamlConfiguration getMessagesConfig() { return messagesConfig; }
    public YamlConfiguration getBalanceConfig() { return balanceConfig; }
    public YamlConfiguration getTypeConfig(FruitType type) { return typeConfigs.get(type); }
    public void setSlotManager(FruitSlotManager sm) { this.slotManager = sm; }

    // ── Config loading ────────────────────────────────────────────────────────

    private void loadConfigs() {
        File guiFolder = new File(plugin.getDataFolder(), "FruitGUI");
        String[] files = {"General.yml", "GeneralMessages.yml", "Paramecia.yml", "Logia.yml", "Zoan.yml", "Balance.yml"};
        for (String file : files) {
            if (!new File(guiFolder, file).exists()) {
                plugin.saveResource("FruitGUI/" + file, false);
            }
        }
        generalConfig  = YamlConfiguration.loadConfiguration(new File(guiFolder, "General.yml"));
        messagesConfig = YamlConfiguration.loadConfiguration(new File(guiFolder, "GeneralMessages.yml"));
        balanceConfig  = YamlConfiguration.loadConfiguration(new File(guiFolder, "Balance.yml"));
        typeConfigs.put(FruitType.PARAMECIA, YamlConfiguration.loadConfiguration(new File(guiFolder, "Paramecia.yml")));
        typeConfigs.put(FruitType.LOGIA,     YamlConfiguration.loadConfiguration(new File(guiFolder, "Logia.yml")));
        typeConfigs.put(FruitType.ZOAN,      YamlConfiguration.loadConfiguration(new File(guiFolder, "Zoan.yml")));
    }

    // ── Custom InventoryHolders ───────────────────────────────────────────────

    public static class GeneralHolder implements InventoryHolder {
        private Inventory inventory;
        @Override public Inventory getInventory() { return inventory; }
        void setInventory(Inventory inv)          { this.inventory = inv; }
    }

    public static class TypeHolder implements InventoryHolder {
        private final FruitType type;
        private Inventory inventory;
        TypeHolder(FruitType type) { this.type = type; }
        public FruitType getType()             { return type; }
        @Override public Inventory getInventory() { return inventory; }
        void setInventory(Inventory inv)          { this.inventory = inv; }
    }

    // ── Open GUIs ─────────────────────────────────────────────────────────────

    public void openGeneral(Player player) {
        GeneralHolder holder = new GeneralHolder();
        String title = ColorUtil.colorize(generalConfig.getString("title", "&5&lDevil Fruits"));
        Inventory inv = Bukkit.createInventory(holder, 9, title);
        holder.setInventory(inv);

        placeGeneralButton(inv, "paramecia");
        placeGeneralButton(inv, "zoan");
        placeGeneralButton(inv, "logia");

        player.openInventory(inv);
        player.playSound(player.getLocation(), Sound.BLOCK_CHEST_OPEN, 0.5f, 1.0f);
    }

    public void openType(Player player, FruitType type) {
        YamlConfiguration config = typeConfigs.get(type);
        if (config == null) return;

        TypeHolder holder = new TypeHolder(type);
        String title = ColorUtil.colorize(config.getString("title", type.getDisplayName()));
        Inventory inv = Bukkit.createInventory(holder, 36, title);
        holder.setInventory(inv);

        fillBorder(inv);

        ConfigurationSection fruitsSection = config.getConfigurationSection("fruits");
        if (fruitsSection != null) {
            for (String fruitKey : fruitsSection.getKeys(false)) {
                String path = "fruits." + fruitKey + ".";
                int slot = config.getInt(path + "slot", 0);
                if (slot < 0 || slot >= 36) continue;
                inv.setItem(slot, buildFruitItem(config, path, fruitKey, player));
            }
        }

        player.openInventory(inv);
        player.playSound(player.getLocation(), Sound.ITEM_BOOK_PAGE_TURN, 0.8f, 1.0f);
    }

    // ── Event Handlers ────────────────────────────────────────────────────────

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        InventoryHolder holder = event.getInventory().getHolder();

        if (holder instanceof GeneralHolder) {
            event.setCancelled(true);
            if (!checkRateLimit(player)) return;
            int slot = event.getRawSlot();
            if (slot == generalConfig.getInt("paramecia.slot", 0)) openType(player, FruitType.PARAMECIA);
            else if (slot == generalConfig.getInt("zoan.slot", 1))      openType(player, FruitType.ZOAN);
            else if (slot == generalConfig.getInt("logia.slot", 2))     openType(player, FruitType.LOGIA);

        } else if (holder instanceof TypeHolder typeHolder) {
            event.setCancelled(true);
            if (!checkRateLimit(player)) return;

            int slot = event.getRawSlot();
            if (slot < 0 || slot >= 36) return;

            ItemStack clicked = event.getCurrentItem();
            if (clicked == null || clicked.getType() == Material.AIR) return;

            YamlConfiguration config = typeConfigs.get(typeHolder.getType());
            if (config == null) return;

            String fruitKey = fruitKeyAtSlot(config, slot);
            if (fruitKey == null) return;

            // Region check: must be in SPAWN
            if (!isInSpawn(player)) {
                player.sendMessage(ColorUtil.colorize(
                        messagesConfig.getString("wrong-region", "&cCan only switch fruits in spawn.")));
                player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
                return;
            }

            // Ownership check
            if (!fruitManager.getOwnedFruits(player.getUniqueId()).contains(fruitKey)) {
                player.sendMessage(ColorUtil.colorize(
                        messagesConfig.getString("no-permission", "&cYou do not own this fruit.")));
                player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.8f, 1.0f);
                return;
            }

            // Equip
            fruitManager.setEquipped(player.getUniqueId(), fruitKey);
            if (slotManager != null) slotManager.updateFruitSlot(player);
            DevilFruit fruit = DevilFruit.fromKey(fruitKey);
            String fruitName = fruit != null ? fruit.getDisplayName() : fruitKey;
            player.sendMessage(ColorUtil.colorize(
                    messagesConfig.getString("fruit-equipped", "&aYou have equipped &f%fruit%&a.")
                            .replace("%fruit%", fruitName)));
            player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.2f);
        }
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;
        InventoryHolder holder = event.getInventory().getHolder();
        if (holder instanceof GeneralHolder || holder instanceof TypeHolder) {
            event.setCancelled(true);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private boolean checkRateLimit(Player player) {
        long now = System.currentTimeMillis();
        ArrayDeque<Long> times = clickTimestamps.computeIfAbsent(player.getUniqueId(), k -> new ArrayDeque<>());
        times.removeIf(t -> now - t > 1000L);
        if (times.size() >= 3) {
            player.sendMessage(ColorUtil.colorize(
                    messagesConfig.getString("click-too-fast", "&cYou are clicking too fast!")));
            return false;
        }
        times.addLast(now);
        return true;
    }

    private boolean isInSpawn(Player player) {
        for (ProtectRegion region : regionManager.getRegionsAt(player.getLocation())) {
            if (region.getType() == RegionType.SPAWN) return true;
        }
        return false;
    }

    private String fruitKeyAtSlot(YamlConfiguration config, int slot) {
        ConfigurationSection section = config.getConfigurationSection("fruits");
        if (section == null) return null;
        for (String key : section.getKeys(false)) {
            if (config.getInt("fruits." + key + ".slot") == slot) return key;
        }
        return null;
    }

    private void placeGeneralButton(Inventory inv, String section) {
        int slot = generalConfig.getInt(section + ".slot", 0);
        if (slot < 0 || slot >= 9) return;
        inv.setItem(slot, buildItem(generalConfig, section + "."));
    }

    /**
     * Builds a fruit item for the type-browse GUI.
     * <ul>
     *   <li>Owned → uses {@code devil-equipped-name} / {@code devil-equipped-lore}
     *       and appends "click to equip".</li>
     *   <li>Not owned → uses the standard {@code name} / {@code lore}
     *       and appends "not owned / store link".</li>
     * </ul>
     */
    private ItemStack buildFruitItem(YamlConfiguration config, String path,
                                     String fruitKey, Player player) {
        String matName = config.getString(path + "material", "PAPER");
        boolean owned = fruitManager.getOwnedFruits(player.getUniqueId()).contains(fruitKey);

        // Pick name + lore source based on ownership
        String name;
        List<String> rawLore;
        if (owned) {
            name    = config.getString(path + "devil-equipped-name", config.getString(path + "name", ""));
            rawLore = config.getStringList(path + "devil-equipped-lore");
        } else {
            name    = config.getString(path + "name", "");
            rawLore = config.getStringList(path + "lore");
        }

        // Build lore — preserve blank lines as spacers, then append action line
        List<String> lore = new ArrayList<>();
        for (String line : rawLore) {
            lore.add(line.isEmpty() ? "" : ColorUtil.colorize(line));
        }
        lore.add("");
        if (owned) {
            lore.add(ColorUtil.colorize(messagesConfig.getString("click-to-equip", "&eClick to equip")));
        } else {
            lore.add(ColorUtil.colorize(messagesConfig.getString("not-owned", "&7&oNot owned")));
        }

        Material material = Material.matchMaterial(matName);
        if (material == null) material = Material.PAPER;

        ItemStack item = new ItemStack(material);
        ItemMeta meta  = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ColorUtil.colorize(name));
            meta.setLore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    /**
     * Fills the outer ring of a 4-row (36-slot) inventory with gray stained glass panes.
     * Border slots: top row (0-8), bottom row (27-35), left column (9, 18), right column (17, 26).
     */
    private void fillBorder(Inventory inv) {
        ItemStack glass = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = glass.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(org.bukkit.ChatColor.RESET + "");
            glass.setItemMeta(meta);
        }
        // Top and bottom rows
        for (int i = 0; i <= 8; i++)  inv.setItem(i, glass);
        for (int i = 27; i <= 35; i++) inv.setItem(i, glass);
        // Left and right columns (middle rows)
        inv.setItem(9,  glass);
        inv.setItem(17, glass);
        inv.setItem(18, glass);
        inv.setItem(26, glass);
    }

    private ItemStack buildItem(YamlConfiguration config, String path) {
        String matName = config.getString(path + "material", "PAPER");
        String name    = config.getString(path + "name", "");
        List<String> lore = config.getStringList(path + "lore");

        Material material = Material.matchMaterial(matName);
        if (material == null) material = Material.PAPER;

        ItemStack item = new ItemStack(material);
        ItemMeta meta  = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ColorUtil.colorize(name));
            List<String> coloredLore = new ArrayList<>();
            for (String line : lore) coloredLore.add(ColorUtil.colorize(line));
            meta.setLore(coloredLore);
            item.setItemMeta(meta);
        }
        return item;
    }
}
