package de.henner.lyricsinthesky.nowplaying;

/**
 * The song position the lyrics follow: a clock that runs on its own and is pulled gently towards what the player
 * reports, instead of jumping to every report.
 *
 * <p>Reports from the media session and the Spotify account each differ by a few dozen milliseconds, and the two
 * take turns. Followed directly, the position would jitter back and forth, and a word sung right at that moment
 * could vanish and come back. This clock never runs backwards while a song plays: a report that is behind only
 * slows it down for a moment. A real jump – skipping within the song, a new song – is taken over at once.
 */
public final class PlaybackClock {
    /** A report this far from the clock is a skip or a new song, not jitter: the clock jumps to it. */
    static final long JUMP_MS = 1_200;
    /** Share of the remaining difference closed per second: jitter is gone within a quarter second or so. */
    static final double CATCH_UP_PER_S = 4.0;

    private String key;
    private double positionMs;
    private long lastNowMs;
    private boolean wasPlaying;
    private boolean started;

    /**
     * @param key        the song; a different one starts the clock afresh
     * @param nowMs      wall-clock time of this frame
     * @param reportedMs position the player reports for {@code nowMs}, already extrapolated from its last report
     * @param playing    whether the song is playing; while paused the clock stands at the reported position
     * @return the position to show, in milliseconds
     */
    public synchronized long at(String key, long nowMs, long reportedMs, boolean playing) {
        if (!started || !key.equals(this.key) || nowMs < lastNowMs) {
            return jump(key, nowMs, reportedMs, playing);
        }
        long dt = nowMs - lastNowMs;
        // The time since the last frame only counts if the song was already playing then.
        double predicted = positionMs + (playing && wasPlaying ? dt : 0);
        double error = reportedMs - predicted;
        if (!playing || Math.abs(error) > JUMP_MS) {
            return jump(key, nowMs, reportedMs, playing);
        }
        lastNowMs = nowMs;
        wasPlaying = true;
        double next = predicted + error * Math.min(1.0, CATCH_UP_PER_S * dt / 1000.0);
        positionMs = Math.max(positionMs, next);
        return Math.round(positionMs);
    }

    private long jump(String key, long nowMs, long reportedMs, boolean playing) {
        this.key = key;
        positionMs = reportedMs;
        lastNowMs = nowMs;
        wasPlaying = playing;
        started = true;
        return reportedMs;
    }
}
