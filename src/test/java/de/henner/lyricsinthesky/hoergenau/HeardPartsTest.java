package de.henner.lyricsinthesky.hoergenau;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.henner.lyricsinthesky.hoergenau.WhisperEngine.Heard;
import java.util.List;
import org.junit.jupiter.api.Test;

class HeardPartsTest {
    private static final long SONG_MS = 200_000;

    @Test
    void partsHeardOnDifferentDaysMakeUpTheSong() {
        // The first half one day: not enough to learn the song.
        HeardParts heard = HeardParts.none().with(0, 100_000,
                List.of(List.of(new Heard("Light", 20_000), new Heard("the", 20_400), new Heard("lanterns", 20_800))), "en");
        assertEquals(0.5, heard.share(SONG_MS), 1e-9);
        assertTrue(heard.covers(50_000));
        assertFalse(heard.covers(150_000));
        // The rest the next day, heard from where the song was not heard yet.
        heard = heard.with(100_000, 200_000, List.of(List.of(new Heard("Paper", 120_000), new Heard("boats", 120_500))), "de");
        assertEquals(1.0, heard.share(SONG_MS), 1e-9);
        assertEquals("en", heard.language(), "the later parts are heard in the first part's language");
        assertEquals(List.of("Light", "the", "lanterns", "Paper", "boats"),
                heard.words().stream().map(Heard::text).toList());
    }

    @Test
    void wordsHeardAgainTakeThePlaceOfTheOldOnes() {
        HeardParts heard = HeardParts.none().with(0, 60_000,
                List.of(List.of(new Heard("misheard", 30_000)), List.of(new Heard("kept", 10_000))), "en");
        heard = heard.with(25_000, 90_000, List.of(List.of(new Heard("heard", 30_100))), "en");
        assertEquals(List.of("kept", "heard"), heard.words().stream().map(Heard::text).toList());
        // The stretches join: 0 to 90 s.
        assertEquals(90_000, heard.heardMs());
    }

    @Test
    void keptAsTextAndReadBack() {
        HeardParts heard = HeardParts.none().with(5_000, 65_000,
                List.of(List.of(new Heard("Über", 7_000), new Heard("den", 7_400)), List.of(new Heard("Wolken", 9_000))), "de")
                .with(120_000, 150_000, List.of(), "de");
        HeardParts back = HeardParts.parse(heard.format());
        assertEquals(heard.format(), back.format());
        assertEquals("de", back.language());
        assertEquals(2, back.segments().size());
        assertTrue(back.covers(130_000));
        assertTrue(HeardParts.parse("").isEmpty());
        assertTrue(HeardParts.parse("[re:Hoergenau]\n[00:01.00]x").isEmpty());
    }
}
