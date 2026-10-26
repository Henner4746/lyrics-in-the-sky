package de.henner.lyricsinthesky.lyrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.henner.lyricsinthesky.nowplaying.NowPlaying;
import java.util.List;
import org.junit.jupiter.api.Test;

class SongCatalogTest {
    private static NowPlaying playing(String title, String artist, long durationMs) {
        return new NowPlaying("test", title, artist, "", true, 0, durationMs, 0);
    }

    @Test
    void aSongACatalogKnowsIsOne() {
        String deezer = "{\"data\":[{\"id\":1,\"title\":\"Geh Down\",\"duration\":151,\"artist\":{\"name\":\"SUV WHATEVER\"}}]}";
        assertTrue(SongCatalog.known(playing("Geh Down", "SUV WHATEVER", 151_000), DeezerClient.hits(deezer)));
        // Another cut or length of it still is the song.
        assertTrue(SongCatalog.known(playing("Geh Down - Radio Edit", "SUV WHATEVER", 140_000), DeezerClient.hits(deezer)));
        String apple = "{\"resultCount\":1,\"results\":[{\"trackId\":7,\"trackName\":\"Staubsaugerliebe\","
                + "\"artistName\":\"Peter Petersen\",\"trackTimeMillis\":210841}]}";
        List<SearchHit> hits = SongCatalog.appleHits(apple);
        assertEquals(210_841, hits.get(0).durationMs());
        assertTrue(SongCatalog.known(playing("Staubsaugerliebe", "Peter Petersen", 210_841), hits));
    }

    @Test
    void aStreamOrVideoIsNoSong() {
        // What a catalog finds for a stream title is other songs, by other artists.
        String deezer = "{\"data\":[{\"id\":2,\"title\":\"Craft Attack\",\"duration\":180,\"artist\":{\"name\":\"Someone\"}}]}";
        assertFalse(SongCatalog.known(playing("🍔CRAFT ATTACK🍔ICH BIN HÄSSLICH UND ICH BIN STOLZ!🍔!burgerbot", "Exsl95",
                600_000), DeezerClient.hits(deezer)));
        assertFalse(SongCatalog.known(playing("Geh Down", "SUV WHATEVER", 151_000), DeezerClient.hits("{\"data\":[]}")));
        assertTrue(SongCatalog.appleHits("{\"resultCount\":0,\"results\":[]}").isEmpty());
    }
}
