package me.revqz.genPvP.Protect;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import me.revqz.genPvP.Database.DatabaseManager;
import me.revqz.genPvP.Protect.flags.RegionType;
import org.bson.Document;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import static com.mongodb.client.model.Filters.eq;

public class ProtectCommand implements CommandExecutor, TabCompleter {

    private final JavaPlugin plugin;
    private final RegionManager regionManager;
    private final MongoDatabase db;
    private final boolean dbConnected;

    private final Map<UUID, Location> pos1 = new HashMap<>();
    private final Map<UUID, Location> pos2 = new HashMap<>();
    private final Set<UUID> bypassing = new HashSet<>();

    private final Set<Location> protectedBlocks = ConcurrentHashMap.newKeySet();

    public ProtectCommand(JavaPlugin plugin, RegionManager regionManager, DatabaseManager dbManager) {
        this.plugin        = plugin;
        this.regionManager = regionManager;
        this.db            = dbManager.isMongoConnected() ? dbManager.getDatabase() : null;
        this.dbConnected   = dbManager.isMongoConnected();

        if (dbConnected) loadProtectedBlocks();
    }

    // ── DB persistence ────────────────────────────────────────────────────────

    // ── Protected blocks storage format ──────────────────────────────────────
    // One document per chunk: { _id: "world:chunkX:chunkZ", b: [[x,y,z], ...] }
    // This cuts document count by ~256x vs one-doc-per-block.

    private static String chunkKey(String world, int x, int z) {
        return world + ":" + (x >> 4) + ":" + (z >> 4);
    }

    private void loadProtectedBlocks() {
        try {
            MongoCollection<Document> coll = db.getCollection("protected_blocks");
            int loaded = 0;
            for (Document doc : coll.find()) {
                String id = doc.getString("_id");
                if (id == null) continue;
                String[] parts = id.split(":", 3);
                if (parts.length != 3) continue;
                World world = Bukkit.getWorld(parts[0]);
                if (world == null) continue;
                List<?> blocks = doc.get("b", List.class);
                if (blocks == null) continue;
                for (Object entry : blocks) {
                    if (!(entry instanceof List<?> coords) || coords.size() < 3) continue;
                    int bx = ((Number) coords.get(0)).intValue();
                    int by = ((Number) coords.get(1)).intValue();
                    int bz = ((Number) coords.get(2)).intValue();
                    protectedBlocks.add(new Location(world, bx, by, bz));
                    loaded++;
                }
            }
            plugin.getLogger().info("[ProtectCommand] Loaded " + loaded + " protected block(s) from database.");
        } catch (Exception e) {
            plugin.getLogger().warning("[ProtectCommand] Failed to load protected blocks: " + e.getMessage());
        }
    }

    private void saveProtectedBlocks(List<Location> locations) {
        if (!dbConnected || locations.isEmpty()) return;
        // Group by chunk key
        Map<String, List<List<Integer>>> byChunk = new HashMap<>();
        for (Location loc : locations) {
            String key = chunkKey(loc.getWorld().getName(), loc.getBlockX(), loc.getBlockZ());
            byChunk.computeIfAbsent(key, k -> new ArrayList<>())
                    .add(List.of(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ()));
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            MongoCollection<Document> coll = db.getCollection("protected_blocks");
            for (var entry : byChunk.entrySet()) {
                try {
                    coll.updateOne(
                            eq("_id", entry.getKey()),
                            new Document("$addToSet", new Document("b",
                                    new Document("$each", entry.getValue()))),
                            new com.mongodb.client.model.UpdateOptions().upsert(true));
                } catch (Exception e) {
                    plugin.getLogger().warning("[ProtectCommand] Failed to save chunk " + entry.getKey() + ": " + e.getMessage());
                }
            }
        });
    }

    private void deleteProtectedBlock(Location loc) {
        if (!dbConnected) return;
        final String key = chunkKey(loc.getWorld().getName(), loc.getBlockX(), loc.getBlockZ());
        final List<Integer> coords = List.of(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                db.getCollection("protected_blocks").updateOne(
                        eq("_id", key),
                        new Document("$pull", new Document("b", coords)));
            } catch (Exception e) {
                plugin.getLogger().warning("[ProtectCommand] Failed to delete protected block: " + e.getMessage());
            }
        });
    }

    // ── Command ───────────────────────────────────────────────────────────────

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
            player.sendMessage("§eUsage: /region <pos1|pos2|define <name> <type>|remove <name>|priority <name> <value>|protect <name>|bypass>");
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
            case "remove", "delete" -> {
                if (args.length < 2) {
                    player.sendMessage("§eUsage: /region remove <name>");
                    return true;
                }
                String regionName = args[1].toLowerCase();
                if (regionManager.getRegion(regionName) == null) {
                    player.sendMessage("§cRegion '" + regionName + "' does not exist.");
                    return true;
                }
                regionManager.deleteRegion(regionName);
                player.sendMessage("§aRegion '" + regionName + "' removed.");
            }
            case "priority" -> {
                if (args.length < 3) {
                    player.sendMessage("§eUsage: /region priority <name> <value>");
                    player.sendMessage("§eBase value is 0. Higher = takes precedence in overlapping areas.");
                    return true;
                }
                double priority;
                try {
                    priority = Double.parseDouble(args[2]);
                } catch (NumberFormatException e) {
                    player.sendMessage("§c'" + args[2] + "' is not a valid number.");
                    return true;
                }
                if (!regionManager.setPriority(args[1], priority)) {
                    player.sendMessage("§cRegion '" + args[1] + "' does not exist.");
                    return true;
                }
                player.sendMessage("§aRegion '" + args[1] + "' priority set to §e" + priority + "§a.");
            }
            case "protect" -> {
                if (args.length < 2) {
                    player.sendMessage("§eUsage: /region protect <name>");
                    return true;
                }
                ProtectRegion region = regionManager.getRegion(args[1]);
                if (region == null) {
                    player.sendMessage("§cRegion '" + args[1] + "' does not exist.");
                    return true;
                }
                World world = Bukkit.getWorld(region.getWorld());
                if (world == null) {
                    player.sendMessage("§cWorld '" + region.getWorld() + "' is not loaded.");
                    return true;
                }
                List<Location> newlyProtected = new ArrayList<>();
                for (int x = region.getMinX(); x <= region.getMaxX(); x++) {
                    for (int y = region.getMinY(); y <= region.getMaxY(); y++) {
                        for (int z = region.getMinZ(); z <= region.getMaxZ(); z++) {
                            Block block = world.getBlockAt(x, y, z);
                            if (!block.getType().isAir()) {
                                Location loc = block.getLocation();
                                if (protectedBlocks.add(loc)) {
                                    newlyProtected.add(loc);
                                }
                            }
                        }
                    }
                }
                saveProtectedBlocks(newlyProtected);
                player.sendMessage("§aProtected §e" + newlyProtected.size() + " §anew block(s) in region '§e"
                        + region.getName() + "§a'. §7New blocks placed here will remain breakable.");
            }
            case "bypass" -> {
                toggleBypass(player);
                player.sendMessage(isBypassing(player) ? "§aRegion bypass enabled." : "§cRegion bypass disabled.");
            }
            default -> player.sendMessage("§eUsage: /region <pos1|pos2|define <name> <type>|remove <name>|priority <name> <value>|protect <name>|bypass>");
        }
        return true;
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public boolean isBypassing(Player player) { return bypassing.contains(player.getUniqueId()); }

    public void toggleBypass(Player player) {
        UUID uuid = player.getUniqueId();
        if (!bypassing.remove(uuid)) bypassing.add(uuid);
    }

    public boolean isProtected(Location blockLoc) { return protectedBlocks.contains(blockLoc); }

    public void removeProtected(Location blockLoc) {
        protectedBlocks.remove(blockLoc);
        deleteProtectedBlock(blockLoc);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String formatLoc(Location loc) {
        return loc.getBlockX() + ", " + loc.getBlockY() + ", " + loc.getBlockZ();
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.isOp()) return Collections.emptyList();

        if (args.length == 1) {
            return Arrays.asList("pos1", "pos2", "define", "remove", "priority", "protect", "bypass").stream()
                .filter(s -> s.startsWith(args[0].toLowerCase()))
                .collect(Collectors.toList());
        }

        String sub = args[0].toLowerCase();

        if (args.length == 2) {
            if (sub.equals("define")) return Collections.singletonList("<name>");
            if (sub.equals("remove") || sub.equals("delete") || sub.equals("priority") || sub.equals("protect")) {
                String partial = args[1].toLowerCase();
                return regionManager.getRegionNames().stream()
                    .filter(n -> n.startsWith(partial))
                    .collect(Collectors.toList());
            }
        }

        if (args.length == 3) {
            if (sub.equals("define")) {
                return Arrays.stream(RegionType.values())
                    .map(Enum::name)
                    .filter(n -> n.toLowerCase().startsWith(args[2].toLowerCase()))
                    .collect(Collectors.toList());
            }
            if (sub.equals("priority")) {
                return Arrays.asList("0", "1", "2", "5", "10").stream()
                    .filter(v -> v.startsWith(args[2]))
                    .collect(Collectors.toList());
            }
        }

        return Collections.emptyList();
    }
}
