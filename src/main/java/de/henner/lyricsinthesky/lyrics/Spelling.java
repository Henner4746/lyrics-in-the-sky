package de.henner.lyricsinthesky.lyrics;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Mends the spelling of word-timed lyrics from a service with LRCLIB's text of the same song. QQ Music, read through
 * LyricsPlus, loses every umlaut together with the letter after it ("ff" for "fünf", "z lt" for "zählt"), spells others
 * plainly ("Funf", "heiss") and writes German nouns small ("das licht"). The words keep their times; a mangled word only
 * ever becomes a word LRCLIB has, and only when the service's text has lost all such letters while LRCLIB's has them.
 */
public final class Spelling {
    /** A mangled form shorter than this could stand for too many words. */
    static final int MIN_FORM = 2;
    /** Words LRCLIB writes with letters beyond plain a-z before a service without any counts as having lost them. */
    static final int MIN_FOREIGN_WORDS = 2;
    /** Nouns are written big as in LRCLIB only when the service writes at least this many of them small. */
    static final int MIN_SMALL_NOUNS = 3;

    /** Every word of LRCLIB's text, as {@link WordTimeTransfer#normalize} makes it. */
    private final Set<String> known = new HashSet<>();
    /** What a service may have made of an LRCLIB word, to that word in small letters; "" when two words share it. */
    private final Map<String, String> mangled = new HashMap<>();
    private final Set<String> seenSmall = new HashSet<>();
    private final Set<String> seenBigInside = new HashSet<>();
    private final Set<String> foreign = new HashSet<>();

    private Spelling(Lyrics base) {
        for (Lyrics.Line line : base.lines()) {
            String[] words = line.words();
            for (int i = 0; i < words.length; i++) {
                Word word = Word.of(words[i]);
                if (word.form().isEmpty()) {
                    continue;
                }
                known.add(word.form());
                if (Character.isLowerCase(word.core().codePointAt(0))) {
                    seenSmall.add(word.form());
                } else if (i > 0 && Character.isUpperCase(word.core().codePointAt(0))) {
                    seenBigInside.add(word.form());
                }
                String small = word.core().toLowerCase(Locale.ROOT);
                if (hasForeign(small)) {
                    foreign.add(word.form());
                    for (String form : mangledForms(small)) {
                        mangled.merge(form, small, (kept, next) -> kept.equals(next) ? kept : "");
                    }
                }
            }
        }
        mangled.keySet().removeAll(known);
    }

    /** {@code timed} with its words spelled as in {@code base} where the service mangled them; the same lyrics if none. */
    public static Lyrics mend(Lyrics timed, Lyrics base) {
        if (base == null || base.isEmpty() || timed.isEmpty() || !timed.hasWordTiming()) {
            return timed;
        }
        Spelling spelling = new Spelling(base);
        boolean letters = spelling.lostLetters(timed);
        boolean nouns = spelling.writesNounsSmall(timed);
        if (!letters && !nouns) {
            return timed;
        }
        StringBuilder lrc = new StringBuilder();
        String first = timed.lrc().lines().findFirst().orElse("");
        if (first.startsWith("[re:")) {
            lrc.append(first).append('\n');
        }
        boolean changed = false;
        for (Lyrics.Line line : timed.lines()) {
            String[] words = line.words();
            long[] starts = line.wordStartsMs();
            lrc.append('[').append(WordTimeTransfer.stamp(line.startMs())).append(']');
            for (int i = 0; i < words.length; i++) {
                long start = starts != null && i < starts.length ? starts[i] : -1;
                boolean lineStart = i == 0;
                Word word = Word.of(words[i]);
                String text = words[i];
                if (letters && !word.form().isEmpty() && !spelling.known.contains(word.form())) {
                    // A lost letter may have left a space behind ("f chtet" for "fürchtet"): the next word joins in
                    // and its time goes, the word starts when its first part does.
                    Word next = i + 1 < words.length ? Word.of(words[i + 1]) : null;
                    String joined = next == null ? null : spelling.correct(word.form() + next.form());
                    String single = spelling.correct(word.form());
                    if (joined != null) {
                        text = word.prefix() + spelling.cased(joined, word.core(), lineStart) + next.suffix();
                        i++;
                    } else if (single != null) {
                        text = word.prefix() + spelling.cased(single, word.core(), lineStart) + word.suffix();
                    }
                } else if (nouns && !lineStart && !word.form().isEmpty() && spelling.bigOnly(word.form())
                        && Character.isLowerCase(word.core().codePointAt(0))) {
                    text = word.prefix() + capitalized(word.core()) + word.suffix();
                }
                changed |= !text.equals(words[i]);
                if (!lineStart) {
                    lrc.append(' ');
                }
                if (start >= 0) {
                    lrc.append('<').append(WordTimeTransfer.stamp(start)).append('>');
                }
                lrc.append(text);
            }
            lrc.append('\n');
        }
        return changed ? Lyrics.parseLrc(lrc.toString()) : timed;
    }

    /** Whether the service wrote none of the letters beyond a-z that LRCLIB's text of the song has. */
    private boolean lostLetters(Lyrics timed) {
        if (foreign.size() < MIN_FOREIGN_WORDS) {
            return false;
        }
        for (Lyrics.Line line : timed.lines()) {
            for (String word : line.words()) {
                if (hasForeign(word)) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Whether the service writes small inside lines what LRCLIB always writes big there, and hardly ever big. */
    private boolean writesNounsSmall(Lyrics timed) {
        Set<String> small = new LinkedHashSet<>();
        Set<String> big = new LinkedHashSet<>();
        for (Lyrics.Line line : timed.lines()) {
            String[] words = line.words();
            for (int i = 1; i < words.length; i++) {
                Word word = Word.of(words[i]);
                if (!word.form().isEmpty() && bigOnly(word.form())) {
                    (Character.isLowerCase(word.core().codePointAt(0)) ? small : big).add(word.form());
                }
            }
        }
        return small.size() >= MIN_SMALL_NOUNS && small.size() > 3 * big.size();
    }

    /** LRCLIB writes the word big inside lines and never small: a German noun or a name. */
    private boolean bigOnly(String form) {
        return seenBigInside.contains(form) && !seenSmall.contains(form);
    }

    /** The LRCLIB word, in small letters, a mangled form stands for; {@code null} for none or several. */
    private String correct(String form) {
        String word = mangled.get(form);
        return word == null || word.isEmpty() ? null : word;
    }

    /** The LRCLIB word written big where the service wrote it big or where LRCLIB always does inside a line. */
    private String cased(String small, String serviceCore, boolean lineStart) {
        boolean big = Character.isUpperCase(serviceCore.codePointAt(0))
                || !lineStart && bigOnly(WordTimeTransfer.normalize(small));
        return big ? capitalized(small) : small;
    }

    /** What a service losing letters beyond a-z may have made of {@code small}: each such letter dropped, dropped
     * together with the letter after it, spelled plainly ("u") or spelled out ("ue"). */
    static Set<String> mangledForms(String small) {
        StringBuilder dropped = new StringBuilder();
        StringBuilder droppedWithNext = new StringBuilder();
        StringBuilder spelledOut = new StringBuilder();
        boolean skipNext = false;
        for (int point : small.codePoints().toArray()) {
            if (isForeign(point)) {
                spelledOut.append(spelledOut(point));
                skipNext = true;
                continue;
            }
            dropped.appendCodePoint(point);
            spelledOut.appendCodePoint(point);
            if (!skipNext || !Character.isLetter(point)) {
                droppedWithNext.appendCodePoint(point);
            }
            skipNext = false;
        }
        String plain = Normalizer.normalize(small, Normalizer.Form.NFD).replaceAll("\\p{M}", "").replace("ß", "ss");
        Set<String> forms = new LinkedHashSet<>();
        for (String form : List.of(dropped.toString(), droppedWithNext.toString(), plain, spelledOut.toString())) {
            String normal = WordTimeTransfer.normalize(form);
            if (normal.length() >= MIN_FORM && !hasForeign(normal)) {
                forms.add(normal);
            }
        }
        return forms;
    }

    private static String spelledOut(int point) {
        return switch (point) {
            case 'ä' -> "ae";
            case 'ö' -> "oe";
            case 'ü' -> "ue";
            case 'ß' -> "ss";
            default -> Normalizer.normalize(new String(Character.toChars(point)), Normalizer.Form.NFD)
                    .replaceAll("\\p{M}", "");
        };
    }

    private static boolean isForeign(int point) {
        return point > 0x7F && Character.isLetter(point);
    }

    private static boolean hasForeign(String text) {
        return text.codePoints().anyMatch(Spelling::isForeign);
    }

    private static String capitalized(String word) {
        int first = word.codePointAt(0);
        return new String(Character.toChars(Character.toUpperCase(first))) + word.substring(Character.charCount(first));
    }

    /** A word as written, split into what comes before its letters ("(", "„"), the word itself, and what follows. */
    private record Word(String prefix, String core, String suffix, String form) {
        static Word of(String raw) {
            int start = 0;
            int end = raw.length();
            while (start < end && !Character.isLetterOrDigit(raw.codePointAt(start))) {
                start += Character.charCount(raw.codePointAt(start));
            }
            while (end > start && !Character.isLetterOrDigit(raw.codePointBefore(end))) {
                end -= Character.charCount(raw.codePointBefore(end));
            }
            String core = raw.substring(start, end);
            return new Word(raw.substring(0, start), core, raw.substring(end), WordTimeTransfer.normalize(core));
        }
    }
}
