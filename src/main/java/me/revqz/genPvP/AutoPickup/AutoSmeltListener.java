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

public class AutoSmeltListener implements Listener, CommandExecutor {

    private final JavaPlugin plugin;
    private final AutoSmeltManager autoSmeltManager;

    public AutoSmeltListener(JavaPlugin plugin, AutoSmeltManager autoSmeltManager) {
        this.plugin           = plugin;
        this.autoSmeltManager = autoSmeltManager;
    }

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

    private String msg(String key) {
        return ColorUtil.colorize(plugin.getConfig().getString("autosmelt.messages." + key, ""));
    }
}
