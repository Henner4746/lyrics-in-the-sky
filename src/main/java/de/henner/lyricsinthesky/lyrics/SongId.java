package de.henner.lyricsinthesky.lyrics;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * An anonymous name for a song together with the lyrics shown for it: a hash of the artist, the title and the first
 * timed lines. Timing corrections belong to exactly these lyrics; when the lyrics change, for example because someone
 * fixed them on LRCLIB, the name changes and old corrections no longer apply.
 */
public final class SongId {
    /** Lines that make up the fingerprint of the lyrics; enough to tell two timings of a song apart. */
    private static final int FINGERPRINT_LINES = 8;

    private SongId() {}

    public static String of(String artist, String title, Lyrics lyrics) {
        StringBuilder text = new StringBuilder();
        text.append(String.join(" ", TrackNames.words(TrackNames.primaryArtist(artist)))).append('\n');
        var titles = TrackNames.titleVariants(title);
        text.append(String.join(" ", TrackNames.words(titles.get(titles.size() - 1)))).append('\n');
        int taken = 0;
        for (Lyrics.Line line : lyrics.lines()) {
            if (line.text().isBlank()) {
                continue;
            }
            // Tenths of a second: the same lyrics give the same name however the LRC writes its times.
            text.append(Math.round(line.startMs() / 100.0)).append(':')
                    .append(String.join(" ", TrackNames.words(line.text()))).append('\n');
            if (++taken == FINGERPRINT_LINES) {
                break;
            }
        }
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(text.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
