package de.henner.lyricsinthesky.lyrics;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import de.henner.lyricsinthesky.nowplaying.NowPlaying;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.StringJoiner;

/**
 * Word times from LyricsPlus, the backend of the YouLy+ browser extension: an unofficial service that reads them from
 * Apple Music and QQ Music. Optional and off by default; when on, the playing song's title, artist, album and length
 * go to lyricsplus.prjktla.my.id. What comes back is only used after {@link WordTimeTransfer} has checked that it was
 * timed for the same recording as the LRCLIB lyrics.
 */
public final class LyricsPlusClient implements WordTimeSource {
    private static final String BASE = "https://lyricsplus.prjktla.my.id/v2/lyrics/get";

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final String userAgent;

    public LyricsPlusClient(String modVersion) {
        this.userAgent = "LyricsInTheSky/" + modVersion + " (Minecraft mod; optional word times)";
    }

    @Override
    public String name() {
        return "LyricsPlus";
    }

    /** Empty when LyricsPlus has no word times for the song: only line times, none, or no lyrics at all. */
    @Override
    public Optional<TimedWords> find(NowPlaying track) throws IOException, InterruptedException {
        return request(track).flatMap(LyricsPlusClient::parse);
    }

    /**
     * The whole lyrics as LRC, for standing in when LRCLIB has none: with word times where LyricsPlus has them,
     * else line by line; empty when it has no synced lyrics.
     */
    public Optional<String> findLrc(NowPlaying track) throws IOException, InterruptedException {
        return request(track).flatMap(LyricsPlusClient::lrc);
    }

    /** The answer for the song, or empty when LyricsPlus does not know it. */
    private Optional<String> request(NowPlaying track) throws IOException, InterruptedException {
        Map<String, String> query = new LinkedHashMap<>();
        query.put("title", TrackNames.cleanTitle(track.title()));
        query.put("artist", track.artist());
        if (!track.album().isBlank()) {
            query.put("album", track.album());
        }
        if (track.durationMs() > 0) {
            query.put("duration", Long.toString(Math.round(track.durationMs() / 1000.0)));
        }
        StringJoiner params = new StringJoiner("&");
        query.forEach((key, value) -> params.add(key + "=" + URLEncoder.encode(value, StandardCharsets.UTF_8)));
        // A song LyricsPlus has not cached yet is looked up at Apple and QQ first, which takes a while.
        HttpRequest request = HttpRequest.newBuilder(URI.create(BASE + "?" + params))
                .timeout(Duration.ofSeconds(30))
                .header("User-Agent", userAgent)
                .GET()
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() == 404) {
            return Optional.empty();
        }
        if (response.statusCode() != 200) {
            throw new IOException("LyricsPlus answered HTTP " + response.statusCode());
        }
        return Optional.of(response.body());
    }

    /** LRC of an answer: word-timed when it has syllables, line-timed when it has only lines, else empty. */
    static Optional<String> lrc(String body) {
        Optional<TimedWords> timed = parse(body);
        if (timed.isPresent()) {
            return Optional.of(WordTimeTransfer.lrc(timed.get().lines(), 0, WordTimeTransfer.STAND_IN_MARK));
        }
        JsonObject root = JsonParser.parseString(body).getAsJsonObject();
        if (!"Line".equalsIgnoreCase(string(root, "type")) || !root.has("lyrics") || !root.get("lyrics").isJsonArray()) {
            return Optional.empty();
        }
        StringBuilder lrc = new StringBuilder(WordTimeTransfer.STAND_IN_MARK).append('\n');
        int lines = 0;
        for (JsonElement element : root.getAsJsonArray("lyrics")) {
            JsonObject line = element.getAsJsonObject();
            String text = string(line, "text").trim();
            if (text.isEmpty() || !line.has("time") || line.get("time").isJsonNull()) {
                continue;
            }
            lrc.append('[').append(WordTimeTransfer.stamp(line.get("time").getAsLong())).append(']').append(text).append('\n');
            lines++;
        }
        return lines == 0 ? Optional.empty() : Optional.of(lrc.toString());
    }

    static Optional<TimedWords> parse(String body) {
        JsonObject root = JsonParser.parseString(body).getAsJsonObject();
        String type = string(root, "type");
        if (!"Word".equalsIgnoreCase(type) && !"Syllable".equalsIgnoreCase(type)) {
            return Optional.empty();
        }
        String source = root.has("metadata") && root.get("metadata").isJsonObject()
                ? string(root.getAsJsonObject("metadata"), "source") : "";
        List<TimedWords.Line> lines = new ArrayList<>();
        if (root.has("lyrics") && root.get("lyrics").isJsonArray()) {
            for (JsonElement element : root.getAsJsonArray("lyrics")) {
                JsonObject line = element.getAsJsonObject();
                if (!line.has("syllabus") || !line.get("syllabus").isJsonArray()) {
                    continue;
                }
                List<TimedWords.Word> words = words(line.getAsJsonArray("syllabus"));
                if (!words.isEmpty()) {
                    lines.add(new TimedWords.Line(words.get(0).startMs(), words));
                }
            }
        }
        return lines.isEmpty() ? Optional.empty() : Optional.of(new TimedWords(source, lines));
    }

    /** Syllables joined into words: a syllable ending in a space ends its word. Backing vocals are left out. */
    static List<TimedWords.Word> words(JsonArray syllables) {
        List<TimedWords.Word> words = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        long start = -1;
        for (JsonElement element : syllables) {
            JsonObject syllable = element.getAsJsonObject();
            if (flag(syllable, "isBackground") || !syllable.has("time") || syllable.get("time").isJsonNull()) {
                continue;
            }
            String text = string(syllable, "text");
            if (start < 0) {
                start = syllable.get("time").getAsLong();
            }
            current.append(text);
            if (!text.isEmpty() && Character.isWhitespace(text.charAt(text.length() - 1))) {
                add(words, start, current);
                start = -1;
            }
        }
        add(words, start, current);
        return words;
    }

    private static void add(List<TimedWords.Word> words, long start, StringBuilder text) {
        String word = text.toString().trim();
        text.setLength(0);
        if (start >= 0 && !word.isEmpty()) {
            words.add(new TimedWords.Word(start, word));
        }
    }

    private static boolean flag(JsonObject object, String key) {
        return object.has(key) && !object.get(key).isJsonNull() && object.get(key).getAsBoolean();
    }

    private static String string(JsonObject object, String key) {
        return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : "";
    }
}
