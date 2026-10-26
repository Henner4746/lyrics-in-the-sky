package de.henner.lyricsinthesky.hoergenau;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import de.henner.lyricsinthesky.lyrics.Lyrics;
import de.henner.lyricsinthesky.lyrics.LyricsCache;
import de.henner.lyricsinthesky.nowplaying.NowPlaying;
import java.util.List;
import org.junit.jupiter.api.Test;

class SharedLearnedTest {
    /** lrclib.net's text, as in tracker/test_lyrics_sync.py. */
    private static final String PLAIN = "[Chorus]\nDie Tür fällt ins Schloss\nIch bin endlich allein\n\nDie Wohnung ist still";
    private static final String LEARNED = "[re:Hoergenau]\n"
            + "[00:00.419]<00:00.419>Die <00:00.769>Tür <00:01.119>fällt <00:01.469>ins <00:01.819>Schloss\n"
            + "[00:02.169]<00:02.169>Ich <00:02.519>bin <00:02.869>endlich <00:03.289>allein\n"
            + "[00:04.369]<00:04.369>Die <00:04.629>Wohnung <00:05.129>ist <00:05.589>still\n";
    /** The tracker computes the same fingerprint for the same text (tracker/test_lyrics_sync.py). */
    private static final String TEXT = "08e0e4adfa0c37b0eb994e0c4d161e90";

    @Test
    void songIdsAreTheTrackersKeys() {
        // The same key the tracker computes from artist, title and album (see tracker/test_lyrics_sync.py).
        assertEquals("c491f2d8dfadbd4752970376365091a3",
                LyricsCache.idOf("Peter Petersen\u0000Staubsaugerliebe\u0000Staubsaugerliebe"));
    }

    @Test
    void onlyTheWordTimesAreSentNeverTheWords() {
        SharedLearned.Timing timing = SharedLearned.timingOf(Lyrics.parseLrc(LEARNED)).orElseThrow();
        assertEquals(TEXT, timing.text());
        assertArrayEquals(new long[] {2169, 2519, 2869, 3289}, timing.times().get(1));
        NowPlaying track = new NowPlaying("test", "Staubsaugerliebe", "Peter Petersen", "Staubsaugerliebe", true, 0,
                210_841, 0);
        JsonObject body = SharedLearned.body("Peter Petersen\u0000Staubsaugerliebe\u0000Staubsaugerliebe", track, timing,
                0.89, "cccccccc-0000-0000-0000-000000000001", "t");
        String sent = body.toString();
        for (String word : List.of("Tür", "Schloss", "endlich", "Wohnung", "still")) {
            assertFalse(sent.contains(word), word + " in " + sent);
        }
        assertEquals("aligned", body.get("kind").getAsString());
        assertEquals(TEXT, body.get("text").getAsString());
    }

    @Test
    void sharedTimesArePutOnThisPcsOwnText() {
        String answer = "{\"times\":[[419,769,1119,1469,1819],[2169,2519,2869,3289],[4369,4629,5129,5589]],"
                + "\"text\":\"" + TEXT + "\",\"kind\":\"aligned\",\"players\":1}";
        SharedLearned.Timing timing = SharedLearned.timingOf(answer).orElseThrow();
        String lrc = SharedLearned.onto(timing, Lyrics.estimated(PLAIN, 210_841)).orElseThrow();
        assertEquals(LEARNED, lrc);
        assertTrue(Hoergenau.learned(Lyrics.parseLrc(lrc)));
        // Another text (a word changed on lrclib.net), or a line with a word more: the times are not used.
        assertTrue(SharedLearned.onto(timing, Lyrics.estimated(PLAIN.replace("still", "leer"), 210_841)).isEmpty());
        assertTrue(SharedLearned.onto(timing, Lyrics.estimated(PLAIN + "\nNoch eine Zeile", 210_841)).isEmpty());
        // Older answers with lyrics, or none.
        assertTrue(SharedLearned.timingOf("{\"lrc\":\"[re:Hoergenau]\\n[00:01.00]<00:01.00>Nur\"}").isEmpty());
        assertTrue(SharedLearned.timingOf("{\"error\":\"none\"}").isEmpty());
    }
}
