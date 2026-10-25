package de.henner.lyricsinthesky.lyrics;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import de.henner.lyricsinthesky.nowplaying.NowPlaying;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.StringJoiner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Looks up synced lyrics on <a href="https://lrclib.net/docs">LRCLIB</a>.
 *
 * <p>Order: exact {@code /api/get} with duration (LRCLIB allows about two seconds of difference),
 * then {@code /api/search} filtered by duration. Nothing is stored on disk.
 */
public final class LrclibClient {
    private static final Logger LOGGER = LoggerFactory.getLogger("lyricsinthesky");
    private static final String BASE = "https://lrclib.net/api/";
    private static final long MAX_DURATION_DIFF_MS = 3_000;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final String userAgent;

    public LrclibClient(String modVersion) {
        // LRCLIB asks clients to identify themselves and grants identified apps a higher rate limit.
        this.userAgent = "LyricsInTheSky/" + modVersion + " (Minecraft mod prototype)";
    }

    /** Empty when LRCLIB has no synced lyrics for the track. */
    public Optional<Lyrics> find(NowPlaying track) throws IOException, InterruptedException {
        String title = TrackNames.cleanTitle(track.title());
        String artist = TrackNames.primaryArtist(track.artist());

        if (track.durationMs() > 0) {
            Map<String, String> query = new LinkedHashMap<>();
            query.put("track_name", title);
            query.put("artist_name", artist);
            if (!track.album().isBlank()) {
                query.put("album_name", track.album());
            }
            query.put("duration", Long.toString(Math.round(track.durationMs() / 1000.0)));
            Optional<JsonObject> exact = getObject("get", query);
            if (exact.isPresent()) {
                Optional<Lyrics> lyrics = toLyrics(exact.get());
                if (lyrics.isPresent()) {
                    return lyrics;
                }
            }
        }

        Map<String, String> query = new LinkedHashMap<>();
        query.put("track_name", title);
        query.put("artist_name", artist);
        JsonArray results = getArray("search", query);
        JsonObject best = null;
        long bestDiff = Long.MAX_VALUE;
        for (JsonElement element : results) {
            JsonObject candidate = element.getAsJsonObject();
            if (!hasText(candidate, "syncedLyrics") && !isInstrumental(candidate)) {
                continue;
            }
            long diff = track.durationMs() > 0 && candidate.has("duration")
                    ? Math.abs(Math.round(candidate.get("duration").getAsDouble() * 1000) - track.durationMs())
                    : 0;
            if (diff <= MAX_DURATION_DIFF_MS && diff < bestDiff) {
                best = candidate;
                bestDiff = diff;
            }
        }
        return best == null ? Optional.empty() : toLyrics(best);
    }

    private static Optional<Lyrics> toLyrics(JsonObject record) {
        if (isInstrumental(record)) {
            return Optional.of(Lyrics.instrumental());
        }
        if (!hasText(record, "syncedLyrics")) {
            return Optional.empty();
        }
        Lyrics lyrics = Lyrics.parseLrc(record.get("syncedLyrics").getAsString());
        return lyrics.isEmpty() ? Optional.empty() : Optional.of(lyrics);
    }

    private static boolean isInstrumental(JsonObject record) {
        return record.has("instrumental") && record.get("instrumental").getAsBoolean();
    }

    private static boolean hasText(JsonObject record, String key) {
        return record.has(key) && !record.get(key).isJsonNull() && !record.get(key).getAsString().isBlank();
    }

    private Optional<JsonObject> getObject(String endpoint, Map<String, String> query)
            throws IOException, InterruptedException {
        HttpResponse<String> response = send(endpoint, query);
        if (response.statusCode() == 404) {
            return Optional.empty();
        }
        checkOk(endpoint, response);
        return Optional.of(JsonParser.parseString(response.body()).getAsJsonObject());
    }

    private JsonArray getArray(String endpoint, Map<String, String> query) throws IOException, InterruptedException {
        HttpResponse<String> response = send(endpoint, query);
        checkOk(endpoint, response);
        return JsonParser.parseString(response.body()).getAsJsonArray();
    }

    private HttpResponse<String> send(String endpoint, Map<String, String> query)
            throws IOException, InterruptedException {
        StringJoiner params = new StringJoiner("&");
        query.forEach((key, value) -> params.add(key + "=" + URLEncoder.encode(value, StandardCharsets.UTF_8)));
        HttpRequest request = HttpRequest.newBuilder(URI.create(BASE + endpoint + "?" + params))
                .timeout(Duration.ofSeconds(10))
                .header("User-Agent", userAgent)
                .GET()
                .build();
        LOGGER.debug("LRCLIB {} {}", endpoint, query);
        return http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static void checkOk(String endpoint, HttpResponse<String> response) throws IOException {
        if (response.statusCode() != 200) {
            throw new IOException("LRCLIB " + endpoint + " answered HTTP " + response.statusCode());
        }
    }
}
