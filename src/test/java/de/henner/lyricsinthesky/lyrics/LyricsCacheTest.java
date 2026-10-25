package de.henner.lyricsinthesky.lyrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LyricsCacheTest {
    @TempDir
    Path dir;

    @Test
    void foundLyricsComeBackFromDiskWithTheirWordTimes() {
        Lyrics lyrics = Lyrics.parseLrc("[00:01.00]<00:01.00>Hello <00:01.50>world\n[00:03.00]Second line");
        new LyricsCache(dir).put("Artist\u0000Title\u0000Album", lyrics);
        // A new cache on the same folder, as after a restart of the game.
        Lyrics back = new LyricsCache(dir).get("Artist\u0000Title\u0000Album").orElseThrow();
        assertEquals(2, back.lines().size());
        assertEquals("Hello world", back.lines().get(0).text());
        assertTrue(back.hasWordTiming());
        assertTrue(new LyricsCache(dir).get("Artist\u0000Other\u0000Album").isEmpty());
    }

    @Test
    void instrumentalsAreKeptAndBrokenFilesIgnored() throws IOException {
        LyricsCache cache = new LyricsCache(dir);
        cache.put("instrumental", Lyrics.instrumental());
        assertTrue(cache.get("instrumental").orElseThrow().isInstrumental());
        Files.writeString(dir.resolve(LyricsCache.fileName("broken")), "no timestamps here");
        assertTrue(cache.get("broken").isEmpty());
    }

    @Test
    void theLeastRecentlyPlayedSongsMakeRoom() throws IOException {
        LyricsCache cache = new LyricsCache(dir, 3);
        Lyrics lyrics = Lyrics.parseLrc("[00:01.00]La la");
        for (int i = 0; i < 3; i++) {
            cache.put("song" + i, lyrics);
            Files.setLastModifiedTime(dir.resolve(LyricsCache.fileName("song" + i)), FileTime.fromMillis(1_000_000L * (i + 1)));
        }
        // Played again: no longer the oldest.
        cache.get("song0");
        cache.put("song3", lyrics);
        assertTrue(cache.get("song0").isPresent());
        assertTrue(cache.get("song1").isEmpty());
        assertTrue(cache.get("song2").isPresent());
        assertTrue(cache.get("song3").isPresent());
    }
}
