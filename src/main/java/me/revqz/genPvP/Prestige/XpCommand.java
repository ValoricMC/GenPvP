package me.revqz.genPvP.Prestige;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class XpCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBCOMMANDS = Arrays.asList(
            "add_xp", "remove_xp", "reset_xp"
    );

    private final PrestigeManager prestigeManager;

    public XpCommand(PrestigeManager prestigeManager) {
        this.prestigeManager = prestigeManager;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {

        if (!(sender instanceof Player admin)) {
            sender.sendMessage("Only players can use /xp.");
            return true;
        }

        if (!admin.isOp()) {
            admin.sendMessage("§cNo permission.");
            return true;
        }

        if (args.length < 2) {
            sendUsage(admin);
            return true;
        }

        String sub        = args[0].toLowerCase();
        String targetName = args[1];
        Player target     = Bukkit.getPlayerExact(targetName);

        if (target == null) {
            admin.sendMessage("§cPlayer §e" + targetName + " §cis not online.");
            return true;
        }

        switch (sub) {
            case "add_xp" -> {
                double amount = parseAmount(args, admin, sub);
                if (amount < 0) return true;
                double total = prestigeManager.addXpAdmin(target.getUniqueId(), target.getName(), amount);
                prestigeManager.sendXpAdded(admin, target.getName(), amount, total);
            }
            case "remove_xp" -> {
                double amount = parseAmount(args, admin, sub);
                if (amount < 0) return true;
                double total = prestigeManager.removeXpAdmin(target.getUniqueId(), target.getName(), amount);
                prestigeManager.sendXpRemoved(admin, target.getName(), amount, total);
            }
            case "reset_xp" -> {
                prestigeManager.resetXpAdmin(target.getUniqueId(), target.getName());
                prestigeManager.sendXpReset(admin, target.getName());
            }
            default -> sendUsage(admin);
        }

        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                       @NotNull String label, @NotNull String[] args) {
        if (!sender.isOp()) return Collections.emptyList();

        if (args.length == 1) {
            String prefix = args[0].toLowerCase();
            return SUBCOMMANDS.stream().filter(s -> s.startsWith(prefix)).toList();
        }

        if (args.length == 2) {
            String prefix = args[1].toLowerCase();
            return Bukkit.getOnlinePlayers().stream()
                    .map(Player::getName)
                    .filter(n -> n.toLowerCase().startsWith(prefix))
                    .toList();
        }

        if (args.length == 3) {
            String sub = args[0].toLowerCase();
            if (sub.equals("add_xp") || sub.equals("remove_xp")) {
                return Arrays.asList("5", "10", "50", "100");
            }
        }

        return Collections.emptyList();
    }

    private double parseAmount(String[] args, Player admin, String sub) {
        if (args.length < 3) {
            admin.sendMessage("§cUsage: /xp " + sub + " <player> <amount>");
            return -1;
        }
        try {
            double amount = Double.parseDouble(args[2]);
            if (amount <= 0) {
                admin.sendMessage("§cAmount must be positive.");
                return -1;
            }
            return amount;
        } catch (NumberFormatException e) {
            admin.sendMessage("§c'" + args[2] + "' is not a valid number.");
            return -1;
        }
    }

    private void sendUsage(Player admin) {
        admin.sendMessage("§7Usage:");
        admin.sendMessage("§e/xp add_xp <player> <amount>");
        admin.sendMessage("§e/xp remove_xp <player> <amount>");
        admin.sendMessage("§e/xp reset_xp <player>");
        admin.sendMessage("§7To blacklist a player from XP gain: §e/prestige blacklist_xp <player>");
    }
}
