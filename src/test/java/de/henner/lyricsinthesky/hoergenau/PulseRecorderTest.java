package de.henner.lyricsinthesky.hoergenau;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class PulseRecorderTest {
    /** As {@code LC_ALL=C pactl list sink-inputs} prints it, shortened: a paused browser tab, then Spotify playing. */
    private static final String LISTING = """
            Sink Input #41
            \tDriver: protocol-native.c
            \tSink: 0
            \tCorked: yes
            \tProperties:
            \t\tapplication.name = "Chromium"
            \t\tapplication.process.binary = "chromium"
            Sink Input #57
            \tDriver: protocol-native.c
            \tSink: 0
            \tCorked: no
            \tProperties:
            \t\tmedia.name = "Spotify"
            \t\tapplication.name = "spotify"
            \t\tapplication.process.binary = "spotify"
            """;

    @Test
    void theStreamOfThePlayerIsFoundByItsMprisName() {
        assertEquals("57", PulseRecorder.streamOf("spotify", LISTING));
        // MPRIS names a browser's player with an instance; a paused stream of it is still its stream.
        assertEquals("41", PulseRecorder.streamOf("chromium.instance4242", LISTING));
        assertNull(PulseRecorder.streamOf("vlc", LISTING));
        assertNull(PulseRecorder.streamOf("spotify", ""));
    }
}
