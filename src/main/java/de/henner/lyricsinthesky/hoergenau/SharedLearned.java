package de.henner.lyricsinthesky.hoergenau;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import de.henner.lyricsinthesky.lyrics.Lyrics;
import de.henner.lyricsinthesky.lyrics.LyricsCache;
import de.henner.lyricsinthesky.nowplaying.NowPlaying;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Songs Hoergenau learned, shared through the tracker on henrik.click: what this PC learns is sent there, and before a
 * song nobody has timed is only estimated, the tracker is asked whether another player learned it. Lyrics placed on
 * lrclib.net's text count there once the tracker found that text on lrclib.net itself; lyrics written down as heard
 * once players on two networks heard alike. Sent are the song (artist, title, album, length), the learned lyrics and
 * the random install ID; nothing at all while sharing is switched off.
 */
public final class SharedLearned {
    private static final Logger LOGGER = LoggerFactory.getLogger("lyricsinthesky");
    private static final String BASE = "https://henrik.click/lyrics-sync/v1/learned";
    /** A song the tracker did not have is asked about again after this long; after a failure, after a minute. */
    private static final long ASK_AGAIN_MS = 30 * 60_000;
    private static final long RETRY_MS = 60_000;

    private final BooleanSupplier sharing;
    private final String installId;
    private final String version;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4)).build();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "LyricsInTheSky-SharedLearned");
        thread.setDaemon(true);
        return thread;
    });
    /** Song ID and when it may be asked about again. */
    private final Map<String, Long> notBefore = new ConcurrentHashMap<>();

    public SharedLearned(BooleanSupplier sharing, String installId, String version) {
        this.sharing = sharing;
        this.installId = installId;
        this.version = version;
    }

    /** What another player learned for this song ({@code artist\0title\0album}), if the tracker has it; blocks briefly. */
    public Optional<String> fetch(String songKey) {
        String id = LyricsCache.idOf(songKey);
        long now = System.currentTimeMillis();
        if (!sharing.getAsBoolean() || notBefore.getOrDefault(id, 0L) > now) {
            return Optional.empty();
        }
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(BASE + "/" + id)).timeout(Duration.ofSeconds(6))
                    .header("User-Agent", userAgent()).GET().build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() == 404) {
                notBefore.put(id, now + ASK_AGAIN_MS);
                return Optional.empty();
            }
            if (response.statusCode() != 200) {
                throw new java.io.IOException("tracker answered HTTP " + response.statusCode());
            }
            Optional<String> lrc = lrcOf(response.body());
            if (lrc.isEmpty()) {
                notBefore.put(id, now + ASK_AGAIN_MS);
            }
            return lrc;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (Exception e) {
            notBefore.put(id, now + RETRY_MS);
            LOGGER.debug("Shared learned lyrics not reached: {}", e.toString());
            return Optional.empty();
        }
    }

    /** Learned lyrics from the tracker's answer, if they are learned lyrics with word times. */
    static Optional<String> lrcOf(String body) {
        JsonObject root = JsonParser.parseString(body).getAsJsonObject();
        if (!root.has("lrc") || !root.get("lrc").isJsonPrimitive()) {
            return Optional.empty();
        }
        String lrc = root.get("lrc").getAsString();
        Lyrics lyrics = Lyrics.parseLrc(lrc);
        return lrc.startsWith(HeardLyrics.MARK) && !lyrics.isEmpty() && lyrics.hasWordTiming()
                ? Optional.of(lrc) : Optional.empty();
    }

    /** Sends what this PC learned for a song, in the background. */
    public void share(String songKey, NowPlaying track, String lrc, boolean onKnownText, double matched) {
        if (!sharing.getAsBoolean() || track.durationMs() <= 0) {
            return;
        }
        worker.execute(() -> {
            try {
                JsonObject body = new JsonObject();
                body.addProperty("song", LyricsCache.idOf(songKey));
                body.addProperty("artist", track.artist());
                body.addProperty("title", track.title());
                body.addProperty("album", track.album());
                body.addProperty("durationMs", track.durationMs());
                body.addProperty("kind", onKnownText ? "aligned" : "heard");
                body.addProperty("matched", Math.max(0, Math.min(1, matched)));
                body.addProperty("lrc", lrc);
                body.addProperty("install", installId);
                body.addProperty("version", version);
                // The tracker checks known text on lrclib.net before it answers, which can take a while.
                HttpRequest request = HttpRequest.newBuilder(URI.create(BASE)).timeout(Duration.ofSeconds(45))
                        .header("User-Agent", userAgent()).header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8)).build();
                HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                LOGGER.info("Hoergenau shared '{}' (HTTP {}: {})", track.title(), response.statusCode(), response.body());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                LOGGER.info("Hoergenau could not share '{}': {}", track.title(), e.toString());
            }
        });
    }

    private String userAgent() {
        return "LyricsInTheSky/" + version + " (Minecraft mod; shared Hoergenau lyrics)";
    }
}
