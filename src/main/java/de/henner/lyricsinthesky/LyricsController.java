package de.henner.lyricsinthesky;

import de.henner.lyricsinthesky.lyrics.LrclibClient;
import de.henner.lyricsinthesky.lyrics.Lyrics;
import de.henner.lyricsinthesky.nowplaying.NowPlaying;
import de.henner.lyricsinthesky.nowplaying.NowPlayingSource;
import de.henner.lyricsinthesky.nowplaying.PlaybackClock;
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
    /** Songs LRCLIB has no lyrics for are asked again after this long, since LRCLIB grows over time. */
    private static final long NEGATIVE_CACHE_MS = 10 * 60 * 1000;
    /** A failed lookup (LRCLIB busy, no connection) is tried again after 5 s, then 15 s, 45 s … up to 2 minutes. */
    private static final long FIRST_RETRY_MS = 5_000;
    private static final long MAX_RETRY_MS = 120_000;
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

    /** @param failures failed lookups in a row for this song, for spacing out the retries */
    private record CacheEntry(Optional<Lyrics> lyrics, LookupState state, long createdAtMs, int failures) {
        CacheEntry(Optional<Lyrics> lyrics, LookupState state, long createdAtMs) {
            this(lyrics, state, createdAtMs, 0);
        }
    }

    private final NowPlayingSource source;
    private final LrclibClient lrclib;
    private final SkyLyricsConfig config;
    private final ExecutorService lookups = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "LyricsInTheSky-Lookup");
        thread.setDaemon(true);
        return thread;
    });
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();
    /** Smooths the reported positions into one steady clock, so words never flicker on report jitter. */
    private final PlaybackClock clock = new PlaybackClock();

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
        trackKey = key;
        if (!Boolean.TRUE.equals(config.lrclibAllowed)) {
            if (cached == null || cached.state() != LookupState.NOT_ALLOWED) {
                cache.put(key, new CacheEntry(Optional.empty(), LookupState.NOT_ALLOWED, System.currentTimeMillis()));
            }
            return;
        }
        long nowMs = System.currentTimeMillis();
        if (cached != null && !lookUpAgain(cached, nowMs)) {
            return;
        }
        if (now.durationMs() <= 0) {
            // Livestreams and videos without a timeline cannot be synced.
            cache.put(key, new CacheEntry(Optional.empty(), LookupState.NOT_FOUND, nowMs));
            return;
        }
        int failures = cached == null ? 0 : cached.failures();
        cache.put(key, new CacheEntry(Optional.empty(), LookupState.SEARCHING, nowMs, failures));
        lookups.submit(() -> lookup(key, now, failures));
    }

    /**
     * Whether a song's lookup is due again, also while it keeps playing: after a failure soon and then less
     * often, when LRCLIB had nothing after a while, never when lyrics were found or a lookup is running.
     */
    static boolean lookUpAgain(LookupState state, boolean found, long ageMs, int failures) {
        return switch (state) {
            case SEARCHING -> false;
            case NOT_ALLOWED -> true;
            case ERROR -> ageMs >= retryDelayMs(failures);
            default -> !found && ageMs >= NEGATIVE_CACHE_MS;
        };
    }

    private static boolean lookUpAgain(CacheEntry entry, long nowMs) {
        return lookUpAgain(entry.state(), entry.lyrics().isPresent(), nowMs - entry.createdAtMs(), entry.failures());
    }

    /** 5 s after the first failure, then three times as long each time, at most 2 minutes. */
    static long retryDelayMs(int failures) {
        long delay = FIRST_RETRY_MS;
        for (int i = 1; i < failures && delay < MAX_RETRY_MS; i++) {
            delay *= 3;
        }
        return Math.min(delay, MAX_RETRY_MS);
    }

    private void lookup(String key, NowPlaying now, int failuresBefore) {
        try {
            Optional<Lyrics> lyrics = lrclib.find(now);
            LookupState state = lyrics.map(found -> found.isInstrumental() ? LookupState.INSTRUMENTAL : LookupState.FOUND)
                    .orElse(LookupState.NOT_FOUND);
            cache.put(key, new CacheEntry(lyrics, state, System.currentTimeMillis()));
            LOGGER.info("Lyrics for '{}' by '{}': {}", now.title(), now.artist(), state);
        } catch (Exception e) {
            int failures = failuresBefore + 1;
            cache.put(key, new CacheEntry(Optional.empty(), LookupState.ERROR, System.currentTimeMillis(), failures));
            LOGGER.warn("Lyrics lookup for '{}' failed ({}. time, next try in {} s): {}", now.title(), failures,
                    retryDelayMs(failures) / 1000, e.toString());
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
        long position = clock.at(key, nowMs, now.estimatePositionMs(nowMs), now.playing());
        return new Timeline(key, now.title(), now.artist(), lyrics, state, position + config.offsetMs, now.durationMs(),
                now.playing());
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
