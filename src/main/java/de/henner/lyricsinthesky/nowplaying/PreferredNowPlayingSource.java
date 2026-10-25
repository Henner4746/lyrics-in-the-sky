package de.henner.lyricsinthesky.nowplaying;

/**
 * Combines the Spotify account with the operating system's media session: whichever is playing wins, and
 * when both are, the Spotify account (exact position, works across devices).
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
            return first;
        }
        if (second != null && second.playing()) {
            return second;
        }
        return first != null ? first : second;
    }
}
