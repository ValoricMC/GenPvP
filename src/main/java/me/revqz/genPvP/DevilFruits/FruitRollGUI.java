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

public class FruitRollGUI implements Listener {

    private final GenPvP plugin;
    private final DevilFruitManager fruitManager;
    private final FruitRollManager rollManager;
    private final FruitGUIManager guiManager;
    private final BankManager bankManager;

    private final Set<UUID> activeRollers = ConcurrentHashMap.newKeySet();
    
    private final Map<UUID, BukkitTask> animationTasks = new ConcurrentHashMap<>();

    private static final int[] SHIFT_DELAYS = {
        2, 2, 2, 2, 2, 2, 2,   
        3, 3, 3, 3, 3,         
        4, 4, 4, 4,            
        5, 5, 5,               
        6, 6,                  
        8, 8,                  
        10, 10,                
        12,                    
        14,                    
        16,                    
        20                     
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

    public boolean startRoll(Player player, FruitType type) {
        UUID uuid = player.getUniqueId();

        if (activeRollers.contains(uuid)) return false;

        List<DevilFruit> pool = buildEligiblePool(type);
        if (pool.isEmpty()) return false;

        Set<String> owned = fruitManager.getOwnedFruits(uuid);
        List<DevilFruit> winnable = pool.stream()
                .filter(f -> !owned.contains(f.getKey()))
                .toList();
        boolean allOwned = winnable.isEmpty();

        DevilFruit winner;
        if (allOwned) {
            
            winner = pool.get(ThreadLocalRandom.current().nextInt(pool.size()));
        } else {
            winner = winnable.get(ThreadLocalRandom.current().nextInt(winnable.size()));
        }

        if (!rollManager.consumeRoll(uuid, type)) return false;

        activeRollers.add(uuid);

        YamlConfiguration messagesConfig = guiManager.getMessagesConfig();
        String title = messagesConfig.getString("roll-title", "Fruit Roll: %type%")
                        .replace("%type%", type.getDisplayName());

        RollHolder holder = new RollHolder(type, uuid);
        holder.setResult(winner, allOwned);
        Inventory inv = Bukkit.createInventory(holder, 27, title);
        holder.setInventory(inv);

        ItemStack glass = buildGlass();
        for (int i = 0; i <= 8; i++)   inv.setItem(i, glass);
        for (int i = 18; i <= 26; i++) inv.setItem(i, glass);

        inv.setItem(22, buildCandle(messagesConfig, null));

        for (int i = 9; i <= 17; i++) {
            inv.setItem(i, buildFruitDisplayItem(randomFromPool(pool), type));
        }

        player.openInventory(inv);

        startAnimation(player, inv, pool, winner, allOwned, type);
        return true;
    }

    public boolean isRolling(UUID uuid) {
        return activeRollers.contains(uuid);
    }

    private void startAnimation(Player player, Inventory inv, List<DevilFruit> pool,
                                DevilFruit winner, boolean allOwned, FruitType type) {
        UUID uuid = player.getUniqueId();

        scheduleShift(player, inv, pool, winner, allOwned, type, 0, 0);
    }

    private void scheduleShift(Player player, Inventory inv, List<DevilFruit> pool,
                               DevilFruit winner, boolean allOwned, FruitType type,
                               int shiftIndex, long accumulatedDelay) {
        UUID uuid = player.getUniqueId();

        if (shiftIndex >= TOTAL_SHIFTS) {
            
            BukkitTask revealTask = plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                animationTasks.remove(uuid);
                onReveal(player, inv, winner, allOwned, type);
            }, 20L); 
            animationTasks.put(uuid, revealTask);
            return;
        }

        int delay = shiftIndex == 0 ? 5 : SHIFT_DELAYS[shiftIndex]; 

        BukkitTask task = plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline() || !activeRollers.contains(uuid)) {
                cleanup(uuid);
                return;
            }

            for (int i = 9; i < 17; i++) {
                inv.setItem(i, inv.getItem(i + 1));
            }

            boolean isFinalShift = (shiftIndex >= TOTAL_SHIFTS - 4);
            
            DevilFruit incoming;
            if (shiftIndex == TOTAL_SHIFTS - 1) {
                
                incoming = randomFromPool(pool);
            } else {
                incoming = randomFromPool(pool);
            }
            inv.setItem(17, buildFruitDisplayItem(incoming, type));

            if (shiftIndex == TOTAL_SHIFTS - 1) {
                inv.setItem(13, buildFruitDisplayItem(winner, type));
            }

            float pitch = 1.0f + (shiftIndex / (float) TOTAL_SHIFTS) * 1.0f;
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 1.0f, pitch);

            scheduleShift(player, inv, pool, winner, allOwned, type, shiftIndex + 1, 0);
        }, delay);

        animationTasks.put(uuid, task);
    }

    private void onReveal(Player player, Inventory inv, DevilFruit winner,
                          boolean allOwned, FruitType type) {
        UUID uuid = player.getUniqueId();
        YamlConfiguration messagesConfig = guiManager.getMessagesConfig();

        if (!player.isOnline()) {
            cleanup(uuid);
            return;
        }

        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 1.5f, 1.5f);

        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline()) {
                player.playSound(player.getLocation(), Sound.ENTITY_FIREWORK_ROCKET_BLAST, 1.5f, 1.2f);
                player.playSound(player.getLocation(), Sound.ENTITY_FIREWORK_ROCKET_TWINKLE, 1.2f, 1.0f);
            }
        }, 5L);

        if (allOwned) {
            
            bankManager.addBalance(uuid, player.getName(), 500);

            String candleName = ColorUtil.colorize(
                    messagesConfig.getString("roll-candle-prize", "&#FCD05C+500 Tokens"));
            inv.setItem(22, buildCandleCustom(candleName));

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

            player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.5f, 1.0f);
        } else {
            
            fruitManager.giveFruit(uuid, winner);

            if (fruitManager.getEquippedFruit(uuid) == null) {
                fruitManager.setEquipped(uuid, winner.getKey());
            }

            inv.setItem(22, buildCandle(messagesConfig, winner));

            player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.5f, 1.0f);

            player.sendMessage(ColorUtil.colorize(
                    messagesConfig.getString("roll-won", "&#7AFB00You won &f%fruit%&#7AFB00!")
                            .replace("%fruit%", winner.getDisplayName())));
        }

        activeRollers.remove(uuid);

        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline()) {
                player.closeInventory();
            }
        }, 60L);
    }

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

    private ItemStack buildFruitDisplayItem(DevilFruit fruit, FruitType type) {
        YamlConfiguration typeConfig = guiManager.getTypeConfig(type);
        if (typeConfig == null) {
            
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
