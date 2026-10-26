package de.henner.lyricsinthesky.lyrics;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import de.henner.lyricsinthesky.nowplaying.NowPlaying;
import java.io.IOException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Word times from SyncLRC (api.synclrc.dev), a free lyrics API that hands out "karaoke" lyrics with a time for every
 * word as enhanced LRC. Optional and off by default; when on, the playing song's artist, title, album and length go to
 * api.synclrc.dev. Songs it has not timed yet it answers with "sources unavailable" (HTTP 503); those are asked again
 * later instead of counting as "no word times".
 */
public final class SyncLrcClient implements WordTimeSource {
    private static final String BASE = "https://api.synclrc.dev/lyrics?";

    private final WebText web;

    public SyncLrcClient(String modVersion) {
        this.web = new WebText(modVersion, Duration.ofSeconds(15));
    }

    @Override
    public String name() {
        return "SyncLRC";
    }

    @Override
    public Optional<TimedWords> find(NowPlaying track) throws IOException, InterruptedException {
        Map<String, String> query = new LinkedHashMap<>();
        query.put("track", track.title());
        query.put("artist", track.artist());
        if (!track.album().isBlank()) {
            query.put("album", track.album());
        }
        if (track.durationMs() > 0) {
            query.put("duration", Long.toString(Math.round(track.durationMs() / 1000.0)));
        }
        query.put("type", "karaoke");
        query.put("format", "lrc");
        try {
            return web.get(BASE + WebText.query(query)).flatMap(SyncLrcClient::parse);
        } catch (WebText.StatusException e) {
            if (e.status == 503 || e.status == 504) {
                throw new Later("SyncLRC has no word times for it yet (HTTP " + e.status + ")");
            }
            throw e;
        }
    }

    /**
     * The word times of an answer; empty when SyncLRC only had lines or plain text, which it hands out instead of
     * karaoke lyrics it does not have.
     */
    static Optional<TimedWords> parse(String body) {
        JsonObject root = JsonParser.parseString(body).getAsJsonObject();
        JsonElement type = root.get("type");
        JsonElement lyrics = root.get("lyrics");
        if (type == null || !"karaoke".equals(type.getAsString()) || lyrics == null || lyrics.isJsonNull()) {
            return Optional.empty();
        }
        TimedWords words = TimedWords.fromLrc("SyncLRC", lyrics.getAsString());
        return words.lines().isEmpty() ? Optional.empty() : Optional.of(words);
    }
}
