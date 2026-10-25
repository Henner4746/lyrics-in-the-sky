package de.henner.lyricsinthesky.lyrics;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Line-synced lyrics parsed from LRC text, sorted by start time. */
public final class Lyrics {
    /** One line timestamp such as {@code [01:23.45]}; fraction may have 1-3 digits. */
    private static final Pattern TIME_TAG = Pattern.compile("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?]");
    /** Enhanced LRC word timestamp such as {@code <01:23.45>}. */
    private static final Pattern WORD_TAG = Pattern.compile("<(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?>");
    private static final Pattern OFFSET_TAG = Pattern.compile("\\[offset:\\s*([+-]?\\d+)\\s*]", Pattern.CASE_INSENSITIVE);
    /**
     * Singing pace when a song has too few lines to measure its own: the middle of what LRCLIB lyrics show, from
     * about 50 ms per character (rap) to 130 (ballads).
     */
    static final long DEFAULT_MS_PER_CHAR = 70;

    /**
     * @param wordStartsMs start time per whitespace-separated word of {@code text}, or {@code null} when the
     *                     source only has line timing; entries without their own tag are -1
     */
    public record Line(long startMs, String text, long[] wordStartsMs) {
        public String[] words() {
            String trimmed = text.trim();
            return trimmed.isEmpty() ? new String[0] : trimmed.split("\\s+");
        }
    }

    private final List<Line> lines;
    private final boolean instrumental;
    /** How fast this song is sung, for spreading words over a line that has no word timing. */
    private final long msPerChar;
    /** The LRC text these lyrics were parsed from, empty for an instrumental. */
    private final String lrc;
    /** Times spread over the song from plain text, so only roughly where the lines are sung. */
    private final boolean estimated;
    /** First line of LRC whose times were estimated rather than timed by anyone. */
    static final String ESTIMATED_MARK = "[re:estimated]";

    private Lyrics(List<Line> lines, boolean instrumental, String lrc) {
        this.lines = List.copyOf(lines);
        this.instrumental = instrumental;
        this.msPerChar = measurePace(this.lines);
        this.lrc = lrc;
        this.estimated = lrc.startsWith(ESTIMATED_MARK);
    }

    /**
     * Plain lyrics without any times, spread over the sung part of the song in proportion to the length of each line.
     * Better than nothing when no one has timed the song, but only roughly where the lines are sung. Section labels
     * such as "[Chorus]" are left out.
     */
    public static Lyrics estimated(String plain, long durationMs) {
        List<String> text = plain.lines().map(String::strip)
                .filter(line -> !line.isEmpty() && !line.matches("[\\[(].*[\\])]"))
                .toList();
        if (text.isEmpty() || durationMs <= 0) {
            return parseLrc("");
        }
        // Most songs start and end without singing; a short song gets less of that.
        long margin = Math.max(4_000, durationMs * 6 / 100);
        long start = Math.min(margin, durationMs / 4);
        long end = Math.max(start + text.size() * 1_000L, durationMs - margin);
        long total = text.stream().mapToLong(line -> line.length() + 12).sum();
        StringBuilder lrc = new StringBuilder(ESTIMATED_MARK).append('\n');
        long weight = 0;
        for (String line : text) {
            lrc.append('[').append(WordTimeTransfer.stamp(start + (end - start) * weight / total)).append(']')
                    .append(line).append('\n');
            weight += line.length() + 12;
        }
        return parseLrc(lrc.toString());
    }

    public boolean isEstimated() {
        return estimated;
    }

    /**
     * Milliseconds per character of this song's singing. A line sung right before the next one shows the pace in
     * its gap; the gaps after other lines also hold breaks, so the pace is a low quantile of all gaps.
     */
    static long measurePace(List<Line> lines) {
        List<Double> perChar = new ArrayList<>();
        for (int i = 0; i + 1 < lines.size(); i++) {
            int chars = lines.get(i).text().length();
            long gap = lines.get(i + 1).startMs() - lines.get(i).startMs();
            if (chars >= 8 && gap > 0) {
                perChar.add(gap / (double) chars);
            }
        }
        if (perChar.size() < 4) {
            return DEFAULT_MS_PER_CHAR;
        }
        Collections.sort(perChar);
        double pace = perChar.get(perChar.size() / 4);
        return Math.round(Math.max(40, Math.min(140, pace)));
    }

    public static Lyrics instrumental() {
        return new Lyrics(List.of(), true, "");
    }

    public static Lyrics parseLrc(String lrc) {
        List<Line> lines = new ArrayList<>();
        long offsetMs = 0;
        for (String raw : lrc.split("\\R")) {
            Matcher offset = OFFSET_TAG.matcher(raw);
            if (offset.find()) {
                offsetMs = Long.parseLong(offset.group(1));
                continue;
            }
            // A line may carry several timestamps when the same text repeats.
            Matcher tag = TIME_TAG.matcher(raw);
            List<Long> starts = new ArrayList<>();
            int textStart = 0;
            while (tag.find() && tag.start() == textStart) {
                starts.add(toMillis(tag));
                textStart = tag.end();
            }
            if (starts.isEmpty()) {
                continue;
            }
            String body = raw.substring(textStart);
            String text = WORD_TAG.matcher(body).replaceAll("").trim().replaceAll("\\s+", " ");
            long[] wordStarts = wordStarts(body, text);
            for (long start : starts) {
                lines.add(new Line(start, text, wordStarts));
            }
        }
        // LRC offset: positive values make lyrics appear earlier.
        List<Line> shifted = new ArrayList<>(lines.size());
        for (Line line : lines) {
            long[] words = line.wordStartsMs();
            if (words != null && offsetMs != 0) {
                long shift = offsetMs;
                words = Arrays.stream(words).map(time -> time < 0 ? time : Math.max(0, time - shift)).toArray();
            }
            shifted.add(new Line(Math.max(0, line.startMs() - offsetMs), line.text(), words));
        }
        shifted.sort((a, b) -> Long.compare(a.startMs(), b.startMs()));
        return new Lyrics(shifted, false, lrc);
    }

    /** The LRC text these lyrics were parsed from, empty for an instrumental. */
    public String lrc() {
        return lrc;
    }

    /** Word times from enhanced LRC tags, or {@code null} when the line has none. */
    private static long[] wordStarts(String body, String text) {
        Matcher tag = WORD_TAG.matcher(body);
        if (!tag.find()) {
            return null;
        }
        String[] words = text.isEmpty() ? new String[0] : text.split(" ");
        long[] starts = new long[words.length];
        Arrays.fill(starts, -1);
        // Walk the raw body: the last tag before a word's first character is that word's start.
        int wordIndex = 0;
        long pending = -1;
        boolean inWord = false;
        int position = 0;
        while (position < body.length() && wordIndex < words.length) {
            if (tag.find(position) && tag.start() == position) {
                pending = toMillis(tag);
                position = tag.end();
                continue;
            }
            if (Character.isWhitespace(body.charAt(position))) {
                if (inWord) {
                    wordIndex++;
                    inWord = false;
                }
            } else if (!inWord) {
                inWord = true;
                if (pending >= 0) {
                    starts[wordIndex] = pending;
                    pending = -1;
                }
            }
            position++;
        }
        return starts;
    }

    private static long toMillis(Matcher tag) {
        long minutes = Long.parseLong(tag.group(1));
        long seconds = Long.parseLong(tag.group(2));
        String fraction = tag.group(3);
        long millis = 0;
        if (fraction != null) {
            // "5" means 500 ms, "45" means 450 ms, "456" means 456 ms.
            millis = Long.parseLong((fraction + "00").substring(0, 3));
        }
        return (minutes * 60 + seconds) * 1000 + millis;
    }

    long msPerChar() {
        return msPerChar;
    }

    /** Whether any line has its own word timestamps (enhanced LRC), so words are exact rather than estimated. */
    public boolean hasWordTiming() {
        return lines.stream().anyMatch(line -> line.wordStartsMs() != null);
    }

    public boolean isInstrumental() {
        return instrumental;
    }

    public boolean isEmpty() {
        return lines.isEmpty();
    }

    public List<Line> lines() {
        return lines;
    }

    /** Index of the line playing at {@code positionMs}, or -1 before the first line. */
    public int indexAt(long positionMs) {
        int low = 0;
        int high = lines.size() - 1;
        int found = -1;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            if (lines.get(mid).startMs() <= positionMs) {
                found = mid;
                low = mid + 1;
            } else {
                high = mid - 1;
            }
        }
        return found;
    }

    /**
     * When the line stops being shown. LRC has no end times, so this is the next line's start, capped so a
     * line does not hang in the sky through a long instrumental break.
     */
    public long endOf(int index, long durationMs) {
        Line line = lines.get(index);
        long cap = line.startMs() + Math.max(5_000, line.text().length() * 140L + 2_500);
        long next = index + 1 < lines.size() ? lines.get(index + 1).startMs() : Math.max(line.startMs() + 4_000, durationMs);
        return Math.min(next, cap);
    }

    /**
     * Start time of every word: exact for enhanced LRC, otherwise spread over the sung part of the line
     * in proportion to word length, at the pace this song is sung.
     */
    public long[] wordTimes(int index, long durationMs) {
        Line line = lines.get(index);
        String[] words = line.words();
        long[] times = new long[words.length];
        if (words.length == 0) {
            return times;
        }
        long end = endOf(index, durationMs);
        // A line is sung at the song's pace; a break before the next line does not stretch it.
        long sung = Math.min((long) ((end - line.startMs()) * 0.92), Math.max(400, line.text().length() * msPerChar));
        int totalWeight = 0;
        for (String word : words) {
            totalWeight += word.length() + 1;
        }
        int weight = 0;
        for (int i = 0; i < words.length; i++) {
            times[i] = line.startMs() + sung * weight / totalWeight;
            weight += words[i].length() + 1;
        }
        long[] exact = line.wordStartsMs();
        if (exact != null) {
            for (int i = 0; i < Math.min(exact.length, times.length); i++) {
                if (exact[i] >= 0) {
                    times[i] = exact[i];
                }
            }
        }
        return times;
    }
}
