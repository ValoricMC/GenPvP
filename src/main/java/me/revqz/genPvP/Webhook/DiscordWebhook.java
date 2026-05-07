package me.revqz.genPvP.Webhook;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

public enum DiscordWebhook {

    DUPE_LOG("https://discord.com/api/webhooks/1494355689689321642/tKs5CcbJC_iDaN64oKspBRjDOobT-Hdpf9aQxFwOj7XO0abINQy9LKhLifIupTHP1pU0"),

    KOTH("https://discord.com/api/webhooks/1494356505472794822/pwdTQRWsQsa7jgNoJ5w9GJVAryS64_yBB2tSdBgxLktFh21N0tSQnu8dg64WhtiCTP8A");

    private static volatile HttpClient HTTP = HttpClient.newHttpClient();

    private final String url;

    DiscordWebhook(String url) { this.url = url; }

    public void send(String json) {
        HttpClient client = HTTP;
        if (client == null) return; 
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();
        client.sendAsync(request, HttpResponse.BodyHandlers.discarding());
    }

    public static void shutdown() {
        HttpClient old = HTTP;
        HTTP = null;
        if (old != null) {
            old.shutdownNow(); 
        }
    }
}
