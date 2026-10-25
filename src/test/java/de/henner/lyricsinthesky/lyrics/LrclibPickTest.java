package de.henner.lyricsinthesky.lyrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

class LrclibPickTest {
    private static JsonObject result(int id, double durationS, String synced) {
        JsonObject object = new JsonObject();
        object.addProperty("id", id);
        object.addProperty("duration", durationS);
        object.addProperty("syncedLyrics", synced);
        return object;
    }

    @Test
    void wordTimedLyricsWinOverCloserLineTimedOnes() {
        JsonArray results = new JsonArray();
        results.add(result(1, 200.0, "[00:10.00]line timed"));
        results.add(result(2, 201.5, "[00:10.00]<00:10.00>word <00:10.40>timed"));
        assertEquals(2, LrclibClient.pick(results, 200_000).orElseThrow().get("id").getAsInt());
    }

    @Test
    void otherwiseTheClosestLengthWinsAndFarOnesAreIgnored() {
        JsonArray results = new JsonArray();
        results.add(result(1, 203.5, "[00:10.00]<00:10.00>word <00:10.40>timed but too long"));
        results.add(result(2, 201.0, "[00:10.00]a"));
        results.add(result(3, 200.2, "[00:10.00]b"));
        assertEquals(3, LrclibClient.pick(results, 200_000).orElseThrow().get("id").getAsInt());
        assertTrue(LrclibClient.pick(new JsonArray(), 200_000).isEmpty());
    }

    @Test
    void resultsWithNullFieldsAreHandled() {
        JsonArray results = new JsonArray();
        JsonObject noDuration = result(1, 0, "[00:10.00]a");
        noDuration.add("duration", com.google.gson.JsonNull.INSTANCE);
        noDuration.add("instrumental", com.google.gson.JsonNull.INSTANCE);
        JsonObject noLyrics = result(2, 200.0, "x");
        noLyrics.add("syncedLyrics", com.google.gson.JsonNull.INSTANCE);
        noLyrics.add("instrumental", com.google.gson.JsonNull.INSTANCE);
        results.add(noDuration);
        results.add(noLyrics);
        assertEquals(1, LrclibClient.pick(results, 200_000).orElseThrow().get("id").getAsInt());
    }

    @Test
    void knowsWhetherLyricsHaveWordTimestamps() {
        assertTrue(Lyrics.parseLrc("[00:01.00]<00:01.00>one <00:01.50>two").hasWordTiming());
        assertEquals(false, Lyrics.parseLrc("[00:01.00]one two").hasWordTiming());
    }
}
