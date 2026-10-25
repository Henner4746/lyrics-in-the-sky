package de.henner.lyricsinthesky.nowplaying;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class MprisParserTest {
    /** Shape of `gdbus call ... GetAll org.mpris.MediaPlayer2.Player` for the Spotify client. */
    private static final String SPOTIFY = "({'CanControl': <true>, 'CanGoNext': <true>, 'LoopStatus': <'None'>, "
            + "'Metadata': <{'mpris:trackid': <'/com/spotify/track/1'>, 'mpris:length': <uint64 272000000>, "
            + "'xesam:album': <'Mutter'>, 'xesam:albumArtist': <['Rammstein']>, "
            + "'xesam:artist': <['Rammstein', 'Someone']>, 'xesam:title': <\"Don't Stop\">}>, "
            + "'PlaybackStatus': <'Playing'>, 'Position': <int64 61500000>, 'Volume': <1.0>},)";

    @Test
    void readsMetadataStatusAndPosition() {
        MprisNowPlayingSource.Snapshot snapshot = MprisNowPlayingSource.parse("org.mpris.MediaPlayer2.spotify", SPOTIFY);
        assertEquals("Don't Stop", snapshot.title());
        assertEquals("Rammstein", snapshot.artist());
        assertEquals("Mutter", snapshot.album());
        assertTrue(snapshot.playing());
        assertEquals(61_500_000, snapshot.positionUs());
        assertEquals(272_000_000, snapshot.lengthUs());
    }

    @Test
    void handlesEscapesAndEmptyArrays() {
        String text = "({'Metadata': <{'xesam:title': <'Gr\\u00fc\\u00dfe \\'live\\''>, 'xesam:artist': <@as []>}>, "
                + "'PlaybackStatus': <'Paused'>},)";
        MprisNowPlayingSource.Snapshot snapshot = MprisNowPlayingSource.parse("org.mpris.MediaPlayer2.vlc", text);
        assertEquals("Grüße 'live'", snapshot.title());
        assertEquals("", snapshot.artist());
        assertEquals(0, snapshot.positionUs());
    }

    @Test
    void ignoresPlayersWithoutTitle() {
        assertNull(MprisNowPlayingSource.parse("x", "({'PlaybackStatus': <'Stopped'>},)"));
    }

    @Test
    void findsPlayerNames() {
        String names = "(['org.freedesktop.DBus', ':1.7', 'org.mpris.MediaPlayer2.spotify', "
                + "'org.mpris.MediaPlayer2.firefox.instance_1_42'],)";
        assertEquals(List.of("org.mpris.MediaPlayer2.spotify", "org.mpris.MediaPlayer2.firefox.instance_1_42"),
                MprisNowPlayingSource.playerNames(names));
    }
}
