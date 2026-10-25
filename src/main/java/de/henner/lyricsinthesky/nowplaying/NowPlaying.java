package de.henner.lyricsinthesky.nowplaying;

/**
 * One snapshot of the OS media session.
 *
 * @param positionMs  playback position the media app last reported
 * @param updatedAtMs wall-clock time (epoch ms) at which that position was valid
 */
public record NowPlaying(
        String app,
        String title,
        String artist,
        String album,
        boolean playing,
        long positionMs,
        long durationMs,
        long updatedAtMs) {

    /** Same song as {@code other}, ignoring position and play state. */
    public boolean sameTrack(NowPlaying other) {
        return other != null
                && title.equals(other.title)
                && artist.equals(other.artist)
                && album.equals(other.album);
    }

    /**
     * Current position, extrapolated from the last report. Apps update the session timeline only every
     * few seconds or on seek, so between reports we advance the clock ourselves.
     */
    public long estimatePositionMs(long nowMs) {
        long position = playing ? positionMs + Math.max(0, nowMs - updatedAtMs) : positionMs;
        return durationMs > 0 ? Math.min(position, durationMs) : position;
    }
}
