package de.henner.lyricsinthesky.lyrics;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Streamer mode: explicit words in lyrics, titles and artists shown as their first letter and stars ("F***"). A hidden
 * word keeps its length and the words around it stay where they are, so word times and the karaoke fill still fit.
 * German and English; words that are harmless in the other language ("dick" is "thick" in German) count only in songs
 * sung in their language.
 */
public final class Explicit {
    /** Explicit wherever they appear in a word ("Motherfucker", "Riesenarschloch"). Lower case, "ß" as "ss". */
    private static final List<String> ANYWHERE = List.of("fuck", "fick", "fotze", "votze", "wichs", "hurensohn",
            "nigga", "nigger", "bitch", "shit", "asshole", "arschloch", "dickhead", "cocksuck", "schwuchtel",
            "missgeburt", "pimmel", "titten", "whore", "porno", "schlampe", "kanake");
    /** Explicit at the start of a word only: "Arschgeweih", but not "Marsch". */
    private static final List<String> START = List.of("arsch", "scheiss", "hure", "nutte", "bums", "schwanz", "muschi",
            "piss", "kack", "spast", "wix", "cunt", "slut", "wank", "jizz", "bastard", "faggot", "pussy", "pussies",
            "sperma", "möse", "neger");
    /** Explicit as whole words in any song. */
    private static final Set<String> WHOLE = Set.of("ass", "asses", "tits", "titties", "fag", "fags", "milf", "bumms");
    /** Explicit as whole words in English songs only; German words spelled the same are harmless. */
    private static final Set<String> WHOLE_ENGLISH = Set.of("dick", "dicks", "cock", "cocks", "cum");
    private static final Set<String> ENGLISH_WORDS = Set.of("the", "and", "you", "i", "me", "my", "it", "is", "to", "of",
            "that", "your", "on", "we", "be", "for", "what", "with", "don't", "can't", "i'm", "it's", "this");
    private static final Set<String> GERMAN_WORDS = Set.of("der", "die", "das", "und", "ich", "du", "nicht", "ist",
            "ein", "eine", "mit", "mich", "dich", "mir", "dir", "wir", "sie", "es", "zu", "auf", "den", "im", "was");
    private static final Pattern LETTERS = Pattern.compile("\\p{L}+");
    private static final Pattern WORD = Pattern.compile("[\\p{L}']+");

    /** A filter for text of unknown language: only the words explicit in both languages. */
    public static final Explicit ANY = new Explicit(false);

    private final boolean english;

    private Explicit(boolean english) {
        this.english = english;
    }

    /** The filter for these lyrics: English words count too when the song is sung in English. */
    public static Explicit forLyrics(Lyrics lyrics) {
        int en = 0;
        int de = 0;
        for (Lyrics.Line line : lyrics.lines()) {
            Matcher words = WORD.matcher(line.text().toLowerCase(Locale.ROOT));
            while (words.find()) {
                en += ENGLISH_WORDS.contains(words.group()) ? 1 : 0;
                de += GERMAN_WORDS.contains(words.group()) ? 1 : 0;
            }
        }
        return en > de ? new Explicit(true) : ANY;
    }

    /** Whether a run of letters is explicit. */
    public boolean explicit(String letters) {
        String word = letters.toLowerCase(Locale.ROOT).replace("ß", "ss");
        if (WHOLE.contains(word) || english && WHOLE_ENGLISH.contains(word)) {
            return true;
        }
        for (String start : START) {
            if (word.startsWith(start)) {
                return true;
            }
        }
        for (String part : ANYWHERE) {
            if (word.contains(part)) {
                return true;
            }
        }
        return false;
    }

    /** The text with every explicit run of letters hidden; everything else, spaces and punctuation, as it was. */
    public String hide(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        Matcher runs = LETTERS.matcher(text);
        StringBuilder out = null;
        int done = 0;
        while (runs.find()) {
            if (!explicit(runs.group())) {
                continue;
            }
            if (out == null) {
                out = new StringBuilder(text.length());
            }
            out.append(text, done, runs.start());
            int first = text.codePointAt(runs.start());
            out.appendCodePoint(first);
            int letters = runs.group().codePointCount(0, runs.group().length());
            out.append("*".repeat(letters - 1));
            done = runs.end();
        }
        return out == null ? text : out.append(text, done, text.length()).toString();
    }
}
