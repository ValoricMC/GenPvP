package me.revqz.genPvP.Koth;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

public class KothCommand implements CommandExecutor, TabCompleter {

    private final KothManager kothManager;

    public KothCommand(KothManager kothManager) {
        this.kothManager = kothManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.isOp()) {
            sender.sendMessage(ChatColor.RED + "You do not have permission to use this command.");
            return true;
        }

        if (args.length == 0) {
            sender.sendMessage(ChatColor.RED + "Usage: /koth <start|stop>");
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "start" -> {
                if (kothManager.isKothActive()) {
                    sender.sendMessage(kothManager.msg("already_active"));
                } else {
                    kothManager.startKoth(true);
                    sender.sendMessage(kothManager.msg("force_started"));
                }
            }
            case "stop" -> {
                if (!kothManager.isKothActive()) {
                    sender.sendMessage(kothManager.msg("not_active"));
                } else {
                    kothManager.stopKoth(true);
                    sender.sendMessage(kothManager.msg("force_stopped"));
                }
            }
            default -> sender.sendMessage(ChatColor.RED + "Usage: /koth <start|stop>");
        }

        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.isOp()) return Collections.emptyList();
        if (args.length == 1) {
            return Arrays.asList("start", "stop").stream()
                    .filter(s -> s.startsWith(args[0].toLowerCase()))
                    .collect(Collectors.toList());
        }
        return Collections.emptyList();
    }
}
