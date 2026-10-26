package de.henner.lyricsinthesky.lyrics;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.StringJoiner;

/**
 * The small HTTP part the word time services share: a GET with the mod's user agent and a time limit, at most one a
 * second per service, so even a quick run of songs never floods a service that serves us for free.
 */
final class WebText {
    private static final long MIN_GAP_MS = 1_000;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final String userAgent;
    private final Duration timeout;
    private long lastRequestMs;

    WebText(String modVersion, Duration timeout) {
        this.userAgent = "LyricsInTheSky/" + modVersion + " (Minecraft mod; optional word times)";
        this.timeout = timeout;
    }

    /** The body of a 200 answer, empty on 404; throws on anything else, so a busy service is asked again later. */
    Optional<String> get(String url) throws IOException, InterruptedException {
        return send(HttpRequest.newBuilder(URI.create(url)).GET());
    }

    /** Posts JSON, with extra headers given as name, value, name, value … */
    Optional<String> postJson(String url, String json, String... headers) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8));
        for (int i = 0; i + 1 < headers.length; i += 2) {
            request.header(headers[i], headers[i + 1]);
        }
        return send(request);
    }

    private Optional<String> send(HttpRequest.Builder builder) throws IOException, InterruptedException {
        waitTurn();
        HttpRequest request = builder.timeout(timeout).header("User-Agent", userAgent).build();
        String url = request.uri().toString();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() == 404) {
            return Optional.empty();
        }
        if (response.statusCode() != 200) {
            throw new StatusException(URI.create(url).getHost(), response.statusCode());
        }
        return Optional.of(response.body());
    }

    /** An answer other than 200 or 404, with its code, for services that use some codes for "not yet". */
    static final class StatusException extends IOException {
        final int status;

        StatusException(String host, int status) {
            super(host + " answered HTTP " + status);
            this.status = status;
        }
    }

    static String query(Map<String, String> params) {
        StringJoiner joined = new StringJoiner("&");
        params.forEach((key, value) -> joined.add(key + "=" + URLEncoder.encode(value, StandardCharsets.UTF_8)));
        return joined.toString();
    }

    private synchronized void waitTurn() throws InterruptedException {
        long wait = lastRequestMs + MIN_GAP_MS - System.currentTimeMillis();
        if (wait > 0) {
            Thread.sleep(wait);
        }
        lastRequestMs = System.currentTimeMillis();
    }
}
