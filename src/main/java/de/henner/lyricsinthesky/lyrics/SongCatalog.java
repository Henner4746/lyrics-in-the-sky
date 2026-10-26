package de.henner.lyricsinthesky.lyrics;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import de.henner.lyricsinthesky.nowplaying.NowPlaying;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Whether what plays is a song: a music catalog knows a song of that artist with that title, Deezer's public search
 * first, else Apple's. Hoergenau writes down what it hears only of songs; a livestream, a video or a podcast a browser
 * or a music app plays has no song text, and what is said in it is nobody's lyrics. Asked are api.deezer.com and
 * itunes.apple.com, with the playing song's artist and title, once per song and game.
 */
public final class SongCatalog {
    private static final Logger LOGGER = LoggerFactory.getLogger("lyricsinthesky");
    private static final String DEEZER = "https://api.deezer.com/search?limit=10&q=";
    private static final String APPLE = "https://itunes.apple.com/search?entity=song&limit=10&term=";
    /** When neither catalog could be asked, they are asked again after this long. */
    private static final long RETRY_MS = 2 * 60_000;

    private final WebText web;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "LyricsInTheSky-SongCatalog");
        thread.setDaemon(true);
        return thread;
    });
    /** Song key and whether a catalog knows it. */
    private final Map<String, Boolean> answers = new ConcurrentHashMap<>();
    /** Song key and when it may be asked about (again). */
    private final Map<String, Long> notBefore = new ConcurrentHashMap<>();

    public SongCatalog(String modVersion) {
        this.web = new WebText(modVersion, Duration.ofSeconds(8));
    }

    /**
     * Whether a catalog knows the song ({@code key} as the lyrics store files it); empty while it is being asked, which
     * happens in the background. Cheap to call every tick.
     */
    public Optional<Boolean> knows(String key, NowPlaying track) {
        Boolean known = answers.get(key);
        if (known != null) {
            return Optional.of(known);
        }
        long now = System.currentTimeMillis();
        if (notBefore.getOrDefault(key, 0L) <= now) {
            notBefore.put(key, Long.MAX_VALUE);
            worker.execute(() -> {
                try {
                    boolean found = ask(track);
                    answers.put(key, found);
                    LOGGER.info("Music catalog: '{}' by '{}' is {}", track.title(), track.artist(),
                            found ? "a song" : "no song it knows (livestream, video or podcast)");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    notBefore.put(key, System.currentTimeMillis() + RETRY_MS);
                } catch (Exception e) {
                    notBefore.put(key, System.currentTimeMillis() + RETRY_MS);
                    LOGGER.info("Music catalog not reached for '{}': {}", track.title(), e.toString());
                }
            });
        }
        return Optional.empty();
    }

    /** Asks the catalogs in turn; throws only when none of them answered. */
    private boolean ask(NowPlaying track) throws IOException, InterruptedException {
        String search = URLEncoder.encode(SearchHit.searchText(track), StandardCharsets.UTF_8);
        IOException failure = null;
        boolean answered = false;
        for (String catalog : List.of(DEEZER, APPLE)) {
            try {
                Optional<String> body = web.get(catalog + search);
                answered = true;
                if (body.isPresent() && known(track, catalog.equals(DEEZER) ? DeezerClient.hits(body.get())
                        : appleHits(body.get()))) {
                    return true;
                }
            } catch (IOException | RuntimeException e) {
                failure = e instanceof IOException io ? io : new IOException(e);
            }
        }
        if (!answered && failure != null) {
            throw failure;
        }
        return false;
    }

    /** Whether one of a catalog's hits is this song: the same title and one artist in common, in any length or cut. */
    static boolean known(NowPlaying track, List<SearchHit> hits) {
        String title = SearchHit.coreTitle(track.title());
        return hits.stream().anyMatch(hit -> TrackNames.sameSong(title, track.artist(), hit.title(), hit.artist()));
    }

    static List<SearchHit> appleHits(String body) {
        List<SearchHit> hits = new ArrayList<>();
        JsonObject root = JsonParser.parseString(body).getAsJsonObject();
        if (!root.has("results") || !root.get("results").isJsonArray()) {
            return hits;
        }
        for (JsonElement element : root.getAsJsonArray("results")) {
            JsonObject song = element.getAsJsonObject();
            long duration = song.has("trackTimeMillis") ? song.get("trackTimeMillis").getAsLong() : 0;
            hits.add(new SearchHit(text(song, "trackId"), text(song, "trackName"), text(song, "artistName"), duration,
                    ""));
        }
        return hits;
    }

    private static String text(JsonObject object, String key) {
        return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : "";
    }
}
