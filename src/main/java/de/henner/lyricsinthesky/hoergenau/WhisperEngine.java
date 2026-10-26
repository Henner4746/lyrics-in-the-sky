package de.henner.lyricsinthesky.hoergenau;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.function.IntConsumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * whisper.cpp, the speech recognition Hoergenau times sung words with. It is not shipped with the mod: switching
 * Hoergenau on downloads the official Windows build of whisper.cpp (8.5 MB, GitHub) and the "small" model (181 MB,
 * Hugging Face) into {@code config/lyricsinthesky/hoergenau}, each checked against a fixed SHA-256 before use. It runs
 * as a process of its own at low priority, so it never takes Minecraft down with it.
 */
public final class WhisperEngine {
    static final String BUILD_URL = "https://github.com/ggml-org/whisper.cpp/releases/download/b5454/whisper-bin-x64.zip";
    static final String BUILD_SHA256 = "6ba69e3482d7826214f90a6a9c84ca07782aec1e1d0c6a7c30c994fd5d816ccb";
    static final String MODEL_URL = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small-q5_1.bin";
    static final String MODEL_SHA256 = "ae85e4a935d7a567bd102fe55afc16bb595bdb618e11b2fc7591bc08120411bb";
    static final long DOWNLOAD_MB = 190;
    /**
     * A quarter of the cores, two to four, at below normal priority: Windows lets the game go first, and a song still
     * takes about a minute instead of seconds.
     */
    static final int THREADS = Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors() / 4));

    /** One word Whisper heard, with its start in ms from the start of the audio. */
    public record Heard(String text, long startMs) {}

    private final Path dir;

    public WhisperEngine(Path dir) {
        this.dir = dir;
    }

    /** Hoergenau needs Windows: the loopback recording and the whisper.cpp build are Windows ones. */
    public static boolean supported() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        return os.contains("win") && (arch.contains("amd64") || arch.contains("x86_64"));
    }

    public boolean installed() {
        return Files.isRegularFile(cli()) && Files.isRegularFile(model());
    }

    private Path cli() {
        return dir.resolve("bin").resolve("whisper-cli.exe");
    }

    private Path model() {
        return dir.resolve("ggml-small-q5_1.bin");
    }

    /** Downloads and checks what is missing; reports the progress in percent of the whole. */
    public void install(IntConsumer percent) throws IOException, InterruptedException {
        Files.createDirectories(dir);
        HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(15)).build();
        if (!Files.isRegularFile(cli())) {
            Path zip = download(http, BUILD_URL, BUILD_SHA256, dir.resolve("whisper-bin-x64.zip"), 0, 5, percent);
            unpack(zip);
            Files.deleteIfExists(zip);
        }
        if (!Files.isRegularFile(model())) {
            download(http, MODEL_URL, MODEL_SHA256, model(), 5, 100, percent);
        }
        percent.accept(100);
    }

    private static Path download(HttpClient http, String url, String sha256, Path target, int from, int to,
            IntConsumer percent) throws IOException, InterruptedException {
        Path partial = target.resolveSibling(target.getFileName() + ".part");
        HttpResponse<InputStream> response = http.send(HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", "LyricsInTheSky (Minecraft mod; Hoergenau)").timeout(Duration.ofMinutes(30)).GET()
                .build(), HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            response.body().close();
            throw new IOException(URI.create(url).getHost() + " answered HTTP " + response.statusCode());
        }
        long size = response.headers().firstValueAsLong("Content-Length").orElse(-1);
        MessageDigest digest = sha256();
        try (InputStream in = new DigestInputStream(response.body(), digest); OutputStream out = Files.newOutputStream(partial)) {
            byte[] buffer = new byte[1 << 16];
            long done = 0;
            int reported = -1;
            for (int n; (n = in.read(buffer)) > 0; ) {
                out.write(buffer, 0, n);
                done += n;
                int now = size > 0 ? from + (int) ((to - from) * done / size) : from;
                if (now / 10 != reported / 10) {
                    reported = now;
                    percent.accept(now);
                }
            }
        }
        String got = HexFormat.of().formatHex(digest.digest());
        if (!got.equals(sha256)) {
            Files.deleteIfExists(partial);
            throw new IOException("download of " + target.getFileName() + " does not match its checksum");
        }
        Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING);
        return target;
    }

    /** whisper-cli and the libraries it loads; the rest of the build (other tools) stays out. */
    private void unpack(Path zip) throws IOException {
        Path bin = dir.resolve("bin");
        Files.createDirectories(bin);
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(zip))) {
            for (ZipEntry entry; (entry = in.getNextEntry()) != null; ) {
                String name = entry.getName().substring(entry.getName().lastIndexOf('/') + 1);
                if (name.equals("whisper-cli.exe") || name.equals("whisper.dll") || name.startsWith("ggml")
                        && name.endsWith(".dll")) {
                    Files.copy(in, bin.resolve(name), StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    /**
     * The words Whisper hears in 16 kHz mono audio, grouped as Whisper groups them into lines, with times from its DTW
     * alignment. The audio goes to a temporary file next to the model, which is deleted right after.
     */
    public List<List<Heard>> transcribe(short[] samples, int count) throws IOException, InterruptedException {
        Path wav = dir.resolve("hoergenau-" + ProcessHandle.current().pid() + ".wav");
        Path out = dir.resolve("hoergenau-" + ProcessHandle.current().pid());
        Path json = dir.resolve(out.getFileName() + ".json");
        try {
            writeWav(wav, samples, count);
            Process process = new ProcessBuilder(cli().toString(), "-m", model().toString(), "-f", wav.toString(),
                    "-l", "auto", "-t", Integer.toString(THREADS), "--dtw", "small", "-nfa", "-ojf",
                    "-of", out.toString(), "-np")
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            lowerPriority(process.pid());
            if (!process.waitFor(15, TimeUnit.MINUTES)) {
                process.destroyForcibly();
                throw new IOException("whisper.cpp took too long");
            }
            if (process.exitValue() != 0 || !Files.isRegularFile(json)) {
                throw new IOException("whisper.cpp ended with " + process.exitValue());
            }
            return parse(Files.readString(json, StandardCharsets.UTF_8));
        } finally {
            Files.deleteIfExists(wav);
            Files.deleteIfExists(json);
        }
    }

    /** Windows has no Java call for it; a short PowerShell line sets the recognizer below normal priority. */
    private static void lowerPriority(long pid) {
        try {
            String systemRoot = System.getenv().getOrDefault("SystemRoot", "C:\\Windows");
            new ProcessBuilder(systemRoot + "\\System32\\WindowsPowerShell\\v1.0\\powershell.exe", "-NoProfile",
                    "-NonInteractive", "-Command", "(Get-Process -Id " + pid + ").PriorityClass = 'BelowNormal'")
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
        } catch (IOException e) {
            // Normal priority then; it still only uses two cores.
        }
    }

    /**
     * Whisper's full JSON: segments of tokens; a token starting with a space starts a word, whose start is the token's
     * DTW time (centiseconds), else its own estimate.
     */
    static List<List<Heard>> parse(String json) {
        List<List<Heard>> segments = new ArrayList<>();
        JsonElement transcription = JsonParser.parseString(json).getAsJsonObject().get("transcription");
        if (transcription == null || !transcription.isJsonArray()) {
            return segments;
        }
        for (JsonElement segmentElement : transcription.getAsJsonArray()) {
            JsonObject segment = segmentElement.getAsJsonObject();
            List<Heard> words = new ArrayList<>();
            StringBuilder word = new StringBuilder();
            long start = -1;
            JsonElement tokens = segment.get("tokens");
            if (tokens == null || !tokens.isJsonArray()) {
                continue;
            }
            for (JsonElement tokenElement : tokens.getAsJsonArray()) {
                JsonObject token = tokenElement.getAsJsonObject();
                String text = token.get("text").getAsString();
                if (text.startsWith("[_") || text.isBlank()) {
                    continue;
                }
                long dtw = token.has("t_dtw") ? token.get("t_dtw").getAsLong() : -1;
                long time = dtw >= 0 ? dtw * 10 : token.getAsJsonObject("offsets").get("from").getAsLong();
                if (text.startsWith(" ") || word.isEmpty()) {
                    if (!word.isEmpty()) {
                        words.add(new Heard(word.toString(), start));
                        word.setLength(0);
                    }
                    start = time;
                }
                word.append(text.strip());
            }
            if (!word.isEmpty()) {
                words.add(new Heard(word.toString(), start));
            }
            if (!words.isEmpty()) {
                segments.add(words);
            }
        }
        return segments;
    }

    static void writeWav(Path path, short[] samples, int count) throws IOException {
        ByteBuffer header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        header.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(36 + count * 2)
                .put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16).putShort((short) 1).putShort((short) 1)
                .putInt(LoopbackRecorder.RATE).putInt(LoopbackRecorder.RATE * 2).putShort((short) 2).putShort((short) 16)
                .put("data".getBytes(StandardCharsets.US_ASCII)).putInt(count * 2);
        ByteBuffer data = ByteBuffer.allocate(count * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < count; i++) {
            data.putShort(samples[i]);
        }
        try (OutputStream out = Files.newOutputStream(path)) {
            out.write(header.array());
            out.write(data.array());
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
