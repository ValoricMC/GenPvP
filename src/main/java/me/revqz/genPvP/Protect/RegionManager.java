package me.revqz.genPvP.Protect;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.ReplaceOptions;
import me.revqz.genPvP.Database.DatabaseManager;
import me.revqz.genPvP.Protect.flags.RegionRule;
import me.revqz.genPvP.Protect.flags.RegionType;
import org.bson.Document;
import org.bukkit.Location;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;

import static com.mongodb.client.model.Filters.eq;

public class RegionManager {

    private final ConcurrentHashMap<String, ProtectRegion> regions = new ConcurrentHashMap<>();

    private final ConcurrentHashMap<String, ConcurrentHashMap<Long, CopyOnWriteArrayList<ProtectRegion>>> chunkIndex =
            new ConcurrentHashMap<>();

    private MongoDatabase db;
    private boolean connected = false;
    private JavaPlugin plugin;

    /** Production constructor — connects to MongoDB and loads regions. */
    public RegionManager(JavaPlugin plugin, DatabaseManager dbManager) {
        this.plugin = plugin;
        if (!dbManager.isMongoConnected()) {
            plugin.getLogger().warning("[RegionManager] MongoDB not connected — regions will not persist across restarts.");
            return;
        }
        db        = dbManager.getDatabase();
        connected = true;
        loadFromMongo(); // blocking, intentional: regions must be ready before players join
    }

    /** Test constructor — in-memory only, no database. */
    RegionManager() {}

    // ── MongoDB ──────────────────────────────────────────────────────────────

    private void loadFromMongo() {
        int loaded = 0, failed = 0;
        try {
            MongoCollection<Document> coll = db.getCollection("regions");
            for (Document doc : coll.find()) {
                try {
                    String name  = doc.getString("_id");
                    String type  = doc.getString("type");
                    String world = doc.getString("world");

                    ProtectRegion region = new ProtectRegion(
                            name, RegionType.valueOf(type), world,
                            doc.getInteger("min_x"), doc.getInteger("min_y"), doc.getInteger("min_z"),
                            doc.getInteger("max_x"), doc.getInteger("max_y"), doc.getInteger("max_z"));
                    region.setPriority(doc.getDouble("priority") != null ? doc.getDouble("priority") : 0.0);

                    regions.put(name.toLowerCase(), region);
                    indexAdd(region);
                    loaded++;
                } catch (Exception e) {
                    plugin.getLogger().warning("[RegionManager] Failed to parse region doc: " + e.getMessage());
                    failed++;
                }
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "[RegionManager] Failed to load regions from MongoDB", e);
        }

        plugin.getLogger().info("[RegionManager] Loaded " + loaded + " region(s)" +
                (failed > 0 ? ", " + failed + " failed to parse." : "."));
    }

    // ── Chunk-index internals ─────────────────────────────────────────────────

    private static long chunkKey(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }

    private void indexAdd(ProtectRegion r) {
        int minCX = r.getMinX() >> 4, maxCX = r.getMaxX() >> 4;
        int minCZ = r.getMinZ() >> 4, maxCZ = r.getMaxZ() >> 4;
        ConcurrentHashMap<Long, CopyOnWriteArrayList<ProtectRegion>> worldMap =
                chunkIndex.computeIfAbsent(r.getWorld(), k -> new ConcurrentHashMap<>());
        for (int cx = minCX; cx <= maxCX; cx++) {
            for (int cz = minCZ; cz <= maxCZ; cz++) {
                worldMap.computeIfAbsent(chunkKey(cx, cz), k -> new CopyOnWriteArrayList<>()).add(r);
            }
        }
    }

    private void indexRemove(ProtectRegion r) {
        ConcurrentHashMap<Long, CopyOnWriteArrayList<ProtectRegion>> worldMap =
                chunkIndex.get(r.getWorld());
        if (worldMap == null) return;
        int minCX = r.getMinX() >> 4, maxCX = r.getMaxX() >> 4;
        int minCZ = r.getMinZ() >> 4, maxCZ = r.getMaxZ() >> 4;
        for (int cx = minCX; cx <= maxCX; cx++) {
            for (int cz = minCZ; cz <= maxCZ; cz++) {
                CopyOnWriteArrayList<ProtectRegion> list = worldMap.get(chunkKey(cx, cz));
                if (list != null) list.remove(r);
            }
        }
    }

    private List<ProtectRegion> candidates(Location loc) {
        if (loc.getWorld() == null) return Collections.emptyList();
        ConcurrentHashMap<Long, CopyOnWriteArrayList<ProtectRegion>> worldMap =
                chunkIndex.get(loc.getWorld().getName());
        if (worldMap == null) return Collections.emptyList();
        CopyOnWriteArrayList<ProtectRegion> list =
                worldMap.get(chunkKey(loc.getBlockX() >> 4, loc.getBlockZ() >> 4));
        return list != null ? list : Collections.emptyList();
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public void defineRegion(String name, RegionType type, String world,
                             int x1, int y1, int z1, int x2, int y2, int z2) {
        ProtectRegion old = regions.remove(name.toLowerCase());
        if (old != null) indexRemove(old);

        ProtectRegion region = new ProtectRegion(name, type, world, x1, y1, z1, x2, y2, z2);
        regions.put(name.toLowerCase(), region);
        indexAdd(region);

        if (connected && plugin != null) {
            final double priority = region.getPriority();
            org.bukkit.Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                try {
                    Document doc = new Document("_id", name)
                            .append("type", type.name())
                            .append("world", world)
                            .append("min_x", region.getMinX())
                            .append("min_y", region.getMinY())
                            .append("min_z", region.getMinZ())
                            .append("max_x", region.getMaxX())
                            .append("max_y", region.getMaxY())
                            .append("max_z", region.getMaxZ())
                            .append("priority", priority);
                    db.getCollection("regions").replaceOne(
                            eq("_id", name), doc, new ReplaceOptions().upsert(true));
                } catch (Exception e) {
                    plugin.getLogger().warning("[RegionManager] Failed to save region '" + name + "': " + e.getMessage());
                }
            });
        }
    }

    public void deleteRegion(String name) {
        ProtectRegion region = regions.remove(name.toLowerCase());
        if (region != null) indexRemove(region);

        if (connected && plugin != null) {
            final String storedName = region != null ? region.getName() : name;
            org.bukkit.Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                try {
                    db.getCollection("regions").deleteOne(eq("_id", storedName));
                } catch (Exception e) {
                    plugin.getLogger().warning("[RegionManager] Failed to delete region '" + storedName + "': " + e.getMessage());
                }
            });
        }
    }

    public boolean setPriority(String name, double priority) {
        ProtectRegion region = regions.get(name.toLowerCase());
        if (region == null) return false;

        region.setPriority(priority);

        if (connected && plugin != null) {
            final String storedName = region.getName();
            org.bukkit.Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                try {
                    db.getCollection("regions").updateOne(
                            eq("_id", storedName),
                            new Document("$set", new Document("priority", priority)));
                } catch (Exception e) {
                    plugin.getLogger().warning("[RegionManager] Failed to update priority for region '" + storedName + "': " + e.getMessage());
                }
            });
        }
        return true;
    }

    public List<ProtectRegion> getRegionsAt(Location loc) {
        List<ProtectRegion> cands = candidates(loc);
        if (cands.isEmpty()) return Collections.emptyList();
        List<ProtectRegion> result = new ArrayList<>(cands.size());
        for (ProtectRegion r : cands) {
            if (r.contains(loc)) result.add(r);
        }
        return result;
    }

    public boolean anyDenies(Location loc, RegionRule rule) {
        double maxPriority  = Double.NEGATIVE_INFINITY;
        boolean highestDenies = false;

        for (ProtectRegion r : candidates(loc)) {
            if (!r.contains(loc)) continue;
            double p = r.getPriority();
            if (p > maxPriority) {
                maxPriority   = p;
                highestDenies = !r.hasRule(rule);
            } else if (p == maxPriority) {
                if (!r.hasRule(rule)) highestDenies = true;
            }
        }
        return maxPriority != Double.NEGATIVE_INFINITY && highestDenies;
    }

    public boolean inRegionAndAllAllow(Location loc, RegionRule rule) {
        double maxPriority = Double.NEGATIVE_INFINITY;
        boolean anyFound   = false;
        boolean highestAllows = true;

        for (ProtectRegion r : candidates(loc)) {
            if (!r.contains(loc)) continue;
            double p = r.getPriority();
            if (p > maxPriority) {
                maxPriority   = p;
                anyFound      = true;
                highestAllows = r.hasRule(rule);
            } else if (p == maxPriority) {
                if (!r.hasRule(rule)) highestAllows = false;
            }
        }
        return anyFound && highestAllows;
    }

    public boolean insideAnyType(Location loc, RegionType typeA, RegionType typeB) {
        for (ProtectRegion r : candidates(loc)) {
            if (r.contains(loc)) {
                RegionType t = r.getType();
                if (t == typeA || t == typeB) return true;
            }
        }
        return false;
    }

    /** Returns true if {@code loc} is inside at least one region of the given type. */
    public boolean insideType(Location loc, RegionType type) {
        for (ProtectRegion r : candidates(loc)) {
            if (r.contains(loc) && r.getType() == type) return true;
        }
        return false;
    }

    public ProtectRegion getRegion(String name) {
        return regions.get(name.toLowerCase());
    }

    public java.util.Set<String> getRegionNames() {
        return regions.keySet();
    }

    public boolean isConnected() { return connected; }
}
