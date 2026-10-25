package de.henner.lyricsinthesky.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class BeatTest {
    private static final String[] WORDS = {"I", "smoked", "away", "my", "brain"};
    private static final long[] TIMES = {10_000, 10_300, 10_700, 11_000, 11_250};

    @Test
    void kickRisesQuicklyAndDiesAwayBeforeTheNextWord() {
        assertEquals(0f, Beat.envelope(-10), 1e-6);
        assertEquals(1f, Beat.envelope(Beat.ATTACK_MS), 1e-6);
        assertTrue(Beat.envelope(Beat.ATTACK_MS / 2) > 0.4f);
        // A quarter note at 120 bpm later the kick is mostly gone.
        assertTrue(Beat.envelope(500) < 0.15f, "still " + Beat.envelope(500));
    }

    @Test
    void stillBeforeTheFirstWordAndLongAfterTheLast() {
        assertEquals(0f, Beat.at(WORDS, TIMES, 9_000).strength(), 1e-6);
        assertEquals(0f, Beat.at(WORDS, TIMES, 13_000).strength(), 1e-6);
    }

    @Test
    void longWordsKickHarderAndTheSideAlternates() {
        long top = Beat.ATTACK_MS - Beat.LEAD_MS;
        Beat.Kick little = Beat.at(WORDS, TIMES, TIMES[0] + top);
        Beat.Kick longer = Beat.at(WORDS, TIMES, TIMES[1] + top);
        assertTrue(longer.strength() > little.strength(), little + " vs " + longer);
        assertEquals(1f, longer.strength(), 1e-6);
        assertTrue(Math.signum(little.tilt()) != Math.signum(longer.tilt()), "both tilt the same way");
    }

    @Test
    void theKickLandsOnTheWord() {
        // With the lead the top of the kick is right at the word's time, not after it.
        Beat.Kick onWord = Beat.at(WORDS, TIMES, TIMES[2] + Beat.ATTACK_MS - Beat.LEAD_MS);
        Beat.Kick late = Beat.at(WORDS, TIMES, TIMES[2] + Beat.ATTACK_MS - Beat.LEAD_MS + 150);
        assertTrue(onWord.strength() > late.strength());
    }
}
