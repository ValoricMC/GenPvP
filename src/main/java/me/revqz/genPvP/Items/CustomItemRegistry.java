package me.revqz.genPvP.Items;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.ReplaceOptions;
import me.revqz.genPvP.GenPvP;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bson.Document;
import org.bson.types.Binary;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import static com.mongodb.client.model.Filters.eq;

/**
 * Registers custom items under a named key and persists their definitions
 * to MongoDB. Works with any ItemStack, including:
 *  - ItemsAdder items (their own PDC keys are preserved untouched)
 *  - Items with custom-enchant lore / PDC entries (fully preserved)
 *
 * Stamping only adds one PDC entry ({@code genpvp:custom_item_id = name}).
 * All existing metadata is left intact.
 */
public class CustomItemRegistry {

    static final String PDC_KEY_NAME = "custom_item_id";

    private final GenPvP        plugin;
    private final NamespacedKey  pdcKey;
    private final MongoDatabase  db;
    private final boolean        dbConnected;

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();

    /** name (lower-case) → raw serialized bytes of the registered template. */
    private final Map<String, byte[]> templates = new ConcurrentHashMap<>();

    /** name (lower-case) → legacy-string lore lines from the registered template. */
    private final Map<String, List<String>> templateLoreCache = new ConcurrentHashMap<>();

    public CustomItemRegistry(GenPvP plugin, MongoDatabase db, boolean dbConnected) {
        this.plugin      = plugin;
        this.pdcKey      = new NamespacedKey(plugin, PDC_KEY_NAME);
        this.db          = db;
        this.dbConnected = dbConnected;
        loadAll();
    }

    public NamespacedKey getPdcKey() { return pdcKey; }

    // ── Registration ──────────────────────────────────────────────────────────

    public ItemStack register(String name, ItemStack item) {
        String key = name.toLowerCase();
        ItemMeta meta = item.getItemMeta();
        meta.getPersistentDataContainer().set(pdcKey, PersistentDataType.STRING, key);
        item.setItemMeta(meta);

        byte[] serialized = item.serializeAsBytes();
        templates.put(key, serialized);
        cacheLore(key, item);

        if (dbConnected) {
            final byte[] data = serialized;
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
                try {
                    Document doc = new Document("_id", key)
                            .append("item_data", new Binary(data))
                            .append("created_at", System.currentTimeMillis());
                    db.getCollection("custom_items").replaceOne(
                            eq("_id", key), doc, new ReplaceOptions().upsert(true));
                } catch (Exception e) {
                    plugin.getLogger().warning("[CustomItemRegistry] save failed for '" + key + "': " + e.getMessage());
                }
            });
        }

        return item;
    }

    // ── Lookup ────────────────────────────────────────────────────────────────

    public String getItemId(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) return null;
        return item.getItemMeta().getPersistentDataContainer().get(pdcKey, PersistentDataType.STRING);
    }

    public boolean hasId(ItemStack item, String name) {
        if (item == null) return false;
        String id = getItemId(item);
        return id != null && id.equalsIgnoreCase(name);
    }

    public ItemStack getTemplate(String name) {
        byte[] data = templates.get(name.toLowerCase());
        return data != null ? ItemStack.deserializeBytes(data) : null;
    }

    public boolean isRegistered(String name) {
        return templates.containsKey(name.toLowerCase());
    }

    public Set<String> getRegisteredNames() {
        return Collections.unmodifiableSet(templates.keySet());
    }

    // ── Startup load ──────────────────────────────────────────────────────────

    private void loadAll() {
        if (!dbConnected) return;
        try {
            MongoCollection<Document> coll = db.getCollection("custom_items");
            int count = 0;
            for (Document doc : coll.find()) {
                Binary binary = doc.get("item_data", Binary.class);
                if (binary != null) {
                    String id = doc.getString("_id");
                    byte[] data = binary.getData();
                    templates.put(id, data);
                    cacheLore(id, ItemStack.deserializeBytes(data));
                    count++;
                }
            }
            plugin.getLogger().info("[CustomItemRegistry] Loaded " + count + " custom item definition(s).");
        } catch (Exception e) {
            plugin.getLogger().warning("[CustomItemRegistry] loadAll failed: " + e.getMessage());
        }
    }

    /**
     * Returns the lore of the registered template as legacy-string lines
     * (e.g. "§7Defense: %genpvp_luffy_armor_haki_currentbuff%").
     * Returns null if the item has no registered template or no lore.
     */
    public List<String> getTemplateLore(String name) {
        return templateLoreCache.get(name.toLowerCase());
    }

    private void cacheLore(String key, ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null || !meta.hasLore()) {
            templateLoreCache.remove(key);
            return;
        }
        List<Component> lore = meta.lore();
        if (lore == null || lore.isEmpty()) {
            templateLoreCache.remove(key);
            return;
        }
        templateLoreCache.put(key, lore.stream().map(LEGACY::serialize).collect(Collectors.toList()));
    }
}
