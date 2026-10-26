package de.henner.lyricsinthesky.timing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Corrections per song: the player's own first, then the one many players agreed on. */
class TimingTest {
    private static final String SONG = "0123456789abcdef0123456789abcdef";

    @Test
    void theOwnCorrectionWinsAndResettingBringsBackTheSharedOne(@TempDir Path dir) {
        SongTimings timings = new SongTimings(dir.resolve("song-timing.json"));
        assertEquals(0, timings.offsetFor(SONG));
        timings.putShared(SONG, new SongTimings.Shared(12, 300));
        assertEquals(300, timings.offsetFor(SONG));
        timings.setOwn(SONG, -150);
        assertEquals(-150, timings.offsetFor(SONG));
        timings.setOwn(SONG, null);
        assertEquals(300, timings.offsetFor(SONG));
        assertTrue(timings.own(SONG).isEmpty());
    }

    @Test
    void ownCorrectionsAreKeptAndStayInRange(@TempDir Path dir) {
        Path file = dir.resolve("song-timing.json");
        new SongTimings(file).setOwn(SONG, 9_000);
        SongTimings again = new SongTimings(file);
        assertEquals(SongTimings.MAX_OFFSET_MS, again.offsetFor(SONG));
        // Shared corrections are asked for again each session, never kept.
        assertNull(again.shared(SONG));
    }

    @Test
    void theTrackersAnswerIsRead() {
        assertEquals(new SongTimings.Shared(12, 350), TimingTracker.parse("{\"players\":12,\"offsetMs\":350}"));
        assertEquals(new SongTimings.Shared(3, null), TimingTracker.parse("{\"players\":3,\"offsetMs\":null}"));
        assertEquals(new SongTimings.Shared(0, null), TimingTracker.parse("{}"));
        // One measured by Hoergenau counts at once.
        assertEquals(new SongTimings.Shared(1, -240, 1),
                TimingTracker.parse("{\"players\":1,\"offsetMs\":-240,\"measured\":1}"));
        assertEquals(new SongTimings.Shared(40, -SongTimings.MAX_OFFSET_MS),
                TimingTracker.parse("{\"players\":40,\"offsetMs\":-20000}"));
    }

    @Test
    void whatHoergenauMeasuredIsToldAsMeasured() {
        // As the tracker reads it (tracker/test_lyrics_sync.py): the correction, also 0 when the lyrics fit, with how
        // many words were paired and how far they lay apart.
        assertEquals("{\"song\":\"" + SONG + "\",\"offsetMs\":0,\"measured\":{\"pairs\":88,\"spreadMs\":48},"
                        + "\"install\":\"cccccccc-0000-0000-0000-000000000001\",\"version\":\"t\"}",
                TimingTracker.measuredBody(SONG, 0, 88, 48, "cccccccc-0000-0000-0000-000000000001", "t").toString());
    }
}
