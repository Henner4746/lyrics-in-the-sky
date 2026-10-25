package de.henner.lyricsinthesky.nowplaying;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PlaybackClockTest {
    @Test
    void neverRunsBackwardsWhileReportsJitter() {
        PlaybackClock clock = new PlaybackClock();
        long shown = clock.at("song", 0, 10_000, true);
        // Reports alternate ±40 ms around the true position, as two sources taking turns do.
        for (int frame = 1; frame <= 600; frame++) {
            long now = frame * 16L;
            long reported = 10_000 + now + (frame % 2 == 0 ? 40 : -40);
            long next = clock.at("song", now, reported, true);
            assertTrue(next >= shown, "went back at frame " + frame + ": " + shown + " -> " + next);
            shown = next;
        }
        // And it stays on the true position, within the jitter.
        assertTrue(Math.abs(shown - (10_000 + 600 * 16)) <= 45, "off by " + (shown - (10_000 + 600 * 16)));
    }

    @Test
    void aReportThatIsBehindSlowsTheClockDownUntilItMatches() {
        PlaybackClock clock = new PlaybackClock();
        clock.at("song", 0, 10_000, true);
        long shown = 0;
        // From now on the player reports 300 ms less than the clock has.
        for (int frame = 1; frame <= 120; frame++) {
            long now = frame * 16L;
            shown = clock.at("song", now, 10_000 + now - 300, true);
        }
        assertTrue(Math.abs(shown - (10_000 + 120 * 16 - 300)) < 5, "still off by " + (shown - (10_000 + 120 * 16 - 300)));
    }

    @Test
    void skipsAndNewSongsAreTakenAtOnce() {
        PlaybackClock clock = new PlaybackClock();
        clock.at("song", 0, 10_000, true);
        assertEquals(90_000, clock.at("song", 16, 90_000, true), "skip forward");
        assertEquals(30_000, clock.at("song", 32, 30_000, true), "skip back");
        assertEquals(1_000, clock.at("other", 48, 1_000, true), "new song");
    }

    @Test
    void standsStillWhilePaused() {
        PlaybackClock clock = new PlaybackClock();
        clock.at("song", 0, 10_000, true);
        assertEquals(10_016, clock.at("song", 16, 10_016, false));
        assertEquals(10_016, clock.at("song", 500, 10_016, false));
        assertEquals(10_016, clock.at("song", 516, 10_016, true));
    }
}
