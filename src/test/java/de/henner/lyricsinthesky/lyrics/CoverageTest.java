package de.henner.lyricsinthesky.lyrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The ways a song still gets lyrics when the first lookup finds none. */
class CoverageTest {
    @Test
    void versionNotesComeOffTheTitle() {
        assertEquals(List.of("Lights - Single Version", "Lights"), TrackNames.titleVariants("Lights - Single Version"));
        assertEquals(List.of("Africa - Remastered 2009", "Africa"), TrackNames.titleVariants("Africa - Remastered 2009"));
        assertEquals(List.of("Take On Me (2015 Remaster)", "Take On Me"), TrackNames.titleVariants("Take On Me (2015 Remaster)"));
        assertEquals(List.of("Cooler Than Me - Single Mix", "Cooler Than Me"),
                TrackNames.titleVariants("Cooler Than Me - Single Mix"));
        // A dash that is part of the name stays in the first variants; only the last one cuts it.
        assertEquals(List.of("Stay - Live at Wembley", "Stay"), TrackNames.titleVariants("Stay - Live at Wembley"));
        assertEquals(List.of("Sonne"), TrackNames.titleVariants("Sonne"));
    }

    @Test
    void aFreeSearchOnlyCountsResultsNamingTheSongAndTheArtist() {
        assertTrue(TrackNames.sameSong("Sunglasses At Night", "Corey Hart", "Sunglasses at Night (Remastered)", "Corey Hart"));
        assertFalse(TrackNames.sameSong("Sunglasses At Night", "Corey Hart", "Night Moves", "Bob Seger"));
        assertFalse(TrackNames.sameSong("Animals", "Maroon 5", "Animals", "Martin Garrix"));
    }

    @Test
    void plainTextIsSpreadOverTheSungPart() {
        String plain = "[Verse 1]\nIch bin der erste Satz\nUnd hier der zweite, etwas länger\n\n(Chorus)\nKurz\n";
        Lyrics lyrics = Lyrics.estimated(plain, 200_000);
        assertTrue(lyrics.isEstimated());
        assertEquals(3, lyrics.lines().size());
        assertEquals("Ich bin der erste Satz", lyrics.lines().get(0).text());
        // No singing in the first and last seconds, lines in order, the longer line given more time.
        assertTrue(lyrics.lines().get(0).startMs() >= 4_000);
        long first = lyrics.lines().get(1).startMs() - lyrics.lines().get(0).startMs();
        long second = lyrics.lines().get(2).startMs() - lyrics.lines().get(1).startMs();
        assertTrue(second > first);
        assertTrue(lyrics.lines().get(2).startMs() < 200_000 - 4_000);
        assertFalse(Lyrics.parseLrc("[00:01.00]timed").isEstimated());
    }

    @Test
    void plainTextComesFromTheResultClosestInLength() {
        var results = JsonParser.parseString("""
                [{"duration": 250.0, "plainLyrics": "too long"},
                 {"duration": 201.0, "plainLyrics": "this one"},
                 {"duration": 199.0, "plainLyrics": null},
                 {"duration": 100.0, "plainLyrics": "far off"}]
                """).getAsJsonArray();
        assertEquals("this one", LrclibClient.pickPlain(results, 200_000));
        assertEquals(null, LrclibClient.pickPlain(results, 400_000));
    }
}
