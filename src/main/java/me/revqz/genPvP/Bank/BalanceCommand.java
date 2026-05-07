package me.revqz.genPvP.Bank;

import me.revqz.genPvP.GenPvP;
import me.revqz.genPvP.util.ColorUtil;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Collections;
import java.util.List;

public class BalanceCommand implements CommandExecutor, TabCompleter {

    private final GenPvP      plugin;
    private final BankManager bankManager;

    public BalanceCommand(GenPvP plugin, BankManager bankManager) {
        this.plugin      = plugin;
        this.bankManager = bankManager;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
            @NotNull String label, @NotNull String[] args) {

        FileConfiguration cfg = plugin.getConfig();

        if (args.length == 0) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(msg(cfg, "balance.messages.players-only",
                        "&cUsage: /balance <player>"));
                return true;
            }

            if (!bankManager.isLoaded(player.getUniqueId())) {
                player.sendMessage(msg(cfg, "balance.messages.loading",
                        "&7Your bank data is still loading, please wait."));
                return true;
            }

            double balance = bankManager.getBalance(player.getUniqueId());
            player.sendMessage(msg(cfg, "balance.messages.self",
                    "&8[&aBank&8] &fYour balance: &a$%balance%")
                    .replace("%balance%", BankManager.formatBalance(balance))
                    .replace("%player%", player.getName()));
            return true;
        }

        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null || !target.isOnline()) {
            sender.sendMessage(msg(cfg, "balance.messages.offline",
                    "&cPlayer &e%player% &cis not online.")
                    .replace("%player%", args[0]));
            return true;
        }

        if (!bankManager.isLoaded(target.getUniqueId())) {
            sender.sendMessage(msg(cfg, "balance.messages.loading",
                    "&7That player's bank data is still loading, please wait."));
            return true;
        }

        double balance = bankManager.getBalance(target.getUniqueId());
        sender.sendMessage(msg(cfg, "balance.messages.other",
                "&8[&aBank&8] &e%player%&f's balance: &a$%balance%")
                .replace("%balance%", BankManager.formatBalance(balance))
                .replace("%player%", target.getName()));
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
            @NotNull String label, @NotNull String[] args) {
        if (args.length == 1) {
            String prefix = args[0].toLowerCase();
            return Bukkit.getOnlinePlayers().stream()
                    .map(Player::getName)
                    .filter(n -> n.toLowerCase().startsWith(prefix))
                    .toList();
        }
        return Collections.emptyList();
    }

    private static String msg(FileConfiguration cfg, String path, String fallback) {
        return ColorUtil.colorize(cfg.isString(path) ? cfg.getString(path) : fallback);
    }
}
