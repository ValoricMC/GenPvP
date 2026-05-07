package me.revqz.genPvP.Bank;

import me.revqz.genPvP.GenPvP;
import me.revqz.genPvP.util.ColorUtil;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public class DepositCommand implements CommandExecutor {

    private final GenPvP            plugin;
    private final BankManager       bankManager;
    private final MoneyShardManager shardManager;

    public DepositCommand(GenPvP plugin, BankManager bankManager, MoneyShardManager shardManager) {
        this.plugin       = plugin;
        this.bankManager  = bankManager;
        this.shardManager = shardManager;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
            @NotNull String label, @NotNull String[] args) {

        FileConfiguration cfg = plugin.getConfig();

        if (!(sender instanceof Player player)) {
            sender.sendMessage(msg(cfg, "deposit.messages.players-only",
                    "&cOnly players can use /deposit."));
            return true;
        }

        if (args.length < 1) {
            player.sendMessage(msg(cfg, "deposit.messages.usage",
                    "&cUsage: /deposit <amount>"));
            return true;
        }

        int amount;
        try {
            amount = Integer.parseInt(args[0]);
            if (amount <= 0) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            player.sendMessage(msg(cfg, "deposit.messages.invalid-amount",
                    "&cInvalid amount. Must be a positive whole number."));
            return true;
        }

        if (!bankManager.isLoaded(player.getUniqueId())) {
            player.sendMessage(msg(cfg, "deposit.messages.loading",
                    "&7Your bank data is still loading, please wait."));
            return true;
        }

        if (bankManager.isDisabled(player.getUniqueId())) {
            player.sendMessage(msg(cfg, "deposit.messages.account-disabled",
                    "&cYour bank account is disabled."));
            return true;
        }

        int held = shardManager.countShards(player);
        if (held < amount) {
            player.sendMessage(ColorUtil.colorize(
                    msg(cfg, "deposit.messages.not-enough-shards",
                            "&cNot enough Money Shards. &7Have &e%have% &7| Need &e%need%&7.")
                    .replace("%have%",   String.valueOf(held))
                    .replace("%need%",   String.valueOf(amount))
                    .replace("%amount%", String.valueOf(amount))));
            return true;
        }

        shardManager.removeShards(player, amount);
        double gained = amount * shardManager.getValuePerShard();
        boolean credited = bankManager.addBalance(player.getUniqueId(), player.getName(), gained);
        if (!credited) {
            
            shardManager.giveShards(player, amount);
            player.sendMessage(msg(cfg, "deposit.messages.account-disabled",
                    "&cYour bank account is disabled."));
            return true;
        }

        player.sendMessage(ColorUtil.colorize(
                msg(cfg, "deposit.messages.success",
                        "&aDeposited &e%amount% &aMoney Shard(s) &7(&e$%gained%&7) &ainto your bank.")
                .replace("%amount%",  String.valueOf(amount))
                .replace("%gained%",  BankManager.formatBalance(gained))
                .replace("%balance%", BankManager.formatBalance(
                        bankManager.getBalance(player.getUniqueId())))));
        return true;
    }

    private static String msg(FileConfiguration cfg, String path, String fallback) {
        return ColorUtil.colorize(cfg.isString(path) ? cfg.getString(path) : fallback);
    }
}
