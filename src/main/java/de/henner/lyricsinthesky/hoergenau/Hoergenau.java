package de.henner.lyricsinthesky.hoergenau;

import de.henner.lyricsinthesky.SkyLyricsConfig;
import de.henner.lyricsinthesky.hoergenau.WhisperEngine.Heard;
import de.henner.lyricsinthesky.lyrics.Explicit;
import de.henner.lyricsinthesky.lyrics.Lyrics;
import de.henner.lyricsinthesky.lyrics.LyricsCache;
import de.henner.lyricsinthesky.nowplaying.NowPlaying;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * "Hoergenau": word times for songs no service has timed, by listening. Off unless the player switches it on; on
 * Windows and Linux. For such a song it records what the music player plays (in memory) wherever the song plays and was not
 * heard yet, and whisper.cpp hears the words of each part: kept with their times in the song, so a song heard only
 * partly is completed the next time it plays. Once nearly all of it was heard, LRCLIB's text without times is placed
 * on the heard words, or, when nobody has the song's text, the heard words become the lyrics. The result is kept like
 * found lyrics, so from then on the song runs word by word. Lyrics with times from LRCLIB or a word time service are
 * never touched.
 */
public final class Hoergenau {
    private static final Logger LOGGER = LoggerFactory.getLogger("lyricsinthesky");
    /** A part of a song shorter than this is not kept: too little to hear words in. */
    static final long MIN_PART_MS = 20_000;
    /** Once this share of the song was heard, in one part or several, it is learned. */
    static final double MIN_HEARD = 0.8;
    /**
     * A song nobody has the text of is heard only with the length of a song: a livestream has none, a podcast or an
     * audio book chapter runs longer, and what is said in them is nobody's lyrics.
     */
    static final long MIN_SONG_MS = 30_000;
    static final long MAX_SONG_MS = 20 * 60_000;
    /** A position this far off the line the earlier ones gave means the player skipped. */
    private static final long SKIP_MS = 2_500;
    /** A player stopping this close to the end of the song has played it to the end (the last song of a queue). */
    private static final long END_MS = 8_000;
    /** How much of a song with word times is heard to check its sync; enough for a few lines with any intro. */
    static final long CHECK_MS = 50_000;
    /** A check that could not tell (no clear singing heard) is tried this often in all. */
    private static final int CHECK_TRIES = 2;
    private static final String CHECKED = "\u0000synccheck";
    /** What was heard of a song not learned yet, beside its lyrics (see {@link HeardParts}). */
    private static final String PARTS = "\u0000heard";

    /** What a learnable song is shown with now: nothing, or text spread over it without times. */
    public enum Shown { NOTHING, TEXT_ONLY, TIMED }

    private final WhisperEngine engine;
    private final Recorder recorder;
    /** Whether this PC can listen: Windows (loopback recording) or Linux (PulseAudio), see {@link WhisperEngine#supported}. */
    private final java.util.function.BooleanSupplier platform;
    private final LyricsCache saved;
    private final SkyLyricsConfig config;
    /** Tells the player what Hoergenau does (called from any thread; the receiver hops to the client thread). */
    private final Notifier notifier;
    /** Tells the lyrics lookup that a song's kept lyrics changed. */
    private final Consumer<String> learned;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "LyricsInTheSky-Hoergenau");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        return thread;
    });

    private volatile boolean installing;
    private volatile int percent = -1;
    private volatile String problem;
    private volatile String working;
    private String considered;
    private Take take;
    /** What was heard of songs not learned yet, read once from the store. */
    private final Map<String, HeardParts> parts = new java.util.concurrent.ConcurrentHashMap<>();

    private static final class Take {
        final String key;
        final NowPlaying track;
        final List<String> lines;
        /** For a sync check: the lyrics with word times whose sync is checked; {@code null} when a song is learned. */
        Lyrics check;
        final List<Long> anchors = new ArrayList<>();
        long lastPositionMs;
        String broken;
        /** Samples recorded when the part ended early (paused, skipped, another player); -1 while it goes on. */
        int endSamples = -1;

        Take(String key, NowPlaying track, List<String> lines, Lyrics check) {
            this.key = key;
            this.track = track;
            this.lines = lines;
            this.check = check;
        }
    }

    /** Moves a song's lyrics to where they are really sung; set by the lyrics lookup. */
    public interface SyncFix {
        /**
         * A sync check that could tell, also when the lyrics fit.
         *
         * @param offsetMs positive to show the words earlier, as the song timing slider; 0 when they fit
         * @param pairs    how many words heard were paired with the lyrics' words
         * @param spreadMs how far the pairs lay from that offset (median)
         */
        void apply(NowPlaying track, Lyrics lyrics, int offsetMs, int pairs, int spreadMs);
    }

    /** Whether what plays is a song a music catalog knows; empty while that is being asked. */
    public interface SongCheck {
        Optional<Boolean> isSong(String key, NowPlaying track);
    }

    private volatile SyncFix syncFix;
    /** The app on this PC playing the song, as the media session names it; only its sound is recorded when it can be. */
    private volatile Supplier<String> player = () -> null;

    public void recordFrom(Supplier<String> player) {
        this.player = player;
    }

    /** Takes every song with a song's length as one; the game sets a music catalog. */
    private volatile SongCheck songs = (key, track) -> Optional.of(true);
    /** Songs found to be none, told once. */
    private final java.util.Set<String> notSongs = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public void checkSongsWith(SongCheck songs) {
        this.songs = songs;
    }

    public void fixSyncWith(SyncFix fix) {
        this.syncFix = fix;
    }

    /** How Hoergenau tells the player what it does; called from any thread. */
    public interface Notifier {
        /** A short note in the corner, replacing the one before: what Hoergenau is doing right now. */
        void toast(String title, String detail);

        /** A line in chat, for what is worth reading later. */
        void chat(String message);
    }

    public Hoergenau(Path dir, SkyLyricsConfig config, LyricsCache saved, Notifier notifier, Consumer<String> learned) {
        this(config, saved, notifier, learned, new WhisperEngine(dir, () -> config.hoergenauModel, () -> config.hoergenauPower),
                WhisperEngine.windows() ? new LoopbackRecorder(dir) : new PulseRecorder(), WhisperEngine::supported);
    }

    /** With another engine, recorder and platform check; for tests, which play a recording back. */
    Hoergenau(SkyLyricsConfig config, LyricsCache saved, Notifier notifier, Consumer<String> learned, WhisperEngine engine,
            Recorder recorder, java.util.function.BooleanSupplier platform) {
        this.engine = engine;
        this.recorder = recorder;
        this.platform = platform;
        this.saved = saved;
        this.config = config;
        this.notifier = notifier;
        this.learned = learned;
    }

    /** Whether this song is being recorded right now; the bar shows it. */
    public boolean listeningTo(String key) {
        Take now = take;
        return now != null && now.broken == null && now.key.equals(key);
    }

    /** Whether a song is being learned in the background now; the bar shows it. */
    public boolean learning() {
        return working != null;
    }

    /** Whether kept lyrics were made by listening; LRCLIB's lines take their place once it has some. */
    public static boolean learned(Lyrics lyrics) {
        return lyrics.lrc().startsWith(HeardLyrics.MARK);
    }

    /**
     * Called every client tick with the song playing (or {@code null}) and what is shown for it.
     *
     * @param key   the song's key in the lyrics store
     * @param shown whether it has lyrics with times, only text, or nothing; {@code null} while still looking
     * @param text  the lines of the text without times when {@code shown} is {@link Shown#TEXT_ONLY}
     * @param other another player playing at the same time, or {@code null}: its sound would end up in the recording
     * @param timed the lyrics with times the song is shown with, for checking their sync; {@code null} for none
     */
    public synchronized void observe(NowPlaying now, String key, Shown shown, List<String> text, String other,
            Lyrics timed) {
        if (!config.hoergenau || !platform.getAsBoolean()) {
            drop("switched off");
            return;
        }
        if (!engine.installed()) {
            install();
            return;
        }
        if (take != null && (now == null || !take.key.equals(key))) {
            finish();
        }
        if (now == null || shown == null) {
            return;
        }
        if (take == null) {
            if (shown == Shown.TIMED) {
                // A check waits while the song is paused, another player would be heard along or the song before is
                // still being worked on.
                if (key.equals(considered) || !now.playing() || heardAlong(other) || working != null) {
                    return;
                }
                considered = key;
                startCheck(now, key, timed);
                return;
            }
            // A song to learn is heard wherever it plays and was not heard yet, while no other player is heard along;
            // what is heard is kept, and the rest comes the next time it plays.
            if (!now.playing() || heardAlong(other)) {
                return;
            }
            if (shown == Shown.NOTHING && !song(now, key)) {
                return;
            }
            long position = now.estimatePositionMs(System.currentTimeMillis());
            HeardParts known = parts(key);
            if (known.covers(position) || now.durationMs() > 0 && now.durationMs() - position < MIN_PART_MS) {
                return;
            }
            try {
                recorder.start(player.get());
                take = new Take(key, now, shown == Shown.TEXT_ONLY ? List.copyOf(text) : List.of(), null);
                int before = (int) Math.round(known.share(now.durationMs()) * 100);
                LOGGER.info("Hoergenau listens to '{}' by '{}' from {} s ({}, {} % heard before)", now.title(),
                        now.artist(), position / 1000, take.lines.isEmpty() ? "no text known"
                                : take.lines.size() + " lines of text known", before);
                notifier.toast("Hörgenau hört mit", shown(now.title()) + (known.isEmpty()
                        ? " – hörst du nur einen Teil, kommt der Rest beim nächsten Mal"
                        : " – hört den Rest dazu, " + before + " % kennt es schon"));
            } catch (Exception e) {
                LOGGER.warn("Hoergenau could not start listening: {}", e.toString());
            }
            return;
        }
        if (take.broken != null) {
            return;
        }
        if (take.check != null && timed != null) {
            // Word times often come in while the check listens (LRCLIB's lines first, a service's words a few seconds
            // later): checked and corrected is what is shown by then.
            take.check = timed;
        }
        if (recorder.failure() != null) {
            breakTake("audio: " + recorder.failure(), "die Aufnahme ging nicht");
        } else if (other != null && recorder.startNanos() != 0 && !recorder.onlyPlayer()) {
            // Recording everything the PC plays (the player's own sound could not be told apart): the other is in it.
            endPart("another player sounded at the same time (" + other + ")");
        } else if (!now.playing() && take.track.durationMs() > 0
                && take.lastPositionMs >= take.track.durationMs() - END_MS) {
            // Played to the end and the player stopped there (or went back to its start): the part is complete.
            finish();
            return;
        } else if (!now.playing()) {
            endPart("paused");
        } else if (recorder.startNanos() != 0) {
            take.lastPositionMs = now.estimatePositionMs(System.currentTimeMillis());
            long anchor = take.lastPositionMs - System.nanoTime() / 1_000_000;
            if (!take.anchors.isEmpty() && Math.abs(anchor - median(take.anchors)) > SKIP_MS) {
                endPart("skipped");
            } else {
                take.anchors.add(anchor);
            }
            if (take.check != null && take.broken == null
                    && recorder.count() >= CHECK_MS * LoopbackRecorder.RATE / 1000) {
                finish();
            }
        }
    }

    /**
     * Whether another player playing at the same time (a stream or video in the browser) would end up in the recording:
     * only when the app playing the song is not known, so everything the PC plays is recorded. With the app known only
     * its own sound is taken, and should Windows not manage that, the part ends once the other is heard.
     */
    private boolean heardAlong(String other) {
        return other != null && player.get() == null;
    }

    /**
     * Whether what plays, of which nobody has the text, is a song to learn: with a song's length, and known to a music
     * catalog as a song of that artist. Waits while the catalog is asked.
     */
    private boolean song(NowPlaying now, String key) {
        if (now.durationMs() < MIN_SONG_MS || now.durationMs() > MAX_SONG_MS) {
            noSong(now, key, now.durationMs() <= 0 ? "no length, a livestream" : now.durationMs() / 1000 + " s long");
            return false;
        }
        Optional<Boolean> known = songs.isSong(key, now);
        if (known.isPresent() && !known.get()) {
            noSong(now, key, "no music catalog knows it");
        }
        return known.orElse(false);
    }

    private void noSong(NowPlaying now, String key, String why) {
        if (notSongs.add(key)) {
            LOGGER.info("Hoergenau does not listen to '{}' by '{}': no song ({})", now.title(), now.artist(), why);
        }
    }

    /**
     * A song shown with word times: heard for a while to check that its words come when they are sung, once per song
     * and only while it plays alone, with enough of it left.
     */
    private void startCheck(NowPlaying now, String key, Lyrics timed) {
        if (timed == null || !config.hoergenauSyncCheck || syncFix == null || !checkDue(key)) {
            return;
        }
        long position = now.estimatePositionMs(System.currentTimeMillis());
        if (now.durationMs() > 0 && now.durationMs() - position < CHECK_MS + 5_000) {
            return;
        }
        try {
            recorder.start(player.get());
            take = new Take(key, now, List.of(), timed);
            LOGGER.info("Hoergenau checks the sync of '{}' by '{}'", now.title(), now.artist());
        } catch (Exception e) {
            LOGGER.warn("Hoergenau could not start listening: {}", e.toString());
        }
    }

    /** Whether the song's sync is still to be checked: never checked, or a check that could not tell, not too often. */
    private boolean checkDue(String key) {
        Optional<String> kept = saved.read(key + CHECKED);
        if (kept.isEmpty()) {
            return true;
        }
        String[] parts = kept.get().trim().split(" ");
        return parts.length >= 2 && "unsure".equals(parts[0]) && Integer.parseInt(parts[1]) < CHECK_TRIES;
    }

    /**
     * The song stops being heard here (paused, skipped, another player sounding): a sync check is tried again another
     * time; a song to learn keeps what was heard up to now as a part.
     */
    private void endPart(String why) {
        if (take.check != null) {
            breakTake(why, why);
            return;
        }
        take.endSamples = recorder.startNanos() != 0 ? recorder.count() : 0;
        LOGGER.info("Hoergenau: a part of '{}' ends ({})", take.track.title(), why);
        finish();
    }

    /** Nothing more to hear for this song: the recording ends now, not when the song does, and the player is told why. */
    private void breakTake(String why, String forPlayer) {
        take.broken = why;
        recorder.stop();
        if (take.check == null) {
            // A sync check is quiet; it is simply tried again another time.
            notifier.toast("Hörgenau hört nicht mehr mit", forPlayer + "; beim nächsten Mal noch einmal");
        }
    }

    private void drop(String why) {
        if (take != null) {
            recorder.stop();
            LOGGER.info("Hoergenau stopped listening to '{}': {}", take.track.title(), why);
            take = null;
        }
        considered = null;
    }

    private void finish() {
        Take done = take;
        take = null;
        short[] recorded = recorder.stop();
        if (done.endSamples >= 0 && done.endSamples < recorded.length) {
            recorded = Arrays.copyOf(recorded, done.endSamples);
        }
        short[] audio = recorded;
        if (done.broken != null) {
            LOGGER.info("Hoergenau dropped '{}': {}", done.track.title(), done.broken);
            return;
        }
        double heardS = audio.length / (double) LoopbackRecorder.RATE;
        if (done.check != null) {
            if (heardS * 1000 < CHECK_MS * 0.7 || done.anchors.isEmpty()) {
                LOGGER.info("Hoergenau dropped the sync check of '{}': heard {} s", done.track.title(), Math.round(heardS));
                return;
            }
            long offsetMs = median(done.anchors) + recorder.startNanos() / 1_000_000;
            working = done.track.title();
            worker.submit(() -> {
                try {
                    check(done, audio, offsetMs);
                } catch (Exception e) {
                    LOGGER.warn("Hoergenau could not check '{}': {}", done.track.title(), e.toString());
                } finally {
                    working = null;
                }
            });
            return;
        }
        if (heardS * 1000 < MIN_PART_MS || done.anchors.isEmpty()) {
            LOGGER.info("Hoergenau: {} s of '{}' are too little to keep", Math.round(heardS), done.track.title());
            return;
        }
        // Song position of the first sample: the media session's positions put on the recording's clock.
        long offsetMs = median(done.anchors) + recorder.startNanos() / 1_000_000;
        working = done.track.title();
        worker.submit(() -> {
            try {
                hearPart(done, audio, offsetMs);
            } catch (Exception e) {
                LOGGER.warn("Hoergenau could not time '{}': {}", done.track.title(), e.toString());
                notifier.toast("Hörgenau: nicht gelernt", String.valueOf(e.getMessage()));
            } finally {
                working = null;
            }
        });
    }

    /** What was heard of a song not learned yet; nothing for a song never heard. */
    private HeardParts parts(String key) {
        return parts.computeIfAbsent(key, k -> saved.read(k + PARTS).map(HeardParts::parse).orElse(HeardParts.none()));
    }

    private void keepParts(String key, HeardParts heard) {
        parts.put(key, heard);
        saved.write(key + PARTS, heard.isEmpty() ? "" : heard.format());
    }

    /**
     * Hears the words of one part and keeps them with the parts heard before; once nearly all of the song was heard,
     * learns it from all of them.
     */
    private void hearPart(Take done, short[] audio, long offsetMs) throws Exception {
        long started = System.nanoTime();
        HeardParts before = parts(done.key);
        String language = before.language() != null ? before.language() : engine.language(audio, audio.length);
        List<List<Heard>> part = engine.transcribe(audio, 0, audio.length, language).stream()
                .map(segment -> segment.stream().map(word -> new Heard(word.text(), word.startMs() + offsetMs)).toList())
                .toList();
        long toMs = offsetMs + audio.length * 1000L / LoopbackRecorder.RATE;
        HeardParts heard = before.with(offsetMs, toMs, part, language);
        long durationMs = done.track.durationMs();
        if (durationMs <= 0 || heard.share(durationMs) < MIN_HEARD) {
            keepParts(done.key, heard);
            int percent = (int) Math.round(heard.share(durationMs) * 100);
            LOGGER.info("Hoergenau heard '{}' from {} to {} s, {} % of it now; the rest when it plays again",
                    done.track.title(), offsetMs / 1000, toMs / 1000, percent);
            notifier.toast("Hörgenau: " + percent + " % gehört", shown(done.track.title())
                    + " – den Rest hört es beim nächsten Mal dazu");
            return;
        }
        if (learn(done, heard, again(engine, audio, language, offsetMs), started)) {
            keepParts(done.key, HeardParts.none());
        } else {
            keepParts(done.key, heard);
        }
    }

    /**
     * Learns a song from everything heard of it (times in ms of the song); stretches nothing was heard in are heard
     * again where they lie in the last part. Whether the song was learned; when its known text was not heard, what was
     * heard is dropped, so it is listened to anew.
     */
    private boolean learn(Take done, HeardParts heardParts, HeardLyrics.Listener again, long started) {
        List<List<Heard>> segments = heardParts.segments();
        List<Heard> heard = heardParts.words();
        String lrc;
        String how;
        double matched = 0;
        if (!done.lines.isEmpty()) {
            Optional<HeardLyrics.Aligned> aligned = HeardLyrics.align(done.lines, heard, 0, again);
            if (aligned.isEmpty()) {
                LOGGER.info("Hoergenau: the known text of '{}' was not heard in the recording", done.track.title());
                notifier.toast("Hörgenau: nicht gelernt", "der bekannte Songtext war in der Aufnahme nicht zu hören");
                parts.put(done.key, HeardParts.none());
                saved.write(done.key + PARTS, "");
                return false;
            }
            lrc = aligned.get().lrc();
            matched = aligned.get().matched();
            how = String.format("Songtext auf die Aufnahme gelegt, %d %% der Wörter gehört", Math.round(matched * 100));
        } else if (!heard.isEmpty()) {
            lrc = HeardLyrics.transcript(segments, 0);
            how = "kein Songtext bekannt, das Gehörte abgeschrieben";
        } else {
            LOGGER.info("Hoergenau heard no singing in '{}'", done.track.title());
            notifier.toast("Hörgenau: nicht gelernt", "in „" + shown(done.track.title()) + "“ war kein Gesang zu hören");
            parts.put(done.key, HeardParts.none());
            saved.write(done.key + PARTS, "");
            return false;
        }
        if (saved.read(done.key).filter(kept -> !kept.startsWith(HeardLyrics.MARK)).isPresent()) {
            return true;  // LRCLIB's lyrics turned up meanwhile; they stay.
        }
        saved.write(done.key, lrc);
        learned.accept(done.key);
        LOGGER.info("Hoergenau learned '{}' in {} s ({})", done.track.title(), (System.nanoTime() - started) / 1_000_000_000,
                how);
        SharedLearned sharer = this.sharer;
        // On lrclib.net's text only its word times go to the others; a song nobody has the text of goes as written down.
        boolean shared = sharer != null && config.shareLearned && (done.lines.isEmpty()
                ? sharer.shareHeard(done.key, done.track, lrc) : sharer.share(done.key, done.track, lrc, matched));
        notifier.toast("Gelernt: " + shown(done.track.title()), "ab jetzt Wort für Wort" + (shared ? ", für alle geteilt" : ""));
        notifier.chat("Hörgenau hat „" + shown(done.track.title()) + "“ gelernt (" + how
                + "). Ab jetzt Wort für Wort" + (shared ? done.lines.isEmpty() ? ", und für alle geteilt."
                        : ", und die Wortzeiten sind für alle geteilt." : "."));
        return true;
    }

    /** Sets a checked song's correction when its words were heard clearly enough off where the lyrics have them. */
    private void check(Take done, short[] audio, long offsetMs) throws Exception {
        String language = engine.language(audio, audio.length);
        List<SyncCheck.Timed> heard = engine.transcribe(audio, 0, audio.length, language).stream().flatMap(List::stream)
                .map(word -> new SyncCheck.Timed(word.startMs() + offsetMs, word.text())).toList();
        SyncCheck.Result result = SyncCheck.measure(SyncCheck.shown(done.check, done.track.durationMs()), heard);
        int fix = result.correctionMs();
        LOGGER.info("Hoergenau sync check of '{}': {} words paired, shown {} ms after they are sung (spread {} ms), {}",
                done.track.title(), result.pairs(), result.offsetMs(), result.spreadMs(),
                !result.trusted() ? "unsure" : fix == 0 ? "fits" : "moved by " + fix + " ms");
        int tries = saved.read(done.key + CHECKED).map(kept -> kept.trim().split(" "))
                .filter(parts -> parts.length >= 2 && "unsure".equals(parts[0])).map(parts -> Integer.parseInt(parts[1]))
                .orElse(0);
        saved.write(done.key + CHECKED, !result.trusted() ? "unsure " + (tries + 1)
                : fix == 0 ? "fits " + result.offsetMs() : "moved " + fix);
        SyncFix to = syncFix;
        if (result.trusted() && to != null) {
            // Also when the lyrics fit: the other players learn that they were checked.
            to.apply(done.track, done.check, fix, result.pairs(), (int) result.spreadMs());
            if (fix != 0) {
                notifier.toast("Sync korrigiert: " + shown(done.track.title()),
                        String.format(Locale.GERMANY, "die Wörter kamen %.1f s zu %s, jetzt passen sie",
                                Math.abs(fix) / 1000.0, fix > 0 ? "spät" : "früh"));
            }
        }
    }

    /** Where what is learned here is shared, so other players get it too. */
    private volatile SharedLearned sharer;

    public void shareWith(SharedLearned sharer) {
        this.sharer = sharer;
    }

    /** A title as it may be shown on screen: in streamer mode with explicit words hidden. */
    private String shown(String title) {
        return config.streamerMode ? Explicit.ANY.hide(title) : title;
    }

    /** Hears a stretch of the recording once more on its own, in the language found before; nothing when that fails. */
    static HeardLyrics.Listener again(WhisperEngine engine, short[] audio, String language) {
        return again(engine, audio, language, 0);
    }

    /**
     * The same for a recording that began {@code startMs} into the song, asked in ms of the song: a stretch outside it
     * gives nothing; the words come with their times in the song.
     */
    static HeardLyrics.Listener again(WhisperEngine engine, short[] audio, String language, long startMs) {
        return (fromMs, toMs) -> {
            int from = (int) Math.max(0, Math.min(audio.length, (fromMs - startMs) * LoopbackRecorder.RATE / 1000));
            int to = (int) Math.max(from, Math.min(audio.length, (toMs - startMs) * LoopbackRecorder.RATE / 1000));
            if (to - from < LoopbackRecorder.RATE) {
                return List.of();
            }
            try {
                return engine.transcribe(audio, from, to - from, language).stream().flatMap(List::stream)
                        .map(word -> new Heard(word.text(), word.startMs() + startMs)).toList();
            } catch (IOException e) {
                LOGGER.warn("Hoergenau could not listen again from {} ms: {}", fromMs, e.toString());
                return List.of();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return List.of();
            }
        };
    }

    private void install() {
        if (installing || problem != null) {
            return;
        }
        installing = true;
        long megabytes = engine.downloadMb();
        notifier.toast("Hörgenau lädt", "einmalig " + megabytes + " MB · 0 %");
        worker.submit(() -> {
            try {
                engine.install(now -> {
                    percent = now;
                    notifier.toast("Hörgenau lädt", "einmalig " + megabytes + " MB · " + now + " %");
                });
                notifier.toast("Hörgenau ist bereit", "Songs ohne Wortzeiten hört es mit, auch in Teilen");
            } catch (Exception e) {
                problem = e.getMessage();
                LOGGER.warn("Hoergenau download failed: {}", e.toString());
                notifier.toast("Hörgenau konnte nicht laden", "aus- und einschalten für einen neuen Versuch");
                notifier.chat("Hörgenau konnte nicht laden: " + e.getMessage()
                        + ". Zum erneuten Versuch aus- und einschalten.");
            } finally {
                installing = false;
            }
        });
    }

    /** After switching on again: a failed download is tried once more. */
    public void reset() {
        problem = null;
    }

    public String statusText() {
        if (!config.hoergenau) {
            return "Hörgenau aus";
        }
        if (!platform.getAsBoolean()) {
            return "Hörgenau nur unter Windows und Linux";
        }
        if (installing) {
            return "Hörgenau lädt (" + Math.max(0, percent) + " %)";
        }
        if (problem != null) {
            return "Hörgenau: Laden fehlgeschlagen";
        }
        Take now = take;
        if (now != null) {
            return (now.check != null ? "Hörgenau prüft den Sync: " : "Hörgenau hört mit: ") + shown(now.track.title())
                    + (now.broken != null ? " (abgebrochen)" : "");
        }
        String model = engine.chosen() == WhisperEngine.Model.TURBO ? "Modell Genau" : "Modell Schnell";
        return working != null ? "Hörgenau rechnet: " + shown(working)
                : engine.installed() ? "Hörgenau bereit · " + model : "Hörgenau lädt beim nächsten Song · " + model;
    }

    private static long median(List<Long> values) {
        long[] sorted = values.stream().mapToLong(Long::longValue).toArray();
        Arrays.sort(sorted);
        return sorted[sorted.length / 2];
    }
}
