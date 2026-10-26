package de.henner.lyricsinthesky.hoergenau;

import java.io.IOException;

/** What Hoergenau records with: the PC's sound on Windows ({@link LoopbackRecorder}), a recording played back in tests. */
interface Recorder {
    /** @param player the app playing the song as the media session names it, to hear only it; {@code null} for all */
    void start(String player) throws IOException;

    /** {@link System#nanoTime()} when the first sample came; 0 before. */
    long startNanos();

    /** Samples recorded so far, 16 kHz mono. */
    int count();

    /** Why recording failed, or {@code null}. */
    String failure();

    /** Whether only the player's own sound is recorded, so other players do not get into it; known once samples come. */
    default boolean onlyPlayer() {
        return false;
    }

    /** Stops and hands over what was recorded. */
    short[] stop();
}
