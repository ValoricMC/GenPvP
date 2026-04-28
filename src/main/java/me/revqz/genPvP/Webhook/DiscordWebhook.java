package me.revqz.genPvP.Webhook;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

/**
 * All Discord webhook endpoints used by GenPvP.
 *
 * <p>Call {@link #send(String)} with a JSON payload — it fires asynchronously
 * and never blocks the server thread.
 *
 * <p><b>Lifecycle:</b> call {@link #shutdown()} from {@code onDisable()} so the
 * underlying HTTP thread pool is terminated before Plugman unloads the classloader.
 * Leaving those threads alive prevents the old classloader from being GC'd, which
 * causes the database to fail to reconnect on the next reload.
 */
public enum DiscordWebhook {

    /** Receives dupe-detection alerts. */
    DUPE_LOG("https://discord.com/api/webhooks/1494355689689321642/tKs5CcbJC_iDaN64oKspBRjDOobT-Hdpf9aQxFwOj7XO0abINQy9LKhLifIupTHP1pU0"),

    /** Receives KOTH start / stop / capture / win events. */
    KOTH("https://discord.com/api/webhooks/1494356505472794822/pwdTQRWsQsa7jgNoJ5w9GJVAryS64_yBB2tSdBgxLktFh21N0tSQnu8dg64WhtiCTP8A");

    // ─── Shared HTTP client — volatile so shutdown() flushes across threads ─
    private static volatile HttpClient HTTP = HttpClient.newHttpClient();

    private final String url;

    DiscordWebhook(String url) { this.url = url; }

    /**
     * Posts {@code json} to this webhook asynchronously (fire-and-forget).
     * Errors are silently discarded so server performance is never affected.
     */
    public void send(String json) {
        HttpClient client = HTTP;
        if (client == null) return; // already shut down
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();
        client.sendAsync(request, HttpResponse.BodyHandlers.discarding());
    }

    /**
     * Immediately cancels all pending webhook requests and terminates the HTTP
     * thread pool. Must be called from {@code GenPvP.onDisable()} so the threads
     * are stopped before Plugman unloads the classloader.
     */
    public static void shutdown() {
        HttpClient old = HTTP;
        HTTP = null;
        if (old != null) {
            old.shutdownNow(); // non-blocking: interrupts in-flight requests and stops threads
        }
    }
}
