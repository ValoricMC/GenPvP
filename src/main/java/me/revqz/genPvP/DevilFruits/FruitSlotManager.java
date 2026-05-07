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

    static final int FRUIT_SLOT = 8; 

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
            
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
            item.setItemMeta(meta);
        }
        return item;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        
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
        
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline()) updateFruitSlot(player);
        }, 60L);
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            Player player = event.getPlayer();
            if (player.isOnline()) updateFruitSlot(player);
        }, 1L);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(PlayerDeathEvent event) {
        
        event.getDrops().removeIf(this::isFruitSlotItem);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;

        if (isFruitSlotItem(event.getCurrentItem())) {
            event.setCancelled(true);
            return;
        }

        if (isFruitSlotItem(event.getCursor())) {
            event.setCancelled(true);
            return;
        }

        if (event.getClickedInventory() == player.getInventory()
                && event.getSlot() == FRUIT_SLOT) {
            event.setCancelled(true);
            return;
        }

        if (event.getClick() == ClickType.NUMBER_KEY && event.getHotbarButton() == FRUIT_SLOT) {
            event.setCancelled(true);
            return;
        }

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
            
            event.getBlockPlaced().setType(Material.AIR);
        }
    }

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

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline() && !isFruitSlotItem(player.getInventory().getItem(FRUIT_SLOT))) {
                updateFruitSlot(player);
            }
        }, 1L);
    }

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
