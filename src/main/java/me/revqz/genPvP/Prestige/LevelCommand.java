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

public class LevelCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBCOMMANDS = Arrays.asList(
            "add_level", "remove_level", "reset_level", "blacklist_level"
    );

    private final PrestigeManager prestigeManager;

    public LevelCommand(PrestigeManager prestigeManager) {
        this.prestigeManager = prestigeManager;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {

        if (!(sender instanceof Player admin)) {
            sender.sendMessage("Only players can use /level.");
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
            case "add_level" -> {
                int amount = parseAmount(args, admin, sub);
                if (amount <= 0) return true;
                int total = prestigeManager.addLevel(target.getUniqueId(), target.getName(), amount);
                prestigeManager.sendLevelAdded(admin, target.getName(), amount, total);
            }
            case "remove_level" -> {
                int amount = parseAmount(args, admin, sub);
                if (amount <= 0) return true;
                int total = prestigeManager.removeLevel(target.getUniqueId(), target.getName(), amount);
                prestigeManager.sendLevelRemoved(admin, target.getName(), amount, total);
            }
            case "reset_level" -> {
                prestigeManager.resetLevel(target.getUniqueId(), target.getName());
                prestigeManager.sendLevelReset(admin, target.getName());
            }
            case "blacklist_level" -> {
                boolean bl = prestigeManager.toggleLevelBlacklist(target.getUniqueId(), target.getName());
                prestigeManager.sendLevelBlacklistToggle(admin, target.getName(), bl);
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
            if (sub.equals("add_level") || sub.equals("remove_level")) {
                return Arrays.asList("1", "5", "10");
            }
        }

        return Collections.emptyList();
    }

    private int parseAmount(String[] args, Player admin, String sub) {
        if (args.length < 3) {
            admin.sendMessage("§cUsage: /level " + sub + " <player> <amount>");
            return -1;
        }
        try {
            int amount = Integer.parseInt(args[2]);
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
        admin.sendMessage("§e/level add_level <player> <amount>");
        admin.sendMessage("§e/level remove_level <player> <amount>");
        admin.sendMessage("§e/level reset_level <player>");
        admin.sendMessage("§e/level blacklist_level <player>");
    }
}
