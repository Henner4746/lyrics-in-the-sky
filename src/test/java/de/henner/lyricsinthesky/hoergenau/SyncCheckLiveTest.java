package de.henner.lyricsinthesky.hoergenau;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * The sync check end to end with the real whisper.cpp, both models, on what a live run recorded: per song the first
 * 50 s as Hoergenau hears them and the word times the game showed. Only runs when {@code SYNCCHECK_LIVE} names a folder
 * with {@code engine} (whisper.cpp in {@code bin} and both models, as the mod keeps them) and {@code songs} (per song
 * {@code <name>.wav}, 16 kHz mono, and {@code <name>.json} with the song's position at the first sample and the shown
 * words). Measured against the sung audio, "in-the-end" showed its words 1.08 s late, "roller" 0.84 s late,
 * "get-lucky" on time.
 */
@EnabledIfEnvironmentVariable(named = "SYNCCHECK_LIVE", matches = ".+")
class SyncCheckLiveTest {
    /** The correction each song must get, in ms, give or take a whisker. */
    private static final Map<String, Integer> EXPECTED = Map.of("in-the-end", 1_080, "roller", 840, "get-lucky", 0);
    private static final int WITHIN_MS = 160;

    @Test
    void bothModelsFindTheVersionsTimedForAnotherCut() throws Exception {
        Path dir = Path.of(System.getenv("SYNCCHECK_LIVE"));
        List<String> failures = new ArrayList<>();
        for (String model : List.of("small", "turbo")) {
            WhisperEngine engine = new WhisperEngine(dir.resolve("engine"), () -> model, () -> "high");
            assertTrue(engine.installed(), "whisper.cpp or the model " + model + " missing in " + dir.resolve("engine"));
            for (Map.Entry<String, Integer> song : EXPECTED.entrySet()) {
                short[] samples = samples(dir.resolve("songs").resolve(song.getKey() + ".wav"));
                JsonObject data = JsonParser.parseString(Files.readString(dir.resolve("songs").resolve(song.getKey() + ".json"),
                        StandardCharsets.UTF_8)).getAsJsonObject();
                long start = data.get("songStartMs").getAsLong();
                List<SyncCheck.Timed> shown = new ArrayList<>();
                for (var word : data.getAsJsonArray("shown")) {
                    JsonArray pair = word.getAsJsonArray();
                    shown.add(new SyncCheck.Timed(pair.get(0).getAsLong(), pair.get(1).getAsString()));
                }
                long started = System.nanoTime();
                // As Hoergenau.check: the language from the recording, then the words with their DTW times.
                String language = engine.language(samples, samples.length);
                List<SyncCheck.Timed> heard = engine.transcribe(samples, 0, samples.length, language).stream()
                        .flatMap(List::stream).map(w -> new SyncCheck.Timed(w.startMs() + start, w.text())).toList();
                SyncCheck.Result result = SyncCheck.measure(shown, heard);
                long seconds = (System.nanoTime() - started) / 1_000_000_000L;
                int expected = song.getValue();
                boolean ok = expected == 0 ? result.correctionMs() == 0
                        : result.trusted() && Math.abs(result.correctionMs() - expected) <= WITHIN_MS;
                System.out.printf("SYNCCHECK %-6s %-11s Sprache %s, %3d Wörter gehört, %3d gepaart, %+5d ms (Streuung %3d)"
                        + " -> Korrektur %+5d ms, erwartet %+5d, %3d s: %s%n", model, song.getKey(), language, heard.size(),
                        result.pairs(), result.offsetMs(), result.spreadMs(), result.correctionMs(), expected, seconds,
                        ok ? "ok" : "FALSCH");
                if (!ok) {
                    failures.add(model + " " + song.getKey() + ": " + result);
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
