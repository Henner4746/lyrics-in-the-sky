package de.henner.lyricsinthesky.hoergenau;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import de.henner.lyricsinthesky.lyrics.Lyrics;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * The sync check end to end with the real whisper.cpp on recorded songs, as Hoergenau runs it. Only runs when
 * {@code SYNCCHECK_LIVE} names a folder with {@code engine} (whisper.cpp in {@code bin} and the models, as the mod keeps
 * them) and {@code songs}: per song {@code <name>.wav} (16 kHz mono, the first 50 s as Hoergenau hears them) and
 * {@code <name>.json} with {@code songStartMs}, the song's position at the first sample, and what the game showed:
 * {@code shown} as [ms, word] pairs, or {@code lrc}, a file of the mod's lyrics store next to it, with
 * {@code durationMs}. {@code SYNCCHECK_MODELS} picks the models (default small and turbo).
 *
 * <p>Measured against the sung audio of a live run, "in-the-end" showed its words 1.08 s late, "roller" 0.84 s late,
 * "get-lucky" on time; these must be found. Other songs, or {@code expectedMs} in their json, are reported or checked.
 */
@EnabledIfEnvironmentVariable(named = "SYNCCHECK_LIVE", matches = ".+")
class SyncCheckLiveTest {
    /** The correction each song must get, in ms, give or take a whisker. */
    private static final Map<String, Integer> EXPECTED = Map.of("in-the-end", 1_080, "roller", 840, "get-lucky", 0);
    private static final int WITHIN_MS = 160;

    @Test
    void theModelsFindTheVersionsTimedForAnotherCut() throws Exception {
        Path dir = Path.of(System.getenv("SYNCCHECK_LIVE"));
        String models = System.getenv().getOrDefault("SYNCCHECK_MODELS", "small,turbo");
        List<String> songs;
        try (Stream<Path> files = Files.list(dir.resolve("songs"))) {
            songs = files.map(file -> file.getFileName().toString()).filter(name -> name.endsWith(".wav"))
                    .map(name -> name.substring(0, name.length() - 4)).sorted().toList();
        }
        List<String> failures = new ArrayList<>();
        for (String model : models.split(",")) {
            WhisperEngine engine = new WhisperEngine(dir.resolve("engine"), () -> model, () -> "high");
            assertTrue(engine.installed(), "whisper.cpp or the model " + model + " missing in " + dir.resolve("engine"));
            for (String song : songs) {
                short[] samples = samples(dir.resolve("songs").resolve(song + ".wav"));
                JsonObject data = JsonParser.parseString(Files.readString(dir.resolve("songs").resolve(song + ".json"),
                        StandardCharsets.UTF_8)).getAsJsonObject();
                long start = data.get("songStartMs").getAsLong();
                List<SyncCheck.Timed> shown = new ArrayList<>();
                if (data.has("lrc")) {
                    Lyrics lyrics = Lyrics.parseLrc(Files.readString(dir.resolve("songs").resolve(
                            data.get("lrc").getAsString()), StandardCharsets.UTF_8));
                    shown.addAll(SyncCheck.shown(lyrics, data.get("durationMs").getAsLong()));
                } else {
                    for (var word : data.getAsJsonArray("shown")) {
                        JsonArray pair = word.getAsJsonArray();
                        shown.add(new SyncCheck.Timed(pair.get(0).getAsLong(), pair.get(1).getAsString()));
                    }
                }
                long started = System.nanoTime();
                // As Hoergenau.check: the language from the recording, then the words with their DTW times.
                String language = engine.language(samples, samples.length);
                List<SyncCheck.Timed> heard = engine.transcribe(samples, 0, samples.length, language).stream()
                        .flatMap(List::stream).map(w -> new SyncCheck.Timed(w.startMs() + start, w.text())).toList();
                SyncCheck.Result result = SyncCheck.measure(shown, heard);
                long seconds = (System.nanoTime() - started) / 1_000_000_000L;
                Integer expected = data.has("expectedMs") ? Integer.valueOf(data.get("expectedMs").getAsInt())
                        : EXPECTED.get(song);
                boolean ok = expected == null || (expected == 0 ? result.correctionMs() == 0
                        : result.trusted() && Math.abs(result.correctionMs() - expected) <= WITHIN_MS);
                System.out.printf("SYNCCHECK %-6s %-11s Sprache %s, %3d Wörter gehört, %3d gepaart, %+5d ms (Streuung %3d)"
                        + " -> Korrektur %+5d ms, erwartet %s, %3d s: %s%n", model, song, language, heard.size(),
                        result.pairs(), result.offsetMs(), result.spreadMs(), result.correctionMs(),
                        expected == null ? "  -  " : String.format("%+5d", expected), seconds,
                        expected == null ? (result.trusted() ? "sicher" : "unsicher") : ok ? "ok" : "FALSCH");
                if (System.getenv("SYNCCHECK_WORDS") != null) {
                    heard.forEach(word -> System.out.printf("  gehört %7d %s%n", word.ms(), word.word()));
                }
                if (!ok) {
                    failures.add(model + " " + song + ": " + result);
                }
            }
        }
        assertTrue(failures.isEmpty(), failures.toString());
    }

    private static short[] samples(Path wav) throws Exception {
        byte[] bytes = Files.readAllBytes(wav);
        ByteBuffer data = ByteBuffer.wrap(bytes, 44, bytes.length - 44).order(ByteOrder.LITTLE_ENDIAN);
        short[] samples = new short[(bytes.length - 44) / 2];
        for (int i = 0; i < samples.length; i++) {
            samples[i] = data.getShort();
        }
        return samples;
    }
}
