package me.revqz.genPvP.DevilFruits;

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
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Handles all {@code /fruit} subcommands.
 *
 * <pre>
 *   /fruit give      &lt;player&gt; &lt;fruit&gt;   — admin only, give a fruit (online or offline)
 *   /fruit remove    &lt;player&gt; &lt;fruit&gt;   — admin only, remove a fruit (online or offline)
 *   /fruit equip     &lt;fruit&gt;            — everyone, equip an owned fruit
 * </pre>
 */
public class FruitCommand implements CommandExecutor, TabCompleter {

    private static final List<String> OP_SUBCOMMANDS = Arrays.asList(
            "give", "remove", "equip", "unequip", "logia", "paramecia", "zoan",
            "roll_give", "roll_giveall", "give_shard", "disable", "enable"
    );
    private static final List<String> PLAYER_SUBCOMMANDS = Arrays.asList(
            "equip", "unequip", "logia", "paramecia", "zoan"
    );
    private static final List<String> FRUIT_TYPE_NAMES = Arrays.asList(
            "paramecia", "logia", "zoan"
    );

    private final GenPvP plugin;
    private final DevilFruitManager fruitManager;
    private final FruitGUIManager guiManager;
    private final FruitRollManager rollManager;
    private final FruitRollGUI rollGUI;
    private DevilFruitShardListener shardListener;

    public FruitCommand(GenPvP plugin, DevilFruitManager fruitManager, FruitGUIManager guiManager,
                        FruitRollManager rollManager, FruitRollGUI rollGUI) {
        this.plugin       = plugin;
        this.fruitManager = fruitManager;
        this.guiManager   = guiManager;
        this.rollManager  = rollManager;
        this.rollGUI      = rollGUI;
    }

    /** Injected after construction — needed for /fruit give_shard. */
    public void setShardListener(DevilFruitShardListener listener) {
        this.shardListener = listener;
    }

    // ── Command ───────────────────────────────────────────────────────────────

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {

        if (!(sender instanceof Player player)) {
            sender.sendMessage(msg("players-only"));
            return true;
        }

        if (args.length == 0) {
            guiManager.openGeneral(player);
            return true;
        }

        String sub = args[0].toLowerCase();

        switch (sub) {
            case "give"      -> handleGive(player, args);
            case "remove"    -> handleRemove(player, args);
            case "equip"     -> handleEquip(player, args);
            case "unequip"   -> handleUnequip(player);
            case "paramecia" -> guiManager.openType(player, FruitType.PARAMECIA);
            case "zoan"      -> guiManager.openType(player, FruitType.ZOAN);
            case "logia"     -> guiManager.openType(player, FruitType.LOGIA);
            case "roll_give"    -> handleRollGive(player, args);
            case "roll_giveall" -> handleRollGiveAll(player, args);
            case "give_shard"   -> handleGiveShard(player, args);
            case "disable"      -> handleDisable(player, args);
            case "enable"       -> handleEnable(player, args);
            default             -> sendUsage(player);
        }

        return true;
    }

    // ── Give ──────────────────────────────────────────────────────────────────

    private void handleGive(Player admin, String[] args) {
        if (!admin.hasPermission("genpvp.admin")) {
            admin.sendMessage(msg("no-permission"));
            return;
        }
        if (args.length < 3) {
            admin.sendMessage(msg("give-usage"));
            return;
        }

        DevilFruit fruit = DevilFruit.fromKey(args[2]);
        if (fruit == null) {
            admin.sendMessage(msg("unknown-fruit").replace("%fruits%", allFruitKeys()));
            return;
        }

        Player target = Bukkit.getPlayerExact(args[1]);
        if (target != null) {
            // Online path — update memory + DB
            if (!fruitManager.giveFruit(target.getUniqueId(), fruit)) {
                admin.sendMessage(msg("fruit-already-owned"));
                return;
            }
            // Auto-equip if the player has nothing currently equipped
            if (fruitManager.getEquippedFruit(target.getUniqueId()) == null) {
                fruitManager.setEquipped(target.getUniqueId(), fruit.getKey());
            }
            admin.sendMessage(msg("fruit-given")
                    .replace("%fruit%", fruit.getDisplayName())
                    .replace("%player%", target.getName()));
        } else {
            // Offline path — write directly to DB
            @SuppressWarnings("deprecation")
            OfflinePlayer offline = Bukkit.getOfflinePlayer(args[1]);
            if (!offline.hasPlayedBefore()) {
                admin.sendMessage(msg("player-never-joined").replace("%player%", args[1]));
                return;
            }
            UUID uuid = offline.getUniqueId();
            String name = offline.getName() != null ? offline.getName() : args[1];
            fruitManager.giveOffline(uuid, fruit, success -> {
                if (!success) {
                    admin.sendMessage(msg("fruit-already-owned"));
                } else {
                    admin.sendMessage(msg("fruit-given")
                            .replace("%fruit%", fruit.getDisplayName())
                            .replace("%player%", name));
                }
            });
        }
    }

    // ── Remove ────────────────────────────────────────────────────────────────

    private void handleRemove(Player admin, String[] args) {
        if (!admin.hasPermission("genpvp.admin")) {
            admin.sendMessage(msg("no-permission"));
            return;
        }
        if (args.length < 3) {
            admin.sendMessage(msg("remove-usage"));
            return;
        }

        DevilFruit fruit = DevilFruit.fromKey(args[2]);
        if (fruit == null) {
            admin.sendMessage(msg("unknown-fruit").replace("%fruits%", allFruitKeys()));
            return;
        }

        Player target = Bukkit.getPlayerExact(args[1]);
        if (target != null) {
            // Online path — update memory + DB
            if (!fruitManager.removeFruit(target.getUniqueId(), fruit)) {
                admin.sendMessage(msg("fruit-not-owned"));
                return;
            }
            admin.sendMessage(msg("fruit-removed")
                    .replace("%fruit%", fruit.getDisplayName())
                    .replace("%player%", target.getName()));
        } else {
            // Offline path — write directly to DB
            @SuppressWarnings("deprecation")
            OfflinePlayer offline = Bukkit.getOfflinePlayer(args[1]);
            if (!offline.hasPlayedBefore()) {
                admin.sendMessage(msg("player-never-joined").replace("%player%", args[1]));
                return;
            }
            UUID uuid = offline.getUniqueId();
            String name = offline.getName() != null ? offline.getName() : args[1];
            fruitManager.removeOffline(uuid, fruit, success -> {
                if (!success) {
                    admin.sendMessage(msg("fruit-not-owned"));
                } else {
                    admin.sendMessage(msg("fruit-removed")
                            .replace("%fruit%", fruit.getDisplayName())
                            .replace("%player%", name));
                }
            });
        }
    }

    // ── Equip ─────────────────────────────────────────────────────────────────

    private void handleEquip(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage(msg("equip-usage"));
            return;
        }

        DevilFruit fruit = DevilFruit.fromKey(args[1]);
        if (fruit == null) {
            player.sendMessage(msg("unknown-fruit").replace("%fruits%", allFruitKeys()));
            return;
        }

        if (!fruitManager.getOwnedFruits(player.getUniqueId()).contains(fruit.getKey())) {
            player.sendMessage(msg("fruit-not-yours"));
            return;
        }

        if (!fruitManager.equipFruit(player.getUniqueId(), fruit)) {
            player.sendMessage(msg("fruit-not-yours"));
            return;
        }

        player.sendMessage(msg("fruit-equipped").replace("%fruit%", fruit.getDisplayName()));
    }

    // ── Unequip ──────────────────────────────────────────────────────────────

    private void handleUnequip(Player player) {
        UUID uuid = player.getUniqueId();
        String equipped = fruitManager.getEquippedFruit(uuid);
        if (equipped == null) {
            player.sendMessage(msg("no-fruit-equipped"));
            return;
        }
        fruitManager.unequip(uuid);
        player.sendMessage(msg("fruit-unequipped"));
    }

    // ── Roll Give (admin) ─────────────────────────────────────────────────────

    private void handleRollGive(Player admin, String[] args) {
        if (!admin.hasPermission("genpvp.admin")) {
            admin.sendMessage(msg("no-permission"));
            return;
        }
        if (args.length < 4) {
            admin.sendMessage(msg("roll-give-usage"));
            return;
        }

        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            admin.sendMessage(msg("player-never-joined").replace("%player%", args[1]));
            return;
        }

        FruitType type = parseFruitType(args[2]);
        if (type == null) {
            admin.sendMessage(msg("roll-invalid-type"));
            return;
        }

        int amount;
        try {
            amount = Integer.parseInt(args[3]);
        } catch (NumberFormatException e) {
            admin.sendMessage(msg("roll-invalid-amount"));
            return;
        }
        if (amount <= 0) {
            admin.sendMessage(msg("roll-invalid-amount"));
            return;
        }

        rollManager.addRolls(target.getUniqueId(), type, amount);

        admin.sendMessage(msg("roll-given")
                .replace("%amount%", String.valueOf(amount))
                .replace("%type%", type.getDisplayName())
                .replace("%player%", target.getName()));

        target.sendMessage(msg("roll-received")
                .replace("%amount%", String.valueOf(amount))
                .replace("%type%", type.getDisplayName()));
    }

    // ── Roll Give All (admin) ─────────────────────────────────────────────────

    private void handleRollGiveAll(Player admin, String[] args) {
        if (!admin.isOp()) {
            admin.sendMessage(msg("no-permission"));
            return;
        }
        if (args.length < 3) {
            admin.sendMessage(msg("roll-giveall-usage"));
            return;
        }

        FruitType type = parseFruitType(args[1]);
        if (type == null) {
            admin.sendMessage(msg("roll-invalid-type"));
            return;
        }

        int amount;
        try {
            amount = Integer.parseInt(args[2]);
        } catch (NumberFormatException e) {
            admin.sendMessage(msg("roll-invalid-amount"));
            return;
        }
        if (amount <= 0) {
            admin.sendMessage(msg("roll-invalid-amount"));
            return;
        }

        int finalAmount = amount;
        FruitType finalType = type;
        Collection<? extends Player> online = Bukkit.getOnlinePlayers();
        for (Player target : online) {
            rollManager.addRolls(target.getUniqueId(), finalType, finalAmount);
            target.sendMessage(msg("roll-received")
                    .replace("%amount%", String.valueOf(finalAmount))
                    .replace("%type%", finalType.getDisplayName()));
        }

        admin.sendMessage(msg("roll-giveall-done")
                .replace("%amount%", String.valueOf(amount))
                .replace("%type%", type.getDisplayName())
                .replace("%count%", String.valueOf(online.size())));
    }

    // ── Give Shard (admin) ────────────────────────────────────────────────────

    private void handleGiveShard(Player admin, String[] args) {
        if (!admin.hasPermission("genpvp.admin")) {
            admin.sendMessage(msg("no-permission"));
            return;
        }
        if (args.length < 3) {
            admin.sendMessage(msg("give-shard-usage"));
            return;
        }
        if (shardListener == null) {
            admin.sendMessage("§cShard system is not loaded.");
            return;
        }

        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            admin.sendMessage(msg("player-never-joined").replace("%player%", args[1]));
            return;
        }

        int amount;
        try {
            amount = Integer.parseInt(args[2]);
        } catch (NumberFormatException e) {
            admin.sendMessage(msg("give-shard-usage"));
            return;
        }
        if (amount <= 0) {
            admin.sendMessage(msg("give-shard-usage"));
            return;
        }

        // Give shards in stacks of 64, overflow drops at player's feet
        int remaining = amount;
        while (remaining > 0) {
            int batch = Math.min(remaining, 64);
            org.bukkit.inventory.ItemStack shard = new org.bukkit.inventory.ItemStack(
                    org.bukkit.Material.AMETHYST_SHARD, batch);
            org.bukkit.inventory.meta.ItemMeta meta = shard.getItemMeta();
            if (meta != null) {
                // Re-use the listener's shard key + config for consistency
                meta.displayName(noItalic(net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
                        .builder().character('&').hexColors().build()
                        .deserialize(plugin.getConfig().getString(
                                "devil-fruit-shard.item.name", "&d&lDevil Fruit Shard"))));
                java.util.List<net.kyori.adventure.text.Component> lore = new java.util.ArrayList<>();
                for (String line : plugin.getConfig().getStringList("devil-fruit-shard.item.lore")) {
                    lore.add(line.isEmpty()
                            ? net.kyori.adventure.text.Component.empty()
                            : noItalic(net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
                                    .builder().character('&').hexColors().build()
                                    .deserialize(ColorUtil.colorize(line))));
                }
                meta.lore(lore);
                meta.getPersistentDataContainer().set(
                        shardListener.getShardKey(),
                        org.bukkit.persistence.PersistentDataType.BYTE, (byte) 1);
                shard.setItemMeta(meta);
            }
            target.getInventory().addItem(shard).values()
                    .forEach(lo -> target.getWorld().dropItemNaturally(target.getLocation(), lo));
            remaining -= batch;
        }

        admin.sendMessage(msg("give-shard-given")
                .replace("%amount%", String.valueOf(amount))
                .replace("%player%", target.getName()));
    }

    private static net.kyori.adventure.text.Component noItalic(net.kyori.adventure.text.Component c) {
        return c.decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false);
    }

    // ── Disable / Enable (OP) ────────────────────────────────────────────────

    private void handleDisable(Player admin, String[] args) {
        if (!admin.isOp()) {
            admin.sendMessage(msg("no-permission"));
            return;
        }
        if (args.length < 2) {
            admin.sendMessage(msg("disable-usage"));
            return;
        }
        DevilFruit fruit = DevilFruit.fromKey(args[1]);
        if (fruit == null) {
            admin.sendMessage(msg("unknown-fruit").replace("%fruits%", allFruitKeys()));
            return;
        }
        if (!fruitManager.disableFruitKey(fruit.getKey())) {
            admin.sendMessage(msg("fruit-already-disabled").replace("%fruit%", fruit.getDisplayName()));
            return;
        }
        admin.sendMessage(msg("fruit-disabled").replace("%fruit%", fruit.getDisplayName()));
    }

    private void handleEnable(Player admin, String[] args) {
        if (!admin.isOp()) {
            admin.sendMessage(msg("no-permission"));
            return;
        }
        if (args.length < 2) {
            admin.sendMessage(msg("enable-usage"));
            return;
        }
        DevilFruit fruit = DevilFruit.fromKey(args[1]);
        if (fruit == null) {
            admin.sendMessage(msg("unknown-fruit").replace("%fruits%", allFruitKeys()));
            return;
        }
        if (!fruitManager.enableFruitKey(fruit.getKey())) {
            admin.sendMessage(msg("fruit-not-disabled").replace("%fruit%", fruit.getDisplayName()));
            return;
        }
        admin.sendMessage(msg("fruit-enabled").replace("%fruit%", fruit.getDisplayName()));
    }

    // ── Tab completion ────────────────────────────────────────────────────────

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                       @NotNull String label, @NotNull String[] args) {

        List<String> subs = sender.hasPermission("genpvp.admin") ? OP_SUBCOMMANDS : PLAYER_SUBCOMMANDS;

        if (args.length == 1) {
            String prefix = args[0].toLowerCase();
            return subs.stream().filter(s -> s.startsWith(prefix)).toList();
        }

        String sub = args[0].toLowerCase();

        if (args.length == 2) {
            switch (sub) {
                case "give", "remove", "roll_give", "give_shard" -> {
                    if (!sender.hasPermission("genpvp.admin")) return Collections.emptyList();
                    String prefix = args[1].toLowerCase();
                    return Bukkit.getOnlinePlayers().stream()
                            .map(Player::getName)
                            .filter(n -> n.toLowerCase().startsWith(prefix))
                            .toList();
                }
                case "disable" -> {
                    if (!sender.isOp()) return Collections.emptyList();
                    String prefix = args[1].toLowerCase();
                    return Arrays.stream(DevilFruit.values())
                            .map(DevilFruit::getKey)
                            .filter(k -> k.startsWith(prefix))
                            .toList();
                }
                case "enable" -> {
                    if (!sender.isOp()) return Collections.emptyList();
                    String prefix = args[1].toLowerCase();
                    return fruitManager.getDisabledFruitKeys().stream()
                            .filter(k -> k.startsWith(prefix))
                            .toList();
                }
                case "equip" -> {
                    if (!(sender instanceof Player p)) return Collections.emptyList();
                    String prefix = args[1].toLowerCase();
                    return fruitManager.getOwnedFruits(p.getUniqueId()).stream()
                            .filter(k -> k.startsWith(prefix))
                            .toList();
                }
            }
        }

        if (args.length == 3) {
            switch (sub) {
                case "give" -> {
                    if (!sender.hasPermission("genpvp.admin")) return Collections.emptyList();
                    String prefix = args[2].toLowerCase();
                    return Arrays.stream(DevilFruit.values())
                            .map(DevilFruit::getKey)
                            .filter(k -> k.startsWith(prefix))
                            .toList();
                }
                case "remove" -> {
                    if (!sender.hasPermission("genpvp.admin")) return Collections.emptyList();
                    Player target = Bukkit.getPlayerExact(args[1]);
                    if (target == null) return Collections.emptyList();
                    String prefix = args[2].toLowerCase();
                    return fruitManager.getOwnedFruits(target.getUniqueId()).stream()
                            .filter(k -> k.startsWith(prefix))
                            .toList();
                }
                case "roll_give", "roll_giveall" -> {
                    if (!sender.hasPermission("genpvp.admin")) return Collections.emptyList();
                    String prefix = args[2].toLowerCase();
                    return FRUIT_TYPE_NAMES.stream()
                            .filter(s -> s.startsWith(prefix))
                            .toList();
                }
            }
        }

        if (args.length == 4 && sub.equals("roll_give")) {
            if (!sender.hasPermission("genpvp.admin")) return Collections.emptyList();
            return Arrays.asList("1", "5", "10");
        }

        if (args.length == 3 && sub.equals("give_shard")) {
            if (!sender.hasPermission("genpvp.admin")) return Collections.emptyList();
            return Arrays.asList("1", "5", "10", "32", "64");
        }

        if (args.length == 3 && sub.equals("roll_giveall")) {
            if (!sender.hasPermission("genpvp.admin")) return Collections.emptyList();
            return Arrays.asList("1", "5", "10");
        }

        return Collections.emptyList();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private FruitType parseFruitType(String input) {
        if (input == null) return null;
        return switch (input.toLowerCase()) {
            case "paramecia" -> FruitType.PARAMECIA;
            case "logia"     -> FruitType.LOGIA;
            case "zoan"      -> FruitType.ZOAN;
            default          -> null;
        };
    }

    private String msg(String key) {
        String raw = guiManager.getMessagesConfig().getString(key, "§cMissing message: " + key);
        return translateHexColors(raw);
    }

    @SuppressWarnings("deprecation")
    private static String translateHexColors(String message) {
        java.util.regex.Pattern hex = java.util.regex.Pattern.compile("&(#[A-Fa-f0-9]{6})");
        java.util.regex.Matcher matcher = hex.matcher(message);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String colour = matcher.group(1);
            StringBuilder replacement = new StringBuilder("§x");
            for (char c : colour.substring(1).toCharArray()) {
                replacement.append('§').append(c);
            }
            matcher.appendReplacement(sb, replacement.toString());
        }
        matcher.appendTail(sb);
        return org.bukkit.ChatColor.translateAlternateColorCodes('&', sb.toString());
    }

    private String allFruitKeys() {
        return Arrays.stream(DevilFruit.values())
                .map(DevilFruit::getKey)
                .collect(Collectors.joining(", "));
    }

    private void sendUsage(Player player) {
        player.sendMessage(msg("usage-header"));
        player.sendMessage(msg("usage-equip"));
        player.sendMessage(msg("usage-unequip"));
        player.sendMessage(msg("roll-usage"));
        if (player.hasPermission("genpvp.admin")) {
            player.sendMessage(msg("usage-give"));
            player.sendMessage(msg("usage-remove"));
            player.sendMessage(msg("roll-give-usage"));
            player.sendMessage(msg("give-shard-usage"));
        }
    }
}
