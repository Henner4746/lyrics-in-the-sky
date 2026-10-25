package de.henner.lyricsinthesky.lyrics;

import java.util.ArrayList;
import java.util.Arrays;
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

    private Lyrics(List<Line> lines, boolean instrumental) {
        this.lines = List.copyOf(lines);
        this.instrumental = instrumental;
    }

    public static Lyrics instrumental() {
        return new Lyrics(List.of(), true);
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
        return new Lyrics(shifted, false);
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
     * in proportion to word length.
     */
    public long[] wordTimes(int index, long durationMs) {
        Line line = lines.get(index);
        String[] words = line.words();
        long[] times = new long[words.length];
        if (words.length == 0) {
            return times;
        }
        long end = endOf(index, durationMs);
        // Singers usually finish before the next line starts; long gaps are capped by character count.
        long sung = Math.min((long) ((end - line.startMs()) * 0.8), Math.max(600, line.text().length() * 160L));
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
