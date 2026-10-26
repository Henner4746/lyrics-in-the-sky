package de.henner.lyricsinthesky.lyrics;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class SpellingTest {
    /** LRCLIB's lines of Rammstein's "Sonne", shortened. */
    private static final Lyrics LRCLIB = Lyrics.parseLrc("""
            [00:03.51] 1, 2, 3, 4, 5, 6, 7, 8, 9, aus
            [00:32.71] Alle warten auf das Licht
            [00:39.18] Fürchtet euch, fürchtet euch nicht
            [00:45.20] Die Sonne scheint mir aus den Augen
            [00:55.35] Und die Welt zählt laut bis zehn
            [01:30.02] Die Sonne scheint mir aus den Händen
            [01:36.88] Wenn sie aus den Fäusten bricht
            [01:39.93] Legt sich heiß auf das Gesicht
            [02:21.19] Fünf, hier kommt die Sonne
            [03:06.37] Lässt dich hart zu Boden geh'n
            """);

    /** The same song with word times from QQ Music through LyricsPlus, as it came: umlauts lost, nouns small. */
    private static final Lyrics QQ = Lyrics.parseLrc("""
            [re:wordtimes LyricsPlus (QQ Music)]
            [00:04.422]<00:04.422>Eins <00:05.806>zwei <00:07.384>drei <00:08.952>vier <00:10.520>ff <00:12.088>sechs
            [00:33.507]<00:33.507>Alle <00:33.739>warten <00:34.199>auf <00:34.951>das <00:35.377>licht
            [00:39.500]<00:39.500>Fchtet <00:40.100>euch <00:40.900>f <00:41.200>chtet <00:41.800>euch <00:42.300>nicht
            [00:45.300]<00:45.300>Die <00:45.600>sonne <00:46.000>scheint <00:46.400>mir <00:46.700>aus <00:47.000>den <00:47.300>augen
            [00:55.400]<00:55.400>Und <00:55.700>die <00:56.000>welt <00:56.300>z <00:56.500>lt <00:56.900>laut <00:57.300>bis <00:57.700>zehn
            [01:30.100]<01:30.100>Die <01:30.400>sonne <01:30.800>scheint <01:31.200>mir <01:31.500>aus <01:31.800>den <01:32.100>h <01:32.300>den
            [01:36.900]<01:36.900>Wenn <01:37.200>sie <01:37.500>aus <01:37.800>den <01:38.100>f <01:38.300>sten <01:38.700>bricht
            [01:40.000]<01:40.000>Legt <01:40.300>sich <01:40.600>heiss <01:41.000>auf <01:41.300>das <01:41.600>gesicht
            [02:21.200]<02:21.200>Funf
            [03:06.400]<03:06.400>L <03:06.600>st <03:06.900>dich <03:07.200>hart <03:07.500>zu <03:07.800>boden <03:08.100>gehen
            """);

    @Test
    void putsBackLettersAServiceLostAndWritesNounsBig() {
        Lyrics mended = Spelling.mend(QQ, LRCLIB);
        assertEquals(List.of(
                "Eins zwei drei vier fünf sechs",
                "Alle warten auf das Licht",
                "Fürchtet euch fürchtet euch nicht",
                "Die Sonne scheint mir aus den Augen",
                "Und die Welt zählt laut bis zehn",
                "Die Sonne scheint mir aus den Händen",
                "Wenn sie aus den Fäusten bricht",
                "Legt sich heiß auf das Gesicht",
                "Fünf",
                // "gehen" is how QQ spells LRCLIB's "geh'n", not a mangled word: it stays.
                "Lässt dich hart zu Boden gehen"),
                mended.lines().stream().map(Lyrics.Line::text).toList());
        assertTrue(mended.lrc().startsWith("[re:wordtimes LyricsPlus (QQ Music)]\n"));
    }

    @Test
    void aWordMendedFromTwoPartsStartsWithItsFirstPart() {
        Lyrics mended = Spelling.mend(QQ, LRCLIB);
        assertArrayEquals(new long[] {39_500, 40_100, 40_900, 41_800, 42_300}, mended.lines().get(2).wordStartsMs());
        assertArrayEquals(QQ.lines().get(0).wordStartsMs(), mended.lines().get(0).wordStartsMs());
        assertEquals(QQ.lines().get(0).startMs(), mended.lines().get(0).startMs());
    }

    @Test
    void leavesWellSpelledServicesAlone() {
        Lyrics apple = Lyrics.parseLrc("""
                [re:wordtimes Apple]
                [00:39.500]<00:39.500>Fürchtet <00:40.100>euch, <00:40.900>fürchtet <00:41.800>euch <00:42.300>nicht
                [00:55.400]<00:55.400>Und <00:55.700>die <00:56.000>Welt <00:56.300>zählt <00:56.900>laut
                [01:40.000]<01:40.000>Legt <01:40.300>sich <01:40.600>heiß <01:41.000>aufs <01:41.300>Gesicht
                """);
        assertSame(apple, Spelling.mend(apple, LRCLIB));
        Lyrics english = Lyrics.parseLrc("[00:01.00]<00:01.00>Will <00:01.40>the <00:01.70>real <00:02.00>Slim <00:02.30>Shady\n");
        assertSame(english, Spelling.mend(english, Lyrics.parseLrc("[00:01.00]Will the real Slim Shady please stand up\n")));
        assertSame(QQ, Spelling.mend(QQ, null));
    }

    @Test
    void mangledFormsCoverWhatServicesMakeOfUmlauts() {
        assertTrue(Spelling.mangledForms("fünf").containsAll(List.of("fnf", "ff", "funf", "fuenf")));
        assertTrue(Spelling.mangledForms("fäusten").containsAll(List.of("fusten", "fsten", "faeusten")));
        assertTrue(Spelling.mangledForms("heiß").containsAll(List.of("hei", "heiss")));
        // Too short to tell which word it was.
        assertTrue(Spelling.mangledForms("für").stream().noneMatch(form -> form.length() < Spelling.MIN_FORM));
    }
}
