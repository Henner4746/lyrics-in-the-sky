package de.henner.lyricsinthesky.lyrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.henner.lyricsinthesky.nowplaying.NowPlaying;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.Deflater;
import org.junit.jupiter.api.Test;

/** The parsers of the word time services, with made-up lyrics. */
class WordSourcesTest {
    @Test
    void readsNeteaseYrcAndSkipsCredits() {
        String yrc = """
                {"t":0,"c":[{"tx":"作词: "},{"tx":"Somebody"}]}
                [0,1000](0,1000,0)作词 : Somebody
                [12000,3000](12000,400,0)Pa(12400,300,0)per (12700,500,0)boats (13200,800,0)float
                [16000,2000](16000,500,0)Down(0,0,0) (16600,700,0)stream
                """;
        TimedWords words = NeteaseClient.parseYrc(yrc).orElseThrow();
        assertEquals(2, words.lines().size());
        TimedWords.Line first = words.lines().get(0);
        assertEquals("Paper boats float", first.text());
        assertEquals(12000, first.words().get(0).startMs());
        assertEquals(12700, first.words().get(1).startMs());
        // A lone space marked at 0 ms only separates words.
        assertEquals("Down stream", words.lines().get(1).text());
        assertEquals(16600, words.lines().get(1).words().get(1).startMs());
    }

    @Test
    void neteaseAnswerWithoutYrcHasNoWordTimes() {
        assertTrue(NeteaseClient.parse("{\"lrc\":{\"lyric\":\"[00:01.00]only lines\"}}").isEmpty());
    }

    @Test
    void unpacksAndReadsKugouKrc() throws Exception {
        String krc = """
                [ti:Lanterns]
                [ar:The Made Up Band]
                [0,2000]<0,1000,0>The Made Up Band - <1000,1000,0>Lanterns
                [20000,3000]<0,500,0>Light <500,400,0>the <900,800,0>lan<1700,600,0>terns
                [24000,2000]<0,700,0>Hold <700,900,0>on
                """;
        String back = KugouClient.decode(pack(krc));
        assertEquals(krc, back);
        TimedWords words = KugouClient.parseKrc(back).orElseThrow().withoutTitleLine("Lanterns");
        // The "Artist - Title" line is not sung and is left out.
        assertEquals(2, words.lines().size());
        assertEquals("Light the lanterns", words.lines().get(0).text());
        assertEquals(20900, words.lines().get(0).words().get(2).startMs());
        assertEquals(24700, words.lines().get(1).words().get(1).startMs());
    }

    @Test
    void titleLinesAreLeftOutButSungLinesWithADashStay() {
        TimedWords words = new TimedWords("X", List.of(
                line(0, "Lanterns (Single Version) - The Made Up Band"),
                line(5000, "Light the lanterns - all of them"),
                line(9000, "Winter＇s night")));
        TimedWords kept = words.withoutTitleLine("Lanterns - 2011 Remaster");
        assertEquals(2, kept.lines().size());
        assertEquals("Light the lanterns - all of them", kept.lines().get(0).text());
        // Full-width forms are made plain.
        assertEquals("Winter's night", TimedWords.line(List.of(new TimedWords.Word(0, "Winter＇s "),
                new TimedWords.Word(300, "night"))).text());
    }

    private static TimedWords.Line line(long start, String text) {
        List<TimedWords.Word> words = new java.util.ArrayList<>();
        for (String word : text.split(" ")) {
            words.add(new TimedWords.Word(start, word));
        }
        return new TimedWords.Line(start, words);
    }

    @Test
    void readsDeezerWordByWordLines() {
        String body = """
                {"data":{"track":{"lyrics":{"synchronizedWordByWordLines":[
                  {"start":5000,"words":[{"start":5000,"word":"Morning"},{"start":5600,"word":"comes"}]},
                  {"start":8000,"words":[{"start":8000,"word":"Slowly"}]}]}}}}
                """;
        TimedWords words = DeezerClient.parse(body).orElseThrow();
        assertEquals("Deezer", words.source());
        assertEquals("Morning comes", words.lines().get(0).text());
        assertEquals(5600, words.lines().get(0).words().get(1).startMs());
        assertTrue(DeezerClient.parse("{\"data\":{\"track\":{\"lyrics\":{\"synchronizedWordByWordLines\":null}}}}").isEmpty());
        assertTrue(DeezerClient.parse("{\"errors\":[{\"message\":\"nope\"}],\"data\":{\"track\":null}}").isEmpty());
    }

    @Test
    void searchHitsMustBeTheSameSongAndLength() {
        NowPlaying track = new NowPlaying("test", "Lanterns - 2011 Remaster", "The Made Up Band", "", true, 0, 200_000, 0);
        List<SearchHit> hits = List.of(
                new SearchHit("1", "Lanterns (Live)", "The Made Up Band", 260_000, ""),
                new SearchHit("2", "Lanterns", "Somebody Else", 200_000, ""),
                new SearchHit("3", "Lanterns", "The Made Up Band", 202_000, ""),
                new SearchHit("4", "Lanterns", "The Made Up Band", 200_500, ""));
        List<SearchHit> matching = SearchHit.matching(track, hits, 5);
        assertEquals(List.of("4", "3"), matching.stream().map(SearchHit::id).toList());
    }

    private static byte[] pack(String text) {
        Deflater deflater = new Deflater();
        deflater.setInput(text.getBytes(StandardCharsets.UTF_8));
        deflater.finish();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes("krc1".getBytes(StandardCharsets.US_ASCII));
        byte[] buffer = new byte[4096];
        byte[] key = {64, 71, 97, 119, 94, 50, 116, 71, 81, 54, 49, 45, (byte) 206, (byte) 210, 110, 105};
        int i = 0;
        while (!deflater.finished()) {
            int n = deflater.deflate(buffer);
            for (int k = 0; k < n; k++, i++) {
                out.write(buffer[k] ^ key[i % key.length]);
            }
        }
        return out.toByteArray();
    }
}
