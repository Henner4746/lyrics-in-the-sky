package de.henner.lyricsinthesky;

import de.henner.lyricsinthesky.lyrics.LrclibClient;
import de.henner.lyricsinthesky.lyrics.Lyrics;
import de.henner.lyricsinthesky.nowplaying.NowPlaying;
import de.henner.lyricsinthesky.nowplaying.NowPlayingSource;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Joins the media session with looked-up lyrics and tells the renderer where the song is. */
public final class LyricsController {
    private static final Logger LOGGER = LoggerFactory.getLogger("lyricsinthesky");
    /** Failed or empty lookups are retried after this long, since LRCLIB grows over time. */
    private static final long NEGATIVE_CACHE_MS = 10 * 60 * 1000;
    private static final String DEMO_KEY = "demo";

    public enum LookupState { IDLE, NOT_ALLOWED, SEARCHING, FOUND, INSTRUMENTAL, NOT_FOUND, ERROR }

    /**
     * Everything the renderer needs for one frame.
     *
     * @param lyrics {@code null} while searching or when nothing was found
     */
    public record Timeline(
            String trackKey,
            String title,
            String artist,
            Lyrics lyrics,
            LookupState state,
            long positionMs,
            long durationMs,
            boolean playing) {}

    private record CacheEntry(Optional<Lyrics> lyrics, LookupState state, long createdAtMs) {}

    private final NowPlayingSource source;
    private final LrclibClient lrclib;
    private final SkyLyricsConfig config;
    private final ExecutorService lookups = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "LyricsInTheSky-Lookup");
        thread.setDaemon(true);
        return thread;
    });
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    private volatile NowPlaying track;
    private volatile String trackKey;
    private volatile long demoStartedAtMs;
    private volatile NowPlaying simulated;
    private volatile boolean sourcesMuted;

    public LyricsController(NowPlayingSource source, LrclibClient lrclib, SkyLyricsConfig config) {
        this.source = source;
        this.lrclib = lrclib;
        this.config = config;
    }

    public SkyLyricsConfig config() {
        return config;
    }

    /** Development only: pretend this track is playing instead of reading the media session. */
    public void simulate(NowPlaying track) {
        simulated = track;
    }

    /** Development only: ignore whatever really plays on this PC or account, e.g. while recording a video. */
    public void muteSources() {
        sourcesMuted = true;
    }

    /** Called every client tick: notices track changes and starts lookups. */
    public void tick() {
        NowPlaying now = simulated != null ? simulated : sourcesMuted ? null : source.current();
        track = now;
        if (now == null) {
            trackKey = null;
            return;
        }
        String key = key(now);
        CacheEntry cached = cache.get(key);
        if (key.equals(trackKey) && cached != null && cached.state() != LookupState.NOT_ALLOWED) {
            return;
        }
        trackKey = key;
        if (!Boolean.TRUE.equals(config.lrclibAllowed)) {
            cache.put(key, new CacheEntry(Optional.empty(), LookupState.NOT_ALLOWED, System.currentTimeMillis()));
            return;
        }
        boolean fresh = cached != null && cached.state() != LookupState.NOT_ALLOWED
                && (cached.lyrics().isPresent() || System.currentTimeMillis() - cached.createdAtMs() < NEGATIVE_CACHE_MS);
        if (fresh) {
            return;
        }
        if (now.durationMs() <= 0) {
            // Livestreams and videos without a timeline cannot be synced.
            cache.put(key, new CacheEntry(Optional.empty(), LookupState.NOT_FOUND, System.currentTimeMillis()));
            return;
        }
        cache.put(key, new CacheEntry(Optional.empty(), LookupState.SEARCHING, System.currentTimeMillis()));
        lookups.submit(() -> lookup(key, now));
    }

    private void lookup(String key, NowPlaying now) {
        try {
            Optional<Lyrics> lyrics = lrclib.find(now);
            LookupState state = lyrics.map(found -> found.isInstrumental() ? LookupState.INSTRUMENTAL : LookupState.FOUND)
                    .orElse(LookupState.NOT_FOUND);
            cache.put(key, new CacheEntry(lyrics, state, System.currentTimeMillis()));
            LOGGER.info("Lyrics for '{}' by '{}': {}", now.title(), now.artist(), state);
        } catch (Exception e) {
            cache.put(key, new CacheEntry(Optional.empty(), LookupState.ERROR, System.currentTimeMillis()));
            LOGGER.warn("Lyrics lookup for '{}' failed: {}", now.title(), e.toString());
        }
    }

    /** Plays the built-in demo text for one loop, independent of any media app. */
    public void startDemo() {
        demoStartedAtMs = System.currentTimeMillis();
    }

    public boolean isDemoRunning() {
        return demoStartedAtMs > 0 && System.currentTimeMillis() - demoStartedAtMs < DemoLyrics.DURATION_MS;
    }

    /** Where the current song is, or {@code null} when nothing should be shown. */
    public Timeline timeline(long nowMs) {
        if (!config.enabled) {
            return null;
        }
        if (isDemoRunning()) {
            return new Timeline(DEMO_KEY + demoStartedAtMs, "Lyrics in the Sky", "Demo", DemoLyrics.LYRICS,
                    LookupState.FOUND, nowMs - demoStartedAtMs, DemoLyrics.DURATION_MS, true);
        }
        NowPlaying now = track;
        if (now == null) {
            return null;
        }
        String key = key(now);
        CacheEntry entry = cache.get(key);
        LookupState state = entry == null ? LookupState.IDLE : entry.state();
        Lyrics lyrics = entry == null ? null : entry.lyrics().filter(found -> !found.isInstrumental()).orElse(null);
        return new Timeline(key, now.title(), now.artist(), lyrics, state,
                now.estimatePositionMs(nowMs) + config.offsetMs, now.durationMs(), now.playing());
    }

    /** Forgets cached "not found"/"not allowed" results, e.g. after the player allowed LRCLIB. */
    public void retryLookups() {
        cache.values().removeIf(entry -> entry.lyrics().isEmpty() && entry.state() != LookupState.SEARCHING);
        trackKey = null;
    }

    public String status() {
        NowPlaying now = track;
        if (now == null) {
            return "Keine Mediensitzung gefunden. Läuft Spotify, Amazon Music oder ein Browser-Player?";
        }
        CacheEntry entry = cache.get(key(now));
        String state = entry == null ? LookupState.IDLE.name() : entry.state().name();
        return String.format("%s – %s [%s] %s, Position %d s, Lyrics: %s, Offset %+d ms",
                now.artist(), now.title(), now.app(), now.playing() ? "spielt" : "pausiert",
                now.estimatePositionMs(System.currentTimeMillis()) / 1000, state, config.offsetMs);
    }

    private static String key(NowPlaying track) {
        return track.artist() + "\u0000" + track.title() + "\u0000" + track.album();
    }
}
