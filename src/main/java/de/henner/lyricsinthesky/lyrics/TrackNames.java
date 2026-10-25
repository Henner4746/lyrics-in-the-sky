package de.henner.lyricsinthesky.lyrics;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Normalizes media-session metadata before a lyrics lookup. */
final class TrackNames {
    // Amazon Music appends "[Explicit]"/"[Clean]"; featured artists are often part of the title.
    private static final Pattern EXPLICIT = Pattern.compile("\\s*[\\[(](explicit|clean)[\\])]", Pattern.CASE_INSENSITIVE);
    private static final Pattern FEATURING = Pattern.compile("\\s*[\\[(](feat\\.?|ft\\.?|featuring)\\s[^\\])]*[\\])]", Pattern.CASE_INSENSITIVE);
    private static final Pattern ARTIST_SEPARATOR = Pattern.compile("\\s*(,|&|;| x | feat\\.? | ft\\.? )\\s*", Pattern.CASE_INSENSITIVE);
    // Version notes the stores add to a title: " - Remastered 2011", " - Single Version", "(2009 Remaster)", " - Radio Edit".
    private static final String VERSION_WORDS =
            "remaster(?:ed)?|version|edit|mix|mono|stereo|single|radio|deluxe|bonus|edition|anniversary|from";
    private static final Pattern DASH_NOTE = Pattern.compile("\\s+-\\s+[^-]*\\b(?:" + VERSION_WORDS + ")\\b[^-]*$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern BRACKET_NOTE = Pattern.compile("\\s*[\\[(][^\\])]*\\b(?:" + VERSION_WORDS + ")\\b[^\\])]*[\\])]",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern BRACKETS = Pattern.compile("\\s*[\\[(][^\\])]*[\\])]");
    private static final Pattern NON_WORD = Pattern.compile("[^\\p{L}\\p{N}]+");

    private TrackNames() {}

    /**
     * Titles to try one after the other: as cleaned, then without version notes, then only the part before a dash and
     * outside brackets. The length check on every result keeps a live or edited version from being taken.
     */
    static List<String> titleVariants(String title) {
        Set<String> variants = new LinkedHashSet<>();
        String clean = cleanTitle(title);
        variants.add(clean);
        String noVersion = BRACKET_NOTE.matcher(DASH_NOTE.matcher(clean).replaceAll("")).replaceAll("").trim();
        variants.add(noVersion);
        variants.add(BRACKETS.matcher(noVersion.split("\\s+-\\s+")[0]).replaceAll("").trim());
        variants.removeIf(String::isBlank);
        return List.copyOf(variants);
    }

    /** Lower-case words without punctuation, for comparing names written slightly differently. */
    static List<String> words(String text) {
        return Arrays.stream(NON_WORD.split(text.toLowerCase(Locale.ROOT))).filter(word -> !word.isEmpty()).toList();
    }

    /**
     * Whether a result of a free search is the song asked for: at least half the title's words and one of the
     * artist's appear in it.
     */
    static boolean sameSong(String title, String artist, String resultTitle, String resultArtist) {
        List<String> wanted = words(title);
        List<String> got = words(resultTitle);
        long shared = wanted.stream().filter(got::contains).count();
        List<String> artistWords = words(artist);
        List<String> resultArtistWords = words(resultArtist);
        return !wanted.isEmpty() && shared * 2 >= wanted.size()
                && artistWords.stream().anyMatch(resultArtistWords::contains);
    }

    /** Drops explicit markers and featured artists, keeps remix/live/version markers. */
    static String cleanTitle(String title) {
        String cleaned = EXPLICIT.matcher(title).replaceAll("");
        cleaned = FEATURING.matcher(cleaned).replaceAll("");
        return cleaned.trim();
    }

    static String primaryArtist(String artist) {
        String[] parts = ARTIST_SEPARATOR.split(artist, 2);
        return parts.length > 0 && !parts[0].isBlank() ? parts[0].trim() : artist.trim();
    }
}
