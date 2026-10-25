package de.henner.lyricsinthesky.lyrics;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class LyricsTest {
    @Test
    void parsesFractionsOfOneToThreeDigits() {
        Lyrics lyrics = Lyrics.parseLrc("[00:01.5]a\n[00:02.45]b\n[01:03.456]c");
        assertEquals(1_500, lyrics.lines().get(0).startMs());
        assertEquals(2_450, lyrics.lines().get(1).startMs());
        assertEquals(63_456, lyrics.lines().get(2).startMs());
    }

    @Test
    void repeatsTextForEveryTimestampAndSorts() {
        Lyrics lyrics = Lyrics.parseLrc("[00:10.00][00:02.00]chorus\n[00:05.00]verse");
        assertEquals(3, lyrics.lines().size());
        assertEquals("chorus", lyrics.lines().get(0).text());
        assertEquals("verse", lyrics.lines().get(1).text());
        assertEquals(10_000, lyrics.lines().get(2).startMs());
    }

    @Test
    void positiveOffsetShowsLinesEarlier() {
        Lyrics lyrics = Lyrics.parseLrc("[offset:+500]\n[00:02.00]x");
        assertEquals(1_500, lyrics.lines().get(0).startMs());
    }

    @Test
    void skipsMetadataAndUntimedLines() {
        Lyrics lyrics = Lyrics.parseLrc("[ar:Someone]\n[ti:Song]\nno time\n[00:01.00]real");
        assertEquals(1, lyrics.lines().size());
        assertEquals("real", lyrics.lines().get(0).text());
    }

    @Test
    void readsEnhancedWordTimestamps() {
        Lyrics lyrics = Lyrics.parseLrc("[00:01.00]<00:01.00>one <00:01.50>two<00:01.80> three");
        Lyrics.Line line = lyrics.lines().get(0);
        assertEquals("one two three", line.text());
        assertArrayEquals(new long[] {1_000, 1_500, 1_800}, line.wordStartsMs());
    }

    @Test
    void plainLinesHaveNoWordTimes() {
        assertNull(Lyrics.parseLrc("[00:01.00]hello world").lines().get(0).wordStartsMs());
    }

    @Test
    void findsLineAtPosition() {
        Lyrics lyrics = Lyrics.parseLrc("[00:01.00]a\n[00:03.00]b");
        assertEquals(-1, lyrics.indexAt(500));
        assertEquals(0, lyrics.indexAt(1_000));
        assertEquals(0, lyrics.indexAt(2_999));
        assertEquals(1, lyrics.indexAt(60_000));
    }

    @Test
    void estimatedWordTimesStayInsideTheLine() {
        Lyrics lyrics = Lyrics.parseLrc("[00:10.00]the quick brown fox jumps\n[00:14.00]next");
        long[] times = lyrics.wordTimes(0, 200_000);
        assertEquals(5, times.length);
        assertEquals(10_000, times[0]);
        for (int i = 1; i < times.length; i++) {
            assertTrue(times[i] > times[i - 1]);
            assertTrue(times[i] < lyrics.endOf(0, 200_000));
        }
    }

    @Test
    void longBreaksDoNotKeepALineForever() {
        Lyrics lyrics = Lyrics.parseLrc("[00:10.00]short\n[01:30.00]after solo");
        assertTrue(lyrics.endOf(0, 200_000) < 20_000);
    }

    @Test
    void cleansAmazonAndFeaturingMarkers() {
        assertEquals("Song", TrackNames.cleanTitle("Song [Explicit]"));
        assertEquals("Song (Live)", TrackNames.cleanTitle("Song (feat. Someone) (Live)"));
        assertEquals("Artist", TrackNames.primaryArtist("Artist, Other & Third"));
        assertEquals("AnnenMayKantereit", TrackNames.primaryArtist("AnnenMayKantereit"));
    }
}
