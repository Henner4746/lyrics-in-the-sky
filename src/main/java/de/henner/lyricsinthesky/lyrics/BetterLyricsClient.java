package de.henner.lyricsinthesky.lyrics;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import de.henner.lyricsinthesky.nowplaying.NowPlaying;
import java.io.IOException;
import java.io.StringReader;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

/**
 * Word times from Better Lyrics (api.betterlyrics.org), the server of the browser extension of the same name, which
 * hands out lyrics timed word by word or syllable by syllable as TTML, mostly Apple Music's. Free without a key for
 * songs it has kept; others it answers with 401 until someone with a key asked for them, so those are asked again
 * later. Optional and off by default; when on, the playing song's title, artist, album and length go to it.
 */
public final class BetterLyricsClient implements WordTimeSource {
    private static final String BASE = "https://api.betterlyrics.org/getLyrics?";
    private static final String TTML = "http://www.w3.org/ns/ttml";
    private static final String TTML_METADATA = "http://www.w3.org/ns/ttml#metadata";

    private final WebText web;

    public BetterLyricsClient(String modVersion) {
        this.web = new WebText(modVersion, Duration.ofSeconds(15));
    }

    @Override
    public String name() {
        return "BetterLyrics";
    }

    @Override
    public Optional<TimedWords> find(NowPlaying track) throws IOException, InterruptedException {
        // All four, as the service asks: its store is keyed by album and length too.
        Map<String, String> query = new LinkedHashMap<>();
        query.put("s", track.title());
        query.put("a", track.artist());
        if (!track.album().isBlank()) {
            query.put("al", track.album());
        }
        if (track.durationMs() > 0) {
            query.put("d", Long.toString(Math.round(track.durationMs() / 1000.0)));
        }
        try {
            return web.get(BASE + WebText.query(query)).flatMap(BetterLyricsClient::parse);
        } catch (WebText.StatusException e) {
            if (e.status == 401 || e.status == 429 || e.status == 503) {
                throw new Later("Better Lyrics has not kept it (HTTP " + e.status + ")");
            }
            throw e;
        }
    }

    static Optional<TimedWords> parse(String body) {
        JsonObject root = JsonParser.parseString(body).getAsJsonObject();
        JsonElement ttml = root.get("ttml");
        if (ttml == null || ttml.isJsonNull()) {
            return Optional.empty();
        }
        TimedWords words = fromTtml("BetterLyrics", ttml.getAsString());
        return words.lines().isEmpty() ? Optional.empty() : Optional.of(words);
    }

    /**
     * Lines of TTML lyrics with a time per word. Spans written together without a space are syllables of one word,
     * which starts with the first of them; background vocals ({@code ttm:role="x-bg"}) are left out, they are sung over
     * the main line.
     */
    static TimedWords fromTtml(String source, String ttml) {
        List<TimedWords.Line> lines = new ArrayList<>();
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setExpandEntityReferences(false);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document document = builder.parse(new InputSource(new StringReader(ttml)));
            NodeList paragraphs = document.getElementsByTagNameNS(TTML, "p");
            for (int i = 0; i < paragraphs.getLength(); i++) {
                List<TimedWords.Word> words = words((Element) paragraphs.item(i));
                if (!words.isEmpty()) {
                    lines.add(new TimedWords.Line(words.get(0).startMs(), words));
                }
            }
        } catch (Exception e) {
            return new TimedWords(source, List.of());
        }
        return new TimedWords(source, lines);
    }

    private static List<TimedWords.Word> words(Element paragraph) {
        List<TimedWords.Word> words = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        long start = -1;
        boolean apart = true;
        for (Node child = paragraph.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element span && "span".equals(span.getLocalName())) {
                if ("x-bg".equals(span.getAttributeNS(TTML_METADATA, "role"))) {
                    continue;
                }
                long begin = time(span.getAttribute("begin"));
                String part = span.getTextContent().strip();
                if (part.isEmpty() || begin < 0) {
                    continue;
                }
                if (apart && !text.isEmpty()) {
                    words.add(new TimedWords.Word(start, text.toString()));
                    text.setLength(0);
                }
                if (text.isEmpty()) {
                    start = begin;
                }
                text.append(part);
                // A syllable written right before the next span belongs to the same word.
                apart = part.endsWith(" ") || span.getTextContent().endsWith(" ");
            } else if (child.getNodeType() == Node.TEXT_NODE) {
                apart = apart || child.getTextContent().chars().anyMatch(Character::isWhitespace);
            }
        }
        if (!text.isEmpty()) {
            words.add(new TimedWords.Word(start, text.toString()));
        }
        return words;
    }

    /** "1:02.345", "02.345", "1:02:03.4" or "62.345s" in ms, or -1. */
    static long time(String value) {
        if (value == null || value.isBlank()) {
            return -1;
        }
        try {
            String clean = value.strip();
            if (clean.endsWith("s")) {
                return Math.round(Double.parseDouble(clean.substring(0, clean.length() - 1)) * 1000);
            }
            double seconds = 0;
            for (String part : clean.split(":")) {
                seconds = seconds * 60 + Double.parseDouble(part);
            }
            return Math.round(seconds * 1000);
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
