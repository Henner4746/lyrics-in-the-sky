package de.henner.lyricsinthesky.hoergenau;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.henner.lyricsinthesky.hoergenau.WhisperEngine.Heard;
import de.henner.lyricsinthesky.lyrics.Lyrics;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Hoergenau's parts that need neither Windows nor whisper.cpp, with made-up lyrics. */
class HoergenauTest {
    private static final List<String> LINES = List.of("Light the lanterns on the river", "Paper boats are drifting slowly",
            "Light the lanterns on the river");

    @Test
    void knownLyricsArePlacedOnWhatWasHeard() {
        // Heard as Whisper writes sung words: one misheard ("lanters"), one missing ("are"), one extra ("oh").
        List<Heard> heard = List.of(new Heard("Light", 1_000), new Heard("the", 1_300), new Heard("lanters", 1_600),
                new Heard("on", 2_100), new Heard("the", 2_300), new Heard("river", 2_500), new Heard("oh", 3_000),
                new Heard("Paper", 5_000), new Heard("boats", 5_400), new Heard("drifting", 6_000),
                new Heard("slowly", 6_600), new Heard("Light", 9_000), new Heard("the", 9_300),
                new Heard("lanterns", 9_600), new Heard("on", 10_100), new Heard("the", 10_300), new Heard("river", 10_500));
        HeardLyrics.Aligned aligned = HeardLyrics.align(LINES, heard, 20_000).orElseThrow();
        assertTrue(aligned.matched() > 0.9);
        Lyrics lyrics = Lyrics.parseLrc(aligned.lrc());
        assertTrue(lyrics.hasWordTiming());
        assertEquals(3, lyrics.lines().size());
        // Times are song positions: the recording started 20 s into the song.
        assertEquals(21_000, lyrics.lines().get(0).startMs());
        assertEquals(21_600, lyrics.lines().get(0).wordStartsMs()[2]);
        // "are" was not heard: it gets a time between "boats" and "drifting".
        long[] second = lyrics.lines().get(1).wordStartsMs();
        assertTrue(second[2] > 25_400 && second[2] < 26_000, "unheard word at " + second[2]);
        assertEquals(29_000, lyrics.lines().get(2).startMs());
        assertTrue(Hoergenau.learned(lyrics));
    }

    @Test
    void lyricsThatAreNotInTheRecordingAreRefused() {
        List<Heard> heard = List.of(new Heard("completely", 1_000), new Heard("different", 1_400),
                new Heard("words", 1_800), new Heard("here", 2_000));
        assertTrue(HeardLyrics.align(LINES, heard, 0).isEmpty());
    }

    @Test
    void withoutLyricsTheHeardWordsBecomeTheLyrics() {
        String lrc = HeardLyrics.transcript(List.of(List.of(new Heard("Hello", 500), new Heard("there", 900)),
                List.of(new Heard("again", 3_000))), 1_000);
        Lyrics lyrics = Lyrics.parseLrc(lrc);
        assertEquals(2, lyrics.lines().size());
        assertEquals(1_500, lyrics.lines().get(0).startMs());
        assertEquals(1_900, lyrics.lines().get(0).wordStartsMs()[1]);
        assertTrue(lrc.startsWith(HeardLyrics.MARK));
    }

    @Test
    void readsWhisperCppTokensWithDtwTimes() {
        String json = """
                {"transcription":[{"tokens":[
                  {"text":"[_BEG_]","offsets":{"from":0,"to":0},"t_dtw":-1},
                  {"text":" Light","offsets":{"from":0,"to":320},"t_dtw":105},
                  {"text":" lan","offsets":{"from":320,"to":640},"t_dtw":150},
                  {"text":"terns","offsets":{"from":640,"to":960},"t_dtw":170},
                  {"text":" now","offsets":{"from":960,"to":1200},"t_dtw":-1}]}]}""";
        List<List<Heard>> segments = WhisperEngine.parse(json);
        assertEquals(List.of(new Heard("Light", 1_050), new Heard("lanterns", 1_500), new Heard("now", 960)),
                segments.get(0));
    }

    @Test
    void writesA16KhzMonoWav(@TempDir Path dir) throws Exception {
        short[] samples = new short[1600];
        samples[0] = 1234;
        Path wav = dir.resolve("a.wav");
        WhisperEngine.writeWav(wav, samples, samples.length);
        byte[] bytes = Files.readAllBytes(wav);
        assertEquals(44 + 3200, bytes.length);
        assertEquals("RIFF", new String(bytes, 0, 4));
        assertEquals(16_000, (bytes[24] & 0xFF) | (bytes[25] & 0xFF) << 8 | (bytes[26] & 0xFF) << 16);
        assertEquals(1234, (short) ((bytes[44] & 0xFF) | bytes[45] << 8));
    }

    @Test
    void gapsAreFilledInOrder() {
        long[] times = {-1, 1_000, -1, -1, 1_600, -1};
        HeardLyrics.fillGaps(times);
        assertEquals(new ArrayList<>(List.of(650L, 1_000L, 1_200L, 1_400L, 1_600L, 1_950L)),
                new ArrayList<>(java.util.Arrays.stream(times).boxed().toList()));
    }
}
