package de.henner.lyricsinthesky;

import de.henner.lyricsinthesky.hoergenau.Hoergenau;
import de.henner.lyricsinthesky.hoergenau.SharedLearned;
import de.henner.lyricsinthesky.lyrics.Explicit;
import de.henner.lyricsinthesky.lyrics.LrclibClient;
import de.henner.lyricsinthesky.lyrics.Lyrics;
import de.henner.lyricsinthesky.lyrics.LyricsCache;
import de.henner.lyricsinthesky.lyrics.LyricsPlusClient;
import de.henner.lyricsinthesky.lyrics.SongId;
import de.henner.lyricsinthesky.lyrics.Spelling;
import de.henner.lyricsinthesky.lyrics.WordTimeFinder;
import de.henner.lyricsinthesky.nowplaying.NowPlaying;
import de.henner.lyricsinthesky.nowplaying.NowPlayingSource;
import de.henner.lyricsinthesky.nowplaying.PlaybackClock;
import de.henner.lyricsinthesky.timing.SongTimings;
import de.henner.lyricsinthesky.timing.TimingTracker;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
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

    /**
     * The song playing now, whose timing can be corrected on its own.
     *
     * @param shareable timed lyrics, whose corrections are shared; text spread over the song is corrected only here
     */
    public record Song(String id, String title, boolean shareable) {}

    /** @param failures failed lookups in a row for this song, for spacing out the retries */
    private record CacheEntry(Optional<Lyrics> lyrics, LookupState state, long createdAtMs, int failures) {
        CacheEntry(Optional<Lyrics> lyrics, LookupState state, long createdAtMs) {
            this(lyrics, state, createdAtMs, 0);
        }
    }

    private record SongRef(Lyrics lyrics, String id) {}

    private final NowPlayingSource source;
    private final LrclibClient lrclib;
    /** Optional word times, only asked when the player switched services on ("Wortgenau"). */
    private final WordTimeFinder wordTimeFinder;
    /** Also stands in line by line when LRCLIB has no lyrics and no service has word times. */
    private final LyricsPlusClient lyricsPlus;
    /** Songs found before, so they still have lyrics when LRCLIB is busy or offline. */
    private final LyricsCache saved;
    private final SkyLyricsConfig config;
    /** Timing corrections per song: the player's own and the ones many players agreed on. */
    private final SongTimings timings;
    /** Shares the player's corrections and asks for the common ones; {@code null} keeps everything on this PC. */
    private final TimingTracker tracker;
    private final ExecutorService lookups = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "LyricsInTheSky-Lookup");
        thread.setDaemon(true);
        return thread;
    });
    /** Word times come in afterwards on a thread of their own, so a slow answer never holds up the next song. */
    private final ExecutorService wordTimes = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "LyricsInTheSky-WordTimes");
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
    /** The song ID of the lyrics shown last; worked out once per lyrics, not every frame. */
    private volatile SongRef songRef;
    /** Learns word times by listening for songs nobody timed; {@code null} where it is not set up. */
    private volatile Hoergenau hoergenau;

    public LyricsController(NowPlayingSource source, LrclibClient lrclib, WordTimeFinder wordTimeFinder,
            LyricsPlusClient lyricsPlus, LyricsCache saved, SkyLyricsConfig config, SongTimings timings,
            TimingTracker tracker) {
        this.source = source;
        this.lrclib = lrclib;
        this.wordTimeFinder = wordTimeFinder;
        this.lyricsPlus = lyricsPlus;
        this.saved = saved;
        this.config = config;
        this.timings = timings;
        this.tracker = tracker;
    }

    public SkyLyricsConfig config() {
        return config;
    }

    public void listenWith(Hoergenau hoergenau) {
        this.hoergenau = hoergenau;
    }

    public Hoergenau hoergenau() {
        return hoergenau;
    }

    /** A song's kept lyrics changed (Hoergenau learned it): the next play reads them again. */
    public void forget(String key) {
        cache.remove(key);
    }

    public SongTimings timings() {
        return timings;
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
        Hoergenau listener = hoergenau;
        if (now == null) {
            trackKey = null;
            if (listener != null) {
                listener.observe(null, null, null, List.of(), null, null);
            }
            return;
        }
        String key = key(now);
        CacheEntry cached = cache.get(key);
        trackKey = key;
        if (!Boolean.TRUE.equals(config.lrclibAllowed)) {
            // Lyrics found before stay where they are, so switching the lookup back on shows them again at once.
            return;
        }
        if (listener != null) {
            listener.observe(now, key, shownFor(cached), cached == null ? List.of() : cached.lyrics()
                    .filter(Lyrics::isEstimated).map(found -> found.lines().stream().map(Lyrics.Line::text).toList())
                    .orElse(List.of()), simulated != null ? null : source.otherPlaying(), cached == null ? null
                    : cached.lyrics().filter(found -> !found.isEstimated() && !found.isInstrumental()
                            && !Hoergenau.learned(found)).orElse(null));
        }
        if (cached != null && tracker != null) {
            // Once per song: did other players correct its timing?
            cached.lyrics().filter(LyricsController::shareable).ifPresent(found -> tracker.ask(songId(now, found)));
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

    /** What Hoergenau needs to know about a song's lyrics; {@code null} while still looking or after a failure. */
    private static Hoergenau.Shown shownFor(CacheEntry entry) {
        if (entry == null || entry.state() == LookupState.SEARCHING || entry.state() == LookupState.ERROR
                || entry.state() == LookupState.IDLE) {
            return null;
        }
        Optional<Lyrics> lyrics = entry.lyrics().filter(found -> !found.isInstrumental());
        if (entry.state() == LookupState.INSTRUMENTAL) {
            return Hoergenau.Shown.TIMED;  // nothing to sing along to
        }
        if (lyrics.isEmpty()) {
            return Hoergenau.Shown.NOTHING;
        }
        return lyrics.get().isEstimated() ? Hoergenau.Shown.TEXT_ONLY : Hoergenau.Shown.TIMED;
    }

    /**
     * Whether a song's lookup is due again, also while it keeps playing: after a failure soon and then less
     * often, when LRCLIB had nothing after a while, never when lyrics were found or a lookup is running.
     */
    static boolean lookUpAgain(LookupState state, boolean found, long ageMs, int failures) {
        return switch (state) {
            case SEARCHING -> false;
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
        Optional<Lyrics> kept = saved.get(key);
        // Lyrics learned by listening are kept only until LRCLIB has lines for the song; so LRCLIB is asked again.
        boolean learned = kept.isPresent() && Hoergenau.learned(kept.get());
        if (kept.isPresent() && !learned) {
            cache.put(key, new CacheEntry(kept, stateOf(kept), System.currentTimeMillis()));
            LOGGER.info("Lyrics for '{}' by '{}': {} (saved)", now.title(), now.artist(), stateOf(kept));
            refine(key, now, kept.get());
            return;
        }
        try {
            LrclibClient.Lookup lookup = lrclib.lookup(now);
            Optional<Lyrics> lyrics = lookup.synced();
            lyrics.ifPresent(found -> saved.put(key, found));
            if (lyrics.isEmpty() && learned) {
                cache.put(key, new CacheEntry(kept, LookupState.FOUND, System.currentTimeMillis()));
                LOGGER.info("Lyrics for '{}' by '{}': FOUND (learned by Hoergenau)", now.title(), now.artist());
                return;
            }
            if (lyrics.isEmpty()) {
                Optional<Lyrics> standIn = standIn(key, now);
                if (standIn.isPresent()) {
                    cache.put(key, new CacheEntry(standIn, LookupState.FOUND, System.currentTimeMillis()));
                    LOGGER.info("Lyrics for '{}' by '{}': FOUND (word time service, LRCLIB has none)", now.title(), now.artist());
                    return;
                }
                // Another player may have learned the song by listening: it runs word by word here too, without
                // listening again. Kept like lyrics learned here, so LRCLIB's lines still replace it once they exist.
                Optional<Lyrics> learnedElsewhere = sharedLearned(key);
                if (learnedElsewhere.isPresent()) {
                    cache.put(key, new CacheEntry(learnedElsewhere, LookupState.FOUND, System.currentTimeMillis()));
                    LOGGER.info("Lyrics for '{}' by '{}': FOUND (learned by another player)", now.title(), now.artist());
                    return;
                }
                // Last: the text without times, spread over the song. Not kept on disk, so timed lyrics that turn up
                // later on LRCLIB are found next time.
                Optional<Lyrics> rough = lookup.plain().map(text -> Lyrics.estimated(text, now.durationMs()))
                        .filter(found -> !found.isEmpty());
                if (rough.isPresent()) {
                    cache.put(key, new CacheEntry(rough, LookupState.FOUND, System.currentTimeMillis()));
                    LOGGER.info("Lyrics for '{}' by '{}': FOUND (text without times, spread over the song)", now.title(),
                            now.artist());
                    return;
                }
            }
            cache.put(key, new CacheEntry(lyrics, stateOf(lyrics), System.currentTimeMillis()));
            LOGGER.info("Lyrics for '{}' by '{}': {}", now.title(), now.artist(), stateOf(lyrics));
            lyrics.ifPresent(found -> refine(key, now, found));
        } catch (Exception e) {
            int failures = failuresBefore + 1;
            if (learned) {
                cache.put(key, new CacheEntry(kept, LookupState.FOUND, System.currentTimeMillis()));
                return;
            }
            // LRCLIB busy or unreachable: with word time services on, theirs show right away instead of after the retry.
            Optional<Lyrics> standIn = standIn(key, now);
            if (standIn.isPresent()) {
                cache.put(key, new CacheEntry(standIn, LookupState.FOUND, System.currentTimeMillis()));
                LOGGER.warn("Lyrics lookup for '{}' failed ({}), a word time service stands in", now.title(), e.toString());
                return;
            }
            cache.put(key, new CacheEntry(Optional.empty(), LookupState.ERROR, System.currentTimeMillis(), failures));
            LOGGER.warn("Lyrics lookup for '{}' failed ({}. time, next try in {} s): {}", now.title(), failures,
                    retryDelayMs(failures) / 1000, e.toString());
        }
    }

    /** Songs other players learned by listening, from the tracker; null until set. */
    private volatile SharedLearned shared;

    public void shareLearnedWith(SharedLearned shared) {
        this.shared = shared;
    }

    private Optional<Lyrics> sharedLearned(String key) {
        SharedLearned from = shared;
        if (from == null || !config.shareLearned) {
            return Optional.empty();
        }
        Optional<String> lrc = from.fetch(key);
        lrc.ifPresent(found -> saved.write(key, found));
        return lrc.map(Lyrics::parseLrc);
    }

    /**
     * With word time services switched on, they stand in when LRCLIB has no lyrics or cannot be reached: the first one
     * that has the song with word times, else LyricsPlus line by line if it is on. They are kept apart from LRCLIB's,
     * so switching the services off brings back the old way, and LRCLIB is still asked first next time.
     */
    private Optional<Lyrics> standIn(String key, NowPlaying now) {
        Set<String> enabled = enabledSources();
        if (enabled.isEmpty() || now.durationMs() <= 0) {
            return Optional.empty();
        }
        String savedKey = key + "\u0000wordtimes-standin";
        Optional<String> known = saved.read(savedKey);
        if (known.isPresent()) {
            if (WordTimeFinder.recentlyNone(known.get(), LocalDate.now(), enabled)) {
                return Optional.empty();
            }
            Optional<String> from = WordTimeFinder.serviceOf(known.get());
            Lyrics lyrics = Lyrics.parseLrc(known.get());
            // LyricsPlus line times kept without a mark came from LyricsPlus.
            if (!lyrics.isEmpty() && enabled.contains(from.orElse("lyricsplus"))) {
                return Optional.of(lyrics);
            }
        }
        try {
            WordTimeFinder.Outcome outcome = wordTimeFinder.find(now, null, enabled);
            if (outcome.taken().isPresent()) {
                WordTimeFinder.Taken taken = outcome.taken().get();
                saved.write(savedKey, taken.lyrics().lrc());
                LOGGER.info("Word times from {} stand in for '{}' by '{}'", taken.source(), now.title(), now.artist());
                return Optional.of(taken.lyrics());
            }
            Optional<String> lines = enabled.contains("lyricsplus") ? lyricsPlus.findLrc(now) : Optional.empty();
            Optional<Lyrics> lyrics = lines.map(Lyrics::parseLrc).filter(found -> !found.isEmpty());
            if (lyrics.isPresent()) {
                saved.write(savedKey, lines.get());
            } else if (outcome.complete()) {
                saved.write(savedKey, WordTimeFinder.noneMarker(LocalDate.now(), enabled));
            }
            LOGGER.info("Stand-in for '{}' by '{}': {}", now.title(), now.artist(),
                    lyrics.isPresent() ? "LyricsPlus line times" : "none");
            return lyrics;
        } catch (Exception e) {
            LOGGER.info("Stand-in for '{}' failed: {}", now.title(), e.toString());
            return Optional.empty();
        }
    }

    /**
     * With word time services switched on: looks for word times of the same recording in the background and swaps them
     * in while the song plays. The lyrics shown meanwhile are LRCLIB's, with estimated word times.
     */
    private void refine(String key, NowPlaying now, Lyrics found) {
        if (!config.wordTimes() || found.isInstrumental() || found.hasWordTiming()) {
            return;
        }
        wordTimes.submit(() -> {
            try {
                wordTimed(key, now, found).ifPresent(better -> cache.computeIfPresent(key, (k, entry) ->
                        entry.lyrics().orElse(null) == found
                                ? new CacheEntry(Optional.of(better), LookupState.FOUND, entry.createdAtMs())
                                : entry));
            } catch (Exception e) {
                // Not kept as "nothing fitting": a busy or unreachable service is asked again next time.
                LOGGER.info("Word times for '{}' failed: {}", now.title(), e.toString());
            }
        });
    }

    private Optional<Lyrics> wordTimed(String key, NowPlaying now, Lyrics found) throws InterruptedException {
        Set<String> enabled = enabledSources();
        String savedKey = key + "\u0000wordtimes";
        // Word times taken by 1.3.0-dev.1 to dev.3 were kept under the LyricsPlus name.
        Optional<String> known = saved.read(savedKey).or(() -> saved.read(key + "\u0000lyricsplus")
                .filter(kept -> WordTimeFinder.serviceOf(kept).isPresent()));
        if (known.isPresent() && WordTimeFinder.recentlyNone(known.get(), LocalDate.now(), enabled)) {
            return Optional.empty();
        }
        Optional<String> from = known.flatMap(WordTimeFinder::serviceOf);
        if (from.isPresent() && enabled.contains(from.get())) {
            Lyrics lyrics = Lyrics.parseLrc(known.get());
            if (lyrics.hasWordTiming()) {
                // Kept before mangled spelling was mended (up to 1.3.0-dev.9): mended on the way in.
                return Optional.of(Spelling.mend(lyrics, found));
            }
        }
        WordTimeFinder.Outcome outcome = wordTimeFinder.find(now, found, enabled);
        if (outcome.taken().isEmpty()) {
            if (outcome.complete()) {
                saved.write(savedKey, WordTimeFinder.noneMarker(LocalDate.now(), enabled));
            }
            LOGGER.info("Word times for '{}' by '{}': none that fit, estimating instead", now.title(), now.artist());
            return Optional.empty();
        }
        WordTimeFinder.Taken taken = outcome.taken().get();
        // The service's words are shown; where it mangled them (QQ Music loses umlauts), LRCLIB's spelling is put back.
        Lyrics lyrics = Spelling.mend(taken.lyrics(), found);
        saved.write(savedKey, lyrics.lrc());
        LOGGER.info("Word times for '{}' by '{}' from {} ({}{})", now.title(), now.artist(), taken.source(), taken.how(),
                lyrics == taken.lyrics() ? "" : ", spelling mended from LRCLIB");
        return Optional.of(lyrics);
    }

    private Set<String> enabledSources() {
        return new LinkedHashSet<>(config.wordSources);
    }

    /** After word time services were switched on or off: found songs are looked up again, from the saved copies. */
    public void refreshWordTimes() {
        cache.values().removeIf(entry -> entry.state() == LookupState.FOUND);
        trackKey = null;
    }

    private static LookupState stateOf(Optional<Lyrics> lyrics) {
        return lyrics.map(found -> found.isInstrumental() ? LookupState.INSTRUMENTAL : LookupState.FOUND)
                .orElse(LookupState.NOT_FOUND);
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
        CacheEntry entry = Boolean.TRUE.equals(config.lrclibAllowed) ? cache.get(key) : null;
        LookupState state = !Boolean.TRUE.equals(config.lrclibAllowed) ? LookupState.NOT_ALLOWED
                : entry == null ? LookupState.IDLE : entry.state();
        Lyrics lyrics = entry == null ? null : entry.lyrics().filter(found -> !found.isInstrumental()).orElse(null);
        long position = clock.at(key, nowMs, now.estimatePositionMs(nowMs), now.playing());
        int songOffset = lyrics == null ? 0 : timings.offsetFor(songId(now, lyrics));
        String title = now.title();
        String artist = now.artist();
        Lyrics shown = lyrics;
        if (config.streamerMode) {
            Explicit filter = lyrics == null ? Explicit.ANY : censored(lyrics).filter();
            title = filter.hide(title);
            artist = filter.hide(artist);
            shown = lyrics == null ? null : censored(lyrics).lyrics();
        }
        if (config.uppercase) {
            title = title.toUpperCase(Locale.ROOT);
            artist = artist.toUpperCase(Locale.ROOT);
            shown = shown == null ? null : upper(shown);
        }
        return new Timeline(key, title, artist, shown, state, position + config.offsetMs + songOffset,
                now.durationMs(), now.playing());
    }

    private record Upper(Lyrics source, Lyrics lyrics) {}

    /** The last lyrics shown in capitals; made once per song, not per frame. */
    private volatile Upper upper;

    private Lyrics upper(Lyrics lyrics) {
        Upper last = upper;
        if (last == null || last.source() != lyrics) {
            // "ß" becomes "SS": a word gets longer, but stays one word, so its time still fits.
            last = new Upper(lyrics, lyrics.mapText(text -> text.toUpperCase(Locale.ROOT)));
            upper = last;
        }
        return last.lyrics();
    }

    /** The lyrics found for the song playing now, as they are kept (not hidden for streamer mode); else {@code null}. */
    public Lyrics currentLyrics() {
        NowPlaying now = track;
        CacheEntry entry = now == null || !Boolean.TRUE.equals(config.lrclibAllowed) ? null : cache.get(key(now));
        return entry == null ? null : entry.lyrics().orElse(null);
    }

    private record Censored(Lyrics source, Explicit filter, Lyrics lyrics) {}

    /** The last lyrics shown in streamer mode, with explicit words hidden; made once per song, not per frame. */
    private volatile Censored censored;

    private Censored censored(Lyrics lyrics) {
        Censored last = censored;
        if (last == null || last.source() != lyrics) {
            Explicit filter = Explicit.forLyrics(lyrics);
            last = new Censored(lyrics, filter, lyrics.mapText(filter::hide));
            censored = last;
        }
        return last;
    }

    /** The song playing now if it has lyrics, else {@code null}; never the demo. */
    public Song currentSong() {
        NowPlaying now = track;
        if (now == null || isDemoRunning() || !Boolean.TRUE.equals(config.lrclibAllowed)) {
            return null;
        }
        CacheEntry entry = cache.get(key(now));
        Lyrics lyrics = entry == null ? null : entry.lyrics().filter(found -> !found.isInstrumental()).orElse(null);
        return lyrics == null ? null : new Song(songId(now, lyrics), shown(now.title()), shareable(lyrics));
    }

    /** A title or artist as it may be shown: in streamer mode with explicit words hidden. */
    public String shown(String name) {
        return config.streamerMode ? Explicit.ANY.hide(name) : name;
    }

    /**
     * The player's own timing for a song; {@code null} forgets it, so the one many players agreed on applies again.
     * Corrections of timed lyrics are shared, unless the player switched that off.
     */
    /**
     * A correction Hoergenau measured for a song; it never replaces one the player set. Shared like a correction set
     * by hand, so other players get it once enough agree.
     */
    public void applyHeardOffset(NowPlaying track, Lyrics lyrics, int offsetMs) {
        String id = SongId.of(track.artist(), track.title(), lyrics);
        if (timings.own(id).isPresent()) {
            LOGGER.info("Hoergenau's correction of {} ms for '{}' not taken: the player set an own one", offsetMs,
                    track.title());
            return;
        }
        setSongOffset(new Song(id, track.title(), shareable(lyrics)), offsetMs);
    }

    public void setSongOffset(Song song, Integer offsetMs) {
        timings.setOwn(song.id(), offsetMs);
        if (tracker != null && song.shareable()) {
            OptionalInt own = timings.own(song.id());
            tracker.tell(song.id(), own.isPresent() ? own.getAsInt() : null);
        }
    }

    /** Only timed lyrics are shared; text spread over the song by estimate is off everywhere in its own way. */
    private static boolean shareable(Lyrics lyrics) {
        return !lyrics.isEstimated() && !lyrics.isInstrumental();
    }

    private String songId(NowPlaying now, Lyrics lyrics) {
        SongRef ref = songRef;
        if (ref == null || ref.lyrics() != lyrics) {
            ref = new SongRef(lyrics, SongId.of(now.artist(), now.title(), lyrics));
            songRef = ref;
        }
        return ref.id();
    }

    /** Forgets cached "not found" and failed lookups, e.g. after the player allowed LRCLIB. */
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
        String state = !Boolean.TRUE.equals(config.lrclibAllowed) ? LookupState.NOT_ALLOWED.name()
                : entry == null ? LookupState.IDLE.name() : entry.state().name();
        if (entry != null && entry.lyrics().map(Hoergenau::learned).orElse(false)) {
            state += " (wortgenau, durch Mithören gelernt)";
        } else if (entry != null && entry.lyrics().map(Lyrics::hasWordTiming).orElse(false)) {
            state += entry.lyrics().flatMap(found -> WordTimeFinder.sourceOf(found.lrc()))
                    .map(from -> " (wortgenau, " + from + ")").orElse(" (wortgenau)");
        } else if (entry != null && entry.lyrics().map(Lyrics::isEstimated).orElse(false)) {
            state += " (ohne Zeiten, ungefähr)";
        }
        Hoergenau listener = hoergenau;
        return String.format("%s – %s [%s] %s, Position %d s, Lyrics: %s, Offset %+d ms%s%s",
                shown(now.artist()), shown(now.title()), now.app(), now.playing() ? "spielt" : "pausiert",
                now.estimatePositionMs(System.currentTimeMillis()) / 1000, state, config.offsetMs, songTimingText(),
                listener != null && config.hoergenau ? ", " + listener.statusText() : "");
    }

    /** ", dieser Song +300 ms (eigenes)" and the like, or nothing when the song has no correction. */
    private String songTimingText() {
        Song song = currentSong();
        if (song == null) {
            return "";
        }
        SongTimings.Shared common = timings.shared(song.id());
        String players = common != null && common.players() > 0 ? ", " + common.players() + " Spieler haben korrigiert" : "";
        if (timings.own(song.id()).isPresent()) {
            return String.format(", dieser Song %+d ms (eigenes%s)", timings.own(song.id()).getAsInt(), players);
        }
        if (common != null && common.offsetMs() != null) {
            return String.format(", dieser Song %+d ms (gemeinsam von %d Spielern)", common.offsetMs(), common.players());
        }
        return players.isEmpty() ? "" : players;
    }

    private static String key(NowPlaying track) {
        return track.artist() + "\u0000" + track.title() + "\u0000" + track.album();
    }
}
