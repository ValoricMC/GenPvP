package me.revqz.genPvP.CommandSpy;

import me.revqz.genPvP.GenPvP;
import me.revqz.genPvP.util.ColorUtil;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.jetbrains.annotations.NotNull;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class CommandSpyManager implements Listener, CommandExecutor {

    private static final String PERMISSION = "genpvp.commandspy";

    private final GenPvP    plugin;
    private final Set<UUID> activeSpies = ConcurrentHashMap.newKeySet();

    public CommandSpyManager(GenPvP plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
            @NotNull String label, @NotNull String[] args) {

        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players can use /commandspy.");
            return true;
        }

        if (!player.hasPermission(PERMISSION)) {
            player.sendMessage(ColorUtil.colorize(
                    plugin.getConfig().getString("commandspy.messages.no-permission",
                            "&cYou don't have permission to use this command.")));
            return true;
        }

        boolean nowEnabled = activeSpies.add(player.getUniqueId());
        if (!nowEnabled) {
            activeSpies.remove(player.getUniqueId());
        }

        String msg = nowEnabled
                ? plugin.getConfig().getString("commandspy.messages.enabled",
                        "&aCommand spy &lenabled&a.")
                : plugin.getConfig().getString("commandspy.messages.disabled",
                        "&cCommand spy &ldisabled&c.");

        player.sendMessage(ColorUtil.colorize(msg));
        return true;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        if (activeSpies.isEmpty()) return;

        Player sender  = event.getPlayer();
        String message = event.getMessage(); 

        String lower = message.toLowerCase();
        if (lower.equals("/commandspy") || lower.startsWith("/commandspy ")) return;

        String format = plugin.getConfig().getString(
                "commandspy.format",
                "&8[&cSPY&8] &7%player% &8» &f%command%");

        String formatted = ColorUtil.colorize(
                format.replace("%player%", sender.getName())
                      .replace("%command%", message));

        for (UUID uuid : activeSpies) {
            Player spy = Bukkit.getPlayer(uuid);
            if (spy == null || !spy.isOnline()) continue;
            if (spy.equals(sender)) continue; 
            spy.sendMessage(formatted);
        }
    }
}
