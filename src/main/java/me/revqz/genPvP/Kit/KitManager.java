package me.revqz.genPvP.Kit;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.UpdateOptions;
import me.revqz.genPvP.Database.DatabaseManager;
import me.revqz.genPvP.GenPvP;
import me.revqz.genPvP.util.ColorUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bson.Document;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.command.CommandSender;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static com.mongodb.client.model.Filters.eq;

public class KitManager implements Listener {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();

    private final GenPvP        plugin;
    private final MongoDatabase db;
    private final boolean       dbConnected;

    private File              kitsFile;
    private FileConfiguration kitsConfig;

    /** Kits in declaration order. */
    private final Map<String, KitData> kits = new LinkedHashMap<>();

    /** uuid → (kitId → expiry epoch ms) */
    private final Map<UUID, Map<String, Long>> cooldowns = new ConcurrentHashMap<>();

    /**
     * Players who have disabled the auto-starter-kit on death feature.
     * Absence from this set means "enabled" (the default).
     */
    private final Set<UUID> starterOnDeathDisabled = ConcurrentHashMap.newKeySet();

    // ── Constructor ───────────────────────────────────────────────────────────

    public KitManager(GenPvP plugin, DatabaseManager dbManager) {
        this.plugin      = plugin;
        this.db          = dbManager.isMongoConnected() ? dbManager.getDatabase() : null;
        this.dbConnected = dbManager.isMongoConnected();
        initFile();
        loadKits();
    }

    // ── File ──────────────────────────────────────────────────────────────────

    private void initFile() {
        kitsFile = new File(plugin.getDataFolder(), "kits.yml");
        if (!kitsFile.exists()) plugin.saveResource("kits.yml", false);
        kitsConfig = YamlConfiguration.loadConfiguration(kitsFile);
    }

    public void reloadKits() {
        kitsConfig = YamlConfiguration.loadConfiguration(kitsFile);
        loadKits();
    }

    private void saveKitsConfig() {
        try {
            kitsConfig.save(kitsFile);
        } catch (IOException e) {
            plugin.getLogger().warning("[KitManager] Failed to save kits.yml: " + e.getMessage());
        }
    }

    // ── Load kits ─────────────────────────────────────────────────────────────

    private void loadKits() {
        kits.clear();
        ConfigurationSection section = kitsConfig.getConfigurationSection("kits");
        if (section == null) return;
        for (String id : section.getKeys(false)) {
            ConfigurationSection k = section.getConfigurationSection(id);
            if (k == null) continue;
            try {
                kits.put(id, parseKit(id, k));
            } catch (Exception e) {
                plugin.getLogger().warning("[KitManager] Failed to load kit '" + id + "': " + e.getMessage());
            }
        }
        plugin.getLogger().info("[KitManager] Loaded " + kits.size() + " kit(s).");
    }

    private KitData parseKit(String id, ConfigurationSection k) {
        String       displayName   = k.getString("display-name", "&b&l" + capitalize(id));
        long         cooldown      = k.getLong("cooldown", 300);
        int          guiSlot       = k.getInt("gui-slot", -1);
        Material     unlockedMat   = parseMat(k.getString("unlocked-material", "CHEST"));
        String       unlockedName  = k.getString("unlocked-name", "&b&l" + capitalize(id));
        List<String> unlockedLore  = k.getStringList("unlocked-lore");
        List<String> cooldownLore  = k.getStringList("cooldown-lore");
        Material     lockedMat     = parseMat(k.getString("locked-material", "BARRIER"));
        String       lockedName    = k.getString("locked-name", "&c&l" + capitalize(id));
        List<String> lockedLore    = k.getStringList("locked-lore");

        Map<Integer, ItemStack> contents = loadItemMap(k.getConfigurationSection("contents"));
        Map<Integer, ItemStack> armor    = loadItemMap(k.getConfigurationSection("armor"));
        ItemStack offhand = itemFromBase64(k.getString("offhand", ""));

        return new KitData(id, displayName, cooldown, guiSlot,
                unlockedMat, unlockedName, unlockedLore, cooldownLore,
                lockedMat, lockedName, lockedLore,
                contents, armor, offhand);
    }

    private Map<Integer, ItemStack> loadItemMap(ConfigurationSection section) {
        Map<Integer, ItemStack> map = new LinkedHashMap<>();
        if (section == null) return map;
        for (String key : section.getKeys(false)) {
            try {
                int slot = Integer.parseInt(key);
                ItemStack item = itemFromBase64(section.getString(key));
                if (item != null) map.put(slot, item);
            } catch (NumberFormatException ignored) {}
        }
        return map;
    }

    // ── Register ──────────────────────────────────────────────────────────────

    /**
     * Registers (or updates) a kit from the player's current inventory.
     * Captures: hotbar + main (slots 0-35), armour (0=boots…3=helmet), offhand.
     */
    public void registerFromInventory(String kitId, Player player) {
        PlayerInventory inv = player.getInventory();

        // Contents — hotbar (0-8) + main inventory (9-35)
        Map<Integer, String> contents = new LinkedHashMap<>();
        for (int i = 0; i < 36; i++) {
            ItemStack item = inv.getItem(i);
            if (item != null && item.getType() != Material.AIR)
                contents.put(i, itemToBase64(item));
        }

        // Armour — boots=0, leggings=1, chestplate=2, helmet=3
        Map<Integer, String> armor = new LinkedHashMap<>();
        ItemStack[] armorArr = inv.getArmorContents();
        for (int i = 0; i < 4; i++) {
            if (armorArr[i] != null && armorArr[i].getType() != Material.AIR)
                armor.put(i, itemToBase64(armorArr[i]));
        }

        // Offhand
        String offhand = itemToBase64(inv.getItemInOffHand());

        String path = "kits." + kitId;

        // Create default GUI config block for brand-new kits
        if (!kitsConfig.contains(path)) {
            kitsConfig.set(path + ".display-name",        "&b&l" + capitalize(kitId));
            kitsConfig.set(path + ".cooldown",            86400);
            kitsConfig.set(path + ".gui-slot",            -1);
            kitsConfig.set(path + ".unlocked-material",   "CHEST");
            kitsConfig.set(path + ".unlocked-name",       "&b&l" + capitalize(kitId));
            kitsConfig.set(path + ".unlocked-lore",       List.of("", "&eClick to claim!"));
            kitsConfig.set(path + ".cooldown-lore",       List.of("", "&cOn cooldown: &e%cooldown%"));
            kitsConfig.set(path + ".locked-material",     "BARRIER");
            kitsConfig.set(path + ".locked-name",         "&c&l" + capitalize(kitId));
            kitsConfig.set(path + ".locked-lore",         List.of("", "&cNo permission."));
        }

        // Write contents (clear then re-write)
        kitsConfig.set(path + ".contents", null);
        contents.forEach((slot, b64) -> kitsConfig.set(path + ".contents." + slot, b64));

        // Write armour
        kitsConfig.set(path + ".armor", null);
        armor.forEach((slot, b64) -> kitsConfig.set(path + ".armor." + slot, b64));

        // Write offhand
        kitsConfig.set(path + ".offhand", offhand);

        saveKitsConfig();
        reloadKits();
    }

    // TODO: Implement /kit register <name> from_file
    //       Read kit contents from a manually written YAML file at
    //       plugins/GenPvP/kits/<name>_import.yml and populate the kit entry.

    // ── GUI ───────────────────────────────────────────────────────────────────

    public void openGUI(Player player) {
        player.openInventory(buildGUI(player));
    }

    private Inventory buildGUI(Player player) {
        int      rows      = kitsConfig.getInt("gui.rows", 3);
        String   rawTitle  = kitsConfig.getString("gui.title", "Kits");
        Material fillerMat = parseMat(kitsConfig.getString("gui.filler-material",
                "BLUE_STAINED_GLASS_PANE"));

        Inventory inv = Bukkit.createInventory(new KitHolder(),
                rows * 9,
                LEGACY.deserialize(ColorUtil.colorize(rawTitle)));

        // Fill all slots
        ItemStack filler = new ItemStack(fillerMat);
        ItemMeta  fm     = filler.getItemMeta();
        if (fm != null) { fm.setHideTooltip(true); filler.setItemMeta(fm); }
        for (int i = 0; i < rows * 9; i++) inv.setItem(i, filler);

        // Place kit buttons
        for (KitData kit : kits.values()) {
            if (kit.guiSlot() < 0 || kit.guiSlot() >= rows * 9) continue;
            inv.setItem(kit.guiSlot(), buildKitItem(player, kit));
        }
        return inv;
    }

    private ItemStack buildKitItem(Player player, KitData kit) {
        boolean hasPerm    = player.hasPermission("genpvp." + kit.id());
        boolean onCooldown = isOnCooldown(player.getUniqueId(), kit.id());

        if (!hasPerm) {
            return buildItem(kit.lockedMat(), kit.lockedName(), kit.lockedLore());
        }
        if (onCooldown) {
            long         remaining = getCooldownRemaining(player.getUniqueId(), kit.id());
            String       formatted = formatCooldown(remaining);
            List<String> lore      = new ArrayList<>();
            for (String line : kit.cooldownLore())
                lore.add(line.replace("%cooldown%", formatted));
            return buildItem(kit.unlockedMat(), kit.unlockedName(), lore);
        }
        return buildItem(kit.unlockedMat(), kit.unlockedName(), kit.unlockedLore());
    }

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

    // ── Events ────────────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = false)
    public void onDrag(InventoryDragEvent event) {
        InventoryHolder holder = event.getInventory().getHolder();
        if (holder instanceof KitHolder || holder instanceof KitPreviewHolder)
            event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = false)
    public void onClick(InventoryClickEvent event) {
        InventoryHolder holder = event.getInventory().getHolder();

        // ── Preview inventory — fully read-only ───────────────────────────────
        if (holder instanceof KitPreviewHolder) {
            event.setCancelled(true);
            return;
        }

        // ── Kit selector GUI ──────────────────────────────────────────────────
        if (!(holder instanceof KitHolder)) return;
        event.setCancelled(true);

        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (event.getClickedInventory() == null
                || !event.getClickedInventory().equals(event.getView().getTopInventory())) return;

        int slot = event.getSlot();
        for (KitData kit : kits.values()) {
            if (kit.guiSlot() != slot) continue;
            if (event.getClick() == ClickType.RIGHT) {
                player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.2f);
                openPreview(player, kit);         // right-click → preview
            } else {
                player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.0f);
                player.closeInventory();
                claimKit(player, kit);            // left-click (or any other) → claim
            }
            return;
        }
    }

    // ── Preview ───────────────────────────────────────────────────────────────

    /**
     * Opens a 4-row read-only inventory showing the kit's contents laid out as:
     *   Row 1 — hotbar items (kit inventory slots 0-8)
     *   Row 2 — armour (helmet → boots) + offhand + fillers
     *   Row 3 — main inventory row 1 (kit inventory slots 9-17)
     *   Row 4 — main inventory row 2 (kit inventory slots 18-26)
     */
    public void openPreview(Player player, KitData kit) {
        // Layout:
        //   Slots 0-3  : armor (helmet, chestplate, leggings, boots)
        //   Slots 4-12 : kit hotbar  (contents[0-8]  → preview 4+i)
        //   Slots 13-39: kit main inv (contents[9-35] → preview 4+i)
        //   Slot  40   : offhand (if present)
        // Inventory size is the minimum number of rows needed to fit the last item.

        // Find the last occupied preview slot
        int lastSlot = 3; // armor always uses slots 0-3
        for (int i = 0; i <= 35; i++) {
            ItemStack item = kit.contents().get(i);
            if (item != null && item.getType() != Material.AIR)
                lastSlot = 4 + i;
        }
        boolean hasOffhand = kit.offhand() != null && kit.offhand().getType() != Material.AIR;
        if (hasOffhand) lastSlot = Math.max(lastSlot, 40);

        int size = (int) Math.ceil((lastSlot + 1) / 9.0) * 9;
        size = Math.max(9, Math.min(54, size));

        String previewTitle = ColorUtil.colorize(
                kitsConfig.getString("gui.preview-title", "&8Preview: ")
                        + kit.displayName());
        Inventory inv = Bukkit.createInventory(new KitPreviewHolder(),
                size, LEGACY.deserialize(previewTitle));

        // Armor — slots 0-3 (helmet=3, chestplate=2, leggings=1, boots=0)
        int[] armorOrder = {3, 2, 1, 0};
        for (int i = 0; i < 4; i++) {
            ItemStack piece = kit.armor().get(armorOrder[i]);
            if (piece != null) inv.setItem(i, piece.clone());
        }

        // Contents — slots 4 + original slot index
        for (int i = 0; i <= 35; i++) {
            int previewSlot = 4 + i;
            if (previewSlot >= size) break;
            ItemStack item = kit.contents().get(i);
            if (item != null) inv.setItem(previewSlot, item.clone());
        }

        // Offhand — slot 40 if it fits
        if (hasOffhand && 40 < size)
            inv.setItem(40, kit.offhand().clone());

        player.openInventory(inv);
    }

    // ── Claiming ──────────────────────────────────────────────────────────────

    private void claimKit(Player player, KitData kit) {
        UUID uuid = player.getUniqueId();

        if (!player.hasPermission("genpvp." + kit.id())) {
            player.sendMessage(ColorUtil.colorize(msg("no-permission",
                    "&cYou don't have permission for this kit.")));
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
            return;
        }

        if (isOnCooldown(uuid, kit.id())) {
            String formatted = formatCooldown(getCooldownRemaining(uuid, kit.id()));
            player.sendMessage(ColorUtil.colorize(
                    msg("on-cooldown", "&cThis kit is on cooldown for &e%cooldown%&c.")
                            .replace("%cooldown%", formatted)));
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
            return;
        }

        if (!hasSpace(player, kit)) {
            player.sendMessage(ColorUtil.colorize(msg("no-space",
                    "&cYou don't have enough inventory space to claim this kit.")));
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
            return;
        }

        giveKit(player, kit);
        setCooldown(uuid, kit.id(), kit.cooldownSeconds());

        player.sendMessage(ColorUtil.colorize(
                msg("claimed", "&aYou claimed the &b%kit% &akit!")
                        .replace("%kit%", ColorUtil.colorize(kit.displayName()))));
        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.5f, 1.0f);
    }

    /** Simulates giving all kit items to a copy of the inventory to verify space. */
    private boolean hasSpace(Player player, KitData kit) {
        PlayerInventory playerInv = player.getInventory();
        List<ItemStack> needsSlot = new ArrayList<>();

        // All content items go into the main inventory
        kit.contents().values().forEach(i -> needsSlot.add(i.clone()));

        // Armour pieces that can't be equipped go into the main inventory
        ItemStack[] wornArmor = playerInv.getArmorContents();
        kit.armor().forEach((slot, piece) -> {
            if (slot >= 0 && slot < 4
                    && wornArmor[slot] != null
                    && wornArmor[slot].getType() != Material.AIR) {
                needsSlot.add(piece.clone());
            }
        });

        // Offhand overflow
        if (kit.offhand() != null && kit.offhand().getType() != Material.AIR
                && playerInv.getItemInOffHand().getType() != Material.AIR) {
            needsSlot.add(kit.offhand().clone());
        }

        // Simulate on a 36-slot copy
        Inventory sim = Bukkit.createInventory(null, 36);
        sim.setContents(playerInv.getStorageContents());
        for (ItemStack item : needsSlot) {
            if (!sim.addItem(item).isEmpty()) return false;
        }
        return true;
    }

    private void giveKit(Player player, KitData kit) {
        PlayerInventory inv = player.getInventory();

        // Armour — equip if slot is free, otherwise spill to inventory
        ItemStack[] worn = inv.getArmorContents();
        kit.armor().forEach((slot, piece) -> {
            if (slot < 0 || slot >= 4) return;
            if (worn[slot] == null || worn[slot].getType() == Material.AIR) {
                worn[slot] = piece.clone();
            } else {
                inv.addItem(piece.clone());
            }
        });
        inv.setArmorContents(worn);

        // Offhand — equip if free, otherwise spill
        if (kit.offhand() != null && kit.offhand().getType() != Material.AIR) {
            if (inv.getItemInOffHand().getType() == Material.AIR) {
                inv.setItemInOffHand(kit.offhand().clone());
            } else {
                inv.addItem(kit.offhand().clone());
            }
        }

        // Contents — add to inventory
        kit.contents().values().forEach(item -> inv.addItem(item.clone()));
    }

    // ── Cooldowns ─────────────────────────────────────────────────────────────

    public boolean isOnCooldown(UUID uuid, String kitId) {
        Map<String, Long> map = cooldowns.get(uuid);
        if (map == null) return false;
        Long expiry = map.get(kitId);
        return expiry != null && expiry > System.currentTimeMillis();
    }

    public long getCooldownRemaining(UUID uuid, String kitId) {
        Map<String, Long> map = cooldowns.get(uuid);
        if (map == null) return 0;
        Long expiry = map.get(kitId);
        if (expiry == null) return 0;
        return Math.max(0, (expiry - System.currentTimeMillis()) / 1000);
    }

    private void setCooldown(UUID uuid, String kitId, long seconds) {
        long expiresAt = System.currentTimeMillis() + (seconds * 1000L);
        cooldowns.computeIfAbsent(uuid, k -> new ConcurrentHashMap<>()).put(kitId, expiresAt);
        if (dbConnected) asyncSaveCooldown(uuid, kitId, expiresAt);
    }

    public String formatCooldown(long totalSeconds) {
        if (totalSeconds <= 0) return "0s";
        long d = totalSeconds / 86400;
        long h = (totalSeconds % 86400) / 3600;
        long m = (totalSeconds % 3600) / 60;
        long s = totalSeconds % 60;
        if (d > 0) return h > 0 ? d + "d " + h + "h" : d + "d";
        if (h > 0) return m > 0 ? h + "h " + m + "m" : h + "h";
        if (m > 0) return s > 0 ? m + "m " + s + "s" : m + "m";
        return s + "s";
    }

    // ── DB ────────────────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.LOW)
    public void onJoin(PlayerJoinEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        cooldowns.put(uuid, new ConcurrentHashMap<>());
        if (!dbConnected) return;
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin,
                () -> loadCooldownsFromDB(uuid));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        cooldowns.remove(uuid);
        starterOnDeathDisabled.remove(uuid);
    }

    /**
     * Automatically re-gives the starter kit when a player respawns, unless
     * they have disabled this feature with /kit starter_on_death.
     * The cooldown is always reset so the kit cannot be claimed again immediately
     * via /kit.
     */
    @EventHandler(priority = EventPriority.NORMAL)
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        UUID   uuid   = player.getUniqueId();

        if (starterOnDeathDisabled.contains(uuid)) return;

        KitData kit = kits.get("starter");
        if (kit == null) return;

        // 1-tick delay so the server finishes clearing the dead inventory first
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline()) {
                giveKit(player, kit);
                setCooldown(uuid, "starter", kit.cooldownSeconds());
            }
        }, 1L);
    }

    // ── Starter-on-death toggle ───────────────────────────────────────────────

    /**
     * Toggles whether this player automatically receives the starter kit on
     * respawn. Persists the preference to MongoDB.
     */
    public boolean isStarterOnDeathDisabled(UUID uuid) {
        return starterOnDeathDisabled.contains(uuid);
    }

    public void toggleStarterOnDeath(Player player) {
        UUID uuid = player.getUniqueId();
        if (starterOnDeathDisabled.contains(uuid)) {
            starterOnDeathDisabled.remove(uuid);
            player.sendMessage(ColorUtil.colorize(
                    "&aStarter kit on death: &2ENABLED&a."));
        } else {
            starterOnDeathDisabled.add(uuid);
            player.sendMessage(ColorUtil.colorize(
                    "&cStarter kit on death: &4DISABLED&c."));
        }
        asyncSaveStarterOnDeathPref(uuid, starterOnDeathDisabled.contains(uuid));
    }

    private void asyncSaveStarterOnDeathPref(UUID uuid, boolean disabled) {
        if (!dbConnected) return;
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                db.getCollection("kit_cooldowns").updateOne(
                        eq("_id", uuid.toString()),
                        new Document("$set", new Document("starter_on_death_disabled", disabled)),
                        new UpdateOptions().upsert(true));
            } catch (Exception e) {
                plugin.getLogger().warning("[KitManager] Failed to save starter_on_death pref: " + e.getMessage());
            }
        });
    }

    // ── First-join / bypass giving ────────────────────────────────────────────

    /**
     * Gives the starter kit bypassing permission and cooldown checks.
     * Used on first join and (internally) by the on-death respawn handler.
     * Always resets the kit's cooldown afterwards.
     */
    public void giveStarterKitBypassing(Player player) {
        KitData kit = kits.get("starter");
        if (kit == null) return;
        giveKit(player, kit);
        setCooldown(player.getUniqueId(), "starter", kit.cooldownSeconds());
        player.sendMessage(ColorUtil.colorize(
                msg("claimed", "&aYou claimed the &b%kit% &akit!")
                        .replace("%kit%", ColorUtil.colorize(kit.displayName()))));
        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.5f, 1.0f);
    }

    private void loadCooldownsFromDB(UUID uuid) {
        long now = System.currentTimeMillis();
        try {
            // Document: { _id: uuid, cooldowns: { kitId: expiresMs }, starter_on_death_disabled: bool }
            Document doc = db.getCollection("kit_cooldowns").find(eq("_id", uuid.toString())).first();
            if (doc == null) return;

            Document cds = doc.get("cooldowns", Document.class);
            if (cds != null) {
                Map<String, Long> map = cooldowns.computeIfAbsent(uuid, k -> new ConcurrentHashMap<>());
                for (String kitId : cds.keySet()) {
                    long expires = cds.getLong(kitId);
                    if (expires > now) map.put(kitId, expires);
                }
            }

            if (doc.getBoolean("starter_on_death_disabled", false)) {
                starterOnDeathDisabled.add(uuid);
            }
        } catch (Exception e) {
            plugin.getLogger().warning("[KitManager] Failed to load cooldowns for " + uuid + ": " + e.getMessage());
        }
    }

    private void asyncSaveCooldown(UUID uuid, String kitId, long expiresAt) {
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                // $set on a sub-field — creates the document if it doesn't exist
                db.getCollection("kit_cooldowns").updateOne(
                        eq("_id", uuid.toString()),
                        new Document("$set", new Document("cooldowns." + kitId, expiresAt)),
                        new UpdateOptions().upsert(true));
            } catch (Exception e) {
                plugin.getLogger().warning("[KitManager] Failed to save cooldown: " + e.getMessage());
            }
        });
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    public Set<String> getKitIds() { return kits.keySet(); }

    public KitData getKit(String id) { return kits.get(id); }

    /**
     * Resets the cooldown for a specific kit for the named player.
     * Works for both online and offline players.
     */
    public void resetCooldown(String playerName, String kitId, CommandSender feedback) {
        if (!kits.containsKey(kitId)) {
            feedback.sendMessage(ColorUtil.colorize("&cUnknown kit: &e" + kitId));
            return;
        }

        @SuppressWarnings("deprecation")
        org.bukkit.OfflinePlayer offline = Bukkit.getOfflinePlayer(playerName);
        if (!offline.hasPlayedBefore() && Bukkit.getPlayerExact(playerName) == null) {
            feedback.sendMessage(ColorUtil.colorize("&cPlayer &e" + playerName + " &cnot found."));
            return;
        }

        UUID uuid = offline.getUniqueId();

        // Remove from in-memory cache if online
        Map<String, Long> map = cooldowns.get(uuid);
        if (map != null) map.remove(kitId);

        if (!dbConnected) {
            feedback.sendMessage(ColorUtil.colorize(
                    "&aReset &e" + kitId + " &acooldown for &e" + playerName + "&a."));
            return;
        }

        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                db.getCollection("kit_cooldowns").updateOne(
                        eq("_id", uuid.toString()),
                        new Document("$unset", new Document("cooldowns." + kitId, "")));
                Bukkit.getScheduler().runTask(plugin, () ->
                        feedback.sendMessage(ColorUtil.colorize(
                                "&aReset &e" + kitId + " &acooldown for &e" + playerName + "&a.")));
            } catch (Exception e) {
                plugin.getLogger().warning("[KitManager] resetCooldown failed: " + e.getMessage());
            }
        });
    }

    public String msg(String key, String fallback) {
        return kitsConfig.getString("messages." + key, fallback);
    }

    private static String itemToBase64(ItemStack item) {
        if (item == null || item.getType() == Material.AIR) return "";
        return Base64.getEncoder().encodeToString(item.serializeAsBytes());
    }

    private ItemStack itemFromBase64(String b64) {
        if (b64 == null || b64.isEmpty()) return null;
        try {
            return ItemStack.deserializeBytes(Base64.getDecoder().decode(b64));
        } catch (Exception e) {
            plugin.getLogger().warning("[KitManager] Could not deserialize item: " + e.getMessage());
            return null;
        }
    }

    private static Material parseMat(String name) {
        if (name == null) return Material.BARRIER;
        Material m = Material.matchMaterial(name);
        return m != null ? m : Material.BARRIER;
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1).toLowerCase();
    }

    private static Component noItalic(Component c) {
        return c.decoration(TextDecoration.ITALIC, false);
    }

    // ── Inner types ───────────────────────────────────────────────────────────

    private static final class KitHolder implements InventoryHolder {
        @Override public Inventory getInventory() { return null; }
    }

    private static final class KitPreviewHolder implements InventoryHolder {
        @Override public Inventory getInventory() { return null; }
    }

    record KitData(
            String id,
            String displayName,
            long cooldownSeconds,
            int guiSlot,
            Material unlockedMat,
            String unlockedName,
            List<String> unlockedLore,
            List<String> cooldownLore,
            Material lockedMat,
            String lockedName,
            List<String> lockedLore,
            Map<Integer, ItemStack> contents,
            Map<Integer, ItemStack> armor,
            ItemStack offhand
    ) {}
}
