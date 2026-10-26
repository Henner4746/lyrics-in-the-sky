package de.henner.lyricsinthesky.hoergenau;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * What Hoergenau learned, shared through the tracker on henrik.click: what this PC learns is sent there, and before a
 * song nobody has timed is only estimated, the tracker is asked whether another player learned it. For a song whose
 * text lrclib.net has, no lyrics are sent or received: only the times of the words and a fingerprint of that text; the
 * times are put on the text this PC got from lrclib.net itself, and only when it is exactly the same. They count on the
 * tracker once it found that text on lrclib.net. For a song nobody has the text of, what was written down as heard is
 * shared with its text; it counts once players on two networks heard it alike. Sent are the song (artist, title,
 * album, length), the word times with the fingerprint or the lyrics written down, and the random install ID; nothing
 * at all while sharing is switched off.
 */
public final class SharedLearned {
    private static final Logger LOGGER = LoggerFactory.getLogger("lyricsinthesky");
    private static final String BASE = "https://henrik.click/lyrics-sync/v1/learned";
    /** A song the tracker did not have is asked about again after this long; after a failure, after a minute. */
    private static final long ASK_AGAIN_MS = 30 * 60_000;
    private static final long RETRY_MS = 60_000;

    /** The times of every word, line by line, and the fingerprint of the text they belong to. */
    public record Timing(List<long[]> times, String text) {}

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

    /**
     * What another player learned for this song ({@code artist\0title\0album}): word times put on {@code text}, the
     * song's lyrics from lrclib.net without times spread over it, or, with {@code text} {@code null} for a song nobody
     * has the text of, the lyrics written down as heard; empty when the tracker has none that fit. Blocks briefly.
     */
    public Optional<String> fetch(String songKey, Lyrics text) {
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
            Optional<String> lrc = learnedOf(response.body(), text);
            if (lrc.isEmpty()) {
                notBefore.put(id, now + ASK_AGAIN_MS);
            }
            return lrc;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (Exception e) {
            notBefore.put(id, now + RETRY_MS);
            LOGGER.debug("Shared word times not reached: {}", e.toString());
            return Optional.empty();
        }
    }

    /**
     * Learned lyrics from the tracker's answer: with lrclib.net's text, its word times put on it; without, the lyrics
     * another player wrote down as heard, if they are learned lyrics with word times.
     */
    static Optional<String> learnedOf(String body, Lyrics text) {
        if (text != null) {
            return timingOf(body).flatMap(timing -> onto(timing, text));
        }
        JsonObject root = JsonParser.parseString(body).getAsJsonObject();
        if (!root.has("lrc") || !root.get("lrc").isJsonPrimitive() || !root.has("kind")
                || !"heard".equals(root.get("kind").getAsString())) {
            return Optional.empty();
        }
        String lrc = root.get("lrc").getAsString();
        Lyrics lyrics = Lyrics.parseLrc(lrc);
        return lrc.startsWith(HeardLyrics.MARK) && !lyrics.isEmpty() && lyrics.hasWordTiming()
                ? Optional.of(lrc) : Optional.empty();
    }

    /** The word times in the tracker's answer, if it has some. */
    static Optional<Timing> timingOf(String body) {
        JsonObject root = JsonParser.parseString(body).getAsJsonObject();
        if (!root.has("times") || !root.get("times").isJsonArray() || !root.has("text")
                || !root.get("text").isJsonPrimitive()) {
            return Optional.empty();
        }
        List<long[]> times = new ArrayList<>();
        for (JsonElement line : root.getAsJsonArray("times")) {
            if (!line.isJsonArray()) {
                return Optional.empty();
            }
            JsonArray words = line.getAsJsonArray();
            long[] starts = new long[words.size()];
            for (int i = 0; i < starts.length; i++) {
                starts[i] = words.get(i).getAsLong();
            }
            times.add(starts);
        }
        return Optional.of(new Timing(times, root.get("text").getAsString()));
    }

    /**
     * Learned lyrics: the shared word times on this PC's own text. Only when it is exactly the text the times belong
     * to, every line with as many words as it has times; else empty.
     */
    static Optional<String> onto(Timing timing, Lyrics text) {
        List<String> lines = text.lines().stream().map(Lyrics.Line::text).toList();
        if (lines.size() != timing.times().size() || !fingerprint(lines).equals(timing.text())) {
            return Optional.empty();
        }
        StringBuilder lrc = new StringBuilder(HeardLyrics.MARK).append('\n');
        for (int i = 0; i < lines.size(); i++) {
            String[] words = lines.get(i).split(" ");
            long[] starts = timing.times().get(i);
            if (words.length != starts.length) {
                return Optional.empty();
            }
            lrc.append('[').append(HeardLyrics.stamp(starts[0])).append(']');
            for (int j = 0; j < words.length; j++) {
                lrc.append(j == 0 ? "" : " ").append('<').append(HeardLyrics.stamp(starts[j])).append('>').append(words[j]);
            }
            lrc.append('\n');
        }
        return Optional.of(lrc.toString());
    }

    /** The word times of learned lyrics, without their text; empty when a word has no time of its own. */
    static Optional<Timing> timingOf(Lyrics learned) {
        List<long[]> times = new ArrayList<>();
        List<String> lines = new ArrayList<>();
        for (Lyrics.Line line : learned.lines()) {
            long[] starts = line.wordStartsMs();
            if (starts == null || starts.length != line.words().length || starts.length == 0) {
                return Optional.empty();
            }
            for (long start : starts) {
                if (start < 0) {
                    return Optional.empty();
                }
            }
            times.add(starts.clone());
            lines.add(line.text());
        }
        return lines.isEmpty() ? Optional.empty() : Optional.of(new Timing(times, fingerprint(lines)));
    }

    /**
     * Which text word times belong to, without the text itself: the first 16 bytes of SHA-256 over its lines in lower
     * case, one below the other. The tracker makes the same from lrclib.net's text to check it.
     */
    static String fingerprint(List<String> lines) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(String.join("\n", lines).toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** What is sent for a song learned on lrclib.net's text: its names and length, and the word times, no words. */
    static JsonObject body(String songKey, NowPlaying track, Timing timing, double matched, String installId,
            String version) {
        JsonObject body = new JsonObject();
        body.addProperty("song", LyricsCache.idOf(songKey));
        body.addProperty("artist", track.artist());
        body.addProperty("title", track.title());
        body.addProperty("album", track.album());
        body.addProperty("durationMs", track.durationMs());
        body.addProperty("kind", "aligned");
        body.addProperty("matched", Math.max(0, Math.min(1, matched)));
        JsonArray times = new JsonArray();
        for (long[] line : timing.times()) {
            JsonArray words = new JsonArray();
            for (long start : line) {
                words.add(start);
            }
            times.add(words);
        }
        body.add("times", times);
        body.addProperty("text", timing.text());
        body.addProperty("install", installId);
        body.addProperty("version", version);
        return body;
    }

    /** What is sent for a song nobody has the text of: its names and length, and the lyrics written down as heard. */
    static JsonObject heardBody(String songKey, NowPlaying track, String lrc, String installId, String version) {
        JsonObject body = new JsonObject();
        body.addProperty("song", LyricsCache.idOf(songKey));
        body.addProperty("artist", track.artist());
        body.addProperty("title", track.title());
        body.addProperty("album", track.album());
        body.addProperty("durationMs", track.durationMs());
        body.addProperty("kind", "heard");
        body.addProperty("lrc", lrc);
        body.addProperty("install", installId);
        body.addProperty("version", version);
        return body;
    }

    /**
     * Sends the word times this PC learned for a song on lrclib.net's text, in the background. Whether they are sent:
     * only with sharing on and when every word has its time.
     */
    public boolean share(String songKey, NowPlaying track, String lrc, double matched) {
        Optional<Timing> timing = timingOf(Lyrics.parseLrc(lrc));
        if (!sharing.getAsBoolean() || track.durationMs() <= 0 || timing.isEmpty()) {
            return false;
        }
        send(track, body(songKey, track, timing.get(), matched, installId, version));
        return true;
    }

    /** Sends the lyrics this PC wrote down as heard for a song nobody has the text of, in the background. */
    public boolean shareHeard(String songKey, NowPlaying track, String lrc) {
        if (!sharing.getAsBoolean() || track.durationMs() <= 0) {
            return false;
        }
        send(track, heardBody(songKey, track, lrc, installId, version));
        return true;
    }

    private void send(NowPlaying track, JsonObject body) {
        worker.execute(() -> {
            try {
                // The tracker checks the text on lrclib.net before it answers, which can take a while.
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
        return "LyricsInTheSky/" + version + " (Minecraft mod; shared Hoergenau learning)";
    }
}
