package me.revqz.genPvP.util;

import me.revqz.genPvP.GenPvP;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * /feedback <reason>
 *
 * <ul>
 *   <li>Open to all players — no permission required.</li>
 *   <li>1-hour cooldown per UUID (in-memory; resets on restart).</li>
 *   <li>Sends a Discord webhook embed with the player's name, UUID, and message.</li>
 * </ul>
 *
 * Configure in config.yml:
 * <pre>
 * feedback:
 *   webhook-url: "https://discord.com/api/webhooks/..."
 *   cooldown-seconds: 3600
 *   messages:
 *     sent:      "&aYour feedback has been sent. Thank you!"
 *     cooldown:  "&cYou can only submit feedback once per hour. Try again in &e%time%&c."
 *     too-short: "&cPlease provide a reason with at least 3 characters."
 * </pre>
 */
public class FeedbackCommand implements CommandExecutor {

    private static final int MIN_LENGTH = 3;

    private final GenPvP plugin;
    /** UUID → epoch-second when the player's cooldown expires. */
    private final Map<UUID, Long> cooldowns = new ConcurrentHashMap<>();

    public FeedbackCommand(GenPvP plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("This command can only be used by players.");
            return true;
        }

        if (args.length == 0) {
            player.sendActionBar(component("&cUsage: /feedback <reason>"));
            return true;
        }

        String reason = String.join(" ", args).trim();
        if (reason.length() < MIN_LENGTH) {
            player.sendActionBar(component(cfg("feedback.messages.too-short",
                    "&cPlease provide a reason with at least 3 characters.")));
            return true;
        }

        // ── Cooldown check ────────────────────────────────────────────────────
        long now = Instant.now().getEpochSecond();
        long cooldownSec = plugin.getConfig().getLong("feedback.cooldown-seconds", 3600L);
        Long expires = cooldowns.get(player.getUniqueId());

        if (expires != null && now < expires) {
            long remaining = expires - now;
            String timeStr = formatRemaining(remaining);
            player.sendActionBar(component(cfg("feedback.messages.cooldown",
                    "&cYou can only submit feedback once per hour. Try again in &e%time%&c.")
                    .replace("%time%", timeStr)));
            return true;
        }

        // Register cooldown immediately to prevent spam while the async call is in-flight
        cooldowns.put(player.getUniqueId(), now + cooldownSec);

        // ── Send webhook async (no I/O on main thread) ────────────────────────
        String webhookUrl = plugin.getConfig().getString("feedback.webhook-url", "");
        if (webhookUrl.isBlank()) {
            player.sendActionBar(component("&cFeedback is not configured. Contact an admin."));
            cooldowns.remove(player.getUniqueId()); // refund cooldown
            return true;
        }

        final String finalReason  = reason;
        final String playerName   = player.getName();
        final String playerUuid   = player.getUniqueId().toString();

        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            boolean ok = sendWebhook(webhookUrl, playerName, playerUuid, finalReason);
            // Feedback to the player back on main thread
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (ok) {
                    player.sendActionBar(component(cfg("feedback.messages.sent",
                            "&aYour feedback has been sent. Thank you!")));
                } else {
                    player.sendActionBar(component("&cCould not deliver feedback right now. Try again later."));
                    cooldowns.remove(player.getUniqueId()); // refund cooldown on failure
                }
            });
        });

        return true;
    }

    // ── Discord webhook ───────────────────────────────────────────────────────

    private boolean sendWebhook(String webhookUrl, String playerName, String uuid, String reason) {
        try {
            // Escape special JSON characters in the reason string
            String safeReason = reason
                    .replace("\\", "\\\\")
                    .replace("\"", "\\\"")
                    .replace("\n", "\\n")
                    .replace("\r", "");

            String timestamp = Instant.now().toString(); // ISO-8601

            // Discord embed: blue color (3447003), player name in footer
            String json = "{\"embeds\":[{"
                    + "\"title\":\"\\uD83D\\uDCE8 Player Feedback\","
                    + "\"description\":\"" + safeReason + "\","
                    + "\"color\":3447003,"
                    + "\"footer\":{\"text\":\"" + playerName + " \u2022 " + uuid + "\"},"
                    + "\"timestamp\":\"" + timestamp + "\""
                    + "}]}";

            byte[] body = json.getBytes(StandardCharsets.UTF_8);

            HttpURLConnection conn = (HttpURLConnection) new URL(webhookUrl).openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Content-Length", String.valueOf(body.length));
            conn.setRequestProperty("User-Agent", "GenPvP-Plugin/1.0");

            try (OutputStream os = conn.getOutputStream()) {
                os.write(body);
            }

            int code = conn.getResponseCode();
            conn.disconnect();
            // Discord returns 204 No Content on success
            return code == 200 || code == 204;

        } catch (Exception e) {
            plugin.getLogger().warning("[Feedback] Webhook delivery failed: " + e.getMessage());
            return false;
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Formats remaining seconds as "Xh Ym Zs", omitting zero parts. */
    private static String formatRemaining(long seconds) {
        long h = seconds / 3600;
        long m = (seconds % 3600) / 60;
        long s = seconds % 60;
        StringBuilder sb = new StringBuilder();
        if (h > 0) sb.append(h).append("h ");
        if (m > 0) sb.append(m).append("m ");
        if (s > 0 || sb.isEmpty()) sb.append(s).append("s");
        return sb.toString().trim();
    }

    private String cfg(String path, String def) {
        return plugin.getConfig().getString(path, def);
    }

    private static net.kyori.adventure.text.Component component(String raw) {
        return net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
                .legacySection()
                .deserialize(ColorUtil.colorize(raw));
    }
}
