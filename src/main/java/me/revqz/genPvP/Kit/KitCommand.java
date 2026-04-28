package me.revqz.genPvP.Kit;

import me.revqz.genPvP.util.ColorUtil;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class KitCommand implements CommandExecutor, TabCompleter {

    private final KitManager kitManager;

    public KitCommand(KitManager kitManager) {
        this.kitManager = kitManager;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
            @NotNull String label, @NotNull String[] args) {

        // /kit — open GUI
        if (args.length == 0) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("Only players can open the kit menu.");
                return true;
            }
            kitManager.openGUI(player);
            return true;
        }

        // /kit preview <name>
        if (args[0].equalsIgnoreCase("preview")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("Only players can preview kits.");
                return true;
            }
            if (args.length < 2) {
                sender.sendMessage(ColorUtil.colorize("&cUsage: /kit preview <name>"));
                return true;
            }
            KitManager.KitData kit = kitManager.getKit(args[1].toLowerCase());
            if (kit == null) {
                sender.sendMessage(ColorUtil.colorize("&cUnknown kit: &e" + args[1]));
                return true;
            }
            kitManager.openPreview(player, kit);
            return true;
        }

        // /kit register <name> <from_inventory|from_file>
        if (args[0].equalsIgnoreCase("register")) {
            if (!sender.isOp()) {
                sender.sendMessage(ColorUtil.colorize(
                        kitManager.msg("not-op", "&cOnly admins can register kits.")));
                return true;
            }
            if (!(sender instanceof Player player)) {
                sender.sendMessage("Only players can register kits from inventory.");
                return true;
            }
            if (args.length < 3) {
                sender.sendMessage(ColorUtil.colorize(
                        "&cUsage: /kit register <name> <from_inventory|from_file>"));
                return true;
            }

            String kitId  = args[1].toLowerCase();
            String source = args[2].toLowerCase();

            switch (source) {
                case "from_inventory" -> {
                    kitManager.registerFromInventory(kitId, player);
                    sender.sendMessage(ColorUtil.colorize(
                            kitManager.msg("register-done",
                                    "&aKit &b%kit% &aregistered successfully from your inventory.")
                                    .replace("%kit%", kitId)));
                }
                case "from_file" -> {
                    // TODO: Implement reading kit contents from a manually written YAML file
                    //       at plugins/GenPvP/kits/<name>_import.yml
                    sender.sendMessage(ColorUtil.colorize(
                            "&cFrom-file registration is not yet implemented."));
                }
                default -> sender.sendMessage(ColorUtil.colorize(
                        "&cUnknown source. Use: &efrom_inventory &cor &efrom_file"));
            }
            return true;
        }

        // /kit starter_on_death — toggle auto-kit-on-respawn for the executing player
        if (args[0].equalsIgnoreCase("starter_on_death")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("Only players can toggle this setting.");
                return true;
            }
            kitManager.toggleStarterOnDeath(player);
            return true;
        }

        // /kit reset_cooldown <player> <kit>
        if (args[0].equalsIgnoreCase("reset_cooldown")) {
            if (!sender.isOp()) {
                sender.sendMessage(ColorUtil.colorize(
                        kitManager.msg("not-op", "&cOnly admins can register kits.")));
                return true;
            }
            if (args.length < 3) {
                sender.sendMessage(ColorUtil.colorize("&cUsage: /kit reset_cooldown <player> <kit>"));
                return true;
            }
            kitManager.resetCooldown(args[1], args[2].toLowerCase(), sender);
            return true;
        }

        sender.sendMessage(ColorUtil.colorize(
                "&cUsage: /kit [register <name> <from_inventory|from_file>]"));
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
            @NotNull String label, @NotNull String[] args) {

        if (args.length == 1) {
            String prefix = args[0].toLowerCase();
            List<String> subs = new ArrayList<>();
            if ("preview".startsWith(prefix)) subs.add("preview");
            if ("starter_on_death".startsWith(prefix)) subs.add("starter_on_death");
            if (sender.isOp()) {
                if ("register".startsWith(prefix)) subs.add("register");
                if ("reset_cooldown".startsWith(prefix)) subs.add("reset_cooldown");
            }
            return subs;
        }

        if (args.length == 2) {
            String prefix = args[1].toLowerCase();
            if (args[0].equalsIgnoreCase("preview")) {
                return kitManager.getKitIds().stream()
                        .filter(id -> id.startsWith(prefix))
                        .toList();
            }
            if (sender.isOp()) {
                if (args[0].equalsIgnoreCase("register")) {
                    return kitManager.getKitIds().stream()
                            .filter(id -> id.startsWith(prefix))
                            .toList();
                }
                if (args[0].equalsIgnoreCase("reset_cooldown")) {
                    return Bukkit.getOnlinePlayers().stream()
                            .map(p -> p.getName())
                            .filter(name -> name.toLowerCase().startsWith(prefix))
                            .toList();
                }
            }
        }

        if (args.length == 3 && sender.isOp()) {
            String prefix = args[2].toLowerCase();
            if (args[0].equalsIgnoreCase("register")) {
                return Arrays.asList("from_inventory", "from_file").stream()
                        .filter(s -> s.startsWith(prefix))
                        .toList();
            }
            if (args[0].equalsIgnoreCase("reset_cooldown")) {
                return kitManager.getKitIds().stream()
                        .filter(id -> id.startsWith(prefix))
                        .toList();
            }
        }

        return Collections.emptyList();
    }
}
