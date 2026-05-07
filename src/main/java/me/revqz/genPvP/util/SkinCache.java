package me.revqz.genPvP.util;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.profile.PlayerTextures;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.util.Base64;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class SkinCache implements Listener {

    private static final int FACE_X    = 8;
    private static final int FACE_Y    = 8;
    private static final int HAT_X     = 40;
    private static final int HAT_Y     = 8;
    private static final int FACE_SIZE = 8;

    private static final String PLACEHOLDER = "&#888888████████";

    private final JavaPlugin    plugin;
    private final ConcurrentHashMap<UUID, BufferedImage> cache   = new ConcurrentHashMap<>();
    private final Set<UUID>                              loading = ConcurrentHashMap.newKeySet();

    public SkinCache(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        loadFromPlayer(event.getPlayer());
    }

    public String renderRow(UUID uuid, int row) {
        BufferedImage face = cache.get(uuid);
        if (face == null) return PLACEHOLDER;

        StringBuilder sb = new StringBuilder(9 * FACE_SIZE);
        for (int x = 0; x < FACE_SIZE; x++) {
            int rgb = face.getRGB(x, row) & 0x00FFFFFF; 
            sb.append(String.format("&#%06X\u2588", rgb)); 
        }
        return sb.toString();
    }

    public void loadForUUID(UUID uuid) {
        if (cache.containsKey(uuid) || !loading.add(uuid)) return;

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                
                Player online = Bukkit.getPlayer(uuid);
                if (online != null) {
                    URL skinUrl = online.getPlayerProfile().getTextures().getSkin();
                    if (skinUrl != null) {
                        BufferedImage face = downloadFace(skinUrl);
                        if (face != null) { cache.put(uuid, face); return; }
                    }
                }

                String uuidNoDash = uuid.toString().replace("-", "");
                URL profileUrl = URI.create(
                        "https://sessionserver.mojang.com/session/minecraft/profile/" + uuidNoDash
                ).toURL();

                HttpURLConnection conn = (HttpURLConnection) profileUrl.openConnection();
                conn.setConnectTimeout(5_000);
                conn.setReadTimeout(10_000);
                conn.setRequestProperty("User-Agent", "GenPvP/1.0");
                if (conn.getResponseCode() != 200) return;

                String json = new String(conn.getInputStream().readAllBytes());

                int idx = json.indexOf("\"value\":\"");
                if (idx < 0) return;
                int start = idx + 9, end = json.indexOf('"', start);
                if (end < 0) return;
                String textureJson = new String(Base64.getDecoder().decode(json.substring(start, end)));

                int urlIdx = textureJson.indexOf("\"SKIN\":{\"url\":\"");
                if (urlIdx < 0) return;
                int urlStart = urlIdx + 15, urlEnd = textureJson.indexOf('"', urlStart);
                if (urlEnd < 0) return;
                URL skinUrl = URI.create(textureJson.substring(urlStart, urlEnd)).toURL();

                BufferedImage face = downloadFace(skinUrl);
                if (face != null) cache.put(uuid, face);

            } catch (Exception ignored) {
                
            } finally {
                loading.remove(uuid);
            }
        });
    }

    private void loadFromPlayer(Player player) {
        UUID uuid = player.getUniqueId();
        if (cache.containsKey(uuid) || !loading.add(uuid)) return;

        URL skinUrl;
        try {
            PlayerTextures textures = player.getPlayerProfile().getTextures();
            skinUrl = textures.getSkin();
        } catch (Exception e) {
            loading.remove(uuid);
            return;
        }
        if (skinUrl == null) { loading.remove(uuid); return; }

        final URL url = skinUrl;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                BufferedImage face = downloadFace(url);
                if (face != null) cache.put(uuid, face);
            } finally {
                loading.remove(uuid);
            }
        });
    }

    private static BufferedImage downloadFace(URL skinUrl) {
        try {
            HttpURLConnection conn = (HttpURLConnection) skinUrl.openConnection();
            conn.setConnectTimeout(5_000);
            conn.setReadTimeout(10_000);
            conn.setRequestProperty("User-Agent", "GenPvP/1.0");
            conn.connect();
            if (conn.getResponseCode() != 200) return null;

            BufferedImage skin = ImageIO.read(conn.getInputStream());
            if (skin == null) return null;

            BufferedImage face = new BufferedImage(FACE_SIZE, FACE_SIZE, BufferedImage.TYPE_INT_ARGB);
            for (int x = 0; x < FACE_SIZE; x++)
                for (int y = 0; y < FACE_SIZE; y++)
                    face.setRGB(x, y, skin.getRGB(FACE_X + x, FACE_Y + y));

            if (skin.getWidth() >= HAT_X + FACE_SIZE && skin.getHeight() >= HAT_Y + FACE_SIZE) {
                for (int x = 0; x < FACE_SIZE; x++) {
                    for (int y = 0; y < FACE_SIZE; y++) {
                        int hat = skin.getRGB(HAT_X + x, HAT_Y + y);
                        if (((hat >> 24) & 0xFF) > 0) face.setRGB(x, y, hat);
                    }
                }
            }

            return face;
        } catch (IOException e) {
            return null;
        }
    }
}
