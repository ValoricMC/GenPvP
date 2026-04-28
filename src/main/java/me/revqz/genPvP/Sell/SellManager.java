package me.revqz.genPvP.Sell;

import me.revqz.genPvP.Bank.BankManager;
import me.revqz.genPvP.GenPvP;
import me.revqz.genPvP.util.ColorUtil;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

public class SellManager {

    /** One configured sellable item. */
    private record SellItem(Material material, int amount, double price) {}

    private final GenPvP plugin;
    private final BankManager bankManager;
    private final List<SellItem> sellItems = new ArrayList<>();

    // Message templates (colour-translated at load time)
    private String msgSold;
    private String msgSoldLine;
    private String msgNothing;

    public SellManager(GenPvP plugin, BankManager bankManager) {
        this.plugin      = plugin;
        this.bankManager = bankManager;
        reload();
    }

    // ── Config ────────────────────────────────────────────────────────────────

    public void reload() {
        sellItems.clear();

        ConfigurationSection cfg = plugin.getConfig().getConfigurationSection("sell");
        if (cfg == null) return;

        for (var raw : cfg.getMapList("items")) {
            Object matObj   = raw.get("material");
            Object amtObj   = raw.get("amount");
            Object priceObj = raw.get("price");
            if (matObj == null || amtObj == null || priceObj == null) continue;

            Material mat = Material.matchMaterial(matObj.toString());
            if (mat == null) {
                plugin.getLogger().warning("[SellManager] Unknown material: " + matObj);
                continue;
            }
            int    amount;
            double price;
            try {
                amount = amtObj instanceof Number n ? n.intValue() : Integer.parseInt(amtObj.toString());
                price  = priceObj instanceof Number n ? n.doubleValue() : Double.parseDouble(priceObj.toString());
            } catch (NumberFormatException nfe) {
                plugin.getLogger().warning("[SellManager] Bad amount/price for material " + matObj + ": " + nfe.getMessage());
                continue;
            }
            if (amount < 1 || price < 0) continue;
            sellItems.add(new SellItem(mat, amount, price));
        }

        msgSold     = ColorUtil.colorize(cfg.getString("messages.sold",
                "&8[&aSell&8] &fYou sold items for &a$%total%&f!"));
        msgSoldLine = ColorUtil.colorize(cfg.getString("messages.sold-line",
                "&8  » &7%amount%x %material% &8(&a$%earned%&8)"));
        msgNothing  = ColorUtil.colorize(cfg.getString("messages.nothing",
                "&8[&aSell&8] &cYou don't have any sellable items."));
    }

    // ── Sell logic ────────────────────────────────────────────────────────────

    /**
     * Scans the player's inventory, removes every complete sell-unit of each
     * configured item, credits the money, and sends a summary.
     *
     * Partial units (items that don't fill a complete sell group) are left untouched.
     */
    public void sellAll(Player player) {
        if (!bankManager.isLoaded(player.getUniqueId())) {
            player.sendMessage(ColorUtil.colorize("&cYour bank data is still loading, try again in a moment."));
            return;
        }

        double totalEarned = 0;
        List<String> lines = new ArrayList<>();

        for (SellItem def : sellItems) {
            int inInventory = countItem(player, def.material());
            int units       = inInventory / def.amount();  // only complete sell-groups
            if (units == 0) continue;

            int    toRemove = units * def.amount();
            double earned   = units * def.price();

            removeItems(player, def.material(), toRemove);
            bankManager.addBalance(player.getUniqueId(), player.getName(), earned);
            totalEarned += earned;

            lines.add(msgSoldLine
                    .replace("%amount%",   String.valueOf(toRemove))
                    .replace("%material%", formatName(def.material()))
                    .replace("%earned%",   BankManager.formatBalance(earned)));
        }

        if (totalEarned == 0) {
            player.sendMessage(msgNothing);
            return;
        }

        player.sendMessage(msgSold.replace("%total%", BankManager.formatBalance(totalEarned)));
        for (String line : lines) player.sendMessage(line);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Counts how many of a material are in the player's inventory (all slots). */
    private static int countItem(Player player, Material mat) {
        int total = 0;
        for (ItemStack stack : player.getInventory().getContents()) {
            if (stack != null && stack.getType() == mat) total += stack.getAmount();
        }
        return total;
    }

    /**
     * Removes exactly {@code toRemove} items of {@code mat} from the player's
     * inventory, working slot-by-slot. Slots that become empty are set to null.
     * Never removes more than {@code toRemove}.
     */
    private static void removeItems(Player player, Material mat, int toRemove) {
        ItemStack[] contents = player.getInventory().getContents();
        for (int i = 0; i < contents.length && toRemove > 0; i++) {
            ItemStack stack = contents[i];
            if (stack == null || stack.getType() != mat) continue;

            int take = Math.min(toRemove, stack.getAmount());
            int left = stack.getAmount() - take;

            if (left == 0) {
                player.getInventory().setItem(i, null);
            } else {
                stack.setAmount(left);
            }
            toRemove -= take;
        }
    }

    /** Converts a Material enum name to a readable title-case string. */
    private static String formatName(Material mat) {
        String raw = mat.name().replace('_', ' ').toLowerCase();
        StringBuilder sb = new StringBuilder();
        for (String word : raw.split(" ")) {
            if (!word.isEmpty()) {
                sb.append(Character.toUpperCase(word.charAt(0)));
                sb.append(word.substring(1));
                sb.append(' ');
            }
        }
        return sb.toString().trim();
    }
}
