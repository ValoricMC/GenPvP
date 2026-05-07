package me.revqz.genPvP.Webhook;

import java.time.Instant;

public final class WebhookSender {

    private static final String BODY_URL = "https://crafatar.com/renders/body/%s?overlay&scale=6";

    private WebhookSender() {}

    public static void sendDupeLog(String playerName, String playerUuid,
                                   String itemType, String itemUid) {
        long unix = Instant.now().getEpochSecond();

        String json = new EmbedBuilder()
                .title("⚠  Dupe Detected")
                .color(0xED4245)                          
                .thumbnail(String.format(BODY_URL, playerUuid))
                .field("Player",   playerName,            true)
                .field("Item",     itemType,              true)
                .field("Item UID", "`" + itemUid + "`",  false)
                .field("Time",     ts(unix),              true)
                .build();

        DiscordWebhook.DUPE_LOG.send(json);
    }

    public static void sendKothStarted() {
        long unix = Instant.now().getEpochSecond();

        String json = new EmbedBuilder()
                .title("KOTH — STARTED")
                .color(0x57F287)                          
                .field("Time", ts(unix), true)
                .field("Date", date(unix), true)
                .build();

        DiscordWebhook.KOTH.send(json);
    }

    public static void sendKothCaptureStarted(String capturerName, String capturerUuid,
                                              String teamName) {
        long unix = Instant.now().getEpochSecond();

        String json = new EmbedBuilder()
                .title("KOTH — CAPTURE STARTED")
                .color(0xFEE75C)                          
                .thumbnail(String.format(BODY_URL, capturerUuid))
                .field("Capturer", capturerName,          true)
                .field("Team",     teamOrDash(teamName),  true)
                .field("Time",     ts(unix),              true)
                .build();

        DiscordWebhook.KOTH.send(json);
    }

    public static void sendKothCaptureStopped(String capturerName, String capturerUuid,
                                              String teamName) {
        long unix = Instant.now().getEpochSecond();

        String json = new EmbedBuilder()
                .title("KOTH — CAPTURE STOPPED")
                .color(0xED4245)                          
                .thumbnail(String.format(BODY_URL, capturerUuid))
                .field("Capturer", capturerName,          true)
                .field("Team",     teamOrDash(teamName),  true)
                .field("Time",     ts(unix),              true)
                .build();

        DiscordWebhook.KOTH.send(json);
    }

    public static void sendKothCaptured(String capturerName, String capturerUuid,
                                        String teamName) {
        long unix = Instant.now().getEpochSecond();

        String json = new EmbedBuilder()
                .title("KOTH — CAPTURED")
                .color(0x57F287)                          
                .thumbnail(String.format(BODY_URL, capturerUuid))
                .field("Capturer", capturerName,          true)
                .field("Team",     teamOrDash(teamName),  true)
                .field("Time",     ts(unix),              true)
                .build();

        DiscordWebhook.KOTH.send(json);
    }

    public static void sendKothStopped(String reason) {
        long unix = Instant.now().getEpochSecond();

        String json = new EmbedBuilder()
                .title("KOTH — STOPPED")
                .color(0xED4245)                          
                .field("Reason", reason,      false)
                .field("Time",   ts(unix),    true)
                .field("Date",   date(unix),  true)
                .build();

        DiscordWebhook.KOTH.send(json);
    }

    private static String ts(long unixSeconds) {
        return "<t:" + unixSeconds + ":t>";
    }

    private static String date(long unixSeconds) {
        return "<t:" + unixSeconds + ":D>";
    }

    private static String teamOrDash(String team) {
        return (team != null && !team.isBlank()) ? team : "—";
    }

    static final class EmbedBuilder {

        private String title     = "";
        private int    color     = 0x5865F2;  
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
