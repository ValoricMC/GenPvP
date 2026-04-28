package me.revqz.genPvP.Guide;

import me.revqz.genPvP.GenPvP;
import me.revqz.genPvP.util.ColorUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
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
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class GuideManager implements CommandExecutor, Listener {

    private final GenPvP plugin;
    private YamlConfiguration config;

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.builder()
            .character('&').hexColors().build();

    // Map of slot -> command to run
    private final Map<Integer, String> slotCommands = new HashMap<>();

    public GuideManager(GenPvP plugin) {
        this.plugin = plugin;
        loadConfig();
    }

    public void loadConfig() {
        File file = new File(plugin.getDataFolder(), "guide.yml");
        if (!file.exists()) {
            plugin.saveResource("guide.yml", false);
        }
        config = YamlConfiguration.loadConfiguration(file);
    }

    public static class GuideHolder implements InventoryHolder {
        private Inventory inventory;
        @Override public @NotNull Inventory getInventory() { return inventory; }
        public void setInventory(Inventory inv) { this.inventory = inv; }
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Players only.");
            return true;
        }

        if (args.length > 0 && args[0].equalsIgnoreCase("reload") && player.hasPermission("genpvp.admin")) {
            loadConfig();
            player.sendMessage("§aGuide configuration reloaded.");
            return true;
        }

        openGuide(player);
        return true;
    }

    private void openGuide(Player player) {
        String title = config.getString("title", "&8Server Guide");
        GuideHolder holder = new GuideHolder();
        
        // 3 rows (3 slots in each row) is generally equivalent to an inventory of 27 slots visually where the middle block is used, 
        // or the user might just want 3 rows of 9 (27 total) where items are placed based on config.
        // We will just use 27 slots (3 rows).
        Inventory inv = Bukkit.createInventory(holder, 27, LEGACY.deserialize(title));
        holder.setInventory(inv);

        slotCommands.clear();

        ConfigurationSection slots = config.getConfigurationSection("slots");
        if (slots != null) {
            for (String key : slots.getKeys(false)) {
                try {
                    int slot = Integer.parseInt(key);
                    if (slot < 0 || slot >= 27) continue;

                    String path = "slots." + key + ".";
                    Material mat = Material.matchMaterial(config.getString(path + "material", "PAPER"));
                    if (mat == null) mat = Material.PAPER;

                    ItemStack item = new ItemStack(mat);
                    ItemMeta meta = item.getItemMeta();
                    if (meta != null) {
                        String name = config.getString(path + "name");
                        if (name != null) {
                            meta.displayName(noItalic(LEGACY.deserialize(name)));
                        }

                        List<String> lore = config.getStringList(path + "lore");
                        if (!lore.isEmpty()) {
                            List<Component> compLore = new ArrayList<>();
                            for (String line : lore) {
                                compLore.add(noItalic(LEGACY.deserialize(line)));
                            }
                            meta.lore(compLore);
                        }
                        item.setItemMeta(meta);
                    }
                    inv.setItem(slot, item);

                    String cmd = config.getString(path + "command");
                    if (cmd != null && !cmd.trim().isEmpty()) {
                        slotCommands.put(slot, cmd.trim());
                    }

                } catch (NumberFormatException ignored) {}
            }
        }

        player.openInventory(inv);
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (event.getInventory().getHolder() instanceof GuideHolder) {
            event.setCancelled(true);

            int slot = event.getRawSlot();
            if (slotCommands.containsKey(slot)) {
                String cmd = slotCommands.get(slot);
                player.closeInventory();
                plugin.getServer().dispatchCommand(player, cmd);
            }
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof GuideHolder) {
            event.setCancelled(true);
        }
    }

    private static Component noItalic(Component c) {
        return c.decoration(TextDecoration.ITALIC, false);
    }
}
