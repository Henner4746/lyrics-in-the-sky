package de.henner.lyricsinthesky.lyrics;

import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;
import java.util.regex.Pattern;

/**
 * Lyrics with a start time for every sung word, as one of the word time services timed them. They are only shown after
 * {@link WordTimeTransfer} checked that they were timed for the same recording as the LRCLIB lyrics.
 *
 * @param source where the words come from, e.g. "Apple" or "NetEase"
 */
public record TimedWords(String source, List<Line> lines) {
    /** Credit lines some services put before the lyrics, e.g. "作词 : …" or "Composed by: …". */
    private static final Pattern CREDIT = Pattern.compile(
            "^\\s*(作词|作曲|编曲|制作|监制|混音|录音|母带|和声|吉他|贝斯|鼓|词|曲|lyrics|lyricist|composer|composed|written|"
                    + "producer|produced|arranger|arranged|mixed|mastered)( by)?\\s*[:：]", Pattern.CASE_INSENSITIVE);

    /** A sung word and when it starts, in ms of the song as the service timed it. */
    public record Word(long startMs, String text) {}

    public record Line(long startMs, List<Word> words) {
        public String text() {
            StringJoiner text = new StringJoiner(" ");
            words.forEach(word -> text.add(word.text()));
            return text.toString();
        }
    }

    /**
     * Syllables joined into words, as the services time syllables: a syllable ending in a space ends its word, one
     * starting with a space begins a new one. A word starts with its first syllable.
     */
    static List<Word> joinSyllables(List<Word> syllables) {
        List<Word> words = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        long start = -1;
        for (Word syllable : syllables) {
            String text = syllable.text();
            if (!text.isEmpty() && Character.isWhitespace(text.charAt(0))) {
                add(words, start, current);
                start = -1;
            }
            if (start < 0) {
                start = syllable.startMs();
            }
            current.append(text);
            if (!text.isEmpty() && Character.isWhitespace(text.charAt(text.length() - 1))) {
                add(words, start, current);
                start = -1;
            }
        }
        add(words, start, current);
        return words;
    }

    /** The line of joined syllables, or {@code null} when it has no words or is a credit line. */
    static Line line(List<Word> syllables) {
        List<Word> words = joinSyllables(syllables);
        if (words.isEmpty()) {
            return null;
        }
        Line line = new Line(words.get(0).startMs(), words);
        return CREDIT.matcher(line.text()).find() ? null : line;
    }

    private static void add(List<Word> words, long start, StringBuilder text) {
        String word = text.toString().trim();
        text.setLength(0);
        if (start >= 0 && !word.isEmpty()) {
            // A syllable may hold several words ("The boy "), which then share its time.
            for (String part : word.split("\\s+")) {
                words.add(new Word(start, part));
            }
        }
    }
}
