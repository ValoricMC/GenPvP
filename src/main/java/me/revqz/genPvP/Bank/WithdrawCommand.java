package me.revqz.genPvP.Bank;

import me.revqz.genPvP.GenPvP;
import me.revqz.genPvP.util.ColorUtil;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public class WithdrawCommand implements CommandExecutor {

    private final GenPvP            plugin;
    private final BankManager       bankManager;
    private final MoneyShardManager shardManager;

    public WithdrawCommand(GenPvP plugin, BankManager bankManager, MoneyShardManager shardManager) {
        this.plugin       = plugin;
        this.bankManager  = bankManager;
        this.shardManager = shardManager;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
            @NotNull String label, @NotNull String[] args) {

        FileConfiguration cfg = plugin.getConfig();

        if (!(sender instanceof Player player)) {
            sender.sendMessage(msg(cfg, "withdraw.messages.players-only",
                    "&cOnly players can use /withdraw."));
            return true;
        }

        if (args.length < 1) {
            player.sendMessage(msg(cfg, "withdraw.messages.usage",
                    "&cUsage: /withdraw <amount>"));
            return true;
        }

        int amount;
        try {
            amount = Integer.parseInt(args[0]);
            if (amount <= 0) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            player.sendMessage(msg(cfg, "withdraw.messages.invalid-amount",
                    "&cInvalid amount. Must be a positive whole number."));
            return true;
        }

        if (!bankManager.isLoaded(player.getUniqueId())) {
            player.sendMessage(msg(cfg, "withdraw.messages.loading",
                    "&7Your bank data is still loading, please wait."));
            return true;
        }

        double cost = amount * shardManager.getValuePerShard();

        if (shardManager.availableCapacity(player) < amount) {
            player.sendMessage(ColorUtil.colorize(
                    msg(cfg, "withdraw.messages.no-space",
                            "&cNot enough inventory space. Free up some room and try again.")
                    .replace("%amount%", String.valueOf(amount))));
            return true;
        }

        if (!bankManager.removeBalance(player.getUniqueId(), player.getName(), cost)) {
            player.sendMessage(ColorUtil.colorize(
                    msg(cfg, "withdraw.messages.insufficient-funds",
                            "&cInsufficient funds. &7Need &e$%need% &7| Have &e$%balance%&7.")
                    .replace("%need%",    BankManager.formatBalance(cost))
                    .replace("%balance%", BankManager.formatBalance(
                            bankManager.getBalance(player.getUniqueId())))
                    .replace("%amount%",  String.valueOf(amount))));
            return true;
        }
        shardManager.giveShards(player, amount);

        player.sendMessage(ColorUtil.colorize(
                msg(cfg, "withdraw.messages.success",
                        "&aWithdrew &e%amount% &aMoney Shard(s) &7(&e$%cost%&7) &afrom your bank.")
                .replace("%amount%",  String.valueOf(amount))
                .replace("%cost%",    BankManager.formatBalance(cost))
                .replace("%balance%", BankManager.formatBalance(
                        bankManager.getBalance(player.getUniqueId())))));
        return true;
    }

    private static String msg(FileConfiguration cfg, String path, String fallback) {
        return ColorUtil.colorize(cfg.isString(path) ? cfg.getString(path) : fallback);
    }
}
