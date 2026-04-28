package me.revqz.genPvP.Sell;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public class SellCommand implements CommandExecutor, TabCompleter {

    private final SellMenu    sellMenu;
    private final SellManager sellManager;

    public SellCommand(SellMenu sellMenu, SellManager sellManager) {
        this.sellMenu    = sellMenu;
        this.sellManager = sellManager;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players can use /sell.");
            return true;
        }

        if (args.length >= 1 && args[0].equalsIgnoreCase("all")) {
            sellManager.sellAll(player);
        } else {
            sellMenu.open(player);
        }
        return true;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                                @NotNull String label, @NotNull String[] args) {
        if (args.length == 1) return List.of("all");
        return List.of();
    }
}
