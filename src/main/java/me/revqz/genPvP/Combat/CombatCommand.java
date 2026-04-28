package me.revqz.genPvP.Combat;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

public class CombatCommand implements CommandExecutor, TabCompleter {

    private final CombatManager combat;

    public CombatCommand(CombatManager combat) {
        this.combat = combat;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.isOp()) {
            sender.sendMessage("§cNo permission.");
            return true;
        }

        if (args.length < 1) {
            sender.sendMessage("§cUsage: /combat tag <player> <seconds>");
            return true;
        }

        if (!args[0].equalsIgnoreCase("tag")) {
            sender.sendMessage("§cUnknown subcommand. Usage: /combat tag <player> <seconds>");
            return true;
        }

        if (args.length < 3) {
            sender.sendMessage("§cUsage: /combat tag <player> <seconds>");
            return true;
        }

        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            sender.sendMessage("§cPlayer not found: §e" + args[1]);
            return true;
        }

        long seconds;
        try {
            seconds = Long.parseLong(args[2]);
            if (seconds <= 0) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            sender.sendMessage("§cSeconds must be a positive integer.");
            return true;
        }

        combat.tagFor(target.getUniqueId(), seconds);
        sender.sendMessage("§aTagged §e" + target.getName() + " §afor §e" + seconds + "s§a.");
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.isOp()) return List.of();
        List<String> result = new ArrayList<>();
        if (args.length == 1) {
            if ("tag".startsWith(args[0].toLowerCase())) result.add("tag");
        } else if (args.length == 2 && args[0].equalsIgnoreCase("tag")) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getName().toLowerCase().startsWith(args[1].toLowerCase())) result.add(p.getName());
            }
        } else if (args.length == 3 && args[0].equalsIgnoreCase("tag")) {
            result.add("15");
            result.add("30");
            result.add("60");
        }
        return result;
    }
}
