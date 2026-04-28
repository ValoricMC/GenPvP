package me.revqz.genPvP.Bank;

import me.revqz.genPvP.GenPvP;
import me.revqz.genPvP.util.ColorUtil;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Collections;
import java.util.List;

public class SettingsCommand implements CommandExecutor, TabCompleter {

    private final GenPvP      plugin;
    private final BankManager bankManager;

    public SettingsCommand(GenPvP plugin, BankManager bankManager) {
        this.plugin      = plugin;
        this.bankManager = bankManager;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
            @NotNull String label, @NotNull String[] args) {

        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players can use /settings.");
            return true;
        }

        if (args.length == 0) {
            player.sendMessage(ColorUtil.colorize(
                    plugin.getConfig().getString("settings.messages.usage",
                            "&cUsage: /settings <receive_payment>")));
            return true;
        }

        if (args[0].equalsIgnoreCase("receive_payment")) {
            boolean nowDisabled = bankManager.toggleReceivePayments(player.getUniqueId());
            String msg = nowDisabled
                    ? plugin.getConfig().getString("settings.messages.payments-disabled",
                            "&cYou will no longer receive payments from other players.")
                    : plugin.getConfig().getString("settings.messages.payments-enabled",
                            "&aYou will now receive payments from other players.");
            player.sendMessage(ColorUtil.colorize(msg));
            return true;
        }

        player.sendMessage(ColorUtil.colorize(
                plugin.getConfig().getString("settings.messages.unknown",
                        "&cUnknown setting. Available: receive_payment")));
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
            @NotNull String label, @NotNull String[] args) {
        if (args.length == 1) {
            String prefix = args[0].toLowerCase();
            if ("receive_payment".startsWith(prefix))
                return List.of("receive_payment");
        }
        return Collections.emptyList();
    }
}
