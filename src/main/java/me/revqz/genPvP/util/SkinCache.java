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

/**
 * Asynchronously fetches and caches the 8×8 composited face (head + hat layer)
 * from player skin textures.
 *
 * <p>Skins are loaded from the player's {@link PlayerTextures} on join.  For
 * offline/leaderboard players, {@link #loadForUUID} fetches the profile from
 * Mojang's session server and downloads the skin PNG asynchronously.
 *
 * <p>{@link #renderRow(UUID, int)} returns a single scoreboard-ready line of 8
 * hex-coloured {@code █} characters representing one row of the face, or a grey
 * placeholder if the skin is not yet cached.
 */
public class SkinCache implements Listener {

    private static final int FACE_X    = 8;
    private static final int FACE_Y    = 8;
    private static final int HAT_X     = 40;
    private static final int HAT_Y     = 8;
    private static final int FACE_SIZE = 8;

    /** Placeholder shown when the face hasn't been cached yet. */
    private static final String PLACEHOLDER = "&#888888████████";

    private final JavaPlugin    plugin;
    private final ConcurrentHashMap<UUID, BufferedImage> cache   = new ConcurrentHashMap<>();
    private final Set<UUID>                              loading = ConcurrentHashMap.newKeySet();

    public SkinCache(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    // ── Listener ──────────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        loadFromPlayer(event.getPlayer());
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Returns one row of the cached face as a scoreboard-ready string of 8
     * hex-coloured {@code █} characters.
     *
     * @param uuid the player UUID
     * @param row  0-indexed row (0 = top of head, 7 = chin)
     */
    public String renderRow(UUID uuid, int row) {
        BufferedImage face = cache.get(uuid);
        if (face == null) return PLACEHOLDER;

        StringBuilder sb = new StringBuilder(9 * FACE_SIZE);
        for (int x = 0; x < FACE_SIZE; x++) {
            int rgb = face.getRGB(x, row) & 0x00FFFFFF; // strip alpha
            sb.append(String.format("&#%06X\u2588", rgb)); // █ = \u2588
        }
        return sb.toString();
    }

    /**
     * Triggers an async load of the skin for an offline/leaderboard player.
     * Uses Mojang's session server to resolve the skin URL, then downloads it.
     * No-ops if the face is already cached or a load is already in flight.
     */
    public void loadForUUID(UUID uuid) {
        if (cache.containsKey(uuid) || !loading.add(uuid)) return;

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                // First check if they're online — avoids a Mojang API call
                Player online = Bukkit.getPlayer(uuid);
                if (online != null) {
                    URL skinUrl = online.getPlayerProfile().getTextures().getSkin();
                    if (skinUrl != null) {
                        BufferedImage face = downloadFace(skinUrl);
                        if (face != null) { cache.put(uuid, face); return; }
                    }
                }

                // Offline path: query Mojang session server
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

                // Extract the base64 texture value from properties array
                int idx = json.indexOf("\"value\":\"");
                if (idx < 0) return;
                int start = idx + 9, end = json.indexOf('"', start);
                if (end < 0) return;
                String textureJson = new String(Base64.getDecoder().decode(json.substring(start, end)));

                // Pull skin URL from decoded JSON
                int urlIdx = textureJson.indexOf("\"SKIN\":{\"url\":\"");
                if (urlIdx < 0) return;
                int urlStart = urlIdx + 15, urlEnd = textureJson.indexOf('"', urlStart);
                if (urlEnd < 0) return;
                URL skinUrl = URI.create(textureJson.substring(urlStart, urlEnd)).toURL();

                BufferedImage face = downloadFace(skinUrl);
                if (face != null) cache.put(uuid, face);

            } catch (Exception ignored) {
                // Silently ignore — grey placeholder will be shown
            } finally {
                loading.remove(uuid);
            }
        });
    }

    // ── Internal ──────────────────────────────────────────────────────────────

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

            // Composite hat overlay
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
