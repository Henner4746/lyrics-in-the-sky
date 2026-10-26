package de.henner.lyricsinthesky.hoergenau;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import de.henner.lyricsinthesky.SkyLyricsConfig;
import de.henner.lyricsinthesky.lyrics.Lyrics;
import de.henner.lyricsinthesky.lyrics.LyricsCache;
import de.henner.lyricsinthesky.nowplaying.NowPlaying;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

/**
 * A song heard on two days with the real whisper.cpp: the first day only its first 55 %, then another song plays; the
 * next day it plays again and Hoergenau hears the rest. It must keep the first part, learn the song after the second,
 * and its line starts must lie where they are really sung. Runs when {@code HOERGENAU_PARTS_LIVE} names a folder with
 * {@code testsong.wav} (16 kHz mono), {@code testsong.json} (the lines and their real starts) and {@code engine}
 * (whisper.cpp and the models); {@code HOERGENAU_MODEL} picks the model (default small).
 */
@EnabledIfEnvironmentVariable(named = "HOERGENAU_PARTS_LIVE", matches = ".+")
class HoergenauPartsLiveTest {
    private static final int RATE = LoopbackRecorder.RATE;

    @Test
    void aSongHeardInTwoPartsIsLearned(@TempDir Path temp) throws Exception {
        Path dir = Path.of(System.getenv("HOERGENAU_PARTS_LIVE"));
        short[] song = samples(dir.resolve("testsong.wav"));
        long durationMs = song.length * 1000L / RATE;
        JsonObject truth = JsonParser.parseString(Files.readString(dir.resolve("testsong.json"), StandardCharsets.UTF_8))
                .getAsJsonObject();
        List<String> lines = new ArrayList<>();
        truth.getAsJsonArray("lines").forEach(line -> lines.add(line.getAsString()));
        List<Long> starts = new ArrayList<>();
        truth.getAsJsonArray("truth").forEach(start -> starts.add(start.getAsLong()));
        SkyLyricsConfig config = new SkyLyricsConfig();
        config.hoergenau = true;
        config.hoergenauModel = System.getenv().getOrDefault("HOERGENAU_MODEL", "small");
        config.hoergenauPower = "high";
        LyricsCache saved = new LyricsCache(temp.resolve("lyrics"));
        Slice recorder = new Slice();
        Hoergenau hoergenau = new Hoergenau(config, saved, new Hoergenau.Notifier() {
            @Override
            public void toast(String title, String detail) {
                System.out.println("  Hinweis: " + title + " – " + detail);
            }

            @Override
            public void chat(String message) {}
        }, key -> {}, new WhisperEngine(dir.resolve("engine"), () -> config.hoergenauModel, () -> config.hoergenauPower),
                recorder, () -> true);
        String key = "test\u0000jamendo\u0000" + config.hoergenauModel;
        long split = durationMs * 55 / 100;
        long started = System.nanoTime();

        // The first day: from the start to 55 %, then another song.
        play(hoergenau, recorder, key, song, 0, split, lines, durationMs);
        Optional<String> parts = waitFor(saved, key + "\u0000heard");
        assertTrue(parts.isPresent() && !parts.get().isBlank(), "the first part was not kept");
        assertTrue(saved.read(key).isEmpty(), "learned from half a song");
        HeardParts first = HeardParts.parse(parts.get());
        System.out.printf("PARTS erster Tag: %.0f %% gehört, %d Wörter, %d s%n", first.share(durationMs) * 100,
                first.words().size(), (System.nanoTime() - started) / 1_000_000_000L);

        // The next day the song plays again; from where it was not heard, Hoergenau hears the rest.
        play(hoergenau, recorder, key, song, split, durationMs, lines, durationMs);
        Optional<String> lrc = waitFor(saved, key);
        assertTrue(lrc.isPresent() && lrc.get().startsWith(HeardLyrics.MARK), "not learned after the second part");
        Lyrics learned = Lyrics.parseLrc(lrc.get());
        long[] off = new long[Math.min(learned.lines().size(), starts.size())];
        int withinSecond = 0;
        for (int i = 0; i < off.length; i++) {
            off[i] = Math.abs(learned.lines().get(i).startMs() - starts.get(i));
            withinSecond += off[i] < 1_000 ? 1 : 0;
        }
        long[] sorted = off.clone();
        Arrays.sort(sorted);
        long median = sorted[sorted.length / 2];
        System.out.printf("PARTS gelernt nach zwei Tagen: %d Zeilen, Zeilenanfänge im Median %d ms daneben, %d von %d "
                + "unter 1 s, %d s%n", learned.lines().size(), median, withinSecond, off.length,
                (System.nanoTime() - started) / 1_000_000_000L);
        assertTrue(saved.read(key + "\u0000heard").map(String::isBlank).orElse(true), "the parts were not let go");
        assertTrue(median <= 400 && withinSecond >= off.length * 0.8, "line starts too far off: median " + median);
    }

    /** The song plays from {@code fromMs} to {@code toMs}, then another song; Hoergenau sees it every tick. */
    private static void play(Hoergenau hoergenau, Slice recorder, String key, short[] song, long fromMs, long toMs,
            List<String> lines, long durationMs) throws InterruptedException {
        recorder.audio = Arrays.copyOfRange(song, (int) (fromMs * RATE / 1000), (int) Math.min(song.length, toMs * RATE / 1000));
        long t0 = System.currentTimeMillis();
        for (int tick = 0; tick < 6; tick++) {
            long now = System.currentTimeMillis();
            hoergenau.observe(new NowPlaying("test", "Jamendo", "Test", "", true, fromMs + (now - t0), durationMs, now), key,
                    Hoergenau.Shown.TEXT_ONLY, lines, null, null);
            Thread.sleep(50);
        }
        hoergenau.observe(null, null, null, List.of(), null, null);
    }

    private static Optional<String> waitFor(LyricsCache saved, String key) throws InterruptedException {
        long until = System.nanoTime() + 15L * 60 * 1_000_000_000;
        while (System.nanoTime() < until) {
            Optional<String> kept = saved.read(key);
            if (kept.isPresent() && !kept.get().isBlank()) {
                return kept;
            }
            Thread.sleep(500);
        }
        return Optional.empty();
    }

    /** What the PC played: the stretch of the song set before it is played, there at once. */
    private static final class Slice implements Recorder {
        short[] audio = new short[0];
        private long startNanos;

        @Override
        public void start(String player) {
            startNanos = System.nanoTime();
        }

        @Override
        public long startNanos() {
            return startNanos;
        }

        @Override
        public int count() {
            return audio.length;
        }

        @Override
        public String failure() {
            return null;
        }

        @Override
        public short[] stop() {
            return audio.clone();
        }
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
