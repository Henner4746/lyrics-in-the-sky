package de.henner.lyricsinthesky.hoergenau;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.henner.lyricsinthesky.lyrics.LyricsCache;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SharedLearnedTest {
    @Test
    void songIdsAreTheTrackersKeys() {
        // The same key the tracker computes from artist, title and album (see tracker/test_lyrics_sync.py).
        assertEquals("c491f2d8dfadbd4752970376365091a3",
                LyricsCache.idOf("Peter Petersen\u0000Staubsaugerliebe\u0000Staubsaugerliebe"));
    }

    @Test
    void onlyLearnedLyricsWithWordTimesAreTaken() {
        String lrc = "[re:Hoergenau]\\n[00:00.419]<00:00.419>Die <00:00.769>Tür <00:01.119>fällt\\n";
        assertEquals(Optional.of(lrc.replace("\\n", "\n")),
                SharedLearned.lrcOf("{\"lrc\":\"" + lrc + "\",\"kind\":\"aligned\",\"players\":1}"));
        assertTrue(SharedLearned.lrcOf("{\"lrc\":\"[00:01.00]Nur eine Zeile\"}").isEmpty());
        assertTrue(SharedLearned.lrcOf("{\"error\":\"none\"}").isEmpty());
    }
}
