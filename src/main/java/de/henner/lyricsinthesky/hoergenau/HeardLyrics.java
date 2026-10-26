package de.henner.lyricsinthesky.hoergenau;

import de.henner.lyricsinthesky.hoergenau.WhisperEngine.Heard;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Word-timed LRC from what Whisper heard: known lyrics (LRCLIB's text without times) placed on the heard words, or,
 * when nobody has the song's text, the heard words themselves.
 */
final class HeardLyrics {
    /** First line of lyrics Hoergenau made; LRCLIB's lines replace them once it has some. */
    static final String MARK = "[re:Hoergenau]";
    /** Below this share of the known lyrics found in the audio, the recording does not carry them. */
    static final double MIN_MATCHED = 0.35;
    /** Up to this many words in a row heard as other words keep the times of those. */
    static final int MAX_MISHEARD = 4;
    /** This many words in a row or more not heard at all are listened to once more on their own. */
    static final int AGAIN_FROM_WORDS = 4;
    /** At most so many stretches are heard again, each at most this long at the start or end of the song. */
    static final int AGAIN_MAX_STRETCHES = 6;
    static final long AGAIN_EDGE_MS = 90_000;
    static final long AGAIN_MAX_MS = 150_000;

    record Aligned(String lrc, double matched) {}

    /** Hears a stretch of the recording again: the words between two times (ms of the recording), times the same. */
    interface Listener {
        List<Heard> hear(long fromMs, long toMs);
    }

    private HeardLyrics() {}

    static Optional<Aligned> align(List<String> lines, List<Heard> heard, long offsetMs) {
        return align(lines, heard, offsetMs, null);
    }

    /**
     * Places every word of {@code lines} on the heard words: Needleman-Wunsch over both word lists, where words count
     * as the same when spelled alike (Whisper writes sung words a little differently). Where Whisper missed several
     * words in a row (an intro line, a verse it took for music), {@code again} hears that stretch once more on its own.
     * A few words heard as other words take those words' times; words without a partner get times spread between
     * their neighbours. Empty when too little of the lyrics was heard.
     *
     * @param offsetMs song position of the first heard sample
     * @param again    listens to a stretch once more, or null
     */
    static Optional<Aligned> align(List<String> lines, List<Heard> heard, long offsetMs, Listener again) {
        List<int[]> place = new ArrayList<>();   // line, word in line
        List<String> lyric = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            String[] words = lines.get(i).trim().split("\\s+");
            for (int j = 0; j < words.length; j++) {
                String token = token(words[j]);
                if (!token.isEmpty()) {
                    place.add(new int[] {i, j});
                    lyric.add(token);
                }
            }
        }
        int n = lyric.size();
        if (n == 0) {
            return Optional.empty();
        }
        long[] times = new long[n];
        long[] misheard = new long[n];   // time of a different word heard in the same place
        Arrays.fill(times, -1);
        Arrays.fill(misheard, -1);
        match(lyric, 0, n, heard, times, misheard);
        if (heardShare(times) < MIN_MATCHED) {
            return Optional.empty();
        }
        if (again != null) {
            listenAgain(lyric, times, misheard, again);
        }
        double matched = heardShare(times);
        // A few words heard differently ("mir wird's" as "es ist") were still sung where they were heard; a long run
        // of different words is more likely paired up wrong and is spread between its neighbours instead.
        for (int k = 0; k < n; ) {
            int end = k;
            while (end < n && times[end] < 0 && misheard[end] >= 0) {
                end++;
            }
            if (end > k && end - k <= MAX_MISHEARD) {
                System.arraycopy(misheard, k, times, k, end - k);
            }
            k = Math.max(end, k + 1);
        }
        fillGaps(times);
        StringBuilder lrc = new StringBuilder(MARK).append('\n');
        int wordIndex = 0;
        for (int i = 0; i < lines.size(); i++) {
            String[] words = lines.get(i).trim().split("\\s+");
            StringBuilder line = new StringBuilder();
            long lineStart = -1;
            long last = -1;
            for (int j = 0; j < words.length; j++) {
                long time = -1;
                if (wordIndex < place.size() && place.get(wordIndex)[0] == i && place.get(wordIndex)[1] == j) {
                    time = times[wordIndex++];
                }
                time = time >= 0 ? time : last;
                if (time < 0) {
                    continue;
                }
                last = time;
                lineStart = lineStart < 0 ? time : lineStart;
                line.append(line.isEmpty() ? "" : " ").append('<').append(stamp(time + offsetMs)).append('>').append(words[j]);
            }
            if (lineStart >= 0) {
                lrc.append('[').append(stamp(lineStart + offsetMs)).append(']').append(line).append('\n');
            }
        }
        return Optional.of(new Aligned(lrc.toString(), matched));
    }

    /**
     * Needleman-Wunsch of the lyric words {@code from} to {@code to} on the heard words: the times of words heard alike
     * go to {@code times}, those of different words heard in the same place to {@code misheard}.
     */
    private static void match(List<String> lyric, int from, int to, List<Heard> heard, long[] times, long[] misheard) {
        List<Heard> said = heard.stream().filter(word -> !token(word.text()).isEmpty()).toList();
        int n = to - from;
        int m = said.size();
        if (n <= 0 || m == 0) {
            return;
        }
        String[] spoken = said.stream().map(word -> token(word.text())).toArray(String[]::new);
        float gap = -1f;
        float[][] score = new float[n + 1][m + 1];
        byte[][] move = new byte[n + 1][m + 1];
        for (int a = 1; a <= n; a++) {
            score[a][0] = gap * a;
            move[a][0] = 2;
        }
        for (int b = 1; b <= m; b++) {
            score[0][b] = gap * b;
            move[0][b] = 3;
        }
        for (int a = 1; a <= n; a++) {
            for (int b = 1; b <= m; b++) {
                float diagonal = score[a - 1][b - 1] + (similar(lyric.get(from + a - 1), spoken[b - 1]) ? 2f : -1f);
                float up = score[a - 1][b] + gap;
                float left = score[a][b - 1] + gap;
                float best = Math.max(diagonal, Math.max(up, left));
                score[a][b] = best;
                move[a][b] = (byte) (best == diagonal ? 1 : best == up ? 2 : 3);
            }
        }
        for (int a = n, b = m; a > 0 && b > 0; ) {
            if (move[a][b] == 1) {
                if (similar(lyric.get(from + a - 1), spoken[b - 1])) {
                    times[from + a - 1] = said.get(b - 1).startMs();
                } else {
                    misheard[from + a - 1] = said.get(b - 1).startMs();
                }
                a--;
                b--;
            } else if (move[a][b] == 2) {
                a--;
            } else {
                b--;
            }
        }
    }

    /**
     * Stretches of words the first listening missed are heard once more on their own, between the words heard around
     * them: Whisper on the whole song sometimes skips an intro line or takes a verse for music, and hears it on a short
     * cut. What it hears there has to lie between those neighbours.
     */
    private static void listenAgain(List<String> lyric, long[] times, long[] misheard, Listener again) {
        int n = times.length;
        int stretches = 0;
        for (int k = 0; k < n && stretches < AGAIN_MAX_STRETCHES; ) {
            if (times[k] >= 0) {
                k++;
                continue;
            }
            int end = k;
            while (end < n && times[end] < 0) {
                end++;
            }
            long before = k > 0 ? times[k - 1] : -1;
            long after = end < n ? times[end] : -1;
            long fromMs = before >= 0 ? before : Math.max(0, after - AGAIN_EDGE_MS);
            long toMs = after >= 0 ? after + 1_000 : before + AGAIN_EDGE_MS;
            if (end - k >= AGAIN_FROM_WORDS && (before >= 0 || after >= 0) && toMs - fromMs <= AGAIN_MAX_MS) {
                stretches++;
                Arrays.fill(misheard, k, end, -1);
                match(lyric, k, end, again.hear(fromMs, toMs), times, misheard);
                for (int i = k; i < end; i++) {
                    if (times[i] < fromMs || after >= 0 && times[i] > after) {
                        times[i] = -1;
                    }
                    if (misheard[i] < fromMs || after >= 0 && misheard[i] > after) {
                        misheard[i] = -1;
                    }
                }
            }
            k = end;
        }
    }

    private static double heardShare(long[] times) {
        return Arrays.stream(times).filter(time -> time >= 0).count() / (double) times.length;
    }

    /** Unheard words between heard ones are spread evenly; at the very start or end 350 ms apart. Never backwards. */
    static void fillGaps(long[] times) {
        int n = times.length;
        for (int k = 0; k < n; k++) {
            if (times[k] >= 0) {
                continue;
            }
            int before = k - 1;
            while (before >= 0 && times[before] < 0) {
                before--;
            }
            int after = k + 1;
            while (after < n && times[after] < 0) {
                after++;
            }
            if (before >= 0 && after < n) {
                times[k] = times[before] + (times[after] - times[before]) * (k - before) / (after - before);
            } else if (before >= 0) {
                times[k] = times[before] + 350L * (k - before);
            } else if (after < n) {
                times[k] = Math.max(0, times[after] - 350L * (after - k));
            }
        }
        for (int k = 1; k < n; k++) {
            times[k] = Math.max(times[k], times[k - 1]);
        }
    }

    /** What was heard, line by line as Whisper grouped it, for songs whose text nobody has. */
    static String transcript(List<List<Heard>> segments, long offsetMs) {
        StringBuilder lrc = new StringBuilder(MARK).append(" heard\n");
        for (List<Heard> segment : segments) {
            lrc.append('[').append(stamp(segment.get(0).startMs() + offsetMs)).append(']');
            for (int i = 0; i < segment.size(); i++) {
                lrc.append(i == 0 ? "" : " ").append('<').append(stamp(segment.get(i).startMs() + offsetMs)).append('>')
                        .append(segment.get(i).text());
            }
            lrc.append('\n');
        }
        return lrc.toString();
    }

    static String token(String text) {
        String lower = text.toLowerCase(Locale.ROOT).replace("ß", "ss");
        return Normalizer.normalize(lower, Normalizer.Form.NFC).replaceAll("[^\\p{L}\\p{N}]", "");
    }

    /** The same word, or spelled alike: at most about a third of the letters differ (for words of three or more). */
    static boolean similar(String a, String b) {
        if (a.equals(b)) {
            return true;
        }
        if (a.length() <= 2 || b.length() <= 2) {
            return false;
        }
        return 1.0 - distance(a, b) / (double) Math.max(a.length(), b.length()) >= 0.67;
    }

    private static int distance(String a, String b) {
        int[] previous = new int[b.length() + 1];
        int[] current = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + cost);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[b.length()];
    }

    static String stamp(long ms) {
        long clamped = Math.max(0, ms);
        return String.format(Locale.ROOT, "%02d:%02d.%03d", clamped / 60000, clamped / 1000 % 60, clamped % 1000);
    }
}
