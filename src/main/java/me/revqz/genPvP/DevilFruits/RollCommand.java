package me.revqz.genPvP.DevilFruits;

import me.revqz.genPvP.util.ColorUtil;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

public class RollCommand implements CommandExecutor, TabCompleter {

    private static final List<String> FRUIT_TYPE_NAMES = Arrays.asList("paramecia", "logia", "zoan");

    private final FruitGUIManager guiManager;
    private final FruitRollManager rollManager;
    private final FruitRollGUI rollGUI;

    public RollCommand(FruitGUIManager guiManager, FruitRollManager rollManager, FruitRollGUI rollGUI) {
        this.guiManager  = guiManager;
        this.rollManager = rollManager;
        this.rollGUI     = rollGUI;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(msg("players-only"));
            return true;
        }

        if (args.length < 1) {
            player.sendMessage(msg("roll-usage"));
            return true;
        }

        FruitType type = parseFruitType(args[0]);
        if (type == null) {
            player.sendMessage(msg("roll-invalid-type"));
            return true;
        }

        UUID uuid = player.getUniqueId();

        if (rollGUI.isRolling(uuid)) {
            player.sendMessage(msg("roll-already-rolling"));
            return true;
        }

        if (rollManager.getRolls(uuid, type) <= 0) {
            player.sendMessage(msg("roll-no-rolls").replace("%type%", type.getDisplayName()));
            return true;
        }

        if (rollGUI.buildEligiblePool(type).isEmpty()) {
            player.sendMessage(msg("roll-no-fruits"));
            return true;
        }

        if (!rollGUI.startRoll(player, type)) {
            player.sendMessage(msg("roll-no-fruits"));
        }

        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String label, @NotNull String[] args) {
        if (args.length == 1) {
            String prefix = args[0].toLowerCase();
            return FRUIT_TYPE_NAMES.stream().filter(s -> s.startsWith(prefix)).toList();
        }
        return Collections.emptyList();
    }

    private FruitType parseFruitType(String input) {
        return switch (input.toLowerCase()) {
            case "paramecia" -> FruitType.PARAMECIA;
            case "logia"     -> FruitType.LOGIA;
            case "zoan"      -> FruitType.ZOAN;
            default          -> null;
        };
    }

    private String msg(String key) {
        return ColorUtil.colorize(
                guiManager.getMessagesConfig().getString(key, "§cMissing message: " + key));
    }
}
