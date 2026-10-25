package de.henner.lyricsinthesky.lyrics;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import de.henner.lyricsinthesky.nowplaying.NowPlaying;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Word times from Deezer, whose lyrics (licensed from Musixmatch, like Spotify's) are often timed word by word. The
 * song is found with Deezer's public search; the lyrics come from the API of Deezer's web player, which hands an
 * anonymous visitor a short session without any account. That session lives only in memory. Optional and off by
 * default; when on, the playing song's artist and title go to api.deezer.com, its Deezer ID to pipe.deezer.com.
 */
public final class DeezerClient implements WordTimeSource {
    private static final String SEARCH = "https://api.deezer.com/search?limit=10&q=";
    private static final String SESSION = "https://auth.deezer.com/login/anonymous?jo=p&rto=c&i=c";
    private static final String LYRICS = "https://pipe.deezer.com/api";
    /** The anonymous session is fetched again after this long, well before Deezer lets it expire. */
    private static final long SESSION_MS = 10 * 60 * 1000;
    private static final String QUERY = "query Lyrics($id: String!) { track(trackId: $id) { lyrics { "
            + "synchronizedWordByWordLines { start words { start word } } } } }";
    private static final int TRIES = 2;

    private final WebText web;
    private String session;
    private long sessionAtMs;

    public DeezerClient(String modVersion) {
        this.web = new WebText(modVersion, Duration.ofSeconds(10));
    }

    @Override
    public String name() {
        return "Deezer";
    }

    @Override
    public Optional<TimedWords> find(NowPlaying track) throws IOException, InterruptedException {
        Optional<String> found = web.get(SEARCH + URLEncoder.encode(SearchHit.searchText(track), StandardCharsets.UTF_8));
        if (found.isEmpty()) {
            return Optional.empty();
        }
        for (SearchHit hit : SearchHit.matching(track, hits(found.get()), TRIES)) {
            JsonObject request = new JsonObject();
            request.addProperty("query", QUERY);
            JsonObject variables = new JsonObject();
            variables.addProperty("id", hit.id());
            request.add("variables", variables);
            Optional<TimedWords> words;
            try {
                words = web.postJson(LYRICS, request.toString(), "Authorization", "Bearer " + session())
                        .flatMap(DeezerClient::parse);
            } catch (IOException e) {
                // A session Deezer no longer takes is fetched anew next time.
                forgetSession();
                throw e;
            }
            if (words.isPresent()) {
                return words;
            }
        }
        return Optional.empty();
    }

    private synchronized String session() throws IOException, InterruptedException {
        if (session == null || System.currentTimeMillis() - sessionAtMs > SESSION_MS) {
            JsonObject answer = JsonParser.parseString(web.get(SESSION).orElseThrow(
                    () -> new IOException("Deezer gave no session"))).getAsJsonObject();
            if (!answer.has("jwt") || answer.get("jwt").isJsonNull()) {
                throw new IOException("Deezer gave no session");
            }
            session = answer.get("jwt").getAsString();
            sessionAtMs = System.currentTimeMillis();
        }
        return session;
    }

    private synchronized void forgetSession() {
        session = null;
    }

    static List<SearchHit> hits(String body) {
        List<SearchHit> hits = new ArrayList<>();
        JsonObject root = JsonParser.parseString(body).getAsJsonObject();
        if (!root.has("data") || !root.get("data").isJsonArray()) {
            return hits;
        }
        for (JsonElement element : root.getAsJsonArray("data")) {
            JsonObject song = element.getAsJsonObject();
            String artist = song.has("artist") && song.get("artist").isJsonObject()
                    ? text(song.getAsJsonObject("artist"), "name") : "";
            long duration = song.has("duration") ? song.get("duration").getAsLong() * 1000 : 0;
            hits.add(new SearchHit(text(song, "id"), text(song, "title"), artist, duration, ""));
        }
        return hits;
    }

    /** The word-timed lines of a lyrics answer, empty when Deezer has the song only line by line or not at all. */
    static Optional<TimedWords> parse(String body) {
        JsonElement lyrics = JsonParser.parseString(body);
        for (String key : new String[] {"data", "track", "lyrics", "synchronizedWordByWordLines"}) {
            if (!lyrics.isJsonObject() || !lyrics.getAsJsonObject().has(key)) {
                return Optional.empty();
            }
            lyrics = lyrics.getAsJsonObject().get(key);
        }
        if (!lyrics.isJsonArray()) {
            return Optional.empty();
        }
        List<TimedWords.Line> lines = new ArrayList<>();
        for (JsonElement element : lyrics.getAsJsonArray()) {
            JsonObject line = element.getAsJsonObject();
            if (!line.has("words") || !line.get("words").isJsonArray()) {
                continue;
            }
            JsonArray words = line.getAsJsonArray("words");
            List<TimedWords.Word> syllables = new ArrayList<>();
            for (JsonElement word : words) {
                JsonObject timed = word.getAsJsonObject();
                if (timed.has("start") && !timed.get("start").isJsonNull()) {
                    // Deezer gives whole words without their spaces.
                    syllables.add(new TimedWords.Word(timed.get("start").getAsLong(), text(timed, "word") + " "));
                }
            }
            TimedWords.Line timed = TimedWords.line(syllables);
            if (timed != null) {
                lines.add(timed);
            }
        }
        return lines.isEmpty() ? Optional.empty() : Optional.of(new TimedWords("Deezer", lines));
    }

    private static String text(JsonObject object, String key) {
        return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : "";
    }
}
