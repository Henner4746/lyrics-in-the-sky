package de.henner.lyricsinthesky.hoergenau;

import de.henner.lyricsinthesky.hoergenau.WhisperEngine.Heard;
import java.text.Normalizer;
import java.util.ArrayList;
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

    record Aligned(String lrc, double matched) {}

    private HeardLyrics() {}

    /**
     * Places every word of {@code lines} on the heard words: Needleman-Wunsch over both word lists, where words count
     * as the same when spelled alike (Whisper writes sung words a little differently). Words without a partner get
     * times spread between their neighbours. Empty when too little of the lyrics was heard.
     *
     * @param offsetMs song position of the first heard sample
     */
    static Optional<Aligned> align(List<String> lines, List<Heard> heard, long offsetMs) {
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
        List<Heard> said = heard.stream().filter(word -> !token(word.text()).isEmpty()).toList();
        int n = lyric.size();
        int m = said.size();
        if (n == 0 || m == 0) {
            return Optional.empty();
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
                float diagonal = score[a - 1][b - 1] + (similar(lyric.get(a - 1), spoken[b - 1]) ? 2f : -1f);
                float up = score[a - 1][b] + gap;
                float left = score[a][b - 1] + gap;
                float best = Math.max(diagonal, Math.max(up, left));
                score[a][b] = best;
                move[a][b] = (byte) (best == diagonal ? 1 : best == up ? 2 : 3);
            }
        }
        long[] times = new long[n];
        java.util.Arrays.fill(times, -1);
        for (int a = n, b = m; a > 0 && b > 0; ) {
            if (move[a][b] == 1) {
                if (similar(lyric.get(a - 1), spoken[b - 1])) {
                    times[a - 1] = said.get(b - 1).startMs();
                }
                a--;
                b--;
            } else if (move[a][b] == 2) {
                a--;
            } else {
                b--;
            }
        }
        int known = 0;
        for (long time : times) {
            known += time >= 0 ? 1 : 0;
        }
        double matched = known / (double) n;
        if (matched < MIN_MATCHED) {
            return Optional.empty();
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
