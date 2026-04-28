package me.revqz.genPvP.Prestige;

import me.revqz.genPvP.util.ColorUtil;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
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

/**
 * Player + admin command for the prestige system.
 *
 * <h3>Player usage (no permission required)</h3>
 * <pre>
 *   /prestige              — prestige up (requires max level)
 * </pre>
 *
 * <h3>Admin usage (OP only)</h3>
 * <pre>
 *   /prestige add_prestige       &lt;player&gt; &lt;amount&gt;
 *   /prestige remove_prestige    &lt;player&gt; &lt;amount&gt;
 *   /prestige reset_prestige     &lt;player&gt;
 *   /prestige blacklist_prestige &lt;player&gt;
 *
 *   /prestige add_level          &lt;player&gt; &lt;amount&gt;
 *   /prestige remove_level       &lt;player&gt; &lt;amount&gt;
 *   /prestige reset_level        &lt;player&gt;
 *   /prestige blacklist_level    &lt;player&gt;
 *
 *   /prestige blacklist_xp       &lt;player&gt;
 * </pre>
 */
public class PrestigeCommand implements CommandExecutor, TabCompleter {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();

    private static final List<String> SUBCOMMANDS = Arrays.asList(
            "add_prestige", "remove_prestige", "reset_prestige", "blacklist_prestige",
            "add_level", "remove_level", "reset_level", "blacklist_level",
            "blacklist_xp"
    );

    private final PrestigeManager prestigeManager;

    public PrestigeCommand(PrestigeManager prestigeManager) {
        this.prestigeManager = prestigeManager;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {

        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players can use /prestige.");
            return true;
        }

        // ── No args: self-prestige for any player ────────────────────────────
        if (args.length == 0) {
            handleSelfPrestige(player);
            return true;
        }

        // ── Admin subcommands (OP only) ──────────────────────────────────────
        if (!player.isOp()) {
            player.sendMessage(ColorUtil.colorize("&cNo permission."));
            return true;
        }

        if (args.length < 2) {
            sendUsage(player);
            return true;
        }

        String sub = args[0].toLowerCase();
        String targetName = args[1];
        Player target = Bukkit.getPlayerExact(targetName);

        if (target == null) {
            player.sendMessage("§cPlayer §e" + targetName + " §cis not online.");
            return true;
        }

        switch (sub) {
            // ── Prestige subcommands ─────────────────────────────────────────
            case "add_prestige" -> {
                int amount = parseAmount(args, player);
                if (amount <= 0) return true;
                int total = prestigeManager.addPrestige(target.getUniqueId(), target.getName(), amount);
                prestigeManager.sendPrestigeAdded(player, target.getName(), amount, total);
            }
            case "remove_prestige" -> {
                int amount = parseAmount(args, player);
                if (amount <= 0) return true;
                int total = prestigeManager.removePrestige(target.getUniqueId(), target.getName(), amount);
                prestigeManager.sendPrestigeRemoved(player, target.getName(), amount, total);
            }
            case "reset_prestige" -> {
                prestigeManager.resetPrestige(target.getUniqueId(), target.getName());
                prestigeManager.sendPrestigeReset(player, target.getName());
            }
            case "blacklist_prestige" -> {
                boolean bl = prestigeManager.togglePrestigeBlacklist(target.getUniqueId(), target.getName());
                prestigeManager.sendPrestigeBlacklistToggle(player, target.getName(), bl);
            }

            // ── Level subcommands ────────────────────────────────────────────
            case "add_level" -> {
                int amount = parseAmount(args, player);
                if (amount <= 0) return true;
                int total = prestigeManager.addLevel(target.getUniqueId(), target.getName(), amount);
                prestigeManager.sendLevelAdded(player, target.getName(), amount, total);
            }
            case "remove_level" -> {
                int amount = parseAmount(args, player);
                if (amount <= 0) return true;
                int total = prestigeManager.removeLevel(target.getUniqueId(), target.getName(), amount);
                prestigeManager.sendLevelRemoved(player, target.getName(), amount, total);
            }
            case "reset_level" -> {
                prestigeManager.resetLevel(target.getUniqueId(), target.getName());
                prestigeManager.sendLevelReset(player, target.getName());
            }
            case "blacklist_level" -> {
                boolean bl = prestigeManager.toggleLevelBlacklist(target.getUniqueId(), target.getName());
                prestigeManager.sendLevelBlacklistToggle(player, target.getName(), bl);
            }

            // ── XP blacklist subcommand ──────────────────────────────────────
            case "blacklist_xp" -> {
                boolean bl = prestigeManager.toggleXpBlacklist(target.getUniqueId(), target.getName());
                prestigeManager.sendXpBlacklistToggle(player, target.getName(), bl);
            }

            default -> sendUsage(player);
        }
        return true;
    }

    // ── Self-prestige logic ──────────────────────────────────────────────────

    /**
     * Attempts to prestige the player. Requires level == levelsPerPrestige.
     * Resets level to 0 and XP to 0, increments prestige by 1.
     */
    private void handleSelfPrestige(Player player) {
        java.util.UUID uuid = player.getUniqueId();

        // Check if data is loaded
        if (!prestigeManager.isLoaded(uuid)) {
            player.sendMessage(LEGACY.deserialize(ColorUtil.colorize(
                    "&cYour data is still loading, please wait.")));
            return;
        }

        // Check if blacklisted
        if (prestigeManager.isPrestigeBlacklisted(uuid)) {
            player.sendMessage(LEGACY.deserialize(ColorUtil.colorize(
                    "&cYou are blacklisted from prestiging.")));
            return;
        }

        // Check if level requirement is met
        int currentLevel = prestigeManager.getLevel(uuid);
        int required = prestigeManager.getLevelsPerPrestige();
        if (currentLevel < required) {
            player.sendMessage(LEGACY.deserialize(ColorUtil.colorize(
                    "&cYou need to reach level &e" + required + " &cbefore you can prestige. "
                    + "(Current: &e" + currentLevel + "&c)")));
            return;
        }

        // Perform the prestige
        int oldPrestige = prestigeManager.getPrestige(uuid);
        prestigeManager.prestigeUp(uuid, player.getName());
        int newPrestige = prestigeManager.getPrestige(uuid);

        player.sendMessage(LEGACY.deserialize(ColorUtil.colorize(
                "&a&l✦ PRESTIGE UP! &aYou are now prestige &e&l" + newPrestige + "&a! "
                + "&7(Level and XP have been reset)")));
    }

    // ── Tab completion ───────────────────────────────────────────────────────

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
            // Only show amount hint for commands that need an amount
            if (sub.equals("add_prestige") || sub.equals("remove_prestige")
                    || sub.equals("add_level") || sub.equals("remove_level")) {
                return Arrays.asList("1", "5", "10");
            }
        }

        return Collections.emptyList();
    }

    private int parseAmount(String[] args, Player admin) {
        if (args.length < 3) {
            admin.sendMessage("§cUsage: /prestige " + args[0] + " <player> <amount>");
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
        admin.sendMessage("§e/prestige §7— prestige up (requires max level)");
        admin.sendMessage("§e/prestige add_prestige <player> <amount>");
        admin.sendMessage("§e/prestige remove_prestige <player> <amount>");
        admin.sendMessage("§e/prestige reset_prestige <player>");
        admin.sendMessage("§e/prestige blacklist_prestige <player>");
        admin.sendMessage("§e/prestige add_level <player> <amount>");
        admin.sendMessage("§e/prestige remove_level <player> <amount>");
        admin.sendMessage("§e/prestige reset_level <player>");
        admin.sendMessage("§e/prestige blacklist_level <player>");
        admin.sendMessage("§e/prestige blacklist_xp <player>");
    }
}
