package de.henner.lyricsinthesky.spotify;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import de.henner.lyricsinthesky.nowplaying.NowPlaying;
import de.henner.lyricsinthesky.nowplaying.NowPlayingSource;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.StringJoiner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * "Now playing" from the Spotify Web API of the connected account. Works for every device the account plays
 * on, including phones and consoles via Spotify Connect, and reports the position exactly.
 */
public final class SpotifyNowPlayingSource implements NowPlayingSource {
    private static final Logger LOGGER = LoggerFactory.getLogger("lyricsinthesky");
    private static final URI CURRENTLY_PLAYING = URI.create("https://api.spotify.com/v1/me/player/currently-playing");
    private static final long POLL_PLAYING_MS = 1_000;
    private static final long POLL_IDLE_MS = 3_000;
    private static final long POLL_DISCONNECTED_MS = 2_000;
    /** Refusals saying the account is not added to the app, in a row, before the login ends. */
    private static final int NOT_REGISTERED_LIMIT = 3;
    private static final long MAX_REFUSED_WAIT_MS = 30_000;
    /** 403 answers in a row, any reason; waits grow with them. */
    private int refusals;
    private int notRegisteredInARow;
    /** Rejected tokens in a row, each followed by a fresh one, before the login ends. */
    private static final int UNAUTHORIZED_LIMIT = 3;
    private int unauthorizedInARow;

    private final SpotifyAccount account;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
    private volatile NowPlaying current;
    private volatile boolean running;
    private Thread worker;

    public SpotifyNowPlayingSource(SpotifyAccount account) {
        this.account = account;
    }

    @Override
    public NowPlaying current() {
        return account.isConnected() ? current : null;
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        worker = new Thread(this::loop, "LyricsInTheSky-Spotify");
        worker.setDaemon(true);
        worker.start();
    }

    @Override
    public synchronized void stop() {
        running = false;
        if (worker != null) {
            worker.interrupt();
        }
    }

    private void loop() {
        while (running) {
            long delay = POLL_DISCONNECTED_MS;
            if (account.isConnected()) {
                try {
                    delay = poll();
                } catch (IOException e) {
                    LOGGER.debug("Spotify poll failed: {}", e.getMessage());
                    current = null;
                    delay = POLL_IDLE_MS;
                } catch (InterruptedException e) {
                    return;
                }
            } else {
                current = null;
            }
            try {
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    /** One request; returns how long to wait before the next one. */
    private long poll() throws IOException, InterruptedException {
        String token = account.accessToken();
        long sentAt = System.currentTimeMillis();
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(CURRENTLY_PLAYING)
                .timeout(Duration.ofSeconds(8))
                .header("Authorization", "Bearer " + token)
                .build(), HttpResponse.BodyHandlers.ofString());
        long receivedAt = System.currentTimeMillis();
        if (response.statusCode() != 403) {
            refusals = 0;
            notRegisteredInARow = 0;
        }
        if (response.statusCode() != 401) {
            unauthorizedInARow = 0;
        }
        switch (response.statusCode()) {
            case 200 -> {
                // The position was measured somewhere between sending and receiving; the middle is the best guess.
                current = parse(response.body(), (sentAt + receivedAt) / 2);
                return current != null && current.playing() ? POLL_PLAYING_MS : POLL_IDLE_MS;
            }
            case 204 -> {
                current = null;
                return POLL_IDLE_MS;
            }
            case 401 -> {
                // Mostly an access token that ran out early: get a fresh one. Only if that keeps failing was the
                // access really taken away (a refresh Spotify refuses ends the login on its own).
                unauthorizedInARow++;
                if (unauthorizedInARow >= UNAUTHORIZED_LIMIT) {
                    account.invalidate("Zugriff wurde entzogen – bitte neu verbinden");
                    current = null;
                    return POLL_DISCONNECTED_MS;
                }
                account.expireAccessToken();
                return 500;
            }
            case 403 -> {
                // Spotify refuses single requests now and then, even for accounts added to the app; the login
                // only ends after several refusals in a row that say the account is not added.
                refusals++;
                if (SpotifyAccount.notRegistered(response.body())) {
                    notRegisteredInARow++;
                    if (notRegisteredInARow >= NOT_REGISTERED_LIMIT) {
                        account.invalidate(SpotifyAccount.NOT_REGISTERED);
                        current = null;
                        return POLL_DISCONNECTED_MS;
                    }
                }
                LOGGER.info("Spotify refused a request (403, {} in a row), trying again", refusals);
                return Math.min(POLL_IDLE_MS * refusals, MAX_REFUSED_WAIT_MS);
            }
            case 429 -> {
                long retry = response.headers().firstValueAsLong("Retry-After").orElse(5);
                return Math.max(1, retry) * 1000L;
            }
            default -> {
                LOGGER.debug("Spotify answered {}", response.statusCode());
                return POLL_IDLE_MS;
            }
        }
    }

    /** Parses a currently-playing response; {@code null} for ads, podcasts without metadata or empty items. */
    static NowPlaying parse(String body, long measuredAtMs) {
        JsonObject json = JsonParser.parseString(body).getAsJsonObject();
        if (!json.has("item") || json.get("item").isJsonNull()) {
            return null;
        }
        JsonObject item = json.getAsJsonObject("item");
        String title = string(item, "name");
        if (title.isEmpty()) {
            return null;
        }
        String artist;
        String album;
        if (item.has("artists")) {
            StringJoiner names = new StringJoiner(", ");
            JsonArray artists = item.getAsJsonArray("artists");
            for (JsonElement entry : artists) {
                names.add(string(entry.getAsJsonObject(), "name"));
            }
            artist = names.toString();
            album = item.has("album") ? string(item.getAsJsonObject("album"), "name") : "";
        } else {
            // Podcast episode: the show stands in for artist and album.
            artist = item.has("show") ? string(item.getAsJsonObject("show"), "name") : "";
            album = artist;
        }
        long duration = item.has("duration_ms") ? item.get("duration_ms").getAsLong() : 0;
        long progress = json.has("progress_ms") && !json.get("progress_ms").isJsonNull() ? json.get("progress_ms").getAsLong() : 0;
        boolean playing = json.has("is_playing") && json.get("is_playing").getAsBoolean();
        return new NowPlaying("Spotify (Konto)", title, artist, album, playing, progress, duration, measuredAtMs);
    }

    private static String string(JsonObject json, String key) {
        return json.has(key) && !json.get(key).isJsonNull() ? json.get(key).getAsString() : "";
    }
}
