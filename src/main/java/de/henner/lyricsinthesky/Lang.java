package de.henner.lyricsinthesky;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * What the player reads, in German or in English: chosen the first time the menu opens and on the page "Menü"
 * afterwards, until then the game's language decides. The texts are written in German in the code; the English of
 * each comes from {@code lyricsinthesky/lang/en.json}, keyed by the German, and is looked up where text is drawn or
 * sent to chat, so the code reads as before. Texts with values in them give both themselves ({@link #f}, {@link #of}).
 */
public final class Lang {
    private static final Logger LOGGER = LoggerFactory.getLogger("lyricsinthesky");
    private static final String DICTIONARY = "/lyricsinthesky/lang/en.json";
    private static final Map<String, String> ENGLISH = load();
    /** In the self test: German texts shown in English without an English one are logged, once each. */
    private static final boolean REPORT = Boolean.getBoolean("lyricsinthesky.selftest");
    private static final Set<String> REPORTED = ConcurrentHashMap.newKeySet();
    private static final Pattern GERMAN = Pattern.compile("[äöüÄÖÜß„“]|\\b(der|die|das|und|nicht|ist|ein|eine|mit|für|"
            + "auf|von|wird|bei|im|zum|dein|deine|Songtexte?|Wort|Zeilen?|Leiste|Spieler)\\b");

    private static volatile Supplier<String> setting = () -> "auto";
    private static volatile Supplier<String> game = () -> "de_de";

    private Lang() {}

    /** The setting ({@code auto}, {@code de} or {@code en}) and the game's language code ({@code de_de}, ...). */
    public static void follow(Supplier<String> chosen, Supplier<String> gameLanguage) {
        setting = chosen;
        game = gameLanguage;
    }

    public static boolean english() {
        String chosen = setting.get();
        if ("en".equals(chosen)) {
            return true;
        }
        if ("de".equals(chosen)) {
            return false;
        }
        return !gameLanguage().startsWith("de");
    }

    /** The game's language, {@code de_de} when it cannot be told (in tests). */
    public static String gameLanguage() {
        try {
            String code = game.get();
            return code == null ? "de_de" : code.toLowerCase(Locale.ROOT);
        } catch (RuntimeException | LinkageError e) {
            return "de_de";
        }
    }

    /** The text as the player reads it: the German, or its English when there is one. */
    public static String t(String german) {
        if (german == null || german.isEmpty() || !english()) {
            return german;
        }
        String english = ENGLISH.get(german);
        if (english != null) {
            return english;
        }
        if (REPORT && GERMAN.matcher(german).find() && REPORTED.add(german)) {
            LOGGER.info("LANG missing: {}", german);
        }
        return german;
    }

    /** One of two texts written out where they are used. */
    public static String of(String german, String english) {
        return english() ? english : german;
    }

    /** A text with values in it, each language with its own pattern ({@link String#format}). */
    public static String f(String german, String english, Object... args) {
        return english() ? String.format(Locale.ENGLISH, english, args) : String.format(Locale.GERMANY, german, args);
    }

    /** How many German texts were shown in English without an English one; counted in the self test only. */
    public static int missing() {
        return REPORTED.size();
    }

    /** Every German text with its English; for the tests. */
    static Map<String, String> dictionary() {
        return Collections.unmodifiableMap(ENGLISH);
    }

    private static Map<String, String> load() {
        Map<String, String> english = new HashMap<>();
        try (InputStream in = Lang.class.getResourceAsStream(DICTIONARY)) {
            if (in == null) {
                LOGGER.warn("No English texts in the mod: {} is missing", DICTIONARY);
                return english;
            }
            JsonObject root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
                english.put(entry.getKey(), entry.getValue().getAsString());
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("Could not read the English texts: {}", e.toString());
        }
        return english;
    }
}
