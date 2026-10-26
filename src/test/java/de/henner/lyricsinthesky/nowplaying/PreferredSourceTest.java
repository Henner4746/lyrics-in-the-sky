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
    void aSkipSeenFirstByTheMediaSessionMovesThePositionAtOnce() {
        NowPlaying account = new NowPlaying("Spotify (Konto)", "Song", "Artist, Guest", "Album", true, 40_000, 200_000, 1_000);
        // The media session saw the skip to 1:30 half a second after the account's last poll.
        NowPlaying os = new NowPlaying("Spotify", "song", "Artist", "Album", true, 90_000, 199_000, 1_500);
        NowPlaying picked = pick(account, os);
        assertEquals(90_000, picked.positionMs());
        assertEquals(1_500, picked.updatedAtMs());
        // Names and length stay the account's, so the song does not look like a new one.
        assertEquals("Song", picked.title());
        assertEquals("Artist, Guest", picked.artist());
        assertEquals(200_000, picked.durationMs());
        // Older reports from the media session, or another song, leave the account alone.
        assertEquals(account, pick(account, new NowPlaying("Spotify", "Song", "Artist", "Album", true, 30_000, 200_000, 500)));
        assertEquals(account, pick(account, new NowPlaying("Amazon", "Other", "Artist", "Album", true, 90_000, 200_000, 1_500)));
    }

    /** A media session as on Windows: it names the app playing here. */
    private static NowPlayingSource session(NowPlaying value) {
        return new NowPlayingSource() {
            @Override
            public void start() {}

            @Override
            public void stop() {}

            @Override
            public NowPlaying current() {
                return value;
            }

            @Override
            public String localPlayer() {
                return value != null && value.playing() ? value.app() : null;
            }
        };
    }

    @Test
    void theLocalPlayerIsTheMediaSessionsAppWhenItPlaysTheSong() {
        NowPlaying account = new NowPlaying("Spotify (Konto)", "Song", "Artist", "Album", true, 40_000, 200_000, 1_000);
        NowPlaying here = new NowPlaying("SpotifyAB.SpotifyMusic_zpdnekdrzrea0!Spotify", "song", "Artist", "Album", true,
                40_000, 200_000, 1_000);
        NowPlaying video = new NowPlaying("Brave", "Ein Video", "Kanal", "", true, 10_000, 600_000, 1_000);
        assertEquals(here.app(), new PreferredNowPlayingSource(fixed(account), session(here)).localPlayer());
        // The account's song plays on a phone: what the PC plays is something else, and nothing is the player here.
        assertNull(new PreferredNowPlayingSource(fixed(account), session(video)).localPlayer());
        assertNull(new PreferredNowPlayingSource(fixed(account), session(null)).localPlayer());
        // Without the account playing, the media session's app.
        assertEquals("Brave", new PreferredNowPlayingSource(fixed(SPOTIFY_PAUSED), session(video)).localPlayer());
    }

    @Test
    void whatElseSoundsOnThisPcIsReported() {
        NowPlaying account = new NowPlaying("Spotify (Konto)", "Song", "Artist", "Album", true, 40_000, 200_000, 1_000);
        NowPlaying here = new NowPlaying("Spotify", "song", "Artist", "Album", true, 40_000, 200_000, 1_000);
        NowPlaying video = new NowPlaying("Brave", "Ein Video", "Kanal", "", true, 10_000, 600_000, 1_000);
        // The account's song plays on this PC: only the media session's own report counts.
        assertNull(new PreferredNowPlayingSource(fixed(account), fixed(here)).otherPlaying());
        // It plays on a phone while the PC plays a video, or nothing the media session knows of.
        assertEquals("Brave", new PreferredNowPlayingSource(fixed(account), fixed(video)).otherPlaying());
        assertEquals("the song plays on another device", new PreferredNowPlayingSource(fixed(account), fixed(null)).otherPlaying());
        // Without the account playing, nothing is added.
        assertNull(new PreferredNowPlayingSource(fixed(SPOTIFY_PAUSED), fixed(video)).otherPlaying());
    }

    @Test
    void theHelperReportsOtherPlayersOnce() {
        assertNull(SmtcNowPlayingSource.othersOf("{\"ok\":true,\"title\":\"t\",\"othersPlaying\":[]}"));
        assertNull(SmtcNowPlayingSource.othersOf("{\"ok\":true,\"title\":\"t\"}"));
        assertEquals("Brave, Brave", SmtcNowPlayingSource.othersOf("{\"ok\":true,\"othersPlaying\":[\"Brave\",\"Brave\"]}"));
        assertNull(SmtcNowPlayingSource.othersOf("not json"));
    }

    @Test
    void fallsBackWhenOneIsMissing() {
        assertEquals(AMAZON_PAUSED, pick(null, AMAZON_PAUSED));
        assertNull(pick(null, null));
    }
}
