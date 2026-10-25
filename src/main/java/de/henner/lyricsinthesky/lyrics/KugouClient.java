package de.henner.lyricsinthesky.lyrics;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import de.henner.lyricsinthesky.nowplaying.NowPlaying;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Word times from Kugou Music's "KRC" lyrics, which its lyrics server hands out without an account. Optional and off by
 * default; when on, the playing song's artist, title and length go to lyrics.kugou.com.
 */
public final class KugouClient implements WordTimeSource {
    private static final String BASE = "https://lyrics.kugou.com/";
    /**
     * KRC files are zlib data XOR'd with this fixed key, the same in every Kugou player (it is no secret: open-source
     * lyric tools such as LyricsX and Lyricify carry it too).
     */
    private static final byte[] KEY = {64, 71, 97, 119, 94, 50, 116, 71, 81, 54, 49, 45, (byte) 206, (byte) 210, 110, 105};
    private static final Pattern LINE = Pattern.compile("^\\[(\\d+),(\\d+)](.*)$");
    /** A KRC syllable mark: {@code <offset,duration,0>}, the offset counts from the line's start. */
    private static final Pattern SYLLABLE = Pattern.compile("<(-?\\d+),(\\d+),-?\\d+>");
    private static final int TRIES = 2;

    private final WebText web;

    public KugouClient(String modVersion) {
        this.web = new WebText(modVersion, Duration.ofSeconds(10));
    }

    @Override
    public String name() {
        return "Kugou";
    }

    @Override
    public Optional<TimedWords> find(NowPlaying track) throws IOException, InterruptedException {
        Map<String, String> query = new LinkedHashMap<>();
        query.put("ver", "1");
        query.put("man", "yes");
        query.put("client", "pc");
        query.put("keyword", TrackNames.primaryArtist(track.artist()) + " - " + SearchHit.coreTitle(track.title()));
        if (track.durationMs() > 0) {
            query.put("duration", Long.toString(track.durationMs()));
        }
        Optional<String> found = web.get(BASE + "search?" + WebText.query(query));
        if (found.isEmpty()) {
            return Optional.empty();
        }
        for (SearchHit hit : SearchHit.matching(track, hits(found.get()), TRIES)) {
            Map<String, String> download = new LinkedHashMap<>();
            download.put("ver", "1");
            download.put("client", "pc");
            download.put("id", hit.id());
            download.put("accesskey", hit.key());
            download.put("fmt", "krc");
            download.put("charset", "utf8");
            Optional<TimedWords> words = web.get(BASE + "download?" + WebText.query(download))
                    .flatMap(KugouClient::content)
                    .flatMap(KugouClient::parseKrc);
            if (words.isPresent()) {
                return words;
            }
        }
        return Optional.empty();
    }

    static List<SearchHit> hits(String body) {
        List<SearchHit> hits = new ArrayList<>();
        JsonObject root = JsonParser.parseString(body).getAsJsonObject();
        if (!root.has("candidates") || !root.get("candidates").isJsonArray()) {
            return hits;
        }
        for (JsonElement element : root.getAsJsonArray("candidates")) {
            JsonObject candidate = element.getAsJsonObject();
            long duration = candidate.has("duration") ? candidate.get("duration").getAsLong() : 0;
            hits.add(new SearchHit(text(candidate, "id"), text(candidate, "song"), text(candidate, "singer"), duration,
                    text(candidate, "accesskey")));
        }
        return hits;
    }

    /** The KRC text of a download answer, empty when it holds none or cannot be unpacked. */
    static Optional<String> content(String body) {
        JsonObject root = JsonParser.parseString(body).getAsJsonObject();
        String content = text(root, "content");
        if (content.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(decode(Base64.getDecoder().decode(content)));
        } catch (IllegalArgumentException | DataFormatException e) {
            return Optional.empty();
        }
    }

    /** Unpacks a KRC file: a 4-byte "krc1" head, then zlib data XOR'd with {@link #KEY}. */
    static String decode(byte[] krc) throws DataFormatException {
        if (krc.length <= 4) {
            throw new DataFormatException("too short for KRC");
        }
        byte[] packed = new byte[krc.length - 4];
        for (int i = 0; i < packed.length; i++) {
            packed[i] = (byte) (krc[i + 4] ^ KEY[i % KEY.length]);
        }
        Inflater inflater = new Inflater();
        inflater.setInput(packed);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        try {
            while (!inflater.finished()) {
                int n = inflater.inflate(buffer);
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
                    break;
                }
                out.write(buffer, 0, n);
            }
        } finally {
            inflater.end();
        }
        String text = out.toString(StandardCharsets.UTF_8);
        return text.startsWith("﻿") ? text.substring(1) : text;
    }

    static Optional<TimedWords> parseKrc(String krc) {
        List<TimedWords.Line> lines = new ArrayList<>();
        String title = null;
        String artist = null;
        for (String raw : krc.split("\\R")) {
            String text = raw.strip();
            if (text.startsWith("[ti:")) {
                title = text.substring(4, text.length() - 1).strip();
            } else if (text.startsWith("[ar:")) {
                artist = text.substring(4, text.length() - 1).strip();
            }
            Matcher line = LINE.matcher(text);
            if (!line.matches()) {
                continue;
            }
            long lineStart = Long.parseLong(line.group(1));
            String body = line.group(3);
            Matcher mark = SYLLABLE.matcher(body);
            List<TimedWords.Word> syllables = new ArrayList<>();
            long start = -1;
            int textFrom = -1;
            while (mark.find()) {
                if (start >= 0) {
                    syllables.add(new TimedWords.Word(start, body.substring(textFrom, mark.start())));
                }
                start = Math.max(0, lineStart + Long.parseLong(mark.group(1)));
                textFrom = mark.end();
            }
            if (start >= 0) {
                syllables.add(new TimedWords.Word(start, body.substring(textFrom)));
            }
            TimedWords.Line timed = TimedWords.line(syllables);
            // Kugou often opens with a line "Artist - Title", which is not sung.
            if (timed != null && !(artist != null && title != null
                    && WordTimeTransfer.normalize(timed.text()).equals(WordTimeTransfer.normalize(artist + title)))) {
                lines.add(timed);
            }
        }
        return lines.isEmpty() ? Optional.empty() : Optional.of(new TimedWords("Kugou", lines));
    }

    private static String text(JsonObject object, String key) {
        return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : "";
    }
}
