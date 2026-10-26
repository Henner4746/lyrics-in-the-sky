package de.henner.lyricsinthesky.hoergenau;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

/**
 * Hoergenau's whole sync check as it runs in the game, with the real whisper.cpp: a song with word times plays,
 * Hoergenau starts the check by itself, records, hears the recording with the model, keeps the result and moves the
 * song. Only the recording comes from a file (what the PC played, as {@link SyncCheckLiveTest} uses it) and the player
 * from the clock. Runs when {@code SYNCCHECK_LIVE} names the folder of {@link SyncCheckLiveTest};
 * {@code SYNCCHECK_MODELS} picks the models. "in-the-end" must be moved by about 1.08 s, "roller" by about 0.84 s,
 * "get-lucky" not at all; other songs are reported.
 */
@EnabledIfEnvironmentVariable(named = "SYNCCHECK_LIVE", matches = ".+")
class HoergenauLiveFlowTest {
    private static final Map<String, Integer> EXPECTED = Map.of("in-the-end", 1_080, "roller", 840, "get-lucky", 0);
    private static final int WITHIN_MS = 160;

    @Test
    void hoergenauChecksAndMovesTheSongsByItself(@TempDir Path temp) throws Exception {
        Path dir = Path.of(System.getenv("SYNCCHECK_LIVE"));
        String models = System.getenv().getOrDefault("SYNCCHECK_MODELS", "small,turbo");
        List<String> songs;
        try (Stream<Path> files = Files.list(dir.resolve("songs"))) {
            songs = files.map(file -> file.getFileName().toString()).filter(name -> name.endsWith(".wav"))
                    .map(name -> name.substring(0, name.length() - 4)).sorted().toList();
        }
        List<String> failures = new ArrayList<>();
        for (String model : models.split(",")) {
            for (String song : songs) {
                JsonObject data = JsonParser.parseString(Files.readString(dir.resolve("songs").resolve(song + ".json"),
                        StandardCharsets.UTF_8)).getAsJsonObject();
                long songStartMs = data.get("songStartMs").getAsLong();
                long durationMs = data.has("durationMs") ? data.get("durationMs").getAsLong() : 240_000;
                Lyrics lyrics = lyrics(dir.resolve("songs"), data);
                if (lyrics.isEmpty()) {
                    continue;
                }
                SkyLyricsConfig config = new SkyLyricsConfig();
                config.hoergenau = true;
                config.hoergenauSyncCheck = true;
                config.hoergenauModel = model;
                config.hoergenauPower = "high";
                LyricsCache saved = new LyricsCache(temp.resolve(model + "-" + song));
                AtomicInteger moved = new AtomicInteger(Integer.MIN_VALUE);
                Hoergenau hoergenau = new Hoergenau(config, saved, new Hoergenau.Notifier() {
                    @Override
                    public void toast(String title, String detail) {
                        System.out.println("  Hinweis: " + title + " – " + detail);
                    }

                    @Override
                    public void chat(String message) {}
                }, key -> {}, new WhisperEngine(dir.resolve("engine"), () -> config.hoergenauModel,
                        () -> config.hoergenauPower), new Playback(samples(dir.resolve("songs").resolve(song + ".wav"))),
                        () -> true);
                hoergenau.fixSyncWith((track, timed, offsetMs) -> moved.set(offsetMs));
                hoergenau.recordFrom(() -> "SpotifyAB.SpotifyMusic_zpdnekdrzrea0!Spotify");
                String key = "test\u0000" + song + "\u0000" + model;
                long startedMs = System.currentTimeMillis();
                long started = System.nanoTime();
                // The song plays from where the recording begins; Hoergenau sees it every tick, as in the game.
                Optional<String> record = Optional.empty();
                while (record.isEmpty() && System.nanoTime() - started < 15L * 60 * 1_000_000_000) {
                    long now = System.currentTimeMillis();
                    NowPlaying track = new NowPlaying("SpotifyAB.SpotifyMusic_zpdnekdrzrea0!Spotify", song, "Test", "",
                            true, songStartMs + (now - startedMs), durationMs, now);
                    hoergenau.observe(track, key, Hoergenau.Shown.TIMED, List.of(), null, lyrics);
                    Thread.sleep(50);
                    record = saved.read(key + "\u0000synccheck");
                }
                long seconds = (System.nanoTime() - started) / 1_000_000_000L;
                String result = record.orElse("nothing after " + seconds + " s").trim();
                int correction = moved.get() == Integer.MIN_VALUE ? 0 : moved.get();
                Integer expected = EXPECTED.get(song);
                boolean ok = expected == null || (expected == 0 ? result.startsWith("fits")
                        : result.startsWith("moved") && Math.abs(correction - expected) <= WITHIN_MS);
                System.out.printf("HOERGENAU %-6s %-12s gespeichert \"%s\", Song verschoben um %+d ms, erwartet %s, %3d s: %s%n",
                        model, song, result, correction, expected == null ? "-" : String.format("%+d", expected), seconds,
                        expected == null ? "gemeldet" : ok ? "ok" : "FALSCH");
                if (!ok) {
                    failures.add(model + " " + song + ": " + result + ", " + correction);
                }
            }
        }
        assertTrue(failures.isEmpty(), failures.toString());
    }

    /** The lyrics the game showed: from the mod's lyrics store, or every shown word as a line of its own. */
    private static Lyrics lyrics(Path songs, JsonObject data) throws Exception {
        if (data.has("lrc")) {
            return Lyrics.parseLrc(Files.readString(songs.resolve(data.get("lrc").getAsString()), StandardCharsets.UTF_8));
        }
        StringBuilder lrc = new StringBuilder();
        JsonArray shown = data.getAsJsonArray("shown");
        for (var word : shown) {
            JsonArray pair = word.getAsJsonArray();
            lrc.append('[').append(HeardLyrics.stamp(pair.get(0).getAsLong())).append(']')
                    .append(pair.get(1).getAsString()).append('\n');
        }
        return Lyrics.parseLrc(lrc.toString());
    }

    /** A recording played back: all of it there at once, from the moment Hoergenau starts it. */
    private static final class Playback implements Recorder {
        private final short[] samples;
        private long startNanos;

        Playback(short[] samples) {
            this.samples = samples;
        }

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
            // Enough for any check at once; what was really recorded is handed over by stop().
            return Integer.MAX_VALUE / 4;
        }

        @Override
        public String failure() {
            return null;
        }

        @Override
        public short[] stop() {
            return samples.clone();
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
