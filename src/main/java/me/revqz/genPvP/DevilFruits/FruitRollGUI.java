package me.revqz.genPvP.DevilFruits;

import me.revqz.genPvP.Bank.BankManager;
import me.revqz.genPvP.GenPvP;
import me.revqz.genPvP.util.ColorUtil;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Slot-machine style rolling GUI for Devil Fruits.
 *
 * <p>Layout (27 slots, 3 rows):
 * <pre>
 *   Row 1 (0-8):   Gray Stained Glass Pane (border)
 *   Row 2 (9-17):  Conveyor belt — 9 fruit items cycling
 *   Row 3 (18-26): Gray Stained Glass Pane, slot 22 = Green Candle ("Your roll:")
 * </pre>
 *
 * <p>The final result is pre-determined before the animation starts.
 * Owned fruits appear in the animation but cannot be the final result
 * (unless all fruits of that type are owned → money prize instead).
 */
public class FruitRollGUI implements Listener {

    private final GenPvP plugin;
    private final DevilFruitManager fruitManager;
    private final FruitRollManager rollManager;
    private final FruitGUIManager guiManager;
    private final BankManager bankManager;

    /** Players currently in an active rolling animation. */
    private final Set<UUID> activeRollers = ConcurrentHashMap.newKeySet();
    /** Active animation tasks per player — cancelled on close/quit. */
    private final Map<UUID, BukkitTask> animationTasks = new ConcurrentHashMap<>();

    // ── Speed schedule: tick delay for each shift ─────────────────────────────
    // ~7 seconds total: starts fast, decelerates to dramatic finale
    private static final int[] SHIFT_DELAYS = {
        2, 2, 2, 2, 2, 2, 2,   // shifts 0-6:   fast  (700ms)
        3, 3, 3, 3, 3,         // shifts 7-11:  medium (750ms)
        4, 4, 4, 4,            // shifts 12-15: slower (800ms)
        5, 5, 5,               // shifts 16-18: slow (750ms)
        6, 6,                  // shifts 19-20: slower (600ms)
        8, 8,                  // shifts 21-22: very slow (800ms)
        10, 10,                // shifts 23-24: crawling (1000ms)
        12,                    // shift 25:    dramatic (600ms)
        14,                    // shift 26:    dramatic (700ms)
        16,                    // shift 27:    near stop (800ms)
        20                     // shift 28:    final (1000ms)
    };
    private static final int TOTAL_SHIFTS = SHIFT_DELAYS.length;

    public FruitRollGUI(GenPvP plugin, DevilFruitManager fruitManager,
                        FruitRollManager rollManager, FruitGUIManager guiManager,
                        BankManager bankManager) {
        this.plugin       = plugin;
        this.fruitManager = fruitManager;
        this.rollManager  = rollManager;
        this.guiManager   = guiManager;
        this.bankManager  = bankManager;
    }

    // ── Custom holder ─────────────────────────────────────────────────────────

    public static class RollHolder implements InventoryHolder {
        private final FruitType type;
        private final UUID playerId;
        private Inventory inventory;
        private DevilFruit winner;
        private boolean allOwned;

        RollHolder(FruitType type, UUID playerId) {
            this.type     = type;
            this.playerId = playerId;
        }

        public FruitType getType()      { return type; }
        public UUID getPlayerId()       { return playerId; }
        public DevilFruit getWinner()   { return winner; }
        public boolean isAllOwned()     { return allOwned; }
        @Override public Inventory getInventory() { return inventory; }
        void setInventory(Inventory inv) { this.inventory = inv; }
        void setResult(DevilFruit winner, boolean allOwned) {
            this.winner   = winner;
            this.allOwned = allOwned;
        }
    }

    // ── Public entry point ────────────────────────────────────────────────────

    /**
     * Opens the rolling GUI and starts the animation.
     * Caller must have already validated rolls are available.
     *
     * @return true if the roll started, false if blocked (already rolling, no fruits, etc.)
     */
    public boolean startRoll(Player player, FruitType type) {
        UUID uuid = player.getUniqueId();

        // Guard: already rolling
        if (activeRollers.contains(uuid)) return false;

        // Build eligible fruit pool: intersection of enum + YML
        List<DevilFruit> pool = buildEligiblePool(type);
        if (pool.isEmpty()) return false;

        // Determine winnability
        Set<String> owned = fruitManager.getOwnedFruits(uuid);
        List<DevilFruit> winnable = pool.stream()
                .filter(f -> !owned.contains(f.getKey()))
                .toList();
        boolean allOwned = winnable.isEmpty();

        // Pre-determine result BEFORE animation
        DevilFruit winner;
        if (allOwned) {
            // Player owns all — they'll get a money prize. Pick a random fruit for visual.
            winner = pool.get(ThreadLocalRandom.current().nextInt(pool.size()));
        } else {
            winner = winnable.get(ThreadLocalRandom.current().nextInt(winnable.size()));
        }

        // Consume the roll token
        if (!rollManager.consumeRoll(uuid, type)) return false;

        // Mark as rolling
        activeRollers.add(uuid);

        // Build GUI
        YamlConfiguration messagesConfig = guiManager.getMessagesConfig();
        String title = messagesConfig.getString("roll-title", "Fruit Roll: %type%")
                        .replace("%type%", type.getDisplayName());

        RollHolder holder = new RollHolder(type, uuid);
        holder.setResult(winner, allOwned);
        Inventory inv = Bukkit.createInventory(holder, 27, title);
        holder.setInventory(inv);

        // Row 1 + Row 3: gray glass
        ItemStack glass = buildGlass();
        for (int i = 0; i <= 8; i++)   inv.setItem(i, glass);
        for (int i = 18; i <= 26; i++) inv.setItem(i, glass);

        // Slot 22: green candle ("Your roll:")
        inv.setItem(22, buildCandle(messagesConfig, null));

        // Row 2: initial random fruits
        for (int i = 9; i <= 17; i++) {
            inv.setItem(i, buildFruitDisplayItem(randomFromPool(pool), type));
        }

        player.openInventory(inv);

        // Start animation
        startAnimation(player, inv, pool, winner, allOwned, type);
        return true;
    }

    public boolean isRolling(UUID uuid) {
        return activeRollers.contains(uuid);
    }

    // ── Animation engine ──────────────────────────────────────────────────────

    private void startAnimation(Player player, Inventory inv, List<DevilFruit> pool,
                                DevilFruit winner, boolean allOwned, FruitType type) {
        UUID uuid = player.getUniqueId();

        // Schedule the chain of shifts
        scheduleShift(player, inv, pool, winner, allOwned, type, 0, 0);
    }

    /**
     * Recursively schedules each shift of the conveyor belt.
     * On the final shift, the winner is placed in slot 13 (center of row 2).
     */
    private void scheduleShift(Player player, Inventory inv, List<DevilFruit> pool,
                               DevilFruit winner, boolean allOwned, FruitType type,
                               int shiftIndex, long accumulatedDelay) {
        UUID uuid = player.getUniqueId();

        if (shiftIndex >= TOTAL_SHIFTS) {
            // Animation complete — reveal after a short pause
            BukkitTask revealTask = plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                animationTasks.remove(uuid);
                onReveal(player, inv, winner, allOwned, type);
            }, 20L); // 1 second pause before reveal
            animationTasks.put(uuid, revealTask);
            return;
        }

        int delay = shiftIndex == 0 ? 5 : SHIFT_DELAYS[shiftIndex]; // initial 5-tick delay

        BukkitTask task = plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline() || !activeRollers.contains(uuid)) {
                cleanup(uuid);
                return;
            }

            // Shift items left: slot 9 ← 10, 10 ← 11, ... , 16 ← 17
            for (int i = 9; i < 17; i++) {
                inv.setItem(i, inv.getItem(i + 1));
            }

            // New item at slot 17 (right edge)
            boolean isFinalShift = (shiftIndex >= TOTAL_SHIFTS - 4);
            // On the last 4 shifts, we engineer the winner to land at slot 13 (center)
            // Final shift (TOTAL_SHIFTS-1): winner goes into slot 17, which after 4 more
            // shifts would land at slot 13. But since we're already shifting, we need to
            // pre-position the winner.
            DevilFruit incoming;
            if (shiftIndex == TOTAL_SHIFTS - 1) {
                // This is the LAST shift. The item at slot 17 will be the one
                // the player just saw slide in. We need the winner at slot 13.
                // Since we already shifted, slot 13 is now what was slot 14.
                // We need to directly place the winner at slot 13 after this shift.
                incoming = randomFromPool(pool);
            } else {
                incoming = randomFromPool(pool);
            }
            inv.setItem(17, buildFruitDisplayItem(incoming, type));

            // On the final shift, override slot 13 with the winner
            if (shiftIndex == TOTAL_SHIFTS - 1) {
                inv.setItem(13, buildFruitDisplayItem(winner, type));
            }

            // Sound: rising pitch click — loud
            float pitch = 1.0f + (shiftIndex / (float) TOTAL_SHIFTS) * 1.0f;
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 1.0f, pitch);

            // Schedule next shift
            scheduleShift(player, inv, pool, winner, allOwned, type, shiftIndex + 1, 0);
        }, delay);

        animationTasks.put(uuid, task);
    }

    /**
     * Called when the animation finishes. Awards the fruit or money prize.
     */
    private void onReveal(Player player, Inventory inv, DevilFruit winner,
                          boolean allOwned, FruitType type) {
        UUID uuid = player.getUniqueId();
        YamlConfiguration messagesConfig = guiManager.getMessagesConfig();

        if (!player.isOnline()) {
            cleanup(uuid);
            return;
        }

        // Final bell sound — loud
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 1.5f, 1.5f);

        // Happy surprised sound
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline()) {
                player.playSound(player.getLocation(), Sound.ENTITY_FIREWORK_ROCKET_BLAST, 1.5f, 1.2f);
                player.playSound(player.getLocation(), Sound.ENTITY_FIREWORK_ROCKET_TWINKLE, 1.2f, 1.0f);
            }
        }, 5L);

        if (allOwned) {
            // All fruits owned → money prize of 500 tokens
            bankManager.addBalance(uuid, player.getName(), 500);

            // Update candle to show money prize (configurable)
            String candleName = ColorUtil.colorize(
                    messagesConfig.getString("roll-candle-prize", "&#FCD05C+500 Tokens"));
            inv.setItem(22, buildCandleCustom(candleName));

            // Replace center slot with gold nugget indicating prize (configurable)
            ItemStack prizeItem = new ItemStack(Material.GOLD_NUGGET);
            ItemMeta prizeMeta = prizeItem.getItemMeta();
            if (prizeMeta != null) {
                prizeMeta.setDisplayName(ColorUtil.colorize(
                        messagesConfig.getString("roll-prize-name", "&#FCD05C&l+500 Tokens")));
                List<String> rawPrizeLore = messagesConfig.getStringList("roll-prize-lore");
                if (rawPrizeLore.isEmpty()) {
                    rawPrizeLore = List.of("", "&#FFDE87You own all %type% fruits!", "&#7AFB00500 tokens added to your bank.");
                }
                List<String> prizeLore = new ArrayList<>();
                for (String line : rawPrizeLore) {
                    prizeLore.add(ColorUtil.colorize(line.replace("%type%", type.getDisplayName())));
                }
                prizeMeta.setLore(prizeLore);
                prizeItem.setItemMeta(prizeMeta);
            }
            inv.setItem(13, prizeItem);

            player.sendMessage(ColorUtil.colorize(
                    messagesConfig.getString("roll-all-owned-prize", "&#FCD05CYou already own all %type% fruits! &#7AFB00+500 tokens awarded.")
                            .replace("%type%", type.getDisplayName())));

            // Level up sound — loud
            player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.5f, 1.0f);
        } else {
            // Award the fruit
            fruitManager.giveFruit(uuid, winner);

            // Auto-equip if nothing equipped
            if (fruitManager.getEquippedFruit(uuid) == null) {
                fruitManager.setEquipped(uuid, winner.getKey());
            }

            // Update candle
            inv.setItem(22, buildCandle(messagesConfig, winner));

            // Level up sound — loud
            player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.5f, 1.0f);

            player.sendMessage(ColorUtil.colorize(
                    messagesConfig.getString("roll-won", "&#7AFB00You won &f%fruit%&#7AFB00!")
                            .replace("%fruit%", winner.getDisplayName())));
        }

        // Mark as no longer animating
        activeRollers.remove(uuid);

        // Auto-close GUI after 3 seconds (60 ticks)
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline()) {
                player.closeInventory();
            }
        }, 60L);
    }

    // ── Event handlers ────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.HIGH)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;
        if (event.getInventory().getHolder() instanceof RollHolder) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;
        if (event.getInventory().getHolder() instanceof RollHolder) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        if (!(event.getInventory().getHolder() instanceof RollHolder holder)) return;

        UUID uuid = player.getUniqueId();

        if (activeRollers.contains(uuid)) {
            BukkitTask task = animationTasks.remove(uuid);
            if (task != null) task.cancel();
            activeRollers.remove(uuid);
            awardResult(player, holder.getWinner(), holder.isAllOwned(), holder.getType());
        }
    }

    // ── Item builders ─────────────────────────────────────────────────────────

    private ItemStack buildGlass() {
        ItemStack glass = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = glass.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(org.bukkit.ChatColor.RESET + "");
            glass.setItemMeta(meta);
        }
        return glass;
    }

    private ItemStack buildCandle(YamlConfiguration messagesConfig, DevilFruit winner) {
        ItemStack candle = new ItemStack(Material.GREEN_CANDLE);
        ItemMeta meta = candle.getItemMeta();
        if (meta != null) {
            if (winner == null) {
                meta.setDisplayName(ColorUtil.colorize(
                        messagesConfig.getString("roll-candle-name", "&#99F5C1Your roll:")));
            } else {
                meta.setDisplayName(ColorUtil.colorize(
                        messagesConfig.getString("roll-candle-result", "&#99F5C1Your roll: %fruit%")
                                .replace("%fruit%", winner.getDisplayName())));
            }
            candle.setItemMeta(meta);
        }
        return candle;
    }

    private ItemStack buildCandleCustom(String displayName) {
        ItemStack candle = new ItemStack(Material.GREEN_CANDLE);
        ItemMeta meta = candle.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(displayName);
            candle.setItemMeta(meta);
        }
        return candle;
    }

    /**
     * Builds a display item for a fruit using its material from the type YML
     * and the devil-equipped-name / devil-equipped-lore fields.
     */
    private ItemStack buildFruitDisplayItem(DevilFruit fruit, FruitType type) {
        YamlConfiguration typeConfig = guiManager.getTypeConfig(type);
        if (typeConfig == null) {
            // Fallback
            ItemStack item = new ItemStack(Material.PAPER);
            ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                meta.setDisplayName(fruit.getDisplayName());
                item.setItemMeta(meta);
            }
            return item;
        }

        String path = "fruits." + fruit.getKey() + ".";
        String matName = typeConfig.getString(path + "material", "PAPER");
        String name = typeConfig.getString(path + "devil-equipped-name", fruit.getDisplayName());
        List<String> rawLore = typeConfig.getStringList(path + "devil-equipped-lore");

        Material material = Material.matchMaterial(matName);
        if (material == null) material = Material.PAPER;

        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ColorUtil.colorize(name));
            List<String> lore = new ArrayList<>();
            for (String line : rawLore) lore.add(ColorUtil.colorize(line));
            meta.setLore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    // ── Pool helpers ──────────────────────────────────────────────────────────

    /**
     * Builds the eligible fruit pool: only fruits that exist in BOTH the
     * {@link DevilFruit} enum AND the corresponding type YML file.
     */
    List<DevilFruit> buildEligiblePool(FruitType type) {
        YamlConfiguration typeConfig = guiManager.getTypeConfig(type);
        if (typeConfig == null) return Collections.emptyList();

        ConfigurationSection fruitsSection = typeConfig.getConfigurationSection("fruits");
        if (fruitsSection == null) return Collections.emptyList();

        Set<String> ymlKeys = fruitsSection.getKeys(false);
        List<DevilFruit> pool = new ArrayList<>();
        for (DevilFruit fruit : DevilFruit.values()) {
            if (fruit.getType() == type && ymlKeys.contains(fruit.getKey())) {
                pool.add(fruit);
            }
        }
        return pool;
    }

    private DevilFruit randomFromPool(List<DevilFruit> pool) {
        return pool.get(ThreadLocalRandom.current().nextInt(pool.size()));
    }

    private void awardResult(Player player, DevilFruit winner, boolean allOwned, FruitType type) {
        UUID uuid = player.getUniqueId();
        YamlConfiguration messagesConfig = guiManager.getMessagesConfig();

        if (allOwned) {
            bankManager.addBalance(uuid, player.getName(), 500);
            player.sendMessage(ColorUtil.colorize(
                    messagesConfig.getString("roll-all-owned-prize", "&#FCD05CYou already own all %type% fruits! &#7AFB00+500 tokens awarded.")
                            .replace("%type%", type.getDisplayName())));
        } else if (winner != null) {
            fruitManager.giveFruit(uuid, winner);
            if (fruitManager.getEquippedFruit(uuid) == null) {
                fruitManager.setEquipped(uuid, winner.getKey());
            }
            player.sendMessage(ColorUtil.colorize(
                    messagesConfig.getString("roll-won", "&#7AFB00You won &f%fruit%&#7AFB00!")
                            .replace("%fruit%", winner.getDisplayName())));
        }
    }

    private void cleanup(UUID uuid) {
        activeRollers.remove(uuid);
        BukkitTask task = animationTasks.remove(uuid);
        if (task != null) task.cancel();
    }
}
