package me.revqz.genPvP.AntiDupe;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.FileConfiguration;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * /antidupe — view and manage anti-dupe flags in real time.
 *
 *   /antidupe                        — show current flag status
 *   /antidupe reload                 — reload config from disk
 *   /antidupe toggle <flag>          — flip a boolean flag and save to config
 *   /antidupe set punish-command <…> — update the punish command and save
 */
public class AntiDupeCommand implements CommandExecutor, TabCompleter {

    private static final String PREFIX = ChatColor.DARK_AQUA + "[AntiDupe] " + ChatColor.RESET;

    private static final Set<String> BOOL_FLAGS = Set.of(
            "enabled",
            "check-unstackables",
            "delete-item",
            "notify-ops",
            "log-console"
    );

    private final AntiDupeManager manager;
    private final org.bukkit.plugin.java.JavaPlugin plugin;

    public AntiDupeCommand(AntiDupeManager manager, org.bukkit.plugin.java.JavaPlugin plugin) {
        this.manager = manager;
        this.plugin  = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!sender.isOp()) {
            sender.sendMessage(PREFIX + ChatColor.RED + "No permission.");
            return true;
        }

        if (args.length == 0) {
            sendStatus(sender);
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "reload" -> {
                manager.reload();
                sender.sendMessage(PREFIX + ChatColor.GREEN + "Config reloaded.");
            }
            case "status" -> sendStatus(sender);
            case "toggle" -> {
                if (args.length < 2) {
                    sender.sendMessage(PREFIX + ChatColor.YELLOW
                            + "Usage: /antidupe toggle <" + String.join("|", BOOL_FLAGS) + ">");
                    return true;
                }
                String flag = args[1].toLowerCase();
                if (!BOOL_FLAGS.contains(flag)) {
                    sender.sendMessage(PREFIX + ChatColor.RED + "Unknown flag: " + flag
                            + ". Valid: " + String.join(", ", BOOL_FLAGS));
                    return true;
                }
                toggleFlag(sender, flag);
            }
            case "set" -> {
                if (args.length < 3 || !args[1].equalsIgnoreCase("punish-command")) {
                    sender.sendMessage(PREFIX + ChatColor.YELLOW
                            + "Usage: /antidupe set punish-command <command|clear>");
                    return true;
                }
                String cmd = args[2].equalsIgnoreCase("clear") ? ""
                        : String.join(" ", Arrays.copyOfRange(args, 2, args.length));
                plugin.getConfig().set("anti-dupe.punish-command", cmd);
                plugin.saveConfig();
                manager.reload();
                sender.sendMessage(PREFIX + ChatColor.GREEN + "punish-command set to: "
                        + (cmd.isEmpty() ? ChatColor.GRAY + "(none)" : ChatColor.YELLOW + cmd));
            }
            default -> {
                sender.sendMessage(PREFIX + ChatColor.RED
                        + "Unknown sub-command. Use: reload | status | toggle <flag> | set punish-command <cmd>");
            }
        }
        return true;
    }

    private void toggleFlag(CommandSender sender, String flag) {
        String cfgKey = "anti-dupe." + flag;
        boolean current = plugin.getConfig().getBoolean(cfgKey, true);
        plugin.getConfig().set(cfgKey, !current);
        plugin.saveConfig();
        manager.reload();
        sender.sendMessage(PREFIX + ChatColor.WHITE + flag + " → "
                + ((!current) ? ChatColor.GREEN + "ENABLED" : ChatColor.RED + "DISABLED"));
    }

    private void sendStatus(CommandSender sender) {
        AntiDupeConfig cfg = manager.getConfig();
        FileConfiguration file = plugin.getConfig();

        sender.sendMessage(ChatColor.DARK_AQUA + "━━━━━━━━ AntiDupe Status ━━━━━━━━");
        row(sender, "enabled",           cfg.enabled);
        row(sender, "check-unstackables",cfg.checkUnstackables);
        row(sender, "delete-item",       cfg.deleteItem);
        row(sender, "notify-ops",        cfg.notifyOps);
        row(sender, "log-console",       cfg.logConsole);
        sender.sendMessage(ChatColor.GRAY + "  scan-interval-ticks  " + ChatColor.WHITE
                + cfg.scanIntervalTicks + ChatColor.DARK_GRAY + "  (use /antidupe reload after editing config)");
        sender.sendMessage(ChatColor.GRAY + "  alert-cooldown        " + ChatColor.WHITE
                + cfg.alertCooldownSeconds + "s");
        sender.sendMessage(ChatColor.GRAY + "  punish-command        "
                + (cfg.punishCommand.isBlank()
                        ? ChatColor.DARK_GRAY + "(none)"
                        : ChatColor.YELLOW + cfg.punishCommand));
        sender.sendMessage(ChatColor.DARK_AQUA + "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
    }

    private void row(CommandSender sender, String name, boolean value) {
        sender.sendMessage(ChatColor.GRAY + "  " + name
                + ChatColor.DARK_GRAY + " → "
                + (value ? ChatColor.GREEN + "ON" : ChatColor.RED + "OFF")
                + ChatColor.DARK_GRAY + "  [/antidupe toggle " + name + "]");
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String label, @NotNull String[] args) {
        if (!sender.isOp()) return List.of();
        if (args.length == 1) return filter(List.of("reload", "status", "toggle", "set"), args[0]);
        if (args.length == 2) {
            return switch (args[0].toLowerCase()) {
                case "toggle" -> filter(List.copyOf(BOOL_FLAGS), args[1]);
                case "set"    -> filter(List.of("punish-command"), args[1]);
                default       -> List.of();
            };
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("set")
                && args[1].equalsIgnoreCase("punish-command")) {
            return List.of("clear");
        }
        return List.of();
    }

    private List<String> filter(List<String> opts, String partial) {
        String lc = partial.toLowerCase();
        return opts.stream().filter(s -> s.startsWith(lc)).toList();
    }
}
