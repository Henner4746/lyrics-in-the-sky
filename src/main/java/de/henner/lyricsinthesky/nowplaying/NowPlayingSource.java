package de.henner.lyricsinthesky.nowplaying;

import java.nio.file.Path;
import java.util.Locale;

/** Where "now playing" comes from: the operating system's media session. */
public interface NowPlayingSource {
    void start();

    void stop();

    /** Latest snapshot, or {@code null} when nothing is playing or the source is not running. */
    NowPlaying current();

    /** Another player sounding at the same time as {@link #current()} (a video beside the music), or {@code null}. */
    default String otherPlaying() {
        return null;
    }

    /** The source for this OS, or {@code null} when the OS is not supported yet. */
    static NowPlayingSource forCurrentOs(Path workDir) {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.startsWith("windows")) {
            return new SmtcNowPlayingSource(workDir);
        }
        if (os.contains("linux") || os.contains("bsd")) {
            return new MprisNowPlayingSource();
        }
        return null;
    }
}
