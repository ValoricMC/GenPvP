package me.revqz.genPvP.SpawnDisplays;

import me.revqz.genPvP.GenPvP;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.profile.PlayerProfile;
import org.bukkit.profile.PlayerTextures;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

public class DisplaysManager {

    private final GenPvP plugin;

    final NamespacedKey KEY_SPAWN_ENTITY;
    final NamespacedKey KEY_DISCORD;
    final NamespacedKey KEY_STORE;

    // Raw config values — stored separately so loadConfig() is safe before worlds load
    private String discordWorld, storeWorld;
    private double discordX, discordY, discordZ, discordYaw, discordPitch;
    private double storeX, storeY, storeZ, storeYaw, storePitch;
    private String discordSkull, storeSkull;
    private String discordText, storeText;

    private final List<BukkitTask> tasks = new ArrayList<>();

    public DisplaysManager(GenPvP plugin) {
        this.plugin = plugin;
        KEY_SPAWN_ENTITY = new NamespacedKey(plugin, "spawn_entity");
        KEY_DISCORD      = new NamespacedKey(plugin, "spawn_entity_discord");
        KEY_STORE        = new NamespacedKey(plugin, "spawn_entity_store");
        loadConfig();
    }

    public void loadConfig() {
        File file = new File(plugin.getDataFolder(), "displays.yml");
        if (!file.exists()) plugin.saveResource("displays.yml", false);
        var cfg = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(file);

        discordWorld = cfg.getString("discord.world", "world");
        discordX     = cfg.getDouble("discord.x");
        discordY     = cfg.getDouble("discord.y");
        discordZ     = cfg.getDouble("discord.z");
        discordYaw   = cfg.getDouble("discord.yaw", 0);
        discordPitch = cfg.getDouble("discord.pitch", 0);
        discordSkull = cfg.getString("discord.skull", "");
        discordText  = cfg.getString("discord.text", "DISCORD");

        storeWorld = cfg.getString("store.world", "world");
        storeX     = cfg.getDouble("store.x");
        storeY     = cfg.getDouble("store.y");
        storeZ     = cfg.getDouble("store.z");
        storeYaw   = cfg.getDouble("store.yaw", 0);
        storePitch = cfg.getDouble("store.pitch", 0);
        storeSkull = cfg.getString("store.skull", "");
        storeText  = cfg.getString("store.text", "STORE");
    }

    public void summon() {
        tasks.forEach(BukkitTask::cancel);
        tasks.clear();

        // Kill entities from the previous session by UUID (force-loads their chunks so the scan is reliable)
        killSavedEntities();

        // Fallback PDC scan for any strays not covered by the UUID file
        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (entity.getPersistentDataContainer().has(KEY_SPAWN_ENTITY, PersistentDataType.BOOLEAN)) {
                    entity.remove();
                }
            }
        }

        World dw = Bukkit.getWorld(discordWorld);
        World sw = Bukkit.getWorld(storeWorld);
        if (dw == null || sw == null) {
            plugin.getLogger().warning("[Displays] World not found — skipping summon.");
            return;
        }

        // Item displays at base + Y 2
        ItemDisplay discord = dw.spawn(loc(dw, discordX, discordY + 2, discordZ, discordYaw, discordPitch), ItemDisplay.class, e -> {
            e.getPersistentDataContainer().set(KEY_SPAWN_ENTITY, PersistentDataType.BOOLEAN, true);
            applyItemDisplay(e, discordSkull);
        });
        ItemDisplay store = sw.spawn(loc(sw, storeX, storeY + 2, storeZ, storeYaw, storePitch), ItemDisplay.class, e -> {
            e.getPersistentDataContainer().set(KEY_SPAWN_ENTITY, PersistentDataType.BOOLEAN, true);
            applyItemDisplay(e, storeSkull);
        });

        // Text displays at base + Y 2.75, riding their item display
        TextDisplay discordLabel = dw.spawn(loc(dw, discordX, discordY + 2.75, discordZ, discordYaw, discordPitch), TextDisplay.class, e -> {
            e.getPersistentDataContainer().set(KEY_SPAWN_ENTITY, PersistentDataType.BOOLEAN, true);
            applyTextDisplay(e, discordText);
        });
        TextDisplay storeLabel = sw.spawn(loc(sw, storeX, storeY + 2.75, storeZ, storeYaw, storePitch), TextDisplay.class, e -> {
            e.getPersistentDataContainer().set(KEY_SPAWN_ENTITY, PersistentDataType.BOOLEAN, true);
            applyTextDisplay(e, storeText);
        });
        discord.addPassenger(discordLabel);
        store.addPassenger(storeLabel);

        // Interactions at base - Y 1, linked to their item display by UUID
        Interaction discordInteraction = dw.spawn(loc(dw, discordX, discordY - 1, discordZ), Interaction.class, e -> {
            e.getPersistentDataContainer().set(KEY_SPAWN_ENTITY, PersistentDataType.BOOLEAN, true);
            e.getPersistentDataContainer().set(KEY_DISCORD, PersistentDataType.STRING, discord.getUniqueId().toString());
            e.setInteractionWidth(3.0f);
            e.setInteractionHeight(3.0f);
        });
        Interaction storeInteraction = sw.spawn(loc(sw, storeX, storeY - 1, storeZ), Interaction.class, e -> {
            e.getPersistentDataContainer().set(KEY_SPAWN_ENTITY, PersistentDataType.BOOLEAN, true);
            e.getPersistentDataContainer().set(KEY_STORE, PersistentDataType.STRING, store.getUniqueId().toString());
            e.setInteractionWidth(3.0f);
            e.setInteractionHeight(3.0f);
        });

        saveEntityUUIDs(discord, store, discordLabel, storeLabel, discordInteraction, storeInteraction);

        tasks.add(startRotation(discord));
        tasks.add(startRotation(store));
    }

    // ── UUID persistence helpers ──────────────────────────────────────────────

    private static final String ENTITY_FILE = "display_entity_uuids.yml";

    private void saveEntityUUIDs(Entity... entities) {
        File file = new File(plugin.getDataFolder(), ENTITY_FILE);
        var cfg = new org.bukkit.configuration.file.YamlConfiguration();
        int i = 0;
        for (Entity e : entities) {
            if (e == null) continue;
            String base = "entities." + i;
            cfg.set(base + ".uuid",  e.getUniqueId().toString());
            cfg.set(base + ".world", e.getWorld().getName());
            cfg.set(base + ".cx",    e.getLocation().getBlockX() >> 4);
            cfg.set(base + ".cz",    e.getLocation().getBlockZ() >> 4);
            i++;
        }
        try { cfg.save(file); } catch (IOException ex) {
            plugin.getLogger().warning("[Displays] Failed to save entity UUIDs: " + ex.getMessage());
        }
    }

    private void killSavedEntities() {
        File file = new File(plugin.getDataFolder(), ENTITY_FILE);
        if (!file.exists()) return;
        var cfg = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(file);
        var section = cfg.getConfigurationSection("entities");
        if (section == null) { file.delete(); return; }
        for (String key : section.getKeys(false)) {
            String uuidStr   = section.getString(key + ".uuid");
            String worldName = section.getString(key + ".world");
            int cx = section.getInt(key + ".cx");
            int cz = section.getInt(key + ".cz");
            if (uuidStr == null || worldName == null) continue;
            World world = Bukkit.getWorld(worldName);
            if (world == null) continue;
            world.loadChunk(cx, cz); // force entity data into memory
            Entity entity = Bukkit.getEntity(UUID.fromString(uuidStr));
            if (entity != null) entity.remove();
        }
        file.delete();
    }

    private void applyItemDisplay(ItemDisplay e, String skull) {
        e.setTeleportDuration(20);
        e.setInterpolationDelay(20);
        e.setTransformation(new Transformation(
            new Vector3f(0, 0, 0),
            new AxisAngle4f(0, 0, 1, 0),
            new Vector3f(3, 3, 3),
            new AxisAngle4f(0, 0, 1, 0)
        ));
        e.setGlowing(true);
        e.setItemStack(buildSkull(skull));
    }

    private void applyTextDisplay(TextDisplay e, String text) {
        e.setShadowed(true);
        e.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
        e.setBillboard(Display.Billboard.VERTICAL);
        e.setTransformation(new Transformation(
            new Vector3f(0, 0.3f, 0),
            new AxisAngle4f(0, 0, 1, 0),
            new Vector3f(1.5f, 1.5f, 1.5f),
            new AxisAngle4f(0, 0, 1, 0)
        ));
        e.text(MiniMessage.miniMessage().deserialize(text));
    }

    private BukkitTask startRotation(ItemDisplay display) {
        int[] tick = {0};
        return plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            if (!display.isValid()) return;
            tick[0]++;
            float yawRad   = (float) Math.toRadians(tick[0] * 2.0);
            float yOffset  = (float) (Math.sin(tick[0] * 2.0 * Math.PI / 180.0) * 0.1);
            display.setInterpolationDelay(0);
            display.setInterpolationDuration(1);
            display.setTransformation(new Transformation(
                new Vector3f(0, yOffset, 0),
                new AxisAngle4f(yawRad, 0, 1, 0),
                new Vector3f(3, 3, 3),
                new AxisAngle4f(0, 0, 1, 0)
            ));
        }, 1L, 1L);
    }

    private ItemStack buildSkull(String base64) {
        ItemStack skull = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) skull.getItemMeta();
        try {
            String json = new String(Base64.getDecoder().decode(base64));
            int s = json.indexOf("\"url\":\"") + 7;
            int e = json.indexOf("\"", s);
            PlayerProfile profile = Bukkit.createPlayerProfile(UUID.randomUUID());
            PlayerTextures textures = profile.getTextures();
            textures.setSkin(new URL(json.substring(s, e)));
            profile.setTextures(textures);
            meta.setOwnerProfile(profile);
        } catch (Exception ex) {
            plugin.getLogger().warning("[Displays] Failed to parse skull texture: " + ex.getMessage());
        }
        skull.setItemMeta(meta);
        return skull;
    }

    private static org.bukkit.Location loc(World w, double x, double y, double z) {
        return new org.bukkit.Location(w, x, y, z);
    }

    private static org.bukkit.Location loc(World w, double x, double y, double z, double yaw, double pitch) {
        return new org.bukkit.Location(w, x, y, z, (float) yaw, (float) pitch);
    }

    public void shutdown() {
        tasks.forEach(BukkitTask::cancel);
        tasks.clear();
        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (entity.getPersistentDataContainer().has(KEY_SPAWN_ENTITY, PersistentDataType.BOOLEAN)) {
                    entity.remove();
                }
            }
        }
    }
}
