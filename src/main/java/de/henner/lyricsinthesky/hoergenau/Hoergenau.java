package de.henner.lyricsinthesky.hoergenau;

import de.henner.lyricsinthesky.SkyLyricsConfig;
import de.henner.lyricsinthesky.hoergenau.WhisperEngine.Heard;
import de.henner.lyricsinthesky.lyrics.Lyrics;
import de.henner.lyricsinthesky.lyrics.LyricsCache;
import de.henner.lyricsinthesky.nowplaying.NowPlaying;
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

    /** What a learnable song is shown with now: nothing, or text spread over it without times. */
    public enum Shown { NOTHING, TEXT_ONLY, TIMED }

    private final WhisperEngine engine;
    private final LoopbackRecorder recorder;
    private final LyricsCache saved;
    private final SkyLyricsConfig config;
    /** Tells the player something in chat (called from any thread; the receiver hops to the client thread). */
    private final Consumer<String> chat;
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
        String broken;

        Take(String key, NowPlaying track, List<String> lines) {
            this.key = key;
            this.track = track;
            this.lines = lines;
        }
    }

    public Hoergenau(Path dir, SkyLyricsConfig config, LyricsCache saved, Consumer<String> chat, Consumer<String> learned) {
        this.engine = new WhisperEngine(dir);
        this.recorder = new LoopbackRecorder(dir);
        this.saved = saved;
        this.config = config;
        this.chat = chat;
        this.learned = learned;
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
     */
    public synchronized void observe(NowPlaying now, String key, Shown shown, List<String> text) {
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
            try {
                recorder.start();
                take = new Take(key, now, shown == Shown.TEXT_ONLY ? List.copyOf(text) : List.of());
                LOGGER.info("Hoergenau listens to '{}' by '{}' ({})", now.title(), now.artist(),
                        take.lines.isEmpty() ? "no text known" : take.lines.size() + " lines of text known");
            } catch (Exception e) {
                LOGGER.warn("Hoergenau could not start listening: {}", e.toString());
            }
            return;
        }
        if (take.broken != null) {
            return;
        }
        if (recorder.failure() != null) {
            take.broken = "audio: " + recorder.failure();
        } else if (!now.playing()) {
            take.broken = "paused";
        } else if (recorder.startNanos() != 0) {
            long anchor = now.estimatePositionMs(System.currentTimeMillis()) - System.nanoTime() / 1_000_000;
            if (!take.anchors.isEmpty() && Math.abs(anchor - median(take.anchors)) > SKIP_MS) {
                take.broken = "skipped";
            }
            take.anchors.add(anchor);
        }
        if (take.broken != null) {
            // Nothing more to hear for this song; the recording ends now, not when the song does.
            recorder.stop();
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
        short[] audio = recorder.stop();
        if (done.broken != null) {
            LOGGER.info("Hoergenau dropped '{}': {}", done.track.title(), done.broken);
            return;
        }
        double heardS = audio.length / (double) LoopbackRecorder.RATE;
        if (done.track.durationMs() > 0 && heardS < MIN_HEARD * done.track.durationMs() / 1000.0) {
            LOGGER.info("Hoergenau dropped '{}': heard {} of {} s", done.track.title(), Math.round(heardS),
                    done.track.durationMs() / 1000);
            return;
        }
        if (done.anchors.isEmpty()) {
            return;
        }
        // Song position of the first sample: the media session's positions put on the recording's clock.
        long offsetMs = median(done.anchors) + recorder.startNanos() / 1_000_000;
        working = done.track.title();
        worker.submit(() -> {
            try {
                learn(done, audio, offsetMs);
            } catch (Exception e) {
                LOGGER.warn("Hoergenau could not time '{}': {}", done.track.title(), e.toString());
            } finally {
                working = null;
            }
        });
    }

    private void learn(Take done, short[] audio, long offsetMs) throws Exception {
        long started = System.nanoTime();
        List<List<Heard>> segments = engine.transcribe(audio, audio.length);
        List<Heard> heard = segments.stream().flatMap(List::stream).toList();
        String lrc;
        String how;
        if (!done.lines.isEmpty()) {
            Optional<HeardLyrics.Aligned> aligned = HeardLyrics.align(done.lines, heard, offsetMs);
            if (aligned.isEmpty()) {
                LOGGER.info("Hoergenau: the known text of '{}' was not heard in the recording", done.track.title());
                return;
            }
            lrc = aligned.get().lrc();
            how = String.format("Songtext auf die Aufnahme gelegt, %d %% der Wörter gehört",
                    Math.round(aligned.get().matched() * 100));
        } else if (!heard.isEmpty()) {
            lrc = HeardLyrics.transcript(segments, offsetMs);
            how = "kein Songtext bekannt, das Gehörte abgeschrieben";
        } else {
            LOGGER.info("Hoergenau heard no singing in '{}'", done.track.title());
            return;
        }
        if (saved.read(done.key).filter(kept -> !kept.startsWith(HeardLyrics.MARK)).isPresent()) {
            return;  // LRCLIB's lyrics turned up meanwhile; they stay.
        }
        saved.write(done.key, lrc);
        learned.accept(done.key);
        LOGGER.info("Hoergenau learned '{}' in {} s ({})", done.track.title(), (System.nanoTime() - started) / 1_000_000_000,
                how);
        chat.accept("Hörgenau hat „" + done.track.title() + "“ gelernt (" + how + "). Ab dem nächsten Abspielen Wort für Wort.");
    }

    private void install() {
        if (installing || problem != null) {
            return;
        }
        installing = true;
        chat.accept("Hörgenau lädt einmalig whisper.cpp und das Sprachmodell (etwa " + WhisperEngine.DOWNLOAD_MB
                + " MB, von GitHub und Hugging Face, mit Prüfsumme).");
        worker.submit(() -> {
            try {
                engine.install(now -> {
                    percent = now;
                    if (now == 50) {
                        chat.accept("Hörgenau: Download zur Hälfte fertig.");
                    }
                });
                chat.accept("Hörgenau ist bereit: Songs ohne Wortzeiten werden ab ihrem Anfang mitgehört.");
            } catch (Exception e) {
                problem = e.getMessage();
                LOGGER.warn("Hoergenau download failed: {}", e.toString());
                chat.accept("Hörgenau konnte nicht laden: " + e.getMessage() + ". Zum erneuten Versuch aus- und einschalten.");
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
            return "Hörgenau hört mit: " + now.track.title() + (now.broken != null ? " (abgebrochen)" : "");
        }
        return working != null ? "Hörgenau lernt: " + working : "Hörgenau bereit";
    }

    private static long median(List<Long> values) {
        long[] sorted = values.stream().mapToLong(Long::longValue).toArray();
        Arrays.sort(sorted);
        return sorted[sorted.length / 2];
    }
}
