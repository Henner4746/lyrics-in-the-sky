package de.henner.lyricsinthesky.lyrics;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import de.henner.lyricsinthesky.nowplaying.NowPlaying;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Word times from NetEase Cloud Music, whose "YRC" lyrics time every syllable, also of many western songs. Its public
 * web API answers without an account. Optional and off by default; when on, the playing song's artist and title go to
 * music.163.com, and the length picks the right one of the songs found.
 */
public final class NeteaseClient implements WordTimeSource {
    private static final String BASE = "https://music.163.com/api/";
    /** A YRC line: {@code [start,duration]} and then the syllables. */
    private static final Pattern LINE = Pattern.compile("^\\[(\\d+),(\\d+)](.*)$");
    /** A YRC syllable mark: {@code (start,duration,0)}, the syllable's text follows up to the next mark. */
    private static final Pattern SYLLABLE = Pattern.compile("\\((\\d+),(\\d+),-?\\d+\\)");
    private static final int SEARCH_RESULTS = 10;
    /** Songs found under the same name are tried one after the other, but not endlessly. */
    private static final int TRIES = 2;

    private final WebText web;

    public NeteaseClient(String modVersion) {
        this.web = new WebText(modVersion, Duration.ofSeconds(10));
    }

    @Override
    public String name() {
        return "NetEase";
    }

    @Override
    public Optional<TimedWords> find(NowPlaying track) throws IOException, InterruptedException {
        for (SearchHit hit : search(track)) {
            Optional<TimedWords> words = web.get(BASE + "song/lyric/v1?id=" + hit.id() + "&lv=-1&yv=-1&tv=0&rv=0&kv=0")
                    .flatMap(NeteaseClient::parse);
            if (words.isPresent()) {
                return words;
            }
        }
        return Optional.empty();
    }

    /** NetEase's songs that are the one playing, best first; also used to find AMLL's lyrics, which go by NetEase IDs. */
    List<SearchHit> search(NowPlaying track) throws IOException, InterruptedException {
        Map<String, String> query = new LinkedHashMap<>();
        query.put("s", SearchHit.searchText(track));
        query.put("type", "1");
        query.put("limit", Integer.toString(SEARCH_RESULTS));
        Optional<String> body = web.get(BASE + "search/get?" + WebText.query(query));
        return body.map(text -> SearchHit.matching(track, hits(text), TRIES)).orElse(List.of());
    }

    static List<SearchHit> hits(String body) {
        List<SearchHit> hits = new ArrayList<>();
        JsonObject root = JsonParser.parseString(body).getAsJsonObject();
        if (!root.has("result") || !root.get("result").isJsonObject()
                || !root.getAsJsonObject("result").has("songs")) {
            return hits;
        }
        for (JsonElement element : root.getAsJsonObject("result").getAsJsonArray("songs")) {
            JsonObject song = element.getAsJsonObject();
            StringJoiner artists = new StringJoiner(", ");
            if (song.has("artists") && song.get("artists").isJsonArray()) {
                song.getAsJsonArray("artists").forEach(artist -> artists.add(text(artist.getAsJsonObject(), "name")));
            }
            long duration = song.has("duration") ? song.get("duration").getAsLong() : 0;
            hits.add(new SearchHit(song.get("id").getAsString(), text(song, "name"), artists.toString(), duration, ""));
        }
        return hits;
    }

    /** The YRC lyrics of a lyric answer, empty when the song has none (most have only line times). */
    static Optional<TimedWords> parse(String body) {
        JsonObject root = JsonParser.parseString(body).getAsJsonObject();
        if (!root.has("yrc") || !root.get("yrc").isJsonObject()) {
            return Optional.empty();
        }
        return parseYrc(text(root.getAsJsonObject("yrc"), "lyric"));
    }

    static Optional<TimedWords> parseYrc(String yrc) {
        List<TimedWords.Line> lines = new ArrayList<>();
        for (String raw : yrc.split("\\R")) {
            // Credit lines come as JSON ({"t":0,"c":[…]}) and are left out with everything else that is not a line.
            Matcher line = LINE.matcher(raw.strip());
            if (!line.matches()) {
                continue;
            }
            String body = line.group(3);
            Matcher mark = SYLLABLE.matcher(body);
            List<TimedWords.Word> syllables = new ArrayList<>();
            long start = -1;
            int textFrom = -1;
            while (mark.find()) {
                if (start >= 0) {
                    syllables.add(new TimedWords.Word(start, body.substring(textFrom, mark.start())));
                }
                start = Long.parseLong(mark.group(1));
                textFrom = mark.end();
            }
            if (start >= 0) {
                syllables.add(new TimedWords.Word(start, body.substring(textFrom)));
            }
            TimedWords.Line timed = TimedWords.line(syllables);
            if (timed != null) {
                lines.add(timed);
            }
        }
        return lines.isEmpty() ? Optional.empty() : Optional.of(new TimedWords("NetEase", lines));
    }

    private static String text(JsonObject object, String key) {
        return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : "";
    }
}
