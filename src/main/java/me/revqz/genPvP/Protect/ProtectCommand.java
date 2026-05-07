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

    private final ConcurrentHashMap<String, Set<Long>> protectedBlocks = new ConcurrentHashMap<>();

    private volatile boolean blocksLoaded = false;

    private static long packBlock(int x, int y, int z) {
        return ((long) x & 0x3FFFFFFL) << 38
             | ((long) z & 0x3FFFFFFL) << 12
             | ((long) y & 0xFFFL);
    }

    private boolean addPacked(String world, int x, int y, int z) {
        return protectedBlocks
                .computeIfAbsent(world, k -> ConcurrentHashMap.newKeySet())
                .add(packBlock(x, y, z));
    }

    private boolean containsPacked(String world, int x, int y, int z) {
        Set<Long> s = protectedBlocks.get(world);
        return s != null && s.contains(packBlock(x, y, z));
    }

    private void removePacked(String world, int x, int y, int z) {
        Set<Long> s = protectedBlocks.get(world);
        if (s != null) s.remove(packBlock(x, y, z));
    }

    public ProtectCommand(JavaPlugin plugin, RegionManager regionManager, DatabaseManager dbManager) {
        this.plugin        = plugin;
        this.regionManager = regionManager;
        this.db            = dbManager.isMongoConnected() ? dbManager.getDatabase() : null;
        this.dbConnected   = dbManager.isMongoConnected();

        if (dbConnected) {
            org.bukkit.Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                loadProtectedBlocks();
                blocksLoaded = true;
            });
        }
    }

    private static String chunkKey(String world, int x, int z) {
        return world + ":" + (x >> 4) + ":" + (z >> 4);
    }

    private static int packLocal(int blockX, int blockY, int blockZ) {
        return ((blockX & 0xF) << 16) | ((blockZ & 0xF) << 12) | (blockY & 0xFFF);
    }

    private static int unpackX(long packed) { return (int)(packed >> 38); }
    private static int unpackY(long packed) { return ((int)(packed & 0xFFFL) << 20) >> 20; }
    private static int unpackZ(long packed) {
        return ((int)((packed >> 12) & 0x3FFFFFFL) << 6) >> 6;
    }

    private void loadProtectedBlocks() {
        long t0 = System.currentTimeMillis();
        boolean needsResync = false;
        try {
            MongoCollection<Document> coll = db.getCollection("protected_blocks");
            int loaded = 0;
            for (Document doc : coll.find()) {
                String id = doc.getString("_id");
                if (id == null) continue;
                String[] parts = id.split(":", 3);
                if (parts.length != 3) continue;
                String worldName = parts[0];
                int chunkX, chunkZ;
                try {
                    chunkX = Integer.parseInt(parts[1]);
                    chunkZ = Integer.parseInt(parts[2]);
                } catch (NumberFormatException e) { continue; }

                List<?> blocks = doc.get("b", List.class);
                if (blocks == null || blocks.isEmpty()) continue;

                Set<Long> worldSet = protectedBlocks.computeIfAbsent(
                        worldName, k -> ConcurrentHashMap.newKeySet());

                Object first = blocks.get(0);
                if (first instanceof Number) {
                    
                    for (Object entry : blocks) {
                        if (!(entry instanceof Number n)) continue;
                        int p = n.intValue();
                        int bx = (chunkX << 4) | ((p >>> 16) & 0xF);
                        int bz = (chunkZ << 4) | ((p >>> 12) & 0xF);
                        int by = (p << 20) >> 20; 
                        worldSet.add(packBlock(bx, by, bz));
                        loaded++;
                    }
                } else {
                    // -- v1 legacy: [[x,y,z], ...]
                    needsResync = true;
                    for (Object entry : blocks) {
                        if (!(entry instanceof List<?> coords) || coords.size() < 3) continue;
                        int bx = ((Number) coords.get(0)).intValue();
                        int by = ((Number) coords.get(1)).intValue();
                        int bz = ((Number) coords.get(2)).intValue();
                        worldSet.add(packBlock(bx, by, bz));
                        loaded++;
                    }
                }
            }
            long elapsed = System.currentTimeMillis() - t0;
            plugin.getLogger().info("[ProtectCommand] Loaded " + loaded
                    + " protected block(s) from database in " + elapsed + "ms.");

            if (needsResync) {
                resyncToCompactFormat();
            }
        } catch (Exception e) {
            plugin.getLogger().warning("[ProtectCommand] Failed to load protected blocks: " + e.getMessage());
        }
    }

    private void resyncToCompactFormat() {
        long t0 = System.currentTimeMillis();
        try {
            
            Map<String, List<Integer>> byChunk = new HashMap<>();
            int totalBlocks = 0;
            for (var worldEntry : protectedBlocks.entrySet()) {
                String worldName = worldEntry.getKey();
                for (long packed : worldEntry.getValue()) {
                    int x = unpackX(packed);
                    int y = unpackY(packed);
                    int z = unpackZ(packed);
                    String key = chunkKey(worldName, x, z);
                    byChunk.computeIfAbsent(key, k -> new ArrayList<>())
                            .add(packLocal(x, y, z));
                    totalBlocks++;
                }
            }

            MongoCollection<Document> coll = db.getCollection("protected_blocks");
            coll.drop();

            List<Document> batch = new ArrayList<>(500);
            for (var entry : byChunk.entrySet()) {
                batch.add(new Document("_id", entry.getKey())
                        .append("b", entry.getValue()));
                if (batch.size() >= 500) {
                    coll.insertMany(batch);
                    batch.clear();
                }
            }
            if (!batch.isEmpty()) coll.insertMany(batch);

            long elapsed = System.currentTimeMillis() - t0;
            plugin.getLogger().info("[ProtectCommand] Resync complete: " + totalBlocks
                    + " block(s) in " + byChunk.size() + " chunk(s), compact format, "
                    + elapsed + "ms.");
        } catch (Exception e) {
            plugin.getLogger().warning("[ProtectCommand] Resync failed: " + e.getMessage());
        }
    }

    private void saveProtectedBlocks(List<Location> locations) {
        if (!dbConnected || locations.isEmpty()) return;
        
        Map<String, List<Integer>> byChunk = new HashMap<>();
        for (Location loc : locations) {
            String key = chunkKey(loc.getWorld().getName(), loc.getBlockX(), loc.getBlockZ());
            byChunk.computeIfAbsent(key, k -> new ArrayList<>())
                    .add(packLocal(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ()));
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
        final int packed = packLocal(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                db.getCollection("protected_blocks").updateOne(
                        eq("_id", key),
                        new Document("$pull", new Document("b", packed)));
            } catch (Exception e) {
                plugin.getLogger().warning("[ProtectCommand] Failed to delete protected block: " + e.getMessage());
            }
        });
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
                String worldName = world.getName();
                List<Location> newlyProtected = new ArrayList<>();
                for (int x = region.getMinX(); x <= region.getMaxX(); x++) {
                    for (int y = region.getMinY(); y <= region.getMaxY(); y++) {
                        for (int z = region.getMinZ(); z <= region.getMaxZ(); z++) {
                            Block block = world.getBlockAt(x, y, z);
                            if (!block.getType().isAir()) {
                                if (addPacked(worldName, x, y, z)) {
                                    newlyProtected.add(block.getLocation());
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

    public boolean isBypassing(Player player) { return bypassing.contains(player.getUniqueId()); }

    public void toggleBypass(Player player) {
        UUID uuid = player.getUniqueId();
        if (!bypassing.remove(uuid)) bypassing.add(uuid);
    }

    public boolean isProtected(Location blockLoc) {
        
        if (!blocksLoaded) return false;
        if (blockLoc.getWorld() == null) return false;
        return containsPacked(blockLoc.getWorld().getName(),
                blockLoc.getBlockX(), blockLoc.getBlockY(), blockLoc.getBlockZ());
    }

    public void removeProtected(Location blockLoc) {
        if (blockLoc.getWorld() != null) {
            removePacked(blockLoc.getWorld().getName(),
                    blockLoc.getBlockX(), blockLoc.getBlockY(), blockLoc.getBlockZ());
        }
        deleteProtectedBlock(blockLoc);
    }

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
