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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Looks up synced lyrics on <a href="https://lrclib.net/docs">LRCLIB</a>.
 *
 * <p>Order: exact {@code /api/get} with duration (LRCLIB allows about two seconds of difference),
 * then {@code /api/search} filtered by duration. What it finds is kept on disk by {@link LyricsCache}.
 */
public final class LrclibClient {
    private static final Logger LOGGER = LoggerFactory.getLogger("lyricsinthesky");
    private static final String BASE = "https://lrclib.net/api/";
    private static final long MAX_DURATION_DIFF_MS = 3_000;
    /** An enhanced-LRC word timestamp such as {@code <01:23.45>}. */
    private static final Pattern WORD_TAG = Pattern.compile("<\\d{1,3}:\\d{1,2}(?:[.:]\\d{1,3})?>");

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final String userAgent;

    public LrclibClient(String modVersion) {
        // LRCLIB asks clients to identify themselves and grants identified apps a higher rate limit.
        this.userAgent = "LyricsInTheSky/" + modVersion + " (Minecraft mod prototype)";
    }

    /**
     * What LRCLIB has for a track.
     *
     * @param synced lyrics with times, empty when it has none
     * @param plain  only when there are no synced lyrics: the text without times, if LRCLIB has that
     */
    public record Lookup(Optional<Lyrics> synced, Optional<String> plain) {}

    /** Synced lyrics only, see {@link #lookup}. */
    public Optional<Lyrics> find(NowPlaying track) throws IOException, InterruptedException {
        return lookup(track).synced();
    }

    /**
     * Tries the exact lookup, then a search for each of the title's variants ("- Remastered 2011", "(Single Version)"
     * and the like taken off), then a free search over artist and title. Lyrics with their own word timestamps win over
     * line-timed ones of the same song: only with them is every word exact instead of estimated. Throws only when LRCLIB
     * could not be asked at all.
     */
    public Lookup lookup(NowPlaying track) throws IOException, InterruptedException {
        String artist = TrackNames.primaryArtist(track.artist());
        List<String> titles = TrackNames.titleVariants(track.title());
        long duration = track.durationMs();
        IOException failure = null;
        boolean answered = false;
        String plain = null;

        Optional<Lyrics> exact = Optional.empty();
        if (duration > 0) {
            Map<String, String> query = new LinkedHashMap<>();
            query.put("track_name", titles.get(0));
            query.put("artist_name", artist);
            if (!track.album().isBlank()) {
                query.put("album_name", track.album());
            }
            query.put("duration", Long.toString(Math.round(duration / 1000.0)));
            try {
                Optional<JsonObject> record = getObject("get", query);
                answered = true;
                exact = record.flatMap(LrclibClient::toLyrics);
                plain = record.filter(found -> hasText(found, "plainLyrics")).map(found -> found.get("plainLyrics").getAsString())
                        .orElse(null);
            } catch (IOException e) {
                // "get" may ask other sources first and time out or answer 503 when LRCLIB is busy; the search
                // below only reads LRCLIB's own records and often still works.
                LOGGER.debug("LRCLIB get failed, trying the search: {}", e.getMessage());
                failure = e;
            }
            if (exact.isPresent() && (exact.get().hasWordTiming() || exact.get().isInstrumental())) {
                return new Lookup(exact, Optional.empty());
            }
        }

        Optional<Lyrics> found = Optional.empty();
        for (String title : titles) {
            Map<String, String> query = new LinkedHashMap<>();
            query.put("track_name", title);
            query.put("artist_name", artist);
            try {
                JsonArray results = getArray("search", query);
                answered = true;
                found = pick(results, duration).flatMap(LrclibClient::toLyrics);
                plain = plain != null ? plain : pickPlain(results, duration);
            } catch (IOException e) {
                failure = e;
            }
            // The exact lookup already has line times; another search is only worth it for word times.
            if (found.isPresent() || exact.isPresent()) {
                break;
            }
        }
        if (found.isEmpty() && exact.isEmpty()) {
            // A free search over artist and title finds songs stored under a slightly different name; its results
            // must still name the song and the artist.
            String bare = titles.get(titles.size() - 1);
            try {
                JsonArray results = getArray("search", Map.of("q", artist + " " + bare));
                answered = true;
                JsonArray sameSong = new JsonArray();
                results.forEach(result -> {
                    JsonObject candidate = result.getAsJsonObject();
                    if (TrackNames.sameSong(bare, artist, string(candidate, "trackName"), string(candidate, "artistName"))) {
                        sameSong.add(candidate);
                    }
                });
                found = pick(sameSong, duration).flatMap(LrclibClient::toLyrics);
                plain = plain != null ? plain : pickPlain(sameSong, duration);
            } catch (IOException e) {
                failure = e;
            }
        }
        if (!answered) {
            throw failure;
        }
        Optional<Lyrics> synced = exact.isPresent() && !found.map(Lyrics::hasWordTiming).orElse(false) ? exact
                : found.isPresent() ? found : exact;
        return new Lookup(synced, synced.isPresent() ? Optional.empty() : Optional.ofNullable(plain));
    }

    /** The plain text of the result closest in length, for a song LRCLIB has no times for. */
    static String pickPlain(JsonArray results, long durationMs) {
        String best = null;
        long bestDiff = Long.MAX_VALUE;
        for (JsonElement element : results) {
            JsonObject candidate = element.getAsJsonObject();
            if (!hasText(candidate, "plainLyrics")) {
                continue;
            }
            boolean hasDuration = candidate.has("duration") && !candidate.get("duration").isJsonNull();
            long diff = durationMs > 0 && hasDuration
                    ? Math.abs(Math.round(candidate.get("duration").getAsDouble() * 1000) - durationMs) : 0;
            if (diff <= MAX_DURATION_DIFF_MS && diff < bestDiff) {
                best = candidate.get("plainLyrics").getAsString();
                bestDiff = diff;
            }
        }
        return best;
    }

    private static String string(JsonObject record, String key) {
        return hasText(record, key) ? record.get(key).getAsString() : "";
    }

    /**
     * The search result that fits the track best: within a few seconds of its length, with word timestamps if any
     * result has them, otherwise the closest in length.
     */
    static Optional<JsonObject> pick(JsonArray results, long durationMs) {
        JsonObject best = null;
        boolean bestWordTimed = false;
        long bestDiff = Long.MAX_VALUE;
        for (JsonElement element : results) {
            JsonObject candidate = element.getAsJsonObject();
            if (!hasText(candidate, "syncedLyrics") && !isInstrumental(candidate)) {
                continue;
            }
            // Results may carry null for any field.
            boolean hasDuration = candidate.has("duration") && !candidate.get("duration").isJsonNull();
            long diff = durationMs > 0 && hasDuration
                    ? Math.abs(Math.round(candidate.get("duration").getAsDouble() * 1000) - durationMs)
                    : 0;
            if (diff > MAX_DURATION_DIFF_MS) {
                continue;
            }
            boolean wordTimed = hasText(candidate, "syncedLyrics") && WORD_TAG.matcher(candidate.get("syncedLyrics").getAsString()).find();
            if (best == null || wordTimed && !bestWordTimed || wordTimed == bestWordTimed && diff < bestDiff) {
                best = candidate;
                bestWordTimed = wordTimed;
                bestDiff = diff;
            }
        }
        return Optional.ofNullable(best);
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
        return record.has("instrumental") && !record.get("instrumental").isJsonNull() && record.get("instrumental").getAsBoolean();
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
