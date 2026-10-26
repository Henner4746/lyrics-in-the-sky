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
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * "Hoergenau": word times for songs no service has timed, by listening. Off unless the player switches it on, and only
 * on Windows. For such a song, played from its start, it records what the PC plays (in memory), and when the song is
 * over whisper.cpp times the heard words: LRCLIB's text without times is placed on them, or, when nobody has the
 * song's text, the heard words become the lyrics. The result is kept like found lyrics, so from the next play on the
 * song runs word by word. Lyrics with times from LRCLIB or a word time service are never touched.
 */
public final class Hoergenau {
    private static final Logger LOGGER = LoggerFactory.getLogger("lyricsinthesky");
    /** Listening starts only near the song's start, so the whole song is heard. */
    private static final long START_WINDOW_MS = 6_000;
    /** At least this share of the song must have been heard. */
    private static final double MIN_HEARD = 0.8;
    /** A position this far off the line the earlier ones gave means the player skipped. */
    private static final long SKIP_MS = 2_500;
    /** A player stopping this close to the end of the song has played it to the end (the last song of a queue). */
    private static final long END_MS = 8_000;

    /** What a learnable song is shown with now: nothing, or text spread over it without times. */
    public enum Shown { NOTHING, TEXT_ONLY, TIMED }

    private final WhisperEngine engine;
    private final LoopbackRecorder recorder;
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

    private static final class Take {
        final String key;
        final NowPlaying track;
        final List<String> lines;
        final List<Long> anchors = new ArrayList<>();
        long lastPositionMs;
        String broken;

        Take(String key, NowPlaying track, List<String> lines) {
            this.key = key;
            this.track = track;
            this.lines = lines;
        }
    }

    /** How Hoergenau tells the player what it does; called from any thread. */
    public interface Notifier {
        /** A short note in the corner, replacing the one before: what Hoergenau is doing right now. */
        void toast(String title, String detail);

        /** A line in chat, for what is worth reading later. */
        void chat(String message);
    }

    public Hoergenau(Path dir, SkyLyricsConfig config, LyricsCache saved, Notifier notifier, Consumer<String> learned) {
        this.engine = new WhisperEngine(dir);
        this.recorder = new LoopbackRecorder(dir);
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
     */
    public synchronized void observe(NowPlaying now, String key, Shown shown, List<String> text, String other) {
        if (!config.hoergenau || !WhisperEngine.supported()) {
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
            if (key.equals(considered)) {
                return;
            }
            considered = key;
            if (shown == Shown.TIMED || working != null) {
                return;
            }
            long position = now.estimatePositionMs(System.currentTimeMillis());
            if (!now.playing() || position > START_WINDOW_MS) {
                LOGGER.info("Hoergenau: '{}' started {} s ago, listens only from the start", now.title(), position / 1000);
                return;
            }
            if (other != null) {
                LOGGER.info("Hoergenau: '{}' plays together with {}, listens only to a song playing alone", now.title(), other);
                return;
            }
            try {
                recorder.start();
                take = new Take(key, now, shown == Shown.TEXT_ONLY ? List.copyOf(text) : List.of());
                LOGGER.info("Hoergenau listens to '{}' by '{}' ({})", now.title(), now.artist(),
                        take.lines.isEmpty() ? "no text known" : take.lines.size() + " lines of text known");
                notifier.toast("Hörgenau hört mit", shown(now.title()) + " – bis zum Ende laufen lassen");
            } catch (Exception e) {
                LOGGER.warn("Hoergenau could not start listening: {}", e.toString());
            }
            return;
        }
        if (take.broken != null) {
            return;
        }
        if (recorder.failure() != null) {
            breakTake("audio: " + recorder.failure(), "die Aufnahme ging nicht");
        } else if (other != null) {
            breakTake("another player sounded at the same time (" + other + ")", "es lief noch anderer Ton mit");
        } else if (!now.playing() && take.track.durationMs() > 0
                && take.lastPositionMs >= take.track.durationMs() - END_MS) {
            // Played to the end and the player stopped there (or went back to its start): the song is complete.
            finish();
            return;
        } else if (!now.playing()) {
            breakTake("paused", "der Song wurde pausiert");
        } else if (recorder.startNanos() != 0) {
            take.lastPositionMs = now.estimatePositionMs(System.currentTimeMillis());
            long anchor = take.lastPositionMs - System.nanoTime() / 1_000_000;
            if (!take.anchors.isEmpty() && Math.abs(anchor - median(take.anchors)) > SKIP_MS) {
                breakTake("skipped", "im Song wurde gespult");
            } else {
                take.anchors.add(anchor);
            }
        }
    }

    /** Nothing more to hear for this song: the recording ends now, not when the song does, and the player is told why. */
    private void breakTake(String why, String forPlayer) {
        take.broken = why;
        recorder.stop();
        notifier.toast("Hörgenau hört nicht mehr mit", forPlayer + "; beim nächsten Mal von vorn");
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
        short[] audio = recorder.stop();
        if (done.broken != null) {
            LOGGER.info("Hoergenau dropped '{}': {}", done.track.title(), done.broken);
            return;
        }
        double heardS = audio.length / (double) LoopbackRecorder.RATE;
        if (done.track.durationMs() > 0 && heardS < MIN_HEARD * done.track.durationMs() / 1000.0) {
            LOGGER.info("Hoergenau dropped '{}': heard {} of {} s", done.track.title(), Math.round(heardS),
                    done.track.durationMs() / 1000);
            notifier.toast("Hörgenau hört nicht mehr mit", "der Song lief nicht bis zum Ende; beim nächsten Mal von vorn");
            return;
        }
        if (done.anchors.isEmpty()) {
            return;
        }
        // Song position of the first sample: the media session's positions put on the recording's clock.
        long offsetMs = median(done.anchors) + recorder.startNanos() / 1_000_000;
        working = done.track.title();
        notifier.toast("Hörgenau lernt", shown(done.track.title()) + " – etwa eine Minute im Hintergrund");
        worker.submit(() -> {
            try {
                learn(done, audio, offsetMs);
            } catch (Exception e) {
                LOGGER.warn("Hoergenau could not time '{}': {}", done.track.title(), e.toString());
                notifier.toast("Hörgenau: nicht gelernt", String.valueOf(e.getMessage()));
            } finally {
                working = null;
            }
        });
    }

    private void learn(Take done, short[] audio, long offsetMs) throws Exception {
        long started = System.nanoTime();
        String language = engine.language(audio, audio.length);
        List<List<Heard>> segments = engine.transcribe(audio, 0, audio.length, language);
        List<Heard> heard = segments.stream().flatMap(List::stream).toList();
        String lrc;
        String how;
        double matched = 0;
        if (!done.lines.isEmpty()) {
            Optional<HeardLyrics.Aligned> aligned = HeardLyrics.align(done.lines, heard, offsetMs,
                    again(engine, audio, language));
            if (aligned.isEmpty()) {
                LOGGER.info("Hoergenau: the known text of '{}' was not heard in the recording", done.track.title());
                notifier.toast("Hörgenau: nicht gelernt", "der bekannte Songtext war in der Aufnahme nicht zu hören");
                return;
            }
            lrc = aligned.get().lrc();
            matched = aligned.get().matched();
            how = String.format("Songtext auf die Aufnahme gelegt, %d %% der Wörter gehört", Math.round(matched * 100));
        } else if (!heard.isEmpty()) {
            lrc = HeardLyrics.transcript(segments, offsetMs);
            how = "kein Songtext bekannt, das Gehörte abgeschrieben";
        } else {
            LOGGER.info("Hoergenau heard no singing in '{}'", done.track.title());
            notifier.toast("Hörgenau: nicht gelernt", "in „" + shown(done.track.title()) + "“ war kein Gesang zu hören");
            return;
        }
        if (saved.read(done.key).filter(kept -> !kept.startsWith(HeardLyrics.MARK)).isPresent()) {
            return;  // LRCLIB's lyrics turned up meanwhile; they stay.
        }
        saved.write(done.key, lrc);
        learned.accept(done.key);
        LOGGER.info("Hoergenau learned '{}' in {} s ({})", done.track.title(), (System.nanoTime() - started) / 1_000_000_000,
                how);
        SharedLearned sharer = this.sharer;
        boolean shared = sharer != null && config.shareLearned;
        if (shared) {
            sharer.share(done.key, done.track, lrc, !done.lines.isEmpty(), matched);
        }
        notifier.toast("Gelernt: " + shown(done.track.title()), "ab jetzt Wort für Wort" + (shared ? ", für alle geteilt" : ""));
        notifier.chat("Hörgenau hat „" + shown(done.track.title()) + "“ gelernt (" + how
                + "). Ab jetzt Wort für Wort" + (shared ? ", und geteilt für alle." : "."));
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
        return (fromMs, toMs) -> {
            int from = (int) Math.max(0, Math.min(audio.length, fromMs * LoopbackRecorder.RATE / 1000));
            int to = (int) Math.max(from, Math.min(audio.length, toMs * LoopbackRecorder.RATE / 1000));
            if (to - from < LoopbackRecorder.RATE) {
                return List.of();
            }
            try {
                return engine.transcribe(audio, from, to - from, language).stream().flatMap(List::stream).toList();
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
        notifier.toast("Hörgenau lädt", "einmalig " + WhisperEngine.DOWNLOAD_MB + " MB · 0 %");
        worker.submit(() -> {
            try {
                engine.install(now -> {
                    percent = now;
                    notifier.toast("Hörgenau lädt", "einmalig " + WhisperEngine.DOWNLOAD_MB + " MB · " + now + " %");
                });
                notifier.toast("Hörgenau ist bereit", "Songs ohne Wortzeiten hört es ab ihrem Anfang mit");
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
        if (!WhisperEngine.supported()) {
            return "Hörgenau nur unter Windows";
        }
        if (installing) {
            return "Hörgenau lädt (" + Math.max(0, percent) + " %)";
        }
        if (problem != null) {
            return "Hörgenau: Laden fehlgeschlagen";
        }
        Take now = take;
        if (now != null) {
            return "Hörgenau hört mit: " + shown(now.track.title()) + (now.broken != null ? " (abgebrochen)" : "");
        }
        return working != null ? "Hörgenau lernt: " + shown(working) : "Hörgenau bereit";
    }

    private static long median(List<Long> values) {
        long[] sorted = values.stream().mapToLong(Long::longValue).toArray();
        Arrays.sort(sorted);
        return sorted[sorted.length / 2];
    }
}
