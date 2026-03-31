package me.revqz.genPvP.Protect;

import me.revqz.genPvP.Protect.flags.RegionType;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class ProtectCommand implements CommandExecutor {

    private final RegionManager regionManager;
    private final Map<UUID, Location> pos1 = new HashMap<>();
    private final Map<UUID, Location> pos2 = new HashMap<>();
    private final Set<UUID> bypassing = new HashSet<>();

    public ProtectCommand(RegionManager regionManager) {
        this.regionManager = regionManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players can use this command.");
            return true;
        }
        if (!player.isOp()) {
            player.sendMessage("§cYou must be OP to use this command.");
            return true;
        }
        if (args.length == 0) {
            player.sendMessage("§eUsage: /region <pos1|pos2|define <name> <type>|bypass>");
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "pos1" -> {
                pos1.put(player.getUniqueId(), player.getLocation().getBlock().getLocation());
                player.sendMessage("§aPos1 set to " + formatLoc(player.getLocation()));
            }
            case "pos2" -> {
                pos2.put(player.getUniqueId(), player.getLocation().getBlock().getLocation());
                player.sendMessage("§aPos2 set to " + formatLoc(player.getLocation()));
            }
            case "define" -> {
                if (args.length < 3) {
                    player.sendMessage("§eUsage: /region define <name> <type>");
                    player.sendMessage("§eValid types: " + Arrays.toString(RegionType.values()));
                    return true;
                }
                Location p1 = pos1.get(player.getUniqueId());
                Location p2 = pos2.get(player.getUniqueId());
                if (p1 == null || p2 == null) {
                    player.sendMessage("§cSet both pos1 and pos2 first.");
                    return true;
                }
                RegionType type;
                try {
                    type = RegionType.valueOf(args[2].toUpperCase());
                } catch (IllegalArgumentException e) {
                    player.sendMessage("§cInvalid type. Valid types: " + Arrays.toString(RegionType.values()));
                    return true;
                }
                regionManager.defineRegion(args[1], type, p1.getWorld().getName(),
                        p1.getBlockX(), p1.getBlockY(), p1.getBlockZ(),
                        p2.getBlockX(), p2.getBlockY(), p2.getBlockZ());
                player.sendMessage("§aRegion '" + args[1] + "' defined as " + type.name() + ".");
            }
            case "bypass" -> {
                toggleBypass(player);
                player.sendMessage(isBypassing(player) ? "§aRegion bypass enabled." : "§cRegion bypass disabled.");
            }
            default -> player.sendMessage("§eUsage: /region <pos1|pos2|define <name> <type>|bypass>");
        }
        return true;
    }

    public boolean isBypassing(Player player) {
        return bypassing.contains(player.getUniqueId());
    }

    public void toggleBypass(Player player) {
        UUID uuid = player.getUniqueId();
        if (!bypassing.remove(uuid)) bypassing.add(uuid);
    }

    private String formatLoc(Location loc) {
        return loc.getBlockX() + ", " + loc.getBlockY() + ", " + loc.getBlockZ();
    }
}
