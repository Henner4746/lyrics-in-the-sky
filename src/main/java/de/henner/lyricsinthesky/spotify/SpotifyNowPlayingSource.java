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
                account.invalidate("Zugriff wurde entzogen – bitte neu verbinden");
                current = null;
                return POLL_DISCONNECTED_MS;
            }
            case 403 -> {
                // The app is in development mode and this account is not (or no longer) added to it.
                account.invalidate(SpotifyAccount.NOT_REGISTERED);
                current = null;
                return POLL_DISCONNECTED_MS;
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
