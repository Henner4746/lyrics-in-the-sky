package de.henner.lyricsinthesky.hoergenau;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.henner.lyricsinthesky.lyrics.Lyrics;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class SyncCheckTest {
    /** Twelve lines of eight words, a word every 400 ms, as a service would time them. */
    private static Lyrics lyrics() {
        StringBuilder lrc = new StringBuilder();
        String[] words = "It starts with one thing I don't know why".split(" ");
        for (int line = 0; line < 12; line++) {
            long start = 18_000 + line * 4_000L;
            lrc.append(String.format("[%02d:%06.3f]", start / 60_000, start % 60_000 / 1000.0));
            for (int w = 0; w < words.length; w++) {
                long at = start + w * 400L;
                lrc.append(String.format("<%02d:%06.3f>%s ", at / 60_000, at % 60_000 / 1000.0, words[w]));
            }
            lrc.append('\n');
        }
        return Lyrics.parseLrc(lrc.toString().replace(',', '.'));
    }

    /** What Whisper would hear: the words sung {@code lateMs} before the lyrics show them, a little off each time. */
    private static List<SyncCheck.Timed> heard(Lyrics lyrics, long earlierMs, int jitterMs, int every) {
        Random random = new Random(7);
        List<SyncCheck.Timed> heard = new ArrayList<>();
        List<SyncCheck.Timed> shown = SyncCheck.shown(lyrics, 240_000);
        for (int i = 0; i < shown.size(); i += every) {
            long jitter = jitterMs == 0 ? 0 : random.nextInt(2 * jitterMs + 1) - jitterMs;
            heard.add(new SyncCheck.Timed(shown.get(i).ms() - earlierMs + jitter, shown.get(i).word()));
        }
        return heard;
    }

    @Test
    void lyricsTimedForAnotherVersionAreMovedByWhatTheWordsAgreeOn() {
        Lyrics lyrics = lyrics();
        // "In the End" from NetEase: every word 1.08 s after it is sung.
        SyncCheck.Result result = SyncCheck.measure(SyncCheck.shown(lyrics, 240_000), heard(lyrics, 1_075, 60, 1));
        assertTrue(result.trusted());
        assertEquals(1_080, result.correctionMs(), 30);
    }

    @Test
    void lyricsWithinWhatWhisperCanTellStayAsTheyAre() {
        Lyrics lyrics = lyrics();
        SyncCheck.Result result = SyncCheck.measure(SyncCheck.shown(lyrics, 240_000), heard(lyrics, 90, 60, 1));
        assertTrue(result.trusted());
        assertEquals(0, result.correctionMs());
    }

    @Test
    void scatteredOrTooFewWordsChangeNothing() {
        Lyrics lyrics = lyrics();
        SyncCheck.Result scattered = SyncCheck.measure(SyncCheck.shown(lyrics, 240_000), heard(lyrics, 800, 900, 1));
        assertFalse(scattered.trusted());
        assertEquals(0, scattered.correctionMs());
        SyncCheck.Result few = SyncCheck.measure(SyncCheck.shown(lyrics, 240_000), heard(lyrics, 800, 0, 12));
        assertTrue(few.pairs() < SyncCheck.MIN_PAIRS);
        assertEquals(0, few.correctionMs());
        assertEquals(0, SyncCheck.measure(SyncCheck.shown(lyrics, 240_000), List.of()).pairs());
    }

    /**
     * Real data from a live run: the first 50 s of three songs played through Spotify and heard by whisper.cpp (small,
     * as Hoergenau hears them), and the word times the game showed. Words are kept as short hashes of their spelling,
     * so no lyrics are in the repository; pairing by spelling works on them all the same. Measured against the sung
     * audio, song A (NetEase) showed its words 1.08 s late, song B (Deezer) 0.84 s late, song C (Deezer) on time.
     */
    @Test
    void songsTimedForAnotherVersionAreFoundInARealRecording() throws Exception {
        SyncCheck.Result a = real("song-a");
        SyncCheck.Result b = real("song-b");
        SyncCheck.Result c = real("song-c");
        assertTrue(a.trusted(), a.toString());
        assertEquals(1_080, a.correctionMs(), 120, a.toString());
        assertTrue(b.trusted(), b.toString());
        assertEquals(840, b.correctionMs(), 120, b.toString());
        assertEquals(0, c.correctionMs(), c.toString());
    }

    private static SyncCheck.Result real(String song) throws Exception {
        return SyncCheck.measure(read(song + "-shown.tsv"), read(song + "-heard.tsv"));
    }

    private static List<SyncCheck.Timed> read(String name) throws Exception {
        try (var in = SyncCheckTest.class.getResourceAsStream("/synccheck/" + name)) {
            List<SyncCheck.Timed> words = new ArrayList<>();
            for (String line : new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).split("\n")) {
                if (!line.isBlank()) {
                    String[] parts = line.split("\t");
                    words.add(new SyncCheck.Timed(Long.parseLong(parts[0]), parts[1]));
                }
            }
            return words;
        }
    }

    @Test
    void wordsArePairedBySpellingIgnoringCaseAndPunctuation() {
        assertEquals("dont", SyncCheck.token("Don't,"));
        assertEquals("fürchtet", SyncCheck.token("Fürchtet"));
        assertEquals("heiss", SyncCheck.token("heiß!"));
    }

    @Test
    void theModelAndThePowerChooseWhatWhisperRunsWith() {
        assertEquals(WhisperEngine.Model.TURBO, WhisperEngine.Model.of("turbo"));
        assertEquals(WhisperEngine.Model.SMALL, WhisperEngine.Model.of("anything"));
        assertEquals("large.v3.turbo", WhisperEngine.Model.TURBO.dtw());
        assertTrue(WhisperEngine.Model.TURBO.url().endsWith("ggml-large-v3-turbo-q5_0.bin"));
        assertEquals(2, WhisperEngine.threads("low", 16));
        assertEquals(4, WhisperEngine.threads("normal", 16));
        assertEquals(2, WhisperEngine.threads("normal", 4));
        assertEquals(15, WhisperEngine.threads("high", 16));
    }
}
