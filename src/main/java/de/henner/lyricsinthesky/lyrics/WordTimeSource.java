package de.henner.lyricsinthesky.lyrics;

import de.henner.lyricsinthesky.nowplaying.NowPlaying;
import java.io.IOException;
import java.util.Optional;

/**
 * A service asked for word times when the player switched on "Wortgenau". All of them are unofficial; they only get
 * the song's title, artist, album and length, and what they send back is checked by {@link WordTimeTransfer} before it
 * is shown over LRCLIB's lines.
 */
public interface WordTimeSource {
    /** Short name for the log and the cache, e.g. "NetEase". */
    String name();

    /** Word-timed lyrics of the song, empty when the service has none; throws when it could not be asked. */
    Optional<TimedWords> find(NowPlaying track) throws IOException, InterruptedException;
}
