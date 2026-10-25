package de.henner.lyricsinthesky.nowplaying;

/**
 * Combines the Spotify account with the operating system's media session: whichever is playing wins, and
 * when both are, the Spotify account (exact position, works across devices). When both play the same song, the
 * position comes from whichever reported last, so a skip within the song shows up as soon as either source
 * notices it.
 */
public final class PreferredNowPlayingSource implements NowPlayingSource {
    private final NowPlayingSource preferred;
    private final NowPlayingSource fallback;

    public PreferredNowPlayingSource(NowPlayingSource preferred, NowPlayingSource fallback) {
        this.preferred = preferred;
        this.fallback = fallback;
    }

    @Override
    public void start() {
        preferred.start();
        fallback.start();
    }

    @Override
    public void stop() {
        preferred.stop();
        fallback.stop();
    }

    @Override
    public NowPlaying current() {
        NowPlaying first = preferred.current();
        NowPlaying second = fallback.current();
        if (first != null && first.playing()) {
            if (second != null && second.playing() && second.sameSong(first) && second.updatedAtMs() > first.updatedAtMs()) {
                // Keep the preferred source's names and length so the song never looks like a new one.
                return new NowPlaying(first.app(), first.title(), first.artist(), first.album(), true,
                        second.positionMs(), first.durationMs(), second.updatedAtMs());
            }
            return first;
        }
        if (second != null && second.playing()) {
            return second;
        }
        return first != null ? first : second;
    }
}
