package de.henner.lyricsinthesky.lyrics;

import de.henner.lyricsinthesky.nowplaying.NowPlaying;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Asks the word time services the player switched on, one after the other in the order that fitted most songs in
 * measurements, until one has word times for the song that can be trusted:
 *
 * <ul>
 *   <li>they line up with LRCLIB's lines ({@link WordTimeTransfer}), or
 *   <li>two services that timed the song on their own agree with each other. Hand-made LRCLIB lines sometimes drift
 *       by a second over a song while the services, timed by their editors, still agree to a tenth of a second; then
 *       the services are right and LRCLIB is not.
 * </ul>
 *
 * Without LRCLIB lyrics the first service that has the song stands in. A service that failed rests for a few minutes,
 * so a service that is down never slows down the next songs.
 */
public final class WordTimeFinder {
    private static final Logger LOGGER = LoggerFactory.getLogger("lyricsinthesky");
    /** First line of LRC whose word times came from a service, followed by the service's name. */
    static final String MARK_PREFIX = "[re:wordtimes ";
    private static final String NONE_PREFIX = "[re:wordtimes-none:";
    /** After no service had anything fitting, they are asked again after this many days. */
    static final int NONE_DAYS = 14;
    static final long REST_MS = 5 * 60 * 1000;
    /** Two services agreeing within this are one timing; more apart, one of them timed another mastering. */
    static final long CROSS_AGREE_MS = 1_000;
    /** Words at the very same millisecond in two answers mean one service passes on the other's data (Kugou and QQ). */
    static final double SAME_DATA_SHARE = 0.6;

    /**
     * @param lyrics what to show, with a first line {@link #MARK_PREFIX} naming the service
     * @param how    "LRCLIB" when checked against LRCLIB's lines, "confirmed by …" when another service confirmed it,
     *               "stand-in" when LRCLIB had no lyrics
     */
    public record Taken(Lyrics lyrics, String source, String how) {}

    /** @param complete false when a service could not be asked, so "nothing found" must not be kept */
    public record Outcome(Optional<Taken> taken, boolean complete) {}

    private final List<WordTimeSource> sources;
    private final Map<String, Long> restingUntil = new ConcurrentHashMap<>();

    /** @param sources in the order they are asked */
    public WordTimeFinder(List<WordTimeSource> sources) {
        this.sources = List.copyOf(sources);
    }

    public List<WordTimeSource> sources() {
        return sources;
    }

    /**
     * Word times for the song from the services in {@code enabled} (lower-case names).
     *
     * @param base LRCLIB's lyrics, or {@code null} when it has none
     */
    public Outcome find(NowPlaying track, Lyrics base, Set<String> enabled) throws InterruptedException {
        List<TimedWords> found = new ArrayList<>();
        boolean complete = true;
        for (WordTimeSource source : sources) {
            if (!enabled.contains(key(source.name()))) {
                continue;
            }
            Long rest = restingUntil.get(source.name());
            if (rest != null && rest > System.currentTimeMillis()) {
                complete = false;
                continue;
            }
            Optional<TimedWords> words;
            try {
                words = source.find(track);
            } catch (InterruptedException e) {
                throw e;
            } catch (Exception e) {
                // Down, changed or too slow: the next service is asked, this one rests.
                restingUntil.put(source.name(), System.currentTimeMillis() + REST_MS);
                LOGGER.info("Word times from {} for '{}' failed, resting it for 5 minutes: {}", source.name(),
                        track.title(), e.toString());
                complete = false;
                continue;
            }
            if (words.isEmpty()) {
                continue;
            }
            // Named after the service asked, which is what the player switches; LyricsPlus adds where it read them.
            String label = words.get().source().equals(source.name()) ? source.name()
                    : source.name() + " (" + words.get().source() + ")";
            found.add(new TimedWords(label, words.get().lines()));
            Optional<Taken> taken = decide(base, found);
            if (taken.isPresent()) {
                return new Outcome(taken, true);
            }
        }
        return new Outcome(Optional.empty(), complete);
    }

    /** The trusted word times among the answers so far, the latest of which just came in. */
    static Optional<Taken> decide(Lyrics base, List<TimedWords> found) {
        TimedWords latest = found.get(found.size() - 1);
        if (base == null || base.isEmpty()) {
            return Optional.of(taken(latest, 0, "stand-in"));
        }
        Optional<WordTimeTransfer.Result> fits = WordTimeTransfer.transfer(base, latest, mark(latest.source()));
        if (fits.isPresent()) {
            return Optional.of(new Taken(fits.get().lyrics(), latest.source(), "LRCLIB"));
        }
        for (TimedWords earlier : found.subList(0, found.size() - 1)) {
            if (sameData(earlier, latest)) {
                continue;
            }
            Lyrics reference = Lyrics.parseLrc(WordTimeTransfer.lrc(earlier.lines(), 0, mark(earlier.source())));
            Optional<WordTimeTransfer.Result> agree = WordTimeTransfer.transfer(reference, latest, mark(latest.source()));
            if (agree.isPresent() && Math.abs(agree.get().offsetMs()) < CROSS_AGREE_MS) {
                // The service asked first is the one that fitted more songs; its times are shown.
                return Optional.of(taken(earlier, 0, "confirmed by " + latest.source()));
            }
        }
        return Optional.empty();
    }

    private static Taken taken(TimedWords words, long shift, String how) {
        return new Taken(Lyrics.parseLrc(WordTimeTransfer.lrc(words.lines(), shift, mark(words.source()))),
                words.source(), how);
    }

    /** Whether most words of {@code a} sit at the very same millisecond in {@code b}: one service's data, twice. */
    static boolean sameData(TimedWords a, TimedWords b) {
        Set<String> inB = new HashSet<>();
        b.lines().forEach(line -> line.words().forEach(word -> inB.add(word.startMs() + WordTimeTransfer.normalize(word.text()))));
        long total = a.lines().stream().mapToLong(line -> line.words().size()).sum();
        long same = a.lines().stream().flatMap(line -> line.words().stream())
                .filter(word -> inB.contains(word.startMs() + WordTimeTransfer.normalize(word.text()))).count();
        return total > 0 && same >= SAME_DATA_SHARE * total;
    }

    static String mark(String source) {
        return MARK_PREFIX + source + "]";
    }

    /** The service named in kept word times, or empty when the LRC holds none. */
    public static Optional<String> sourceOf(String lrc) {
        if (lrc.startsWith(MARK_PREFIX)) {
            int end = lrc.indexOf(']');
            return end > MARK_PREFIX.length() ? Optional.of(lrc.substring(MARK_PREFIX.length(), end)) : Optional.empty();
        }
        // Word times kept by 1.3.0-dev.1 to dev.3 came from LyricsPlus.
        return lrc.startsWith(WordTimeTransfer.MARK) ? Optional.of("LyricsPlus") : Optional.empty();
    }

    /** The service (lower-case, as in the config) that kept word times came from, or empty when they hold none. */
    public static Optional<String> serviceOf(String lrc) {
        return sourceOf(lrc).map(label -> key(label.split(" ")[0]));
    }

    /** Lower-case name as used in the config and the command. */
    public static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    /** What is kept on disk when no service had anything fitting; it names the services that were asked. */
    public static String noneMarker(LocalDate today, Set<String> asked) {
        return NONE_PREFIX + today + ":" + String.join(",", asked.stream().sorted().toList()) + "]\n";
    }

    /**
     * Whether a kept "nothing fitting" still holds: recent enough, and every service switched on now was asked then.
     * Switching on another service asks again at once.
     */
    public static boolean recentlyNone(String kept, LocalDate today, Set<String> enabled) {
        if (!kept.startsWith(NONE_PREFIX)) {
            return false;
        }
        int end = kept.indexOf(']');
        if (end < 0) {
            return false;
        }
        String[] parts = kept.substring(NONE_PREFIX.length(), end).split(":", 2);
        try {
            LocalDate checked = LocalDate.parse(parts[0]);
            Set<String> asked = parts.length > 1 && !parts[1].isEmpty() ? Set.of(parts[1].split(",")) : Set.of();
            return !today.isAfter(checked.plusDays(NONE_DAYS)) && asked.containsAll(enabled);
        } catch (DateTimeParseException | IllegalArgumentException e) {
            return false;
        }
    }
}
