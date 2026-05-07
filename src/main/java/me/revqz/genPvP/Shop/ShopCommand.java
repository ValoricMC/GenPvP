package me.revqz.genPvP.Shop;

import me.revqz.genPvP.util.ColorUtil;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Collections;
import java.util.List;

public class ShopCommand implements CommandExecutor, TabCompleter {

    private final ShopManager     shopManager;
    private final ItemShopManager itemShopManager;

    public ShopCommand(ShopManager shopManager, ItemShopManager itemShopManager) {
        this.shopManager     = shopManager;
        this.itemShopManager = itemShopManager;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players can use /shop.");
            return true;
        }

        if (args.length == 0) {
            shopManager.openDefault(player);
        } else {
            String id = args[0].toLowerCase();
            
            if (itemShopManager.hasShop(id)) {
                itemShopManager.openShop(player, id);
            } else {
                shopManager.openMenu(player, id);
            }
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String label, @NotNull String[] args) {
        if (args.length == 1) {
            String prefix = args[0].toLowerCase();
            
            return shopManager.getMenus().keySet().stream()
                    .filter(k -> k.startsWith(prefix))
                    .toList();
        }
        return Collections.emptyList();
    }
}
