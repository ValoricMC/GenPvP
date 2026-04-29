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
    private double discordX, discordY, discordZ;
    private double storeX, storeY, storeZ;
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
        discordSkull = cfg.getString("discord.skull", "");
        discordText  = cfg.getString("discord.text", "DISCORD");

        storeWorld = cfg.getString("store.world", "world");
        storeX     = cfg.getDouble("store.x");
        storeY     = cfg.getDouble("store.y");
        storeZ     = cfg.getDouble("store.z");
        storeSkull = cfg.getString("store.skull", "");
        storeText  = cfg.getString("store.text", "STORE");
    }

    public void summon() {
        tasks.forEach(BukkitTask::cancel);
        tasks.clear();

        // Kill old spawn entities across all worlds
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
        ItemDisplay discord = dw.spawn(loc(dw, discordX, discordY + 2, discordZ), ItemDisplay.class, e -> {
            e.getPersistentDataContainer().set(KEY_SPAWN_ENTITY, PersistentDataType.BOOLEAN, true);
            applyItemDisplay(e, discordSkull);
        });
        ItemDisplay store = sw.spawn(loc(sw, storeX, storeY + 2, storeZ), ItemDisplay.class, e -> {
            e.getPersistentDataContainer().set(KEY_SPAWN_ENTITY, PersistentDataType.BOOLEAN, true);
            applyItemDisplay(e, storeSkull);
        });

        // Text displays at base + Y 2.75, riding their item display
        TextDisplay discordLabel = dw.spawn(loc(dw, discordX, discordY + 2.75, discordZ), TextDisplay.class, e -> {
            e.getPersistentDataContainer().set(KEY_SPAWN_ENTITY, PersistentDataType.BOOLEAN, true);
            applyTextDisplay(e, discordText);
        });
        TextDisplay storeLabel = sw.spawn(loc(sw, storeX, storeY + 2.75, storeZ), TextDisplay.class, e -> {
            e.getPersistentDataContainer().set(KEY_SPAWN_ENTITY, PersistentDataType.BOOLEAN, true);
            applyTextDisplay(e, storeText);
        });
        discord.addPassenger(discordLabel);
        store.addPassenger(storeLabel);

        // Interactions at base - Y 1, linked to their item display by UUID
        dw.spawn(loc(dw, discordX, discordY - 1, discordZ), Interaction.class, e -> {
            e.getPersistentDataContainer().set(KEY_SPAWN_ENTITY, PersistentDataType.BOOLEAN, true);
            e.getPersistentDataContainer().set(KEY_DISCORD, PersistentDataType.STRING, discord.getUniqueId().toString());
            e.setInteractionWidth(4.0f);
            e.setInteractionHeight(4.0f);
        });
        sw.spawn(loc(sw, storeX, storeY - 1, storeZ), Interaction.class, e -> {
            e.getPersistentDataContainer().set(KEY_SPAWN_ENTITY, PersistentDataType.BOOLEAN, true);
            e.getPersistentDataContainer().set(KEY_STORE, PersistentDataType.STRING, store.getUniqueId().toString());
            e.setInteractionWidth(4.0f);
            e.setInteractionHeight(4.0f);
        });

        tasks.add(startRotation(discord));
        tasks.add(startRotation(store));
    }

    private void applyItemDisplay(ItemDisplay e, String skull) {
        e.setTeleportDuration(20);
        e.setInterpolationDelay(20);
        e.setTransformation(new Transformation(
            new Vector3f(0, 0, 0),
            new AxisAngle4f(0, 0, 1, 0),
            new Vector3f(4, 4, 4),
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
                new Vector3f(4, 4, 4),
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
