package de.henner.lyricsinthesky.lyrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class LyricsPlusTest {
    private static final String WORD_RESPONSE = """
            {"type":"Word","metadata":{"source":"Apple"},"lyrics":[
              {"time":1000,"text":"She watches the ships","syllabus":[
                {"time":1000,"text":"She "},{"time":1200,"text":"wat"},{"time":1350,"text":"ches "},
                {"time":1600,"text":"the "},{"time":1800,"text":"ships"}]},
              {"time":3000,"text":"Backing","syllabus":[{"time":3000,"text":"ooh","isBackground":true}]},
              {"time":4000,"text":"Come on","syllabus":[{"time":4000,"text":"Come "},{"time":4300,"text":"on"}]}]}
            """;

    @Test
    void readsWordsAndSkipsBackingVocals() {
        TimedWords timed = LyricsPlusClient.parse(WORD_RESPONSE).orElseThrow();
        assertEquals("Apple", timed.source());
        assertEquals(2, timed.lines().size());
        TimedWords.Line first = timed.lines().get(0);
        assertEquals("She watches the ships", first.text());
        assertEquals(1000, first.startMs());
        // "wat" + "ches " joins into one word at the first syllable's time.
        assertEquals(4, first.words().size());
        assertEquals(1200, first.words().get(1).startMs());
        assertEquals("watches", first.words().get(1).text());
    }

    @Test
    void lineAndUnsyncedTypesGiveNothing() {
        assertTrue(LyricsPlusClient.parse("{\"type\":\"Line\",\"lyrics\":[]}").isEmpty());
        assertTrue(LyricsPlusClient.parse("{\"type\":\"None\"}").isEmpty());
    }

    @Test
    void standsInWithWordTimesWhereItHasThem() {
        Lyrics lyrics = Lyrics.parseLrc(LyricsPlusClient.lrc(WORD_RESPONSE).orElseThrow());
        assertTrue(lyrics.hasWordTiming());
        assertEquals(2, lyrics.lines().size());
        assertEquals("She watches the ships", lyrics.lines().get(0).text());
        assertEquals(1200, lyrics.wordTimes(0, 60_000)[1]);
    }

    @Test
    void standsInLineByLineWhenItHasOnlyLines() {
        String lines = """
                {"type":"Line","metadata":{"source":"Musixmatch"},"lyrics":[
                  {"time":2500,"text":"I can't sleep until I feel your touch"},
                  {"time":6100,"text":""},
                  {"time":7300,"text":"I said, ooh"}]}
                """;
        Lyrics lyrics = Lyrics.parseLrc(LyricsPlusClient.lrc(lines).orElseThrow());
        assertFalse(lyrics.hasWordTiming());
        assertEquals(2, lyrics.lines().size());
        assertEquals(2500, lyrics.lines().get(0).startMs());
        assertEquals(7300, lyrics.lines().get(1).startMs());
    }

    @Test
    void unsyncedLyricsDoNotStandIn() {
        assertTrue(LyricsPlusClient.lrc("{\"type\":\"None\",\"lyrics\":[{\"text\":\"plain words\"}]}").isEmpty());
    }

    @Test
    void takesWordTimesWhenTheLinesLineUp() {
        Lyrics base = Lyrics.parseLrc(lrcAt(0));
        TimedWords timed = timedAt(0);
        WordTimeTransfer.Result result = WordTimeTransfer.transfer(base, timed).orElseThrow();
        assertTrue(result.lyrics().hasWordTiming());
        assertEquals(0, result.shiftMs());
        assertEquals(10, result.matched());
        assertTrue(result.lrc().startsWith(WordTimeTransfer.MARK));
    }

    @Test
    void shiftsAwayAConstantOffsetBetweenTwoMasterings() {
        Lyrics base = Lyrics.parseLrc(lrcAt(0));
        // The same recording, but LyricsPlus timed a version that starts 1.2 s later.
        WordTimeTransfer.Result result = WordTimeTransfer.transfer(base, timedAt(1200)).orElseThrow();
        assertEquals(-1200, result.shiftMs());
        // After the shift the first line is back where LRCLIB has it.
        assertEquals(base.lines().get(0).startMs(), result.lyrics().lines().get(0).startMs(), 60);
    }

    @Test
    void refusesWordTimesOfAnotherSong() {
        Lyrics base = Lyrics.parseLrc(lrcAt(0));
        StringBuilder other = new StringBuilder("{\"type\":\"Word\",\"metadata\":{\"source\":\"QQ Music\"},\"lyrics\":[");
        for (int i = 0; i < 12; i++) {
            long t = i * 2000L;
            other.append(i > 0 ? "," : "").append("{\"time\":").append(t)
                    .append(",\"syllabus\":[{\"time\":").append(t).append(",\"text\":\"different").append(i).append("\"}]}");
        }
        assertTrue(WordTimeTransfer.transfer(base, LyricsPlusClient.parse(other + "]}").orElseThrow()).isEmpty());
    }

    @Test
    void refusesWhenTheMatchesDisagree() {
        // Half the lines match at +0 ms, half at +2500 ms: no single recording, so nothing is taken.
        Lyrics base = Lyrics.parseLrc(lrcAt(0));
        StringBuilder mixed = new StringBuilder("{\"type\":\"Word\",\"metadata\":{\"source\":\"Apple\"},\"lyrics\":[");
        for (int i = 0; i < 10; i++) {
            long t = i * 4000L + (i % 2 == 0 ? 0 : 2500);
            mixed.append(i > 0 ? "," : "").append("{\"time\":").append(t)
                    .append(",\"syllabus\":[{\"time\":").append(t).append(",\"text\":\"line").append(i).append("\"}]}");
        }
        assertTrue(WordTimeTransfer.transfer(base, LyricsPlusClient.parse(mixed + "]}").orElseThrow()).isEmpty());
    }

    private static String lrcAt(long shiftMs) {
        StringBuilder lrc = new StringBuilder();
        for (int i = 0; i < 10; i++) {
            lrc.append('[').append(stamp(i * 4000L + shiftMs)).append(']').append("line ").append(i).append('\n');
        }
        return lrc.toString();
    }

    private static TimedWords timedAt(long shiftMs) {
        StringBuilder json = new StringBuilder("{\"type\":\"Word\",\"metadata\":{\"source\":\"Apple\"},\"lyrics\":[");
        for (int i = 0; i < 10; i++) {
            long t = i * 4000L + shiftMs;
            json.append(i > 0 ? "," : "").append("{\"time\":").append(t).append(",\"syllabus\":[")
                    .append("{\"time\":").append(t).append(",\"text\":\"line \"},")
                    .append("{\"time\":").append(t + 500).append(",\"text\":\"").append(i).append("\"}]}");
        }
        return LyricsPlusClient.parse(json + "]}").orElseThrow();
    }

    private static String stamp(long ms) {
        return String.format(java.util.Locale.ROOT, "%02d:%02d.%03d", ms / 60_000, ms / 1000 % 60, ms % 1000);
    }
}
