package de.henner.lyricsinthesky.timing;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Talks to the timing tracker on henrik.click. For a song with lyrics it asks which correction other players found;
 * once enough of them agree, that correction applies here too. The player's own corrections are told to it a few
 * seconds after the last change. Only a random install ID and song IDs (hashes) are sent, and nothing at all while
 * sharing is switched off.
 */
public final class TimingTracker {
    private static final Logger LOGGER = LoggerFactory.getLogger("lyricsinthesky");
    private static final String BASE = "https://henrik.click/lyrics-sync/v1/corrections";
    /** A song is asked about once; after a failure, again after this long. */
    private static final long ASK_AGAIN_MS = 10 * 60_000;
    /** A correction is told this long after the last change, so dragging a slider sends one value, not dozens. */
    private static final long TELL_AFTER_MS = 6_000;

    private final SongTimings timings;
    private final BooleanSupplier sharing;
    private final String installId;
    private final String version;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "LyricsInTheSky-Tracker");
        thread.setDaemon(true);
        return thread;
    });
    private final Map<String, Long> askedAt = new ConcurrentHashMap<>();
    private final Map<String, ScheduledFuture<?>> pending = new ConcurrentHashMap<>();

    public TimingTracker(SongTimings timings, BooleanSupplier sharing, String installId, String version) {
        this.timings = timings;
        this.sharing = sharing;
        this.installId = installId;
        this.version = version;
    }

    /** Asks once what other players corrected for this song; cheap to call every tick. */
    public void ask(String songId) {
        long now = System.currentTimeMillis();
        Long last = askedAt.get(songId);
        if (!sharing.getAsBoolean() || last != null && now - last < ASK_AGAIN_MS) {
            return;
        }
        askedAt.put(songId, now);
        worker.execute(() -> {
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(BASE + "/" + songId))
                        .timeout(Duration.ofSeconds(10)).header("User-Agent", userAgent()).GET().build();
                HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (response.statusCode() != 200) {
                    throw new java.io.IOException("tracker answered HTTP " + response.statusCode());
                }
                SongTimings.Shared common = parse(response.body());
                timings.putShared(songId, common);
                // Only the answer counts as asked; a failure is retried after a while.
                askedAt.put(songId, Long.MAX_VALUE);
                if (common.offsetMs() != null) {
                    LOGGER.info("Timing tracker: {} players agree on {} ms for this song", common.players(), common.offsetMs());
                }
            } catch (Exception e) {
                LOGGER.debug("Timing tracker not reached: {}", e.toString());
            }
        });
    }

    /**
     * The player's own correction for a song, told to the tracker once it has not changed for a few seconds;
     * {@code null} takes it back.
     */
    public void tell(String songId, Integer offsetMs) {
        if (!sharing.getAsBoolean()) {
            return;
        }
        ScheduledFuture<?> before = pending.put(songId, worker.schedule(() -> send(songId, offsetMs), TELL_AFTER_MS,
                TimeUnit.MILLISECONDS));
        if (before != null) {
            before.cancel(false);
        }
    }

    private void send(String songId, Integer offsetMs) {
        pending.remove(songId);
        try {
            JsonObject body = new JsonObject();
            body.addProperty("song", songId);
            body.addProperty("offsetMs", offsetMs);
            body.addProperty("install", installId);
            body.addProperty("version", version);
            HttpRequest request = HttpRequest.newBuilder(URI.create(BASE)).timeout(Duration.ofSeconds(10))
                    .header("User-Agent", userAgent()).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8)).build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            LOGGER.info("Timing tracker: correction {} (HTTP {})", offsetMs == null ? "taken back" : offsetMs + " ms shared",
                    response.statusCode());
            // Ask again so a newly reached agreement shows up.
            askedAt.remove(songId);
        } catch (Exception e) {
            LOGGER.info("Timing tracker: correction not shared: {}", e.toString());
        }
    }

    static SongTimings.Shared parse(String body) {
        JsonObject root = JsonParser.parseString(body).getAsJsonObject();
        int players = root.has("players") ? root.get("players").getAsInt() : 0;
        Integer offset = root.has("offsetMs") && !root.get("offsetMs").isJsonNull()
                ? Math.max(-SongTimings.MAX_OFFSET_MS, Math.min(SongTimings.MAX_OFFSET_MS, root.get("offsetMs").getAsInt())) : null;
        return new SongTimings.Shared(players, offset);
    }

    private String userAgent() {
        return "LyricsInTheSky/" + version + " (Minecraft mod; timing corrections)";
    }
}
