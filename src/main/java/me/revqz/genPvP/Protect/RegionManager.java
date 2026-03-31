package me.revqz.genPvP.Protect;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.ReplaceOptions;
import me.revqz.genPvP.Protect.flags.RegionType;
import org.bson.Document;
import org.bukkit.Location;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

public class RegionManager {

    private final Map<String, ProtectRegion> regions = new HashMap<>();
    private MongoCollection<Document> collection;
    private boolean connected = false;

    /** Production constructor — connects to MongoDB and loads regions. */
    public RegionManager(JavaPlugin plugin) {
        String uri = plugin.getConfig().getString("mongodb-uri", "mongodb://localhost:27017");
        try {
            MongoClient client = MongoClients.create(uri);
            MongoDatabase db = client.getDatabase("genpvp");
            collection = db.getCollection("regions");
            connected = true;
            loadFromMongo();
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE,
                    "Failed to connect to MongoDB — region protection disabled", e);
        }
    }

    /** Test constructor — in-memory only, no MongoDB. */
    RegionManager() {}

    private void loadFromMongo() {
        for (Document doc : collection.find()) {
            try {
                RegionType type = RegionType.valueOf(doc.getString("type"));
                ProtectRegion region = new ProtectRegion(
                        doc.getString("name"), type, doc.getString("world"),
                        doc.getInteger("minX"), doc.getInteger("minY"), doc.getInteger("minZ"),
                        doc.getInteger("maxX"), doc.getInteger("maxY"), doc.getInteger("maxZ")
                );
                regions.put(region.getName().toLowerCase(), region);
            } catch (Exception ignored) {}
        }
    }

    public void defineRegion(String name, RegionType type, String world,
                              int x1, int y1, int z1, int x2, int y2, int z2) {
        ProtectRegion region = new ProtectRegion(name, type, world, x1, y1, z1, x2, y2, z2);
        regions.put(name.toLowerCase(), region);
        if (connected) {
            Document filter = new Document("name", name);
            Document doc = new Document("name", name)
                    .append("type", type.name())
                    .append("world", world)
                    .append("minX", region.getMinX()).append("minY", region.getMinY()).append("minZ", region.getMinZ())
                    .append("maxX", region.getMaxX()).append("maxY", region.getMaxY()).append("maxZ", region.getMaxZ());
            collection.replaceOne(filter, doc, new ReplaceOptions().upsert(true));
        }
    }

    public List<ProtectRegion> getRegionsAt(Location loc) {
        List<ProtectRegion> result = new ArrayList<>();
        for (ProtectRegion r : regions.values()) {
            if (r.contains(loc)) result.add(r);
        }
        return result;
    }

    public boolean isConnected() { return connected; }
}
