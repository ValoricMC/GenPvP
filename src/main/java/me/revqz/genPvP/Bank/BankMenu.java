package me.revqz.genPvP.Bank;

import me.revqz.genPvP.GenPvP;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.io.File;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class BankMenu implements Listener {

    /** Handles & codes and &#RRGGBB hex directly — no ColorUtil pre-processing needed. */
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.builder()
            .character('&').hexColors().build();

    private final GenPvP            plugin;
    private final BankManager       bankManager;
    private final MoneyShardManager shardManager;

    private final Set<UUID>        openMenus    = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Long>  lastClickMs  = new ConcurrentHashMap<>();
    private static final long      CLICK_COOLDOWN_MS = 200;

    // ── Config ────────────────────────────────────────────────────────────────
    private FileConfiguration cfg;

    private int rows;
    private int slotInfo;
    private int slotD1, slotD2, slotD3, slotDAll;
    private int slotW1, slotW2, slotW3, slotWAll;
    private int amtD1, amtD2, amtD3;
    private int amtW1, amtW2, amtW3;
    private boolean autoFill;
    private List<Integer> fillerSlots;

    // ── Init ──────────────────────────────────────────────────────────────────

    public BankMenu(GenPvP plugin, BankManager bankManager, MoneyShardManager shardManager) {
        this.plugin       = plugin;
        this.bankManager  = bankManager;
        this.shardManager = shardManager;
        loadConfig();
    }

    public void reload() { loadConfig(); }

    private void loadConfig() {
        File file = new File(plugin.getDataFolder(), "bank.yml");
        if (!file.exists()) plugin.saveResource("bank.yml", false);
        cfg = YamlConfiguration.loadConfiguration(file);

        rows     = Math.max(1, Math.min(6, cfg.getInt("gui.rows", 4)));
        slotInfo = cfg.getInt("info.slot", 13);

        slotD1   = cfg.getInt("info.deposit-1.slot",    12);
        slotD2   = cfg.getInt("info.deposit-2.slot",    11);
        slotD3   = cfg.getInt("info.deposit-3.slot",    10);
        slotDAll = cfg.getInt("info.deposit-all.slot",  21);

        slotW1   = cfg.getInt("info.withdraw-1.slot",   14);
        slotW2   = cfg.getInt("info.withdraw-2.slot",   15);
        slotW3   = cfg.getInt("info.withdraw-3.slot",   16);
        slotWAll = cfg.getInt("info.withdraw-all.slot", 23);

        amtD1 = cfg.getInt("info.deposit-1.amount",   1);
        amtD2 = cfg.getInt("info.deposit-2.amount",   8);
        amtD3 = cfg.getInt("info.deposit-3.amount",  32);
        amtW1 = cfg.getInt("info.withdraw-1.amount",  8);
        amtW2 = cfg.getInt("info.withdraw-2.amount", 16);
        amtW3 = cfg.getInt("info.withdraw-3.amount", 32);

        autoFill    = cfg.getBoolean("gui.filler.auto-fill", false);
        fillerSlots = new ArrayList<>();
        for (Object o : cfg.getList("gui.filler.slots", List.of())) {
            if (o instanceof Number n) fillerSlots.add(n.intValue());
        }
    }

    // ── Open ──────────────────────────────────────────────────────────────────

    public void open(Player player) {
        if (!bankManager.isLoaded(player.getUniqueId())) {
            player.sendMessage(LEGACY.deserialize(msg("messages.loading", "&7Loading your bank data...")));
            return;
        }
        if (bankManager.isDisabled(player.getUniqueId())) {
            player.sendMessage(LEGACY.deserialize(msg("messages.account-disabled", "&cYour bank account is disabled.")));
            return;
        }
        openMenus.add(player.getUniqueId());
        player.openInventory(buildInventory(player));
    }

    // ── Build inventory ───────────────────────────────────────────────────────

    private Inventory buildInventory(Player player) {
        int  size  = rows * 9;
        UUID uuid  = player.getUniqueId();
        Inventory inv = Bukkit.createInventory(null, size, LEGACY.deserialize(cfg.getString("gui.title", "&8Bank")));

        // Functional items first so auto-fill knows which slots are taken
        if (slotInfo >= 0 && slotInfo < size) inv.setItem(slotInfo, buildInfoHead(player, uuid));

        setButton(inv, slotD1,   "info.deposit-1",   amtD1, size);
        setButton(inv, slotD2,   "info.deposit-2",   amtD2, size);
        setButton(inv, slotD3,   "info.deposit-3",   amtD3, size);
        setButton(inv, slotDAll, "info.deposit-all",  -1,  size);
        setButton(inv, slotW1,   "info.withdraw-1",  amtW1, size);
        setButton(inv, slotW2,   "info.withdraw-2",  amtW2, size);
        setButton(inv, slotW3,   "info.withdraw-3",  amtW3, size);
        setButton(inv, slotWAll, "info.withdraw-all", -1,  size);

        // Fillers
        Material fillerMat  = parseMat(cfg.getString("gui.filler.material", "GRAY_STAINED_GLASS_PANE"));
        String   fillerName = cfg.getString("gui.filler.name", "&r");
        ItemStack filler    = buildFiller(fillerMat, fillerName);

        if (autoFill) {
            for (int i = 0; i < size; i++) {
                if (inv.getItem(i) == null) inv.setItem(i, filler);
            }
        } else {
            for (int slot : fillerSlots) {
                if (slot >= 0 && slot < size) inv.setItem(slot, filler);
            }
        }

        return inv;
    }

    private void setButton(Inventory inv, int slot, String cfgPath, int amount, int size) {
        if (slot >= 0 && slot < size) inv.setItem(slot, buildButton(cfgPath, amount));
    }

    // ── Events ────────────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.HIGH)
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (!openMenus.contains(player.getUniqueId())) return;
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        UUID uuid = player.getUniqueId();
        if (!openMenus.contains(uuid)) return;

        event.setCancelled(true);
        if (event.getClickedInventory() == null
                || !event.getClickedInventory().equals(event.getView().getTopInventory())) return;

        if (!bankManager.isLoaded(uuid)) {
            player.sendMessage(LEGACY.deserialize(msg("messages.loading", "&7Still loading...")));
            return;
        }

        if (bankManager.isDisabled(uuid)) {
            player.sendMessage(LEGACY.deserialize(msg("messages.account-disabled", "&cYour bank account is disabled.")));
            return;
        }

        long now = System.currentTimeMillis();
        Long last = lastClickMs.put(uuid, now);
        if (last != null && now - last < CLICK_COOLDOWN_MS) return;

        int slot = event.getSlot();
        if      (slot == slotD1)   handleDeposit(player, uuid, amtD1);
        else if (slot == slotD2)   handleDeposit(player, uuid, amtD2);
        else if (slot == slotD3)   handleDeposit(player, uuid, amtD3);
        else if (slot == slotDAll) handleDepositAll(player, uuid);
        else if (slot == slotW1)   handleWithdraw(player, uuid, amtW1);
        else if (slot == slotW2)   handleWithdraw(player, uuid, amtW2);
        else if (slot == slotW3)   handleWithdraw(player, uuid, amtW3);
        else if (slot == slotWAll) handleWithdrawAll(player, uuid);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        openMenus.remove(uuid);
        lastClickMs.remove(uuid);
    }

    // ── Actions ───────────────────────────────────────────────────────────────

    private void handleDeposit(Player player, UUID uuid, int amount) {
        int held = shardManager.countShards(player);
        if (held < amount) {
            player.sendMessage(LEGACY.deserialize(
                    msg("messages.deposit-not-enough", "&cNot enough Money Shards.")
                    .replace("%have%", String.valueOf(held))
                    .replace("%need%", String.valueOf(amount))));
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.6f, 0.9f);
            return;
        }
        shardManager.removeShards(player, amount);
        double gained = amount * shardManager.getValuePerShard();
        if (!bankManager.addBalance(uuid, player.getName(), gained)) {
            shardManager.giveShards(player, amount);
            player.sendMessage(LEGACY.deserialize(msg("messages.account-disabled", "&cYour bank account is disabled.")));
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.6f, 0.9f);
            return;
        }

        player.sendMessage(LEGACY.deserialize(
                msg("messages.deposit-success", "&#A7A7A7Deposited &#00A4FB%amount% &#A7A7A7shard(s).")
                .replace("%amount%", String.valueOf(amount))
                .replace("%value%", BankManager.formatBalance(gained))));
        player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.6f, 1.3f);
        refreshInfo(player, uuid);
    }

    private void handleDepositAll(Player player, UUID uuid) {
        int held = shardManager.countShards(player);
        if (held == 0) {
            player.sendMessage(LEGACY.deserialize(msg("messages.deposit-all-empty", "&cNo Money Shards to deposit.")));
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.6f, 0.9f);
            return;
        }
        shardManager.removeShards(player, held);
        double gained = held * shardManager.getValuePerShard();
        if (!bankManager.addBalance(uuid, player.getName(), gained)) {
            shardManager.giveShards(player, held);
            player.sendMessage(LEGACY.deserialize(msg("messages.account-disabled", "&cYour bank account is disabled.")));
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.6f, 0.9f);
            return;
        }

        player.sendMessage(LEGACY.deserialize(
                msg("messages.deposit-success", "&#A7A7A7Deposited &#00A4FB%amount% &#A7A7A7shard(s).")
                .replace("%amount%", String.valueOf(held))
                .replace("%value%", BankManager.formatBalance(gained))));
        player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.6f, 1.3f);
        refreshInfo(player, uuid);
    }

    private void handleWithdraw(Player player, UUID uuid, int amount) {
        double cost    = amount * shardManager.getValuePerShard();
        double balance = bankManager.getBalance(uuid);

        if (balance < cost) {
            player.sendMessage(LEGACY.deserialize(
                    msg("messages.withdraw-not-enough-funds", "&cInsufficient funds.")
                    .replace("%need%",    BankManager.formatBalance(cost))
                    .replace("%balance%", BankManager.formatBalance(balance))));
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.6f, 0.9f);
            return;
        }

        int capacity = shardManager.availableCapacity(player);
        if (capacity < amount) {
            player.sendMessage(LEGACY.deserialize(
                    msg("messages.withdraw-no-space", "&cNot enough inventory space.")
                    .replace("%amount%", String.valueOf(amount))));
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.6f, 0.9f);
            return;
        }

        if (!bankManager.removeBalance(uuid, player.getName(), cost)) {
            player.sendMessage(LEGACY.deserialize(
                    msg("messages.withdraw-not-enough-funds", "&cInsufficient funds.")
                    .replace("%need%",    BankManager.formatBalance(cost))
                    .replace("%balance%", BankManager.formatBalance(bankManager.getBalance(uuid)))));
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.6f, 0.9f);
            return;
        }
        shardManager.giveShards(player, amount);

        player.sendMessage(LEGACY.deserialize(
                msg("messages.withdraw-success", "&#A7A7A7Withdrew &#00A4FB%amount% &#A7A7A7shard(s).")
                .replace("%amount%", String.valueOf(amount))
                .replace("%value%", BankManager.formatBalance(cost))));
        player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.6f, 1.0f);
        refreshInfo(player, uuid);
    }

    private void handleWithdrawAll(Player player, UUID uuid) {
        int capacity = shardManager.availableCapacity(player);
        if (capacity == 0) {
            player.sendMessage(LEGACY.deserialize(msg("messages.withdraw-all-full", "&cInventory is full.")));
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.6f, 0.9f);
            return;
        }

        double balance = bankManager.getBalance(uuid);
        if (balance <= 0) {
            player.sendMessage(LEGACY.deserialize(msg("messages.withdraw-all-no-funds", "&cNo funds to withdraw.")));
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.6f, 0.9f);
            return;
        }

        double valuePerShard  = shardManager.getValuePerShard();
        long   maxByBalanceLong = (long) Math.floor(balance / valuePerShard);
        int    maxByBalance     = (int) Math.min(maxByBalanceLong, Integer.MAX_VALUE);
        int    toWithdraw       = Math.min(capacity, maxByBalance);

        if (toWithdraw <= 0) {
            player.sendMessage(LEGACY.deserialize(
                    msg("messages.withdraw-not-enough-funds", "&cInsufficient funds.")
                    .replace("%need%",    BankManager.formatBalance(valuePerShard))
                    .replace("%balance%", BankManager.formatBalance(balance))));
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.6f, 0.9f);
            return;
        }

        double cost = toWithdraw * valuePerShard;
        if (!bankManager.removeBalance(uuid, player.getName(), cost)) {
            player.sendMessage(LEGACY.deserialize(
                    msg("messages.withdraw-not-enough-funds", "&cInsufficient funds.")
                    .replace("%need%",    BankManager.formatBalance(cost))
                    .replace("%balance%", BankManager.formatBalance(bankManager.getBalance(uuid)))));
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.6f, 0.9f);
            return;
        }
        shardManager.giveShards(player, toWithdraw);

        player.sendMessage(LEGACY.deserialize(
                msg("messages.withdraw-success", "&#A7A7A7Withdrew &#00A4FB%amount% &#A7A7A7shard(s).")
                .replace("%amount%", String.valueOf(toWithdraw))
                .replace("%value%", BankManager.formatBalance(cost))));
        player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.6f, 1.0f);
        refreshInfo(player, uuid);
    }

    // ── Refresh ───────────────────────────────────────────────────────────────

    /** Updates only the player-head info slot after a transaction. */
    private void refreshInfo(Player player, UUID uuid) {
        Inventory top  = player.getOpenInventory().getTopInventory();
        int       size = rows * 9;
        if (slotInfo >= 0 && slotInfo < size) {
            top.setItem(slotInfo, buildInfoHead(player, uuid));
        }
    }

    // ── Item builders ─────────────────────────────────────────────────────────

    private ItemStack buildInfoHead(Player player, UUID uuid) {
        ItemStack skull = new ItemStack(Material.PLAYER_HEAD);
        if (!(skull.getItemMeta() instanceof SkullMeta meta)) return skull;

        meta.setOwningPlayer(player);

        double balance = bankManager.getBalance(uuid);
        int    shards  = shardManager.countShards(player);

        String rawName = cfg.getString("info.name", "&f%player%")
                .replace("%player%",  player.getName())
                .replace("%balance%", BankManager.formatBalance(balance))
                .replace("%shards%",  String.valueOf(shards));
        meta.displayName(noItalic(LEGACY.deserialize(rawName)));

        List<Component> loreComp = new ArrayList<>();
        for (String line : cfg.getStringList("info.lore")) {
            loreComp.add(noItalic(LEGACY.deserialize(
                    line.replace("%player%",  player.getName())
                        .replace("%balance%", BankManager.formatBalance(balance))
                        .replace("%shards%",  String.valueOf(shards)))));
        }
        if (!loreComp.isEmpty()) meta.lore(loreComp);

        meta.addItemFlags(ItemFlag.HIDE_ENCHANTS, ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
        skull.setItemMeta(meta);
        return skull;
    }

    /**
     * Builds a deposit or withdraw button from bank.yml.
     * @param amount  the configured shard amount for this button; -1 = "all" buttons (no fixed amount)
     */
    private ItemStack buildButton(String cfgPath, int amount) {
        Material  mat   = parseMat(cfg.getString(cfgPath + ".material", "STONE"));
        ItemStack stack = new ItemStack(mat);
        ItemMeta  meta  = stack.getItemMeta();
        if (meta == null) return stack;

        String amtStr  = amount < 0 ? "all" : String.valueOf(amount);
        String rawName = cfg.getString(cfgPath + ".name", "&f" + cfgPath);
        meta.displayName(noItalic(LEGACY.deserialize(rawName.replace("%amount%", amtStr))));

        List<Component> loreComp = new ArrayList<>();
        for (String line : cfg.getStringList(cfgPath + ".lore")) {
            loreComp.add(noItalic(LEGACY.deserialize(line.replace("%amount%", amtStr))));
        }
        if (!loreComp.isEmpty()) meta.lore(loreComp);

        meta.addItemFlags(ItemFlag.HIDE_ENCHANTS, ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
        stack.setItemMeta(meta);
        return stack;
    }

    private ItemStack buildFiller(Material mat, String name) {
        ItemStack stack = new ItemStack(mat);
        ItemMeta  meta  = stack.getItemMeta();
        if (meta == null) return stack;
        if (name.isBlank() || name.strip().equals("&r")) {
            meta.setHideTooltip(true);
        } else {
            meta.displayName(noItalic(LEGACY.deserialize(name)));
        }
        meta.addItemFlags(ItemFlag.HIDE_ENCHANTS, ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
        stack.setItemMeta(meta);
        return stack;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String msg(String path, String fallback) {
        return cfg.isString(path) ? cfg.getString(path) : fallback;
    }

    private static Material parseMat(String name) {
        if (name == null) return Material.STONE;
        Material m = Material.matchMaterial(name.toUpperCase());
        return m != null ? m : Material.STONE;
    }

    private static Component noItalic(Component c) {
        return c.decoration(TextDecoration.ITALIC, false);
    }
}
