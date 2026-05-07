package me.revqz.genPvP;

import me.revqz.genPvP.Items.AutoCompressor;
import me.revqz.genPvP.Items.AutoCompressorListener;
import me.revqz.genPvP.Items.CustomItemRegistry;
import me.revqz.genPvP.PitNetherite.PitNetheriteManager;
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
    private final PitNetheriteManager      pitNetheriteManager;

    public GenPvPCommand(GenPvP plugin, ShopManager shopManager,
                         ItemShopManager itemShopManager,
                         AutoCompressorListener autoCompressorListener,
                         CustomItemRegistry registry,
                         PitNetheriteManager pitNetheriteManager) {
        this.plugin                 = plugin;
        this.shopManager            = shopManager;
        this.itemShopManager        = itemShopManager;
        this.autoCompressorListener = autoCompressorListener;
        this.registry               = registry;
        this.pitNetheriteManager    = pitNetheriteManager;
    }

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
            case "reload"           -> handleReload(sender);
            case "item_give"        -> handleGiveItem(sender, args);
            case "onepiece"         -> handleRegisterItem(sender, args, "onepiece", ONEPIECE_NAMES);
            case "head"             -> handleRegisterItem(sender, args, "head", HEAD_NAMES);
            case "debug_armor"      -> handleDebugArmor(sender);
            case "force_reset_pit"  -> handleForceResetPit(sender);
            case "create_world"     -> handleCreateWorld(sender, args);
            default                 -> sender.sendMessage(msg("usage"));
        }

        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("genpvp.admin")) return List.of();
        String partial = args.length > 0 ? args[args.length - 1].toLowerCase() : "";

        return switch (args.length) {
            case 1 -> filter(List.of("reload", "item_give", "onepiece", "head", "debug_armor", "force_reset_pit", "create_world"), partial);
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

    private void handleForceResetPit(CommandSender sender) {
        pitNetheriteManager.forceReset();
        sender.sendMessage(ColorUtil.colorize(
                plugin.getConfig().getString("pit-netherite.force-reset-feedback",
                        "&#FCD05C&lPIT &8» &7Ancient Debris reset forced.")));
    }

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

    private void handleDebugArmor(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cMust be a player.");
            return;
        }
        String[] slotNames = {"Helmet", "Chestplate", "Leggings", "Boots"};
        String[] pieceIds  = me.revqz.genPvP.Items.LuffyArmorManager.PIECE_IDS;
        org.bukkit.inventory.ItemStack[] slots = {
                player.getInventory().getHelmet(),
                player.getInventory().getChestplate(),
                player.getInventory().getLeggings(),
                player.getInventory().getBoots()
        };
        player.sendMessage("§6§l── Armor Debug ──");
        int count = 0;
        for (int i = 0; i < 4; i++) {
            String type = slots[i] == null ? "empty" : slots[i].getType().name();
            String pdcId = (slots[i] != null && !slots[i].getType().isAir())
                    ? registry.getItemId(slots[i]) : "none";
            boolean match = registry.hasId(slots[i], pieceIds[i]);
            if (match) count++;
            java.util.List<String> tLore = registry.getTemplateLore(pieceIds[i]);
            int loreCount = tLore != null ? tLore.size() : 0;
            boolean hasPapi = tLore != null && tLore.stream().anyMatch(l -> l.contains("%"));
            player.sendMessage("§e" + slotNames[i] + "§7: type=§f" + type
                    + "§7 pdc=§f" + pdcId
                    + "§7 expect=§f" + pieceIds[i]
                    + (match ? " §a✓" : " §c✗"));
            player.sendMessage("   §7template=§f" + loreCount + " lines§7 hasPAPI=§f" + hasPapi);
            if (tLore != null && !tLore.isEmpty()) {
                
                String preview = tLore.get(0);
                if (preview.length() > 60) preview = preview.substring(0, 60) + "...";
                player.sendMessage("   §7line[0]=§f" + preview);
            }
        }
        player.sendMessage("§6Detected pieces: §f" + count + "/4");
        player.sendMessage("§6Registered items: §f" + String.join(", ", registry.getRegisteredNames()));
    }

    private void handleCreateWorld(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cMust be a player to use this command.");
            return;
        }
        if (args.length < 2) {
            sender.sendMessage("§cUsage: /genpvp create_world <name>");
            return;
        }
        String worldName = args[1];
        if (Bukkit.getWorld(worldName) != null) {
            sender.sendMessage("§cA world named §e" + worldName + " §calready exists.");
            return;
        }
        sender.sendMessage("§7Creating world §e" + worldName + "§7, please wait...");
        org.bukkit.World world = new org.bukkit.WorldCreator(worldName).createWorld();
        if (world == null) {
            sender.sendMessage("§cFailed to create world §e" + worldName + "§c.");
            return;
        }
        player.teleport(world.getSpawnLocation());
        sender.sendMessage("§aWorld §e" + worldName + " §acreated and you have been teleported to it.");
    }

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
