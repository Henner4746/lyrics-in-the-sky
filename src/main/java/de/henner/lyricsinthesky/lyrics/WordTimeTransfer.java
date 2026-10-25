package de.henner.lyricsinthesky.lyrics;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Takes word times timed elsewhere (Deezer, LyricsPlus, NetEase, Kugou) only when they belong to the same recording as
 * the LRCLIB lyrics: each line's first words have to be sung at the moment the line starts, give or take one constant
 * shift between two masterings of the song. Too few matching lines, or matches that disagree with each other, mean
 * another version of the song; then nothing is taken and the words are estimated as before.
 */
public final class WordTimeTransfer {
    /** Lines further apart than this are not compared at all. */
    static final long CANDIDATE_WINDOW_MS = 10_000;
    /** A line is looked for by this many of its first words; shorter lines need at least {@link #MIN_LINE_WORDS}. */
    static final int OPENING_WORDS = 4;
    static final int MIN_LINE_WORDS = 2;
    /** Matches this close to their common shift agree with it. */
    static final long AGREE_MS = 400;
    /**
     * Another mastering may start a few seconds later or earlier (a longer intro); as long as nearly all lines agree on
     * one shift, it is the same performance.
     */
    static final long MAX_OFFSET_MS = 8_000;
    /**
     * A smaller difference is LRCLIB's own imprecision, not another version: the word times stay as the service timed
     * them. A larger one is another mastering and is shifted away. Measured on 36 songs: where two services timed the
     * same recording, they agreed with each other while LRCLIB was off by up to 0.9 s, so shifting from 0.4 s on (as
     * before) moved good times onto LRCLIB's worse ones.
     */
    static final long SHIFT_FROM_MS = 1_000;
    static final int MIN_MATCHES = 8;
    static final double MIN_MATCHED_SHARE = 0.3;
    static final double MIN_AGREEING_SHARE = 0.75;
    /**
     * The looser check for imprecise LRCLIB lines: this share of the matches within {@link #LOOSE_AGREE_MS} of their
     * common offset, which itself stays below {@link #SHIFT_FROM_MS}.
     */
    static final long LOOSE_AGREE_MS = 800;
    static final double LOOSE_SHARE = 0.6;
    static final double IN_ORDER_SHARE = 0.9;
    /** Marked word times taken by 1.3.0-dev.1 to dev.3, all from LyricsPlus; still read from the disk. */
    public static final String MARK = "[re:LyricsPlus]";
    /** Kept on disk with LyricsPlus lyrics that stood in because LRCLIB had none. */
    static final String STAND_IN_MARK = "[re:LyricsPlus stand-in]";

    /**
     * @param offsetMs how much later LyricsPlus' lines start than LRCLIB's
     * @param shiftMs  what was added to every LyricsPlus time
     * @param matched  LRCLIB lines found in the timed words
     * @param loose    LRCLIB's lines were too imprecise for the strict check; the looser one held
     */
    public record Result(Lyrics lyrics, String lrc, long offsetMs, long shiftMs, int matched, boolean loose) {}

    /** Whether the matched lines come in the service's words in the same order as in LRCLIB, nearly all of them. */
    private static boolean inOrder(List<Lyrics.Line> lines, Map<Integer, Long> chosen) {
        List<Integer> matched = chosen.keySet().stream().sorted().toList();
        int ordered = 0;
        for (int i = 1; i < matched.size(); i++) {
            long before = lines.get(matched.get(i - 1)).startMs() + chosen.get(matched.get(i - 1));
            long after = lines.get(matched.get(i)).startMs() + chosen.get(matched.get(i));
            if (after > before) {
                ordered++;
            }
        }
        return ordered >= IN_ORDER_SHARE * (matched.size() - 1);
    }

    private WordTimeTransfer() {}

    public static Optional<Result> transfer(Lyrics base, TimedWords timed) {
        return transfer(base, timed, MARK);
    }

    /** @param mark first line of the LRC that is made, naming where the word times come from */
    public static Optional<Result> transfer(Lyrics base, TimedWords timed, String mark) {
        List<Lyrics.Line> lines = base.lines().stream().filter(line -> !normalize(line.text()).isEmpty()).toList();
        // The services split lines their own way ("You're as cold as ice," / "you're willing to …"), so a line is found
        // by its first words anywhere in the timed words, not by the whole line's text.
        List<String> words = new ArrayList<>();
        List<Long> times = new ArrayList<>();
        for (TimedWords.Line line : timed.lines()) {
            for (TimedWords.Word word : line.words()) {
                for (String part : tokens(word.text())) {
                    words.add(part);
                    times.add(word.startMs());
                }
            }
        }
        record Candidate(int line, long diff) {}
        List<Candidate> candidates = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            List<String> opening = tokens(lines.get(i).text());
            if (opening.size() < MIN_LINE_WORDS) {
                continue;
            }
            opening = opening.subList(0, Math.min(OPENING_WORDS, opening.size()));
            for (int at = 0; at + opening.size() <= words.size(); at++) {
                long diff = times.get(at) - lines.get(i).startMs();
                if (Math.abs(diff) < CANDIDATE_WINDOW_MS && words.subList(at, at + opening.size()).equals(opening)) {
                    candidates.add(new Candidate(i, diff));
                }
            }
        }
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        // A chorus matches several of its repeats. The shift most lines can agree on wins, and each line keeps the
        // repeat closest to it.
        long guess = 0;
        int best = -1;
        for (Candidate candidate : candidates) {
            long diff = candidate.diff();
            int support = (int) candidates.stream().filter(other -> Math.abs(other.diff() - diff) <= AGREE_MS)
                    .mapToInt(Candidate::line).distinct().count();
            if (support > best || support == best && Math.abs(diff) < Math.abs(guess)) {
                best = support;
                guess = diff;
            }
        }
        long agreed = guess;
        Map<Integer, Long> chosen = new HashMap<>();
        for (Candidate candidate : candidates) {
            chosen.merge(candidate.line(), candidate.diff(),
                    (kept, next) -> Math.abs(next - agreed) < Math.abs(kept - agreed) ? next : kept);
        }
        long offset = median(chosen.values());
        long agreeing = chosen.values().stream().filter(diff -> Math.abs(diff - offset) <= AGREE_MS).count();
        if (chosen.size() < MIN_MATCHES || chosen.size() < MIN_MATCHED_SHARE * lines.size()
                || Math.abs(offset) > MAX_OFFSET_MS) {
            return Optional.empty();
        }
        boolean loose = false;
        if (agreeing < MIN_AGREEING_SHARE * chosen.size()) {
            // Hand-timed LRCLIB lines are often a few hundred ms off either way. When the service's lines still fall
            // around LRCLIB's, in the same order and without a shift, it is the same recording, timed better.
            long near = chosen.values().stream().filter(diff -> Math.abs(diff - offset) <= LOOSE_AGREE_MS).count();
            if (Math.abs(offset) >= SHIFT_FROM_MS || near < LOOSE_SHARE * chosen.size()
                    || !inOrder(lines, chosen)) {
                return Optional.empty();
            }
            loose = true;
        }
        long shift = Math.abs(offset) >= SHIFT_FROM_MS ? -offset : 0;
        String lrc = lrc(timed.lines(), shift, mark);
        Lyrics lyrics = Lyrics.parseLrc(lrc);
        if (lyrics.isEmpty() || !lyrics.hasWordTiming()) {
            return Optional.empty();
        }
        return Optional.of(new Result(lyrics, lrc, offset, shift, chosen.size(), loose));
    }

    /** Word-timed lines as enhanced LRC, every time moved by {@code shiftMs}, under a first line {@code mark}. */
    static String lrc(List<TimedWords.Line> lines, long shiftMs, String mark) {
        StringBuilder lrc = new StringBuilder(mark).append('\n');
        for (TimedWords.Line line : lines) {
            lrc.append('[').append(stamp(line.startMs() + shiftMs)).append(']');
            for (int w = 0; w < line.words().size(); w++) {
                TimedWords.Word word = line.words().get(w);
                lrc.append(w > 0 ? " " : "").append('<').append(stamp(word.startMs() + shiftMs)).append('>').append(word.text());
            }
            lrc.append('\n');
        }
        return lrc.toString();
    }

    /** Lower-case words without punctuation; hyphens split, so "knee-deep" and "knee deep" are the same. */
    static List<String> tokens(String text) {
        List<String> tokens = new ArrayList<>();
        for (String part : text.split("[\\s\\-‐–—]+")) {
            String token = normalize(part);
            if (!token.isEmpty()) {
                tokens.add(token);
            }
        }
        return tokens;
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
