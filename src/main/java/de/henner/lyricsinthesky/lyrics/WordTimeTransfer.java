package de.henner.lyricsinthesky.lyrics;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Takes word times timed elsewhere (LyricsPlus) only when they belong to the same recording as the LRCLIB lyrics: lines
 * with the same text have to start at the same moment, give or take one constant shift between two masterings of the
 * song. Too few matching lines, or matches that disagree with each other, mean another version of the song; then
 * nothing is taken and the words are estimated as before.
 */
public final class WordTimeTransfer {
    /** Same-text lines further apart than this are not compared at all. */
    static final long CANDIDATE_WINDOW_MS = 5_000;
    /** Matches this close to their common shift agree with it. */
    static final long AGREE_MS = 400;
    static final long MAX_OFFSET_MS = 3_000;
    /**
     * A smaller difference is LRCLIB's own imprecision (its lines are often set a little early), not another version:
     * the word times stay as Apple and QQ timed them. A larger one is another mastering and is shifted away.
     */
    static final long SHIFT_FROM_MS = 400;
    static final int MIN_MATCHES = 8;
    static final double MIN_MATCHED_SHARE = 0.3;
    static final double MIN_AGREEING_SHARE = 0.75;
    /** Kept on disk with word times that were taken, so they are not asked for again. */
    public static final String MARK = "[re:LyricsPlus]";
    /** Kept on disk with LyricsPlus lyrics that stood in because LRCLIB had none. */
    static final String STAND_IN_MARK = "[re:LyricsPlus stand-in]";
    private static final String NONE_PREFIX = "[re:lyricsplus-none:";
    /** After LyricsPlus had nothing fitting, it is asked again after this many days. */
    static final int NONE_DAYS = 14;

    /**
     * @param offsetMs how much later LyricsPlus' lines start than LRCLIB's
     * @param shiftMs  what was added to every LyricsPlus time
     * @param matched  lines found in both with the same text
     */
    public record Result(Lyrics lyrics, String lrc, long offsetMs, long shiftMs, int matched) {}

    private WordTimeTransfer() {}

    public static Optional<Result> transfer(Lyrics base, LyricsPlusClient.Timed timed) {
        List<Lyrics.Line> lines = base.lines().stream().filter(line -> !normalize(line.text()).isEmpty()).toList();
        record Candidate(int line, long diff) {}
        List<Candidate> candidates = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            String text = normalize(lines.get(i).text());
            for (LyricsPlusClient.Line other : timed.lines()) {
                long diff = other.startMs() - lines.get(i).startMs();
                if (Math.abs(diff) < CANDIDATE_WINDOW_MS && text.equals(normalize(other.text()))) {
                    candidates.add(new Candidate(i, diff));
                }
            }
        }
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        // A chorus matches several of its repeats; each line keeps the one closest to what most lines say.
        long guess = median(candidates.stream().map(Candidate::diff).toList());
        Map<Integer, Long> chosen = new HashMap<>();
        for (Candidate candidate : candidates) {
            chosen.merge(candidate.line(), candidate.diff(),
                    (kept, next) -> Math.abs(next - guess) < Math.abs(kept - guess) ? next : kept);
        }
        long offset = median(chosen.values());
        long agreeing = chosen.values().stream().filter(diff -> Math.abs(diff - offset) <= AGREE_MS).count();
        if (chosen.size() < MIN_MATCHES || chosen.size() < MIN_MATCHED_SHARE * lines.size()
                || agreeing < MIN_AGREEING_SHARE * chosen.size() || Math.abs(offset) > MAX_OFFSET_MS) {
            return Optional.empty();
        }
        long shift = Math.abs(offset) >= SHIFT_FROM_MS ? -offset : 0;
        String lrc = lrc(timed.lines(), shift, MARK);
        Lyrics lyrics = Lyrics.parseLrc(lrc);
        if (lyrics.isEmpty() || !lyrics.hasWordTiming()) {
            return Optional.empty();
        }
        return Optional.of(new Result(lyrics, lrc, offset, shift, chosen.size()));
    }

    /** Word-timed lines as enhanced LRC, every time moved by {@code shiftMs}, under a first line {@code mark}. */
    static String lrc(List<LyricsPlusClient.Line> lines, long shiftMs, String mark) {
        StringBuilder lrc = new StringBuilder(mark).append('\n');
        for (LyricsPlusClient.Line line : lines) {
            lrc.append('[').append(stamp(line.startMs() + shiftMs)).append(']');
            for (int w = 0; w < line.words().size(); w++) {
                LyricsPlusClient.Word word = line.words().get(w);
                lrc.append(w > 0 ? " " : "").append('<').append(stamp(word.startMs() + shiftMs)).append('>').append(word.text());
            }
            lrc.append('\n');
        }
        return lrc.toString();
    }

    /** What is kept on disk when LyricsPlus had nothing fitting, so it is not asked on every play. */
    public static String noneMarker(LocalDate today) {
        return NONE_PREFIX + today + "]\n";
    }

    /** Whether a kept "nothing fitting" is recent enough not to ask again. */
    public static boolean recentlyNone(String kept, LocalDate today) {
        if (!kept.startsWith(NONE_PREFIX)) {
            return false;
        }
        int end = kept.indexOf(']');
        try {
            LocalDate checked = LocalDate.parse(kept.substring(NONE_PREFIX.length(), end));
            return !today.isAfter(checked.plusDays(NONE_DAYS));
        } catch (DateTimeParseException | IndexOutOfBoundsException e) {
            return false;
        }
    }

    static String normalize(String text) {
        return text.toLowerCase(Locale.ROOT).replace('’', '\'').replaceAll("[^\\p{L}\\p{N}]", "");
    }

    private static long median(Collection<Long> values) {
        List<Long> sorted = values.stream().sorted().toList();
        return sorted.get(sorted.size() / 2);
    }

    static String stamp(long ms) {
        long clamped = Math.max(0, ms);
        return String.format(Locale.ROOT, "%02d:%02d.%03d", clamped / 60_000, clamped / 1000 % 60, clamped % 1000);
    }
}
