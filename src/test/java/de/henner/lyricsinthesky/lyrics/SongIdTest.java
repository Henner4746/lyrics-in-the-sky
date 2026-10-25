package de.henner.lyricsinthesky.lyrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** A timing correction belongs to one song with exactly the lyrics it was made for. */
class SongIdTest {
    private static final String LRC = "[00:12.30]Hier kommt die Sonne\n[00:15.80]Eins\n[00:17.10]Zwei\n";

    @Test
    void theSameLyricsGiveTheSameName() {
        String id = SongId.of("Rammstein", "Sonne", Lyrics.parseLrc(LRC));
        assertEquals(32, id.length());
        assertTrue(id.matches("[0-9a-f]+"));
        // Times written a little differently, a version note and a second artist do not matter.
        String sameLyrics = "[00:12.31]Hier kommt die Sonne!\n[00:15.79]Eins\n[00:17.12]Zwei\n";
        assertEquals(id, SongId.of("Rammstein, Someone", "Sonne - Remastered 2020", Lyrics.parseLrc(sameLyrics)));
    }

    @Test
    void otherTimesOrAnotherSongGiveAnotherName() {
        String id = SongId.of("Rammstein", "Sonne", Lyrics.parseLrc(LRC));
        String later = "[00:13.30]Hier kommt die Sonne\n[00:16.80]Eins\n[00:18.10]Zwei\n";
        assertNotEquals(id, SongId.of("Rammstein", "Sonne", Lyrics.parseLrc(later)));
        assertNotEquals(id, SongId.of("Rammstein", "Mutter", Lyrics.parseLrc(LRC)));
        assertNotEquals(id, SongId.of("Coldplay", "Sonne", Lyrics.parseLrc(LRC)));
    }
}
