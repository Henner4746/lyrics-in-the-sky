package de.henner.lyricsinthesky.hoergenau;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import de.henner.lyricsinthesky.hoergenau.WhisperEngine.Heard;
import de.henner.lyricsinthesky.lyrics.Lyrics;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Hoergenau end to end on Windows with the real whisper.cpp: download and check, then a song whose line starts are
 * known. Only runs when {@code HOERGENAU_LIVE} names a folder with {@code testsong.wav} and {@code testsong.json}
 * (lines and their real starts); whisper.cpp is installed into its {@code engine} folder.
 */
@EnabledIfEnvironmentVariable(named = "HOERGENAU_LIVE", matches = ".+")
class HoergenauLiveTest {
    @Test
    void timesASongItHears() throws Exception {
        Path dir = Path.of(System.getenv("HOERGENAU_LIVE"));
        WhisperEngine engine = new WhisperEngine(dir.resolve("engine"));
        long started = System.nanoTime();
        engine.install(percent -> { });
        System.out.printf("installiert in %d s%n", (System.nanoTime() - started) / 1_000_000_000);
        byte[] wav = Files.readAllBytes(dir.resolve("testsong.wav"));
        ByteBuffer data = ByteBuffer.wrap(wav, 44, wav.length - 44).order(ByteOrder.LITTLE_ENDIAN);
        short[] samples = new short[(wav.length - 44) / 2];
        for (int i = 0; i < samples.length; i++) {
            samples[i] = data.getShort();
        }
        JsonObject truth = JsonParser.parseString(Files.readString(dir.resolve("testsong.json"), StandardCharsets.UTF_8))
                .getAsJsonObject();
        List<String> lines = new ArrayList<>();
        truth.getAsJsonArray("lines").forEach(line -> lines.add(line.getAsString()));
        started = System.nanoTime();
        List<List<Heard>> segments = engine.transcribe(samples, samples.length);
        long took = (System.nanoTime() - started) / 1_000_000;
        List<Heard> heard = segments.stream().flatMap(List::stream).toList();
        HeardLyrics.Aligned aligned = HeardLyrics.align(lines, heard, 0).orElseThrow();
        Lyrics lyrics = Lyrics.parseLrc(aligned.lrc());
        List<Long> errors = new ArrayList<>();
        for (int i = 0; i < lyrics.lines().size(); i++) {
            errors.add(Math.abs(lyrics.lines().get(i).startMs() - truth.getAsJsonArray("truth").get(i).getAsLong()));
        }
        System.out.printf("%d Woerter gehoert in %d ms (%.0f s Audio), %.0f %% des Texts, Zeilenanfaenge daneben (ms): %s%n",
                heard.size(), took, samples.length / 16000.0, aligned.matched() * 100, errors);
        assertTrue(lyrics.lines().size() == lines.size() && errors.stream().allMatch(error -> error < 400), errors.toString());
    }
}
