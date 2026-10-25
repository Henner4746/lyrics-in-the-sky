package de.henner.lyricsinthesky.nowplaying;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class PreferredSourceTest {
    private static final NowPlaying SPOTIFY_PLAYING = track("Spotify", true);
    private static final NowPlaying SPOTIFY_PAUSED = track("Spotify", false);
    private static final NowPlaying AMAZON_PLAYING = track("Amazon", true);
    private static final NowPlaying AMAZON_PAUSED = track("Amazon", false);

    private static NowPlaying track(String app, boolean playing) {
        return new NowPlaying(app, "t", "a", "b", playing, 0, 1000, 0);
    }

    private static NowPlaying pick(NowPlaying account, NowPlaying os) {
        return new PreferredNowPlayingSource(fixed(account), fixed(os)).current();
    }

    private static NowPlayingSource fixed(NowPlaying value) {
        return new NowPlayingSource() {
            @Override
            public void start() {}

            @Override
            public void stop() {}

            @Override
            public NowPlaying current() {
                return value;
            }
        };
    }

    @Test
    void playingWinsOverPaused() {
        assertEquals(AMAZON_PLAYING, pick(SPOTIFY_PAUSED, AMAZON_PLAYING));
        assertEquals(SPOTIFY_PLAYING, pick(SPOTIFY_PLAYING, AMAZON_PAUSED));
    }

    @Test
    void accountWinsWhenBothPlayOrBothPause() {
        assertEquals(SPOTIFY_PLAYING, pick(SPOTIFY_PLAYING, AMAZON_PLAYING));
        assertEquals(SPOTIFY_PAUSED, pick(SPOTIFY_PAUSED, AMAZON_PAUSED));
    }

    @Test
    void fallsBackWhenOneIsMissing() {
        assertEquals(AMAZON_PAUSED, pick(null, AMAZON_PAUSED));
        assertNull(pick(null, null));
    }
}
