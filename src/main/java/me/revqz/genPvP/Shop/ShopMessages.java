package me.revqz.genPvP.Shop;

import me.revqz.genPvP.util.ColorUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

public class ShopMessages {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();

    private String purchase;
    private String insufficientFunds;
    private String bankDisabled;
    private String bankLoading;
    private String noMenus;
    private String unknownShop;
    private String slowDown;
    private String prestigeRequired;
    private String requiresItem;

    public ShopMessages(FileConfiguration config) {
        reload(config);
    }

    public void reload(FileConfiguration config) {
        purchase          = get(config, "shop.messages.purchase",
                "&aPurchased &e%amount%x %item% &afor &e%price% %currency%&a.");
        insufficientFunds = get(config, "shop.messages.insufficient-funds",
                "&cNot enough %currency%! &7Need &e%need% &7| Have &e%have%");
        bankDisabled      = get(config, "shop.messages.bank-disabled",
                "&cYour bank account is disabled.");
        bankLoading       = get(config, "shop.messages.bank-loading",
                "&7Your bank data is still loading, please wait.");
        noMenus           = get(config, "shop.messages.no-menus",
                "&cNo shop menus are configured.");
        unknownShop       = get(config, "shop.messages.unknown-shop",
                "&cUnknown shop.");
        slowDown          = get(config, "shop.messages.slow-down",
                "&cSlow down friend!");
        prestigeRequired  = get(config, "shop.messages.prestige-required",
                "&cYou need Prestige level %level% to buy this item.");
        requiresItem      = get(config, "shop.messages.requires-item",
                "&cYou need a &e%item% &cto unlock this upgrade.");
    }

    public void sendPurchase(Player player, int qty, String itemName, String price, String currency) {
        send(player, purchase
                .replace("%amount%",   String.valueOf(qty))
                .replace("%item%",     itemName)
                .replace("%price%",    price)
                .replace("%currency%", currency));
    }

    public void sendInsufficientFunds(Player player, String currency, String need, String have) {
        send(player, insufficientFunds
                .replace("%currency%", currency)
                .replace("%need%",     need)
                .replace("%have%",     have));
    }

    public void sendBankDisabled(Player player)  { send(player, bankDisabled); }
    public void sendBankLoading(Player player)   { send(player, bankLoading); }
    public void sendNoMenus(Player player)       { send(player, noMenus); }
    public void sendUnknownShop(Player player)   { send(player, unknownShop); }
    public void sendSlowDown(Player player)      { send(player, slowDown); }

    public void sendPrestigeRequired(Player player, int level) {
        send(player, prestigeRequired.replace("%level%", String.valueOf(level)));
    }

    public void sendRequiresItem(Player player, String itemLabel) {
        send(player, requiresItem.replace("%item%", itemLabel));
    }

    private static void send(Player player, String message) {
        Component comp = LEGACY.deserialize(ColorUtil.colorize(message));
        player.sendActionBar(comp);
    }

    private static String get(FileConfiguration cfg, String path, String fallback) {
        return cfg.isString(path) ? cfg.getString(path) : fallback;
    }
}
