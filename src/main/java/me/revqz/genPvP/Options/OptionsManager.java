package me.revqz.genPvP.Options;

import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.UpdateOptions;
import io.papermc.paper.event.player.AsyncChatEvent;
import me.revqz.genPvP.Database.DatabaseManager;
import me.revqz.genPvP.GenPvP;
import me.revqz.genPvP.Kit.KitManager;
import me.revqz.genPvP.Scoreboard.ScoreboardManager;
import me.revqz.genPvP.util.ColorUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bson.Document;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static com.mongodb.client.model.Filters.eq;

/**
 * Manages the /options GUI and five per-player toggles:
 *
 *  • Global Chat        — hide chat messages sent by other players
 *  • Scoreboard         — show / hide the sidebar scoreboard
 *  • Kit on Death       — auto-receive starter kit on respawn
 *  • Sounds             — mute / unmute all plugin-triggered sounds
 *  • Receive Payments   — accept / reject /pay money from other players
 *
 * Preferences for Chat, Scoreboard, and Sounds are persisted in MongoDB
 * ("player_options" collection). Kit-on-Death persistence is delegated
 * to KitManager (already in kit_cooldowns). Receive-Payments persistence
 * is delegated to BankManager (already in players collection).
 */
public class OptionsManager implements Listener, CommandExecutor {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();

    /** All 36 slots that form the border of a 4-row (36-slot) inventory. */
    private static final Set<Integer> BORDER;
    static {
        Set<Integer> b = new HashSet<>();
        for (int i =  0; i <  9; i++) b.add(i);   // top row
        for (int i = 27; i < 36; i++) b.add(i);   // bottom row
        b.add(9);  b.add(17);                       // row-2 sides
        b.add(18); b.add(26);                       // row-3 sides
        BORDER = Collections.unmodifiableSet(b);
    }

    private static final int SIZE = 36; // 4 rows

    private final GenPvP           plugin;
    private final MongoDatabase    db;
    private final boolean          dbConnected;
    private final KitManager       kitManager;
    private final ScoreboardManager scoreboardManager;
    private me.revqz.genPvP.Bank.BankManager bankManager;

    /** UUIDs of players who have opted out of seeing other players' chat. */
    private final Set<UUID> chatDisabled      = ConcurrentHashMap.newKeySet();
    /** UUIDs of players who have hidden their scoreboard. */
    private final Set<UUID> scoreboardHidden  = ConcurrentHashMap.newKeySet();
    /** UUIDs of players who have muted all plugin sounds. */
    private final Set<UUID> soundsMuted       = ConcurrentHashMap.newKeySet();

    public OptionsManager(GenPvP plugin, DatabaseManager dbManager,
                          KitManager kitManager, ScoreboardManager scoreboardManager) {
        this.plugin            = plugin;
        this.db                = dbManager.isMongoConnected() ? dbManager.getDatabase() : null;
        this.dbConnected       = dbManager.isMongoConnected();
        this.kitManager        = kitManager;
        this.scoreboardManager = scoreboardManager;
    }

    /** Injected after construction so receive-payments can be toggled from the options menu. */
    public void setBankManager(me.revqz.genPvP.Bank.BankManager bankManager) {
        this.bankManager = bankManager;
    }

    // ── /options command ──────────────────────────────────────────────────────

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players can use /options.");
            return true;
        }
        openMenu(player);
        return true;
    }

    // ── Events ────────────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.LOW)
    public void onJoin(PlayerJoinEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        if (!dbConnected) return;
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin,
                () -> loadPrefs(uuid));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        chatDisabled.remove(uuid);
        scoreboardHidden.remove(uuid);
        soundsMuted.remove(uuid);
    }

    /**
     * Filters chat: players who have disabled Global Chat do not receive
     * messages typed by other players. Plugin-sent messages (sendMessage)
     * are unaffected — only this AsyncChatEvent path is filtered.
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onChat(AsyncChatEvent event) {
        if (chatDisabled.isEmpty()) return;
        UUID senderId = event.getPlayer().getUniqueId();
        event.viewers().removeIf(viewer ->
                viewer instanceof Player p
                && !p.getUniqueId().equals(senderId)
                && chatDisabled.contains(p.getUniqueId()));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof OptionsHolder)
            event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof OptionsHolder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        // Only handle clicks on the top (Options) inventory
        if (event.getClickedInventory() == null
                || !event.getClickedInventory().equals(event.getView().getTopInventory())) return;

        int slot = event.getSlot();
        if (BORDER.contains(slot)) return;

        ConfigurationSection cfg = plugin.getConfig().getConfigurationSection("options");
        if (cfg == null) return;

        int chatSlot     = cfg.getInt("global-chat.slot",        11);
        int sbSlot       = cfg.getInt("scoreboard.slot",          13);
        int kitSlot      = cfg.getInt("kit-on-death.slot",        15);
        int soundsSlot   = cfg.getInt("sounds.slot",              20);
        int paymentSlot  = cfg.getInt("receive-payments.slot",    22);

        if (slot == chatSlot) {
            toggleChat(player);
        } else if (slot == sbSlot) {
            toggleScoreboard(player);
        } else if (slot == kitSlot) {
            kitManager.toggleStarterOnDeath(player);
        } else if (slot == soundsSlot) {
            toggleSounds(player);
        } else if (slot == paymentSlot && bankManager != null) {
            bankManager.toggleReceivePayments(player.getUniqueId());
        } else {
            return; // clicked an empty inner slot — no sound
        }

        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.0f);
        refreshMenu(player);
    }

    // ── Menu building ─────────────────────────────────────────────────────────

    public void openMenu(Player player) {
        String titleRaw = plugin.getConfig().getString("options.title", "Settings");
        Inventory inv = Bukkit.createInventory(new OptionsHolder(), SIZE,
                LEGACY.deserialize(ColorUtil.colorize(titleRaw)));

        // Border filler
        String fillerMatName = plugin.getConfig().getString(
                "options.filler-material", "GRAY_STAINED_GLASS_PANE");
        ItemStack filler = buildFiller(parseMat(fillerMatName));
        for (int i : BORDER) inv.setItem(i, filler);

        // Setting items
        ConfigurationSection cfg = plugin.getConfig().getConfigurationSection("options");
        if (cfg != null) {
            UUID uuid = player.getUniqueId();
            placeItem(inv, cfg, "global-chat",       !chatDisabled.contains(uuid),                         11);
            placeItem(inv, cfg, "scoreboard",        !scoreboardHidden.contains(uuid),                     13);
            placeItem(inv, cfg, "kit-on-death",      !kitManager.isStarterOnDeathDisabled(uuid),            15);
            placeItem(inv, cfg, "sounds",            !soundsMuted.contains(uuid),                          20);
            if (bankManager != null)
                placeItem(inv, cfg, "receive-payments",
                        !bankManager.isReceivePaymentsDisabled(uuid),                                      22);
        }

        player.openInventory(inv);
    }

    /**
     * Updates only the three setting items in the already-open menu.
     * Call after every toggle so the icon reflects the new state instantly.
     */
    private void refreshMenu(Player player) {
        if (!(player.getOpenInventory().getTopInventory().getHolder() instanceof OptionsHolder)) return;
        Inventory inv = player.getOpenInventory().getTopInventory();

        ConfigurationSection cfg = plugin.getConfig().getConfigurationSection("options");
        if (cfg == null) return;

        UUID uuid = player.getUniqueId();
        placeItem(inv, cfg, "global-chat",       !chatDisabled.contains(uuid),                         11);
        placeItem(inv, cfg, "scoreboard",        !scoreboardHidden.contains(uuid),                     13);
        placeItem(inv, cfg, "kit-on-death",      !kitManager.isStarterOnDeathDisabled(uuid),            15);
        placeItem(inv, cfg, "sounds",            !soundsMuted.contains(uuid),                          20);
        if (bankManager != null)
            placeItem(inv, cfg, "receive-payments",
                    !bankManager.isReceivePaymentsDisabled(uuid),                                      22);
    }

    private void placeItem(Inventory inv, ConfigurationSection cfg,
                           String key, boolean enabled, int defaultSlot) {
        ConfigurationSection sec = cfg.getConfigurationSection(key);
        if (sec == null) return;

        int slot = sec.getInt("slot", defaultSlot);
        if (slot < 0 || slot >= SIZE || BORDER.contains(slot)) return;

        String prefix = enabled ? "enabled-" : "disabled-";
        Material mat   = parseMat(sec.getString(prefix + "material", enabled ? "LIME_DYE" : "RED_DYE"));
        String   name  = sec.getString(prefix + "name",  enabled ? "&aEnabled" : "&cDisabled");
        List<String> lore = sec.getStringList(prefix + "lore");

        inv.setItem(slot, buildItem(mat, name, lore));
    }

    // ── Toggles ───────────────────────────────────────────────────────────────

    private void toggleChat(Player player) {
        UUID uuid = player.getUniqueId();
        boolean nowDisabled;
        if (chatDisabled.contains(uuid)) {
            chatDisabled.remove(uuid);
            nowDisabled = false;
        } else {
            chatDisabled.add(uuid);
            nowDisabled = true;
        }
        asyncSave(uuid, "chat_disabled", nowDisabled);
    }

    private void toggleScoreboard(Player player) {
        UUID uuid = player.getUniqueId();
        boolean nowHidden;
        if (scoreboardHidden.contains(uuid)) {
            scoreboardHidden.remove(uuid);
            scoreboardManager.showBoard(player);
            nowHidden = false;
        } else {
            scoreboardHidden.add(uuid);
            scoreboardManager.hideBoard(player);
            nowHidden = true;
        }
        asyncSave(uuid, "scoreboard_disabled", nowHidden);
    }

    private void toggleSounds(Player player) {
        UUID uuid = player.getUniqueId();
        boolean nowMuted;
        if (soundsMuted.contains(uuid)) {
            soundsMuted.remove(uuid);
            nowMuted = false;
        } else {
            soundsMuted.add(uuid);
            nowMuted = true;
        }
        asyncSave(uuid, "sounds_muted", nowMuted);
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Returns {@code true} if the player has opted to mute all plugin sounds.
     * Call this before every {@code player.playSound()} throughout the plugin:
     * <pre>{@code
     *   if (!optionsManager.isSoundMuted(player.getUniqueId()))
     *       player.playSound(player.getLocation(), sound, vol, pitch);
     * }</pre>
     */
    public boolean isSoundMuted(UUID uuid) {
        return soundsMuted.contains(uuid);
    }

    // ── Persistence ───────────────────────────────────────────────────────────

    private void loadPrefs(UUID uuid) {
        try {
            Document doc = db.getCollection("player_options")
                    .find(eq("_id", uuid.toString())).first();
            if (doc == null) return;

            if (doc.getBoolean("chat_disabled", false)) {
                chatDisabled.add(uuid);
                // No visual change needed — filter kicks in at chat time
            }

            if (doc.getBoolean("scoreboard_disabled", false)) {
                scoreboardHidden.add(uuid);
                // Hide on main thread (scoreboard was already set up by ScoreboardManager.onJoin)
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    Player player = Bukkit.getPlayer(uuid);
                    if (player != null) scoreboardManager.hideBoard(player);
                });
            }

            if (doc.getBoolean("sounds_muted", false)) {
                soundsMuted.add(uuid);
                // No immediate side-effect needed — checked at each playSound call site
            }
        } catch (Exception e) {
            plugin.getLogger().warning(
                    "[OptionsManager] Failed to load prefs for " + uuid + ": " + e.getMessage());
        }
    }

    private void asyncSave(UUID uuid, String field, boolean value) {
        if (!dbConnected) return;
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                db.getCollection("player_options").updateOne(
                        eq("_id", uuid.toString()),
                        new Document("$set", new Document(field, value)),
                        new UpdateOptions().upsert(true));
            } catch (Exception e) {
                plugin.getLogger().warning(
                        "[OptionsManager] Failed to save pref '" + field + "': " + e.getMessage());
            }
        });
    }

    // ── Item builders ─────────────────────────────────────────────────────────

    private ItemStack buildFiller(Material mat) {
        ItemStack stack = new ItemStack(mat);
        ItemMeta  meta  = stack.getItemMeta();
        if (meta != null) {
            // "&r" resets all formatting — displays as an empty/blank name
            meta.displayName(noItalic(LEGACY.deserialize(ColorUtil.colorize("&r"))));
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS, ItemFlag.HIDE_ATTRIBUTES,
                              ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
            stack.setItemMeta(meta);
        }
        return stack;
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

    // ── Utilities ─────────────────────────────────────────────────────────────

    private static Material parseMat(String name) {
        if (name == null) return Material.STONE;
        Material m = Material.matchMaterial(name);
        return m != null ? m : Material.STONE;
    }

    private static Component noItalic(Component c) {
        return c.decoration(TextDecoration.ITALIC, false);
    }
}
