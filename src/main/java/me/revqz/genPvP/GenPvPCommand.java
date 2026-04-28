package me.revqz.genPvP;

import me.revqz.genPvP.Items.AutoCompressor;
import me.revqz.genPvP.Items.AutoCompressorListener;
import me.revqz.genPvP.Items.CustomItemRegistry;
import me.revqz.genPvP.Scoreboard.ScoreboardManager;
import me.revqz.genPvP.Shop.ItemShopManager;
import me.revqz.genPvP.Shop.ShopManager;
import me.revqz.genPvP.util.ColorUtil;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class GenPvPCommand implements CommandExecutor, TabCompleter {

    private static final List<String> HEAD_NAMES = List.of(
            "luffy_head", "zoro_head", "nami_head", "sanji_head"
    );
    private static final List<String> ONEPIECE_NAMES = List.of(
            "luffy_helmet", "luffy_chestplate", "luffy_leggings", "luffy_boots",
            "luffy_sword", "pirate_axe", "pirate_sword", "box_sphere"
    );

    private final GenPvP                   plugin;
    private final ShopManager              shopManager;
    private final ItemShopManager          itemShopManager;
    private final AutoCompressorListener   autoCompressorListener;
    private final CustomItemRegistry       registry;

    public GenPvPCommand(GenPvP plugin, ShopManager shopManager,
                         ItemShopManager itemShopManager,
                         AutoCompressorListener autoCompressorListener,
                         CustomItemRegistry registry) {
        this.plugin                 = plugin;
        this.shopManager            = shopManager;
        this.itemShopManager        = itemShopManager;
        this.autoCompressorListener = autoCompressorListener;
        this.registry               = registry;
    }

    // ── Message helpers ───────────────────────────────────────────────────────

    private String msg(String key) {
        return ColorUtil.colorize(plugin.getConfig().getString("genpvp.messages." + key,
                "&cMessage not configured: genpvp.messages." + key));
    }

    private String msg(String key, String... pairs) {
        String raw = plugin.getConfig().getString("genpvp.messages." + key,
                "&cMessage not configured: genpvp.messages." + key);
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            raw = raw.replace(pairs[i], pairs[i + 1]);
        }
        return ColorUtil.colorize(raw);
    }

    // ── Command dispatch ──────────────────────────────────────────────────────

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("genpvp.admin")) {
            sender.sendMessage(msg("no-permission"));
            return true;
        }

        if (args.length == 0) {
            sender.sendMessage(msg("usage"));
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "reload"    -> handleReload(sender);
            case "item_give" -> handleGiveItem(sender, args);
            case "onepiece"  -> handleRegisterItem(sender, args, "onepiece", ONEPIECE_NAMES);
            case "head"      -> handleRegisterItem(sender, args, "head", HEAD_NAMES);
            default          -> sender.sendMessage(msg("usage"));
        }

        return true;
    }

    // ── Tab completion ────────────────────────────────────────────────────────

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("genpvp.admin")) return List.of();
        String partial = args.length > 0 ? args[args.length - 1].toLowerCase() : "";

        return switch (args.length) {
            case 1 -> filter(List.of("reload", "item_give", "onepiece", "head"), partial);
            case 2 -> switch (args[0].toLowerCase()) {
                case "item_give"        -> filter(availableItems(), partial);
                case "onepiece", "head" -> filter(List.of("set"), partial);
                default                 -> List.of();
            };
            case 3 -> switch (args[0].toLowerCase()) {
                case "item_give" -> Bukkit.getOnlinePlayers().stream()
                        .map(Player::getName)
                        .filter(n -> n.toLowerCase().startsWith(partial))
                        .collect(Collectors.toList());
                case "onepiece", "head" -> filter(List.of("from_hand"), partial);
                default                 -> List.of();
            };
            case 4 -> switch (args[0].toLowerCase()) {
                case "onepiece" -> filter(ONEPIECE_NAMES, partial);
                case "head"     -> filter(HEAD_NAMES, partial);
                default         -> List.of();
            };
            default -> List.of();
        };
    }

    // ── reload ────────────────────────────────────────────────────────────────

    private void handleReload(CommandSender sender) {
        plugin.reloadConfig();
        shopManager.loadShops();
        itemShopManager.reload();
        ScoreboardManager sb = plugin.getScoreboardManager();
        if (sb != null) sb.reload();
        plugin.getSpawnHandler().reload();
        plugin.getXpPickupListener().reload();
        sender.sendMessage(msg("reload-success"));
    }

    // ── item_give ─────────────────────────────────────────────────────────────

    private void handleGiveItem(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(msg("item-give-usage"));
            sender.sendMessage(msg("item-give-available", "%items%", String.join(", ", availableItems())));
            return;
        }

        String itemName = args[1].toLowerCase();
        Player target   = resolveTarget(sender, args.length >= 3 ? args[2] : null);
        if (target == null) return;

        ItemStack item = buildItem(itemName);
        if (item == null) {
            sender.sendMessage(msg("item-give-unknown", "%item%", args[1]));
            sender.sendMessage(msg("item-give-available", "%items%", String.join(", ", availableItems())));
            return;
        }

        Map<Integer, ItemStack> overflow = target.getInventory().addItem(item);
        if (!overflow.isEmpty()) {
            target.getWorld().dropItemNaturally(target.getLocation(), item);
        }

        target.sendMessage(msg("item-give-received", "%item%", itemName));
        if (!target.equals(sender)) {
            sender.sendMessage(msg("item-gave-to", "%item%", itemName, "%player%", target.getName()));
        }
    }

    // ── onepiece / head ───────────────────────────────────────────────────────

    private void handleRegisterItem(CommandSender sender, String[] args, String subCmd,
                                    List<String> suggestedNames) {
        if (args.length < 2 || !args[1].equalsIgnoreCase("set")) {
            sender.sendMessage(msg("register-usage", "%subcmd%", subCmd));
            sender.sendMessage(msg("register-names", "%names%", String.join(", ", suggestedNames)));
            return;
        }
        if (args.length < 3 || !args[2].equalsIgnoreCase("from_hand")) {
            sender.sendMessage(msg("register-hand-only"));
            return;
        }
        if (args.length < 4) {
            sender.sendMessage(msg("register-usage", "%subcmd%", subCmd));
            return;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage(msg("register-player-only"));
            return;
        }

        ItemStack held = player.getInventory().getItemInMainHand();
        if (held == null || held.getType().isAir()) {
            sender.sendMessage(msg("register-hold-item"));
            return;
        }

        String name = args[3].toLowerCase();
        registry.register(name, held);
        player.getInventory().setItemInMainHand(held);
        player.updateInventory();
        sender.sendMessage(msg("register-success", "%name%", name));
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private ItemStack buildItem(String name) {
        if (name.equals("autocompressor")) return AutoCompressor.create(plugin);
        return registry.getTemplate(name);
    }

    private List<String> availableItems() {
        Set<String> items = new LinkedHashSet<>();
        items.add("autocompressor");
        items.addAll(HEAD_NAMES);
        items.addAll(ONEPIECE_NAMES);
        items.addAll(registry.getRegisteredNames());
        return new ArrayList<>(items);
    }

    private Player resolveTarget(CommandSender sender, String name) {
        if (name != null) {
            Player p = Bukkit.getPlayerExact(name);
            if (p == null) sender.sendMessage(msg("player-not-found", "%player%", name));
            return p;
        }
        if (!(sender instanceof Player)) {
            sender.sendMessage(msg("player-required"));
            return null;
        }
        return (Player) sender;
    }

    private static List<String> filter(List<String> options, String partial) {
        if (partial.isEmpty()) return options;
        return options.stream().filter(s -> s.startsWith(partial)).collect(Collectors.toList());
    }
}
