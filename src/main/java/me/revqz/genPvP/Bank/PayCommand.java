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

public class PayCommand implements CommandExecutor, TabCompleter {

    private final GenPvP      plugin;
    private final BankManager bankManager;

    public PayCommand(GenPvP plugin, BankManager bankManager) {
        this.plugin      = plugin;
        this.bankManager = bankManager;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
            @NotNull String label, @NotNull String[] args) {

        if (!(sender instanceof Player payer)) {
            sender.sendMessage("Only players can use /pay.");
            return true;
        }

        FileConfiguration cfg = plugin.getConfig();

        if (args.length < 2) {
            payer.sendMessage(ColorUtil.colorize(
                    cfg.getString("pay.messages.usage", "&cUsage: /pay <player> <amount>")));
            return true;
        }

        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null || !target.isOnline()) {
            payer.sendMessage(ColorUtil.colorize(
                    cfg.getString("pay.messages.offline", "&cPlayer &e%player% &cis not online.")
                       .replace("%player%", args[0])));
            return true;
        }

        if (target.equals(payer)) {
            payer.sendMessage(ColorUtil.colorize(
                    cfg.getString("pay.messages.self", "&cYou cannot pay yourself.")));
            return true;
        }

        double amount;
        try {
            amount = Double.parseDouble(args[1]);
            if (amount <= 0) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            payer.sendMessage(ColorUtil.colorize(
                    cfg.getString("pay.messages.invalid-amount", "&cInvalid amount: &e%input%")
                       .replace("%input%", args[1])));
            return true;
        }

        if (!bankManager.isLoaded(payer.getUniqueId())) {
            payer.sendMessage(ColorUtil.colorize(
                    cfg.getString("pay.messages.loading", "&7Your bank data is still loading, try again in a moment.")));
            return true;
        }

        if (!bankManager.isLoaded(target.getUniqueId())) {
            payer.sendMessage(ColorUtil.colorize(
                    cfg.getString("pay.messages.loading", "&7That player's bank data is still loading, try again in a moment.")));
            return true;
        }

        if (bankManager.isDisabled(payer.getUniqueId())) {
            payer.sendMessage(ColorUtil.colorize(
                    cfg.getString("pay.messages.account-disabled", "&cYour bank account is disabled.")));
            return true;
        }

        if (bankManager.isDisabled(target.getUniqueId())) {
            payer.sendMessage(ColorUtil.colorize(
                    cfg.getString("pay.messages.target-disabled", "&cThat player's bank account is disabled.")));
            return true;
        }

        if (bankManager.isReceivePaymentsDisabled(target.getUniqueId())) {
            payer.sendMessage(ColorUtil.colorize(
                    cfg.getString("pay.messages.payments-disabled", "&e%player% &cis not accepting payments.")
                       .replace("%player%", target.getName())));
            return true;
        }

        // removeBalance is atomic (ConcurrentHashMap.compute) — no separate balance check
        // needed. Removing the pre-check eliminates the TOCTOU race that allowed a player
        // to double-spend by sending /pay commands faster than they processed.
        boolean removed = bankManager.removeBalance(payer.getUniqueId(), payer.getName(), amount);
        if (!removed) {
            payer.sendMessage(ColorUtil.colorize(
                    cfg.getString("pay.messages.insufficient", "&cYou don't have enough money. Balance: &e$%balance%")
                       .replace("%balance%", BankManager.formatBalance(bankManager.getBalance(payer.getUniqueId())))));
            return true;
        }
        bankManager.addBalance(target.getUniqueId(), target.getName(), amount);

        String formatted = BankManager.formatBalance(amount);
        payer.sendMessage(ColorUtil.colorize(
                cfg.getString("pay.messages.sent", "&aYou paid &e$%amount% &ato &e%player%&a.")
                   .replace("%amount%", formatted)
                   .replace("%player%", target.getName())));
        target.sendMessage(ColorUtil.colorize(
                cfg.getString("pay.messages.received", "&e%player% &apaid you &e$%amount%&a.")
                   .replace("%amount%", formatted)
                   .replace("%player%", payer.getName())));

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
}
