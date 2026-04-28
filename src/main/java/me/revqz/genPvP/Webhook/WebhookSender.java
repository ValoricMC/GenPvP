package me.revqz.genPvP.Webhook;

import java.time.Instant;

/**
 * Convenience façade that builds Discord embeds and dispatches them to the
 * correct {@link DiscordWebhook} endpoint.
 *
 * <p>Every method is fire-and-forget — the HTTP POST is dispatched
 * asynchronously and will never block a server thread.
 *
 * <p>Player skins are rendered as full-body "natural position" images via
 * <a href="https://crafatar.com">Crafatar</a>.
 *
 * <p>All timestamps use Discord's dynamic {@code <t:UNIX:t>} format so each
 * user sees the time in their own local timezone automatically.
 */
public final class WebhookSender {

    /** Crafatar full-body render — upright, natural position, hat overlay included. */
    private static final String BODY_URL = "https://crafatar.com/renders/body/%s?overlay&scale=6";

    private WebhookSender() {}

    // ── Dupe Detection ────────────────────────────────────────────────────────

    /**
     * Sends a dupe-detection alert to {@link DiscordWebhook#DUPE_LOG}.
     *
     * @param playerName  IGN of the suspect
     * @param playerUuid  UUID string (used for the skin thumbnail)
     * @param itemType    material name, e.g. {@code "DIAMOND_SWORD"}
     * @param itemUid     internal PDC UUID assigned to this item
     */
    public static void sendDupeLog(String playerName, String playerUuid,
                                   String itemType, String itemUid) {
        long unix = Instant.now().getEpochSecond();

        String json = new EmbedBuilder()
                .title("⚠  Dupe Detected")
                .color(0xED4245)                          // Discord red
                .thumbnail(String.format(BODY_URL, playerUuid))
                .field("Player",   playerName,            true)
                .field("Item",     itemType,              true)
                .field("Item UID", "`" + itemUid + "`",  false)
                .field("Time",     ts(unix),              true)
                .build();

        DiscordWebhook.DUPE_LOG.send(json);
    }

    // ── KOTH ─────────────────────────────────────────────────────────────────

    /** KOTH event has just started. */
    public static void sendKothStarted() {
        long unix = Instant.now().getEpochSecond();

        String json = new EmbedBuilder()
                .title("KOTH — STARTED")
                .color(0x57F287)                          // Discord green
                .field("Time", ts(unix), true)
                .field("Date", date(unix), true)
                .build();

        DiscordWebhook.KOTH.send(json);
    }

    /**
     * A player began capturing the KOTH zone.
     *
     * @param capturerName IGN of the player
     * @param capturerUuid UUID string (for the skin thumbnail)
     * @param teamName     team name, or {@code null} if the player has no team
     */
    public static void sendKothCaptureStarted(String capturerName, String capturerUuid,
                                              String teamName) {
        long unix = Instant.now().getEpochSecond();

        String json = new EmbedBuilder()
                .title("KOTH — CAPTURE STARTED")
                .color(0xFEE75C)                          // Discord yellow
                .thumbnail(String.format(BODY_URL, capturerUuid))
                .field("Capturer", capturerName,          true)
                .field("Team",     teamOrDash(teamName),  true)
                .field("Time",     ts(unix),              true)
                .build();

        DiscordWebhook.KOTH.send(json);
    }

    /**
     * An ongoing capture was interrupted (player left the zone or a second
     * player entered).
     *
     * @param capturerName IGN of the player whose progress was reset
     * @param capturerUuid UUID string (for the skin thumbnail)
     * @param teamName     team name, or {@code null} if the player has no team
     */
    public static void sendKothCaptureStopped(String capturerName, String capturerUuid,
                                              String teamName) {
        long unix = Instant.now().getEpochSecond();

        String json = new EmbedBuilder()
                .title("KOTH — CAPTURE STOPPED")
                .color(0xED4245)                          // Discord red
                .thumbnail(String.format(BODY_URL, capturerUuid))
                .field("Capturer", capturerName,          true)
                .field("Team",     teamOrDash(teamName),  true)
                .field("Time",     ts(unix),              true)
                .build();

        DiscordWebhook.KOTH.send(json);
    }

    /**
     * A player successfully captured KOTH and won.
     *
     * @param capturerName IGN of the winner
     * @param capturerUuid UUID string (for the skin thumbnail)
     * @param teamName     team name, or {@code null} if the player has no team
     */
    public static void sendKothCaptured(String capturerName, String capturerUuid,
                                        String teamName) {
        long unix = Instant.now().getEpochSecond();

        String json = new EmbedBuilder()
                .title("KOTH — CAPTURED")
                .color(0x57F287)                          // Discord green
                .thumbnail(String.format(BODY_URL, capturerUuid))
                .field("Capturer", capturerName,          true)
                .field("Team",     teamOrDash(teamName),  true)
                .field("Time",     ts(unix),              true)
                .build();

        DiscordWebhook.KOTH.send(json);
    }

    /**
     * KOTH ended without a winner (timeout or admin force-stop).
     *
     * @param reason human-readable reason, e.g. {@code "Ran out of time"} or
     *               {@code "Admin force-stopped"}
     */
    public static void sendKothStopped(String reason) {
        long unix = Instant.now().getEpochSecond();

        String json = new EmbedBuilder()
                .title("KOTH — STOPPED")
                .color(0xED4245)                          // Discord red
                .field("Reason", reason,      false)
                .field("Time",   ts(unix),    true)
                .field("Date",   date(unix),  true)
                .build();

        DiscordWebhook.KOTH.send(json);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Discord short-time timestamp: shows e.g. {@code 8:48 PM} in the user's timezone. */
    private static String ts(long unixSeconds) {
        return "<t:" + unixSeconds + ":t>";
    }

    /** Discord long-date timestamp: shows e.g. {@code April 16, 2026}. */
    private static String date(long unixSeconds) {
        return "<t:" + unixSeconds + ":D>";
    }

    private static String teamOrDash(String team) {
        return (team != null && !team.isBlank()) ? team : "—";
    }

    // ── Embed builder ─────────────────────────────────────────────────────────

    static final class EmbedBuilder {

        private String title     = "";
        private int    color     = 0x5865F2;  // Discord blurple
        private String thumbnail = null;
        private final StringBuilder fields = new StringBuilder();

        EmbedBuilder title(String t)     { title = escape(t); return this; }
        EmbedBuilder color(int c)        { color = c;         return this; }
        EmbedBuilder thumbnail(String u) { thumbnail = u;     return this; }

        EmbedBuilder field(String name, String value, boolean inline) {
            if (fields.length() > 0) fields.append(',');
            fields.append("{\"name\":\"").append(escape(name)).append("\",")
                  .append("\"value\":\"").append(escape(value)).append("\",")
                  .append("\"inline\":").append(inline).append('}');
            return this;
        }

        String build() {
            StringBuilder sb = new StringBuilder("{\"embeds\":[{");
            sb.append("\"title\":\"").append(title).append("\",");
            sb.append("\"color\":").append(color);
            if (thumbnail != null) {
                sb.append(",\"thumbnail\":{\"url\":\"").append(thumbnail).append("\"}");
            }
            if (fields.length() > 0) {
                sb.append(",\"fields\":[").append(fields).append(']');
            }
            sb.append("}]}");
            return sb.toString();
        }

        private static String escape(String s) {
            return s.replace("\\", "\\\\")
                    .replace("\"", "\\\"")
                    .replace("\n", "\\n")
                    .replace("\r", "");
        }
    }
}
