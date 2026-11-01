package lootgobbler;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Minimal Discord webhook client. All calls are async; failures come back as an error message, never thrown.
 */
public class Discord {
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private static final String PAYLOAD = "{\"username\":\"Loot Gobbler\"}";

    public static boolean isWebhookUrl(String url) {
        return url != null && (url.startsWith("https://discord.com/api/webhooks/")
                || url.startsWith("https://discordapp.com/api/webhooks/"));
    }

    /**
     * Uploads a PNG as the message attachment.
     *
     * @return Future holding null on success, or an error message.
     */
    public static CompletableFuture<String> postImage(String webhook, byte[] png, String fileName) {
        String boundary = "----LootGobbler" + UUID.randomUUID();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        write(body, "--" + boundary + "\r\nContent-Disposition: form-data; name=\"payload_json\"\r\n"
                + "Content-Type: application/json\r\n\r\n" + PAYLOAD + "\r\n");
        write(body, "--" + boundary + "\r\nContent-Disposition: form-data; name=\"files[0]\"; filename=\""
                + fileName + "\"\r\nContent-Type: image/png\r\n\r\n");
        body.writeBytes(png);
        write(body, "\r\n--" + boundary + "--\r\n");

        HttpRequest req = HttpRequest.newBuilder(URI.create(webhook))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .timeout(Duration.ofSeconds(20))
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
                .build();
        return send(req).thenApply(res -> res.statusCode() < 300 ? null : "HTTP " + res.statusCode() + " " + res.body())
                .exceptionally(Throwable::getMessage);
    }

    /**
     * Looks the webhook up; Discord answers with its configured name.
     *
     * @return Future holding the webhook's name. Completes exceptionally if the URL does not work.
     */
    public static CompletableFuture<String> fetchName(String webhook) {
        HttpRequest req = HttpRequest.newBuilder(URI.create(webhook)).timeout(Duration.ofSeconds(10)).GET().build();
        return send(req).thenApply(res -> {
            if (res.statusCode() >= 300) throw new IllegalStateException("HTTP " + res.statusCode());
            JsonObject o = JsonParser.parseString(res.body()).getAsJsonObject();
            return o.has("name") && !o.get("name").isJsonNull() ? o.get("name").getAsString() : "Webhook";
        });
    }

    private static CompletableFuture<HttpResponse<String>> send(HttpRequest req) {
        return HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString());
    }

    private static void write(ByteArrayOutputStream out, String s) {
        out.writeBytes(s.getBytes(StandardCharsets.UTF_8));
    }
}
