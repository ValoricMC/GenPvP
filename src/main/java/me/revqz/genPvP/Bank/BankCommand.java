package me.revqz.genPvP.Bank;

import me.revqz.genPvP.GenPvP;
import me.revqz.genPvP.util.ColorUtil;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class BankCommand implements CommandExecutor, TabCompleter {

    private final BankManager       bankManager;
    private final BankMenu          bankMenu;
    private final MoneyShardManager shardManager;

    public BankCommand(GenPvP plugin, BankManager bankManager, BankMenu bankMenu,
            MoneyShardManager shardManager) {
        this.bankManager  = bankManager;
        this.bankMenu     = bankMenu;
        this.shardManager = shardManager;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
            @NotNull String label, @NotNull String[] args) {

        // /bank — opens the GUI
        if (args.length == 0) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("Only players can use /bank.");
                return true;
            }
            if (!bankManager.isLoaded(player.getUniqueId())) {
                player.sendMessage(ColorUtil.colorize("&7Loading your bank data, please try again in a moment."));
                return true;
            }
            bankMenu.open(player);
            return true;
        }

        String sub = args[0].toLowerCase();

        // All subcommands require OP
        if (!sender.isOp()) {
            sender.sendMessage(ColorUtil.colorize("&cYou do not have permission to use this command."));
            return true;
        }

        switch (sub) {

            // ── Money ─────────────────────────────────────────────────────────
            case "give_money" -> {
                if (args.length < 3) {
                    sender.sendMessage(ColorUtil.colorize("&cUsage: /bank give_money <player> <amount>"));
                    return true;
                }
                double amt = parseAmount(sender, args[2]);
                if (Double.isNaN(amt))
                    return true;
                OfflinePlayer t = resolvePlayer(sender, args[1]);
                if (t == null)
                    return true;
                bankManager.adminAdd(t.getUniqueId(), playerName(t, args[1]), amt);
                sender.sendMessage(ColorUtil
                        .colorize("&aGave &e$" + BankManager.formatBalance(amt) + " &ato &e" + displayName(t) + "&a."));
            }
            case "remove_money" -> {
                if (args.length < 3) {
                    sender.sendMessage(ColorUtil.colorize("&cUsage: /bank remove_money <player> <amount>"));
                    return true;
                }
                double amt = parseAmount(sender, args[2]);
                if (Double.isNaN(amt))
                    return true;
                OfflinePlayer t = resolvePlayer(sender, args[1]);
                if (t == null)
                    return true;
                bankManager.adminRemove(t.getUniqueId(), playerName(t, args[1]), amt);
                sender.sendMessage(ColorUtil.colorize(
                        "&aRemoved &e$" + BankManager.formatBalance(amt) + " &afrom &e" + displayName(t) + "&a."));
            }
            case "wipe_money" -> {
                if (args.length < 2) {
                    sender.sendMessage(ColorUtil.colorize("&cUsage: /bank wipe_money <player>"));
                    return true;
                }
                OfflinePlayer t = resolvePlayer(sender, args[1]);
                if (t == null)
                    return true;
                bankManager.adminWipe(t.getUniqueId(), playerName(t, args[1]));
                sender.sendMessage(ColorUtil.colorize("&aWiped money balance of &e" + displayName(t) + "&a."));
            }

            // ── Shards ────────────────────────────────────────────────────────
            case "give_shards" -> {
                if (args.length < 3) {
                    sender.sendMessage(ColorUtil.colorize("&cUsage: /bank give_shards <player> <amount>"));
                    return true;
                }
                double amt = parseAmount(sender, args[2]);
                if (Double.isNaN(amt))
                    return true;
                OfflinePlayer t = resolvePlayer(sender, args[1]);
                if (t == null)
                    return true;
                bankManager.adminAddShards(t.getUniqueId(), playerName(t, args[1]), amt);
                sender.sendMessage(ColorUtil.colorize(
                        "&aGave &e" + BankManager.formatBalance(amt) + " shards &ato &e" + displayName(t) + "&a."));
            }
            case "remove_shards" -> {
                if (args.length < 3) {
                    sender.sendMessage(ColorUtil.colorize("&cUsage: /bank remove_shards <player> <amount>"));
                    return true;
                }
                double amt = parseAmount(sender, args[2]);
                if (Double.isNaN(amt))
                    return true;
                OfflinePlayer t = resolvePlayer(sender, args[1]);
                if (t == null)
                    return true;
                bankManager.adminRemoveShards(t.getUniqueId(), playerName(t, args[1]), amt);
                sender.sendMessage(ColorUtil.colorize(
                        "&aRemoved &e" + BankManager.formatBalance(amt) + " shards &afrom &e" + displayName(t) + "&a."));
            }
            case "wipe_shards" -> {
                if (args.length < 2) {
                    sender.sendMessage(ColorUtil.colorize("&cUsage: /bank wipe_shards <player>"));
                    return true;
                }
                OfflinePlayer t = resolvePlayer(sender, args[1]);
                if (t == null)
                    return true;
                bankManager.adminWipeShards(t.getUniqueId(), playerName(t, args[1]));
                sender.sendMessage(ColorUtil.colorize("&aWiped shards of &e" + displayName(t) + "&a."));
            }

            // ── Gold ──────────────────────────────────────────────────────────
            case "give_gold" -> {
                if (args.length < 3) {
                    sender.sendMessage(ColorUtil.colorize("&cUsage: /bank give_gold <player> <amount>"));
                    return true;
                }
                double amt = parseAmount(sender, args[2]);
                if (Double.isNaN(amt))
                    return true;
                OfflinePlayer t = resolvePlayer(sender, args[1]);
                if (t == null)
                    return true;
                bankManager.adminAddGold(t.getUniqueId(), playerName(t, args[1]), amt);
                sender.sendMessage(ColorUtil.colorize(
                        "&aGave &e" + BankManager.formatBalance(amt) + " gold &ato &e" + displayName(t) + "&a."));
            }
            case "remove_gold" -> {
                if (args.length < 3) {
                    sender.sendMessage(ColorUtil.colorize("&cUsage: /bank remove_gold <player> <amount>"));
                    return true;
                }
                double amt = parseAmount(sender, args[2]);
                if (Double.isNaN(amt))
                    return true;
                OfflinePlayer t = resolvePlayer(sender, args[1]);
                if (t == null)
                    return true;
                bankManager.adminRemoveGold(t.getUniqueId(), playerName(t, args[1]), amt);
                sender.sendMessage(ColorUtil.colorize(
                        "&aRemoved &e" + BankManager.formatBalance(amt) + " gold &afrom &e" + displayName(t) + "&a."));
            }
            case "wipe_gold" -> {
                if (args.length < 2) {
                    sender.sendMessage(ColorUtil.colorize("&cUsage: /bank wipe_gold <player>"));
                    return true;
                }
                OfflinePlayer t = resolvePlayer(sender, args[1]);
                if (t == null)
                    return true;
                bankManager.adminWipeGold(t.getUniqueId(), playerName(t, args[1]));
                sender.sendMessage(ColorUtil.colorize("&aWiped gold of &e" + displayName(t) + "&a."));
            }

            // ── Give item (physical money shards) ────────────────────────────
            case "give_item" -> {
                if (args.length < 3) {
                    sender.sendMessage(ColorUtil.colorize("&cUsage: /bank give_item <player> <amount>"));
                    return true;
                }
                int amt;
                try {
                    amt = Integer.parseInt(args[2]);
                    if (amt <= 0) throw new NumberFormatException();
                } catch (NumberFormatException e) {
                    sender.sendMessage(ColorUtil.colorize("&cInvalid amount: &e" + args[2]));
                    return true;
                }
                Player target = Bukkit.getPlayerExact(args[1]);
                if (target == null) {
                    sender.sendMessage(ColorUtil.colorize("&cPlayer &e" + args[1] + " &cis not online."));
                    return true;
                }
                shardManager.giveShards(target, amt);
                sender.sendMessage(ColorUtil.colorize(
                        "&aGave &e" + amt + " &aMoney Shard" + (amt == 1 ? "" : "s")
                        + " &ato &e" + target.getName() + "&a."));
                target.sendMessage(ColorUtil.colorize(
                        "&aYou received &e" + amt + " &aMoney Shard" + (amt == 1 ? "" : "s") + "&a."));
            }

            // ── Disable ───────────────────────────────────────────────────────
            case "disable" -> {
                if (args.length < 2) {
                    sender.sendMessage(ColorUtil.colorize("&cUsage: /bank disable <player>"));
                    return true;
                }
                OfflinePlayer t = resolvePlayer(sender, args[1]);
                if (t == null)
                    return true;
                boolean nowDisabled = bankManager.toggleDisabled(t.getUniqueId(), playerName(t, args[1]));
                String state = nowDisabled ? "&cdisabled" : "&aenabled";
                sender.sendMessage(
                        ColorUtil.colorize("&aBank account for &e" + displayName(t) + " &ais now " + state + "&a."));
            }

            default -> sender.sendMessage(ColorUtil.colorize(
                    "&cUnknown subcommand. Available: give_money, remove_money, wipe_money, " +
                            "give_shards, remove_shards, wipe_shards, give_gold, remove_gold, wipe_gold, " +
                            "give_item, disable"));
        }

        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
            @NotNull String label, @NotNull String[] args) {
        if (args.length == 1 && sender.isOp()) {
            List<String> subs = Arrays.asList(
                    "give_money", "remove_money", "wipe_money",
                    "give_shards", "remove_shards", "wipe_shards",
                    "give_gold", "remove_gold", "wipe_gold",
                    "give_item", "disable");
            String prefix = args[0].toLowerCase();
            return subs.stream().filter(s -> s.startsWith(prefix)).toList();
        }

        if (args.length == 2 && sender.isOp()) {
            String sub = args[0].toLowerCase();
            boolean needsPlayer = sub.equals("give_money") || sub.equals("remove_money") || sub.equals("wipe_money") ||
                    sub.equals("give_shards") || sub.equals("remove_shards") || sub.equals("wipe_shards") ||
                    sub.equals("give_gold") || sub.equals("remove_gold") || sub.equals("wipe_gold") ||
                    sub.equals("give_item") || sub.equals("disable");
            if (needsPlayer) {
                String prefix = args[1].toLowerCase();
                return Bukkit.getOnlinePlayers().stream()
                        .map(Player::getName)
                        .filter(n -> n.toLowerCase().startsWith(prefix))
                        .toList();
            }
        }

        return Collections.emptyList();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private double parseAmount(CommandSender sender, String raw) {
        try {
            double v = Double.parseDouble(raw);
            if (v <= 0)
                throw new NumberFormatException();
            return v;
        } catch (NumberFormatException e) {
            sender.sendMessage(ColorUtil.colorize("&cInvalid amount: &e" + raw));
            return Double.NaN;
        }
    }

    @SuppressWarnings("deprecation")
    private OfflinePlayer resolvePlayer(CommandSender sender, String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null)
            return online;
        OfflinePlayer offline = Bukkit.getOfflinePlayer(name);
        if (offline.getName() != null)
            return offline;
        sender.sendMessage(ColorUtil.colorize("&cPlayer &e" + name + " &cnot found."));
        return null;
    }

    private static String playerName(OfflinePlayer player, String fallback) {
        return player.getName() != null ? player.getName() : fallback;
    }

    private static String displayName(OfflinePlayer player) {
        return player.getName() != null ? player.getName() : player.getUniqueId().toString();
    }
}
