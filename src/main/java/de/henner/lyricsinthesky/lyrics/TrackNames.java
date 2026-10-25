package de.henner.lyricsinthesky.lyrics;

import java.util.regex.Pattern;

/** Normalizes media-session metadata before a lyrics lookup. */
final class TrackNames {
    // Amazon Music appends "[Explicit]"/"[Clean]"; featured artists are often part of the title.
    private static final Pattern EXPLICIT = Pattern.compile("\\s*[\\[(](explicit|clean)[\\])]", Pattern.CASE_INSENSITIVE);
    private static final Pattern FEATURING = Pattern.compile("\\s*[\\[(](feat\\.?|ft\\.?|featuring)\\s[^\\])]*[\\])]", Pattern.CASE_INSENSITIVE);
    private static final Pattern ARTIST_SEPARATOR = Pattern.compile("\\s*(,|&|;| x | feat\\.? | ft\\.? )\\s*", Pattern.CASE_INSENSITIVE);

    private TrackNames() {}

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
