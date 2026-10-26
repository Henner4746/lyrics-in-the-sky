package de.henner.lyricsinthesky.hoergenau;

import de.henner.lyricsinthesky.lyrics.Lyrics;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Whether lyrics with word times are shown where the words are really sung. A service may have timed another version
 * of the song: measured live against the sung audio, "In the End" came 1.1 s late from NetEase and "Roller" 0.8 s late
 * from Deezer, every word by the same amount, while other songs were within a tenth of a second. Hoergenau hears a
 * stretch of the song; its words are paired in order with the lyrics' words by spelling, and when nearly all pairs agree
 * on one offset, the song is moved by it.
 */
final class SyncCheck {
    /** A word heard further from where the lyrics have it is another repeat, not the same word. */
    static final long MAX_PAIR_MS = 3_000;
    /** Fewer pairs say too little: an instrumental stretch, or singing Whisper could not make out. */
    static final int MIN_PAIRS = 12;
    /** Off by less than this, the lyrics fit; Whisper itself is about this exact. */
    static final long FITS_MS = 180;
    /** The pairs must agree on their offset within this (median deviation), else nothing is changed. */
    static final long MAX_SPREAD_MS = 220;

    /** A word and when it is shown or heard, in ms of the song. */
    record Timed(long ms, String word) {}

    /**
     * @param offsetMs how much later the lyrics show the words than they are sung (median over the pairs)
     * @param spreadMs median distance of the pairs from that offset
     */
    record Result(int pairs, long offsetMs, long spreadMs) {
        boolean trusted() {
            return pairs >= MIN_PAIRS && spreadMs <= MAX_SPREAD_MS;
        }

        /** The song's correction, positive to show earlier, in steps of 10 ms; 0 when it fits or is not trusted. */
        int correctionMs() {
            return !trusted() || Math.abs(offsetMs) < FITS_MS ? 0 : (int) (Math.round(offsetMs / 10.0) * 10);
        }
    }

    private SyncCheck() {}

    /** Every word of the lyrics with the time it is shown at. */
    static List<Timed> shown(Lyrics lyrics, long durationMs) {
        List<Timed> words = new ArrayList<>();
        for (int i = 0; i < lyrics.lines().size(); i++) {
            String[] text = lyrics.lines().get(i).words();
            long[] times = lyrics.wordTimes(i, durationMs);
            for (int w = 0; w < Math.min(text.length, times.length); w++) {
                words.add(new Timed(times[w], text[w]));
            }
        }
        return words;
    }

    /** Pairs the heard words with the shown ones near them and measures how far apart the pairs are. */
    static Result measure(List<Timed> shown, List<Timed> heard) {
        List<Timed> said = heard.stream().filter(t -> !token(t.word()).isEmpty()).toList();
        if (said.isEmpty()) {
            return new Result(0, 0, 0);
        }
        long from = said.get(0).ms() - MAX_PAIR_MS;
        long to = said.get(said.size() - 1).ms() + MAX_PAIR_MS;
        // Only the stretch that was heard: the rest of the song has nothing to pair with.
        List<Timed> near = shown.stream().filter(t -> t.ms() >= from && t.ms() <= to && !token(t.word()).isEmpty()).toList();
        long[] offsets = pairs(near, said);
        if (offsets.length == 0) {
            return new Result(0, 0, 0);
        }
        long middle = median(offsets);
        long[] deviations = Arrays.stream(offsets).map(o -> Math.abs(o - middle)).toArray();
        return new Result(offsets.length, middle, median(deviations));
    }

    /** Shown minus heard time of every pair: same spelling, in order, as many as possible. */
    private static long[] pairs(List<Timed> shown, List<Timed> heard) {
        int n = shown.size();
        int m = heard.size();
        String[] a = shown.stream().map(t -> token(t.word())).toArray(String[]::new);
        String[] b = heard.stream().map(t -> token(t.word())).toArray(String[]::new);
        int[][] score = new int[n + 1][m + 1];
        for (int i = n - 1; i >= 0; i--) {
            for (int j = m - 1; j >= 0; j--) {
                int best = Math.max(score[i + 1][j], score[i][j + 1]);
                if (match(a[i], b[j], shown.get(i), heard.get(j))) {
                    best = Math.max(best, score[i + 1][j + 1] + 1);
                }
                score[i][j] = best;
            }
        }
        List<Long> offsets = new ArrayList<>();
        int i = 0;
        int j = 0;
        while (i < n && j < m) {
            if (match(a[i], b[j], shown.get(i), heard.get(j)) && score[i][j] == score[i + 1][j + 1] + 1) {
                offsets.add(shown.get(i).ms() - heard.get(j).ms());
                i++;
                j++;
            } else if (score[i + 1][j] >= score[i][j + 1]) {
                i++;
            } else {
                j++;
            }
        }
        return offsets.stream().mapToLong(Long::longValue).toArray();
    }

    private static boolean match(String a, String b, Timed shown, Timed heard) {
        // Short words like "a" or "I" pair with anything nearby; only words of two letters and more count.
        return a.length() >= 2 && a.equals(b) && Math.abs(shown.ms() - heard.ms()) <= MAX_PAIR_MS;
    }

    static String token(String word) {
        String lower = Normalizer.normalize(word.toLowerCase(Locale.ROOT).replace("ß", "ss").replace('’', '\''),
                Normalizer.Form.NFC);
        return lower.replaceAll("[^\\p{L}\\p{N}]", "");
    }

    private static long median(long[] values) {
        long[] sorted = values.clone();
        Arrays.sort(sorted);
        return sorted[sorted.length / 2];
    }
}
