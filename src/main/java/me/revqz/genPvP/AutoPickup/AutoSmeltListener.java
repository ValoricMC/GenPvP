package me.revqz.genPvP.AutoPickup;

import me.revqz.genPvP.util.ColorUtil;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;

/**
 * Handles the /autosmelt command and ground-drop conversion.
 *
 * Ground-drop conversion runs at MONITOR + ignoreCancelled=true so it only
 * fires when a block was actually broken. For blocks in auto-pickup regions,
 * AutoPickupListener handles the items directly (smelt happens there too).
 * This listener catches all other ore breaks that produce a ground entity.
 */
public class AutoSmeltListener implements Listener, CommandExecutor {

    private final JavaPlugin plugin;
    private final AutoSmeltManager autoSmeltManager;

    public AutoSmeltListener(JavaPlugin plugin, AutoSmeltManager autoSmeltManager) {
        this.plugin           = plugin;
        this.autoSmeltManager = autoSmeltManager;
    }

    // ── /autosmelt command ────────────────────────────────────────────────────

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Only players can use this command.");
            return true;
        }

        if (!player.hasPermission("genpvp.autosmelt")) {
            player.sendMessage(msg("no_permission"));
            return true;
        }

        boolean nowEnabled = autoSmeltManager.toggle(player.getUniqueId());
        player.sendMessage(nowEnabled ? msg("enabled") : msg("disabled"));
        return true;
    }

    // ── Ground drop conversion ────────────────────────────────────────────────

    /**
     * Converts ore ground-drops to their smelted form when the miner has auto-smelt on.
     * Only smeltable ores are touched; everything else is left alone.
     *
     * BlockDropItemEvent fires after the block is removed and drop entities are spawned
     * but before they exist in the world — we can edit the drop list directly.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockDrop(BlockDropItemEvent event) {
        Player player = event.getPlayer();
        if (!autoSmeltManager.isEnabled(player.getUniqueId())) return;

        List<org.bukkit.entity.Item> items = event.getItems();
        for (org.bukkit.entity.Item entity : items) {
            ItemStack stack = entity.getItemStack();
            if (!autoSmeltManager.isSmeltable(stack.getType())) continue;
            entity.setItemStack(autoSmeltManager.smelt(stack));
        }
    }

    // ── Config message helper ─────────────────────────────────────────────────

    private String msg(String key) {
        return ColorUtil.colorize(plugin.getConfig().getString("autosmelt.messages." + key, ""));
    }
}
