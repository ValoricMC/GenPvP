package me.revqz.genPvP.DevilFruits;

import me.revqz.genPvP.GenPvP;
import me.revqz.genPvP.Protect.ProtectRegion;
import me.revqz.genPvP.Protect.RegionManager;
import me.revqz.genPvP.Protect.flags.RegionType;
import me.revqz.genPvP.util.ColorUtil;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;

public class FruitSlotManager implements Listener {

    static final int FRUIT_SLOT = 8; // 0-indexed hotbar slot (key 9)

    private final GenPvP plugin;
    private final DevilFruitManager fruitManager;
    private final FruitGUIManager guiManager;
    private final YamlConfiguration messagesConfig;
    private final NamespacedKey FRUIT_SLOT_KEY;
    private RegionManager regionManager;

    public FruitSlotManager(GenPvP plugin, DevilFruitManager fruitManager,
                             FruitGUIManager guiManager, YamlConfiguration messagesConfig) {
        this.plugin         = plugin;
        this.fruitManager   = fruitManager;
        this.guiManager     = guiManager;
        this.messagesConfig = messagesConfig;
        this.FRUIT_SLOT_KEY = new NamespacedKey(plugin, "fruit_slot_item");
    }

    /** Injected after construction so the slot manager can check SPAWN regions. */
    public void setRegionManager(RegionManager regionManager) {
        this.regionManager = regionManager;
    }

    public boolean isFruitSlotItem(ItemStack item) {
        if (item == null || item.getType() == Material.AIR || !item.hasItemMeta()) return false;
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.getPersistentDataContainer().has(FRUIT_SLOT_KEY, PersistentDataType.BYTE);
    }

    public void updateFruitSlot(Player player) {
        String equipped = fruitManager.getEquippedFruit(player.getUniqueId());
        ItemStack item = (equipped != null) ? buildEquippedItem(equipped) : buildNoFruitItem();
        player.getInventory().setItem(FRUIT_SLOT, item);
    }

    // ── Item builders ─────────────────────────────────────────────────────────

    private ItemStack buildNoFruitItem() {
        String name = messagesConfig.getString("no-fruit-name", "&#FDCD4DNo Fruit");
        List<String> rawLore = messagesConfig.getStringList("no-fruit-lore");
        if (rawLore.isEmpty()) {
            rawLore = List.of("", "&#FF6A6A❌ You have not equipped", "&#FF6A6A   any fruit.", "", "&#FFDE87Choose a fruit");
        }

        ItemStack item = new ItemStack(Material.PAPER);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ColorUtil.colorize(name));
            List<String> lore = new ArrayList<>();
            for (String line : rawLore) lore.add(ColorUtil.colorize(line));
            meta.setLore(lore);
            meta.getPersistentDataContainer().set(FRUIT_SLOT_KEY, PersistentDataType.BYTE, (byte) 1);
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack buildEquippedItem(String fruitKey) {
        DevilFruit fruit = DevilFruit.fromKey(fruitKey);
        if (fruit == null) return buildNoFruitItem();

        YamlConfiguration typeConfig = guiManager.getTypeConfig(fruit.getType());
        if (typeConfig == null) return buildNoFruitItem();

        String path    = "fruits." + fruitKey + ".";
        String matName = typeConfig.getString(path + "material", "PAPER");
        String name    = typeConfig.getString(path + "devil-equipped-name", fruit.getDisplayName());
        List<String> rawLore = typeConfig.getStringList(path + "devil-equipped-lore");

        Material material = Material.matchMaterial(matName);
        if (material == null) material = Material.PAPER;

        ItemStack item = new ItemStack(material);
        ItemMeta meta  = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ColorUtil.colorize(name));
            List<String> lore = new ArrayList<>();
            for (String line : rawLore) lore.add(ColorUtil.colorize(line));
            meta.setLore(lore);
            meta.getPersistentDataContainer().set(FRUIT_SLOT_KEY, PersistentDataType.BYTE, (byte) 1);
            // Hide weapon/tool attribute tooltips (e.g. "1.6 Attack Speed", "7 Attack Damage")
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
            item.setItemMeta(meta);
        }
        return item;
    }

    // ── Events ────────────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        // 2-tick delay: client inventory is ready, injects are all done at NORMAL priority
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) return;
            updateFruitSlot(player);
            if (player.isOp()) {
                ItemStack slot8 = player.getInventory().getItem(FRUIT_SLOT);
                boolean present = isFruitSlotItem(slot8);
                String matName = slot8 != null ? slot8.getType().name() : "null";
                player.sendMessage("slot " + FRUIT_SLOT + " item=" + matName + " isFruitSlotItem=" + present);
            }
        }, 2L);
        // 60-tick safety net: catches slow async DB loads (>3 s to MongoDB)
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline()) updateFruitSlot(player);
        }, 60L);
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        // Restore the slot item after the player inventory is cleared on death
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            Player player = event.getPlayer();
            if (player.isOnline()) updateFruitSlot(player);
        }, 1L);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(PlayerDeathEvent event) {
        // Remove the fruit slot item from drops so it never lands on the ground
        event.getDrops().removeIf(this::isFruitSlotItem);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;

        // Block any interaction where the fruit slot item is the current item
        if (isFruitSlotItem(event.getCurrentItem())) {
            event.setCancelled(true);
            return;
        }

        // Block placing the fruit slot item from cursor into any slot
        if (isFruitSlotItem(event.getCursor())) {
            event.setCancelled(true);
            return;
        }

        // Block placing anything into the fruit slot
        if (event.getClickedInventory() == player.getInventory()
                && event.getSlot() == FRUIT_SLOT) {
            event.setCancelled(true);
            return;
        }

        // Block number-key swaps that would target the fruit slot
        if (event.getClick() == ClickType.NUMBER_KEY && event.getHotbarButton() == FRUIT_SLOT) {
            event.setCancelled(true);
            return;
        }

        // Block DOUBLE_CLICK collect-all that would pull the fruit slot item
        if (event.getClick() == ClickType.DOUBLE_CLICK) {
            ItemStack slot8 = player.getInventory().getItem(FRUIT_SLOT);
            ItemStack cursor = event.getCursor();
            if (isFruitSlotItem(slot8) && cursor != null && !cursor.getType().isAir()
                    && cursor.getType() == slot8.getType()) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        for (int rawSlot : event.getRawSlots()) {
            if (event.getView().getInventory(rawSlot) == player.getInventory()
                    && event.getView().convertSlot(rawSlot) == FRUIT_SLOT) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onCreativeInventory(InventoryCreativeEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (isFruitSlotItem(event.getCursor()) || isFruitSlotItem(event.getCurrentItem())) {
            event.setCancelled(true);
            return;
        }
        if (event.getClickedInventory() == player.getInventory()
                && event.getSlot() == FRUIT_SLOT) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (isFruitSlotItem(event.getItemInHand())) {
            event.setCancelled(true);
            // Immediately revert the block to AIR in case the client rendered it
            event.getBlockPlaced().setType(Material.AIR);
        }
    }

    // Prevent fruit-slot weapons (e.g. IRON_SWORD for Supa Supa) from dealing melee damage
    @EventHandler(priority = EventPriority.LOWEST)
    public void onAttackWithFruitItem(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player attacker)) return;
        if (isFruitSlotItem(attacker.getInventory().getItemInMainHand())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onDrop(PlayerDropItemEvent event) {
        if (isFruitSlotItem(event.getItemDrop().getItemStack())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        if (isFruitSlotItem(event.getMainHandItem()) || isFruitSlotItem(event.getOffHandItem())) {
            event.setCancelled(true);
        }
    }

    // Safety net: if something managed to displace the item, restore it after inventory closes
    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline() && !isFruitSlotItem(player.getInventory().getItem(FRUIT_SLOT))) {
                updateFruitSlot(player);
            }
        }, 1L);
    }

    // Right-click the fruit slot item:
    //   - no fruit equipped  → open general fruit GUI
    //   - fruit equipped     → cancel (ParameciaAbilityListener at NORMAL fires next)
    @EventHandler(priority = EventPriority.LOW)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        org.bukkit.event.block.Action action = event.getAction();
        if (action != org.bukkit.event.block.Action.RIGHT_CLICK_AIR
                && action != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK) return;

        Player player = event.getPlayer();
        if (!isFruitSlotItem(player.getInventory().getItemInMainHand())) return;

        event.setCancelled(true);

        if (fruitManager.getEquippedFruit(player.getUniqueId()) == null) {
            guiManager.openGeneral(player);
        }
    }

    // Block /ah sell <amount> when holding the fruit slot item
    @EventHandler
    public void onCommand(PlayerCommandPreprocessEvent event) {
        String lower = event.getMessage().toLowerCase();
        if (!lower.startsWith("/ah sell")) return;
        Player player = event.getPlayer();
        if (isFruitSlotItem(player.getInventory().getItemInMainHand())) {
            event.setCancelled(true);
            player.sendMessage(ColorUtil.colorize(
                    messagesConfig.getString("cannot-sell-fruit", "&cYou cannot sell this item.")));
        }
    }
}
