package de.henner.lyricsinthesky;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.henner.lyricsinthesky.LyricsController.LookupState;
import org.junit.jupiter.api.Test;

class LookupRetryTest {
    @Test
    void aFailedLookupIsTriedAgainSoonThenLessOften() {
        assertEquals(5_000, LyricsController.retryDelayMs(1));
        assertEquals(15_000, LyricsController.retryDelayMs(2));
        assertEquals(45_000, LyricsController.retryDelayMs(3));
        assertEquals(120_000, LyricsController.retryDelayMs(10));
        // While the same song keeps playing, too – the old code waited for the next song or ten minutes.
        assertFalse(LyricsController.lookUpAgain(LookupState.ERROR, false, 4_000, 1));
        assertTrue(LyricsController.lookUpAgain(LookupState.ERROR, false, 5_000, 1));
        assertFalse(LyricsController.lookUpAgain(LookupState.ERROR, false, 10_000, 2));
    }

    @Test
    void foundLyricsAndRunningLookupsAreLeftAlone() {
        assertFalse(LyricsController.lookUpAgain(LookupState.FOUND, true, 3_600_000, 0));
        assertFalse(LyricsController.lookUpAgain(LookupState.SEARCHING, false, 3_600_000, 0));
        assertFalse(LyricsController.lookUpAgain(LookupState.NOT_FOUND, false, 60_000, 0));
        assertTrue(LyricsController.lookUpAgain(LookupState.NOT_FOUND, false, 11 * 60_000, 0));
    }
}
