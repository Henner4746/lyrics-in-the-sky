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
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.LongSummaryStatistics;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.IntConsumer;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * whisper.cpp, the speech recognition Hoergenau times sung words with. It is not shipped with the mod: switching
 * Hoergenau on downloads the official Windows build of whisper.cpp (8.5 MB, GitHub) and the chosen model ("small",
 * 181 MB, or large-v3-turbo, 547 MB, Hugging Face) into {@code config/lyricsinthesky/hoergenau}, each checked against
 * a fixed SHA-256 before use. It runs as a process of its own at low priority, so it never takes Minecraft down with it.
 */
public final class WhisperEngine {
    static final String BUILD_URL = "https://github.com/ggml-org/whisper.cpp/releases/download/b5454/whisper-bin-x64.zip";
    static final String BUILD_SHA256 = "6ba69e3482d7826214f90a6a9c84ca07782aec1e1d0c6a7c30c994fd5d816ccb";
    /** The build itself, about 8.5 MB; the model comes on top. */
    static final long BUILD_MB = 9;

    /**
     * The speech models to choose from ("Modell" in the menu).
     *
     * @param dtw    whisper.cpp's alignment preset for the model's attention heads
     * @param lateMs how late DTW places sung words with this model, measured on three real German songs (rock, pop,
     *               rap) against their known word times; taken off every DTW time
     */
    public record Model(String id, String file, String sha256, long megabytes, String dtw, long lateMs) {
        /** Quick on any PC: about a minute per song with two to four cores. */
        public static final Model SMALL = new Model("small", "ggml-small-q5_1.bin",
                "ae85e4a935d7a567bd102fe55afc16bb595bdb618e11b2fc7591bc08120411bb", 190, "small", 150);
        /** Whisper large-v3-turbo: hears sung words far better, needs a few times as long. */
        public static final Model TURBO = new Model("turbo", "ggml-large-v3-turbo-q5_0.bin",
                "394221709cd5ad1f40c46e6031ca61bce88931e6e088c188294c6d5a55ffa7e2", 574, "large.v3.turbo", TURBO_LATE_MS);

        public String url() {
            return "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/" + file;
        }

        public static Model of(String id) {
            return "turbo".equals(id) ? TURBO : SMALL;
        }
    }

    /**
     * See {@link Model#lateMs}. Word by word on the three songs, large-v3-turbo placed words 12 ms later than small
     * (192 against 180 ms in the median), with the same spread; so it is small's 150 ms plus that. It heard 87, 94 and
     * 86 % of the words where small heard 77, 92 and 80 %, in about four times the time.
     */
    static final long TURBO_LATE_MS = 162;
    /** Kept for small, the model Hoergenau started with. */
    static final long DTW_LATE_MS = Model.SMALL.lateMs();

    /**
     * Threads for whisper.cpp at below normal priority, so Windows lets the game go first: "low" two, "normal" a
     * quarter of the cores (two to four; a song takes about a minute), "high" all but one.
     */
    static int threads(String power, int cores) {
        return switch (power) {
            case "low" -> 2;
            case "high" -> Math.max(2, cores - 1);
            default -> Math.max(2, Math.min(4, cores / 4));
        };
    }
    /**
     * DTW often pins the first word of a line to the end of the pause before it ("Es" 2.6 s before "ist"). A first word
     * this far before the next one is moved to just before it.
     */
    static final long FIRST_WORD_GAP_MS = 1_000;
    /**
     * Over music without singing Whisper makes up text, often one line again and again ("Die Kälte wird in der Kälte
     * gebraten." 13 times in an instrumental), and DTW puts its words at nearly one moment: under 25 ms a word, where
     * sung lines took 0.5–1.3 s. A line of three words or more faster than this per word was not sung.
     */
    static final long UNSUNG_MS_PER_WORD = 60;
    /**
     * Lines Whisper makes up from the subtitles it learned from, never sung: credits of German TV subtitles ("WDR
     * mediagroup GmbH im Auftrag des WDR", heard over a whole quiet recording) and video endings.
     */
    private static final Pattern MADE_UP = Pattern.compile("untertitel\\w*\\b.*\\b(zdf|ard|wdr|ndr|swr|br|funk|amara"
            + "|auftrag|community)|wdr mediagroup|im auftrag des (wdr|zdf|ndr|swr|br|mdr|hr)\\b|amara ?org"
            + "|(vielen )?dank(e)? f(ue|u|ü)rs zu(schauen|sehen)|thanks? (you )?for watching|subtitles by");
    /**
     * Recordings are made as loud as this before Whisper hears them (the 99.9th percentile of the samples): only the
     * player's own sound comes without what Windows adds afterwards, and was measured 17 dB quieter than all the PC
     * plays. Whisper "Genau" heard nothing but a made-up line in it, and the whole song once it was louder.
     */
    static final int LOUD = 29_500;
    /** More than this is not added: a recording this quiet is silence, and noise should stay noise. */
    static final double MAX_GAIN = 20;
    private static final Pattern DETECTED = Pattern.compile("auto-detected language: ([a-z]{2,3}) \\(p = ([0-9.]+)\\)");

    /** One word Whisper heard, with its start in ms from the start of the audio. */
    public record Heard(String text, long startMs) {}

    private final Path dir;
    /** The player's choice of model and power, read whenever whisper.cpp runs. */
    private final Supplier<String> modelChoice;
    private final Supplier<String> powerChoice;

    public WhisperEngine(Path dir) {
        this(dir, () -> "small", () -> "normal");
    }

    public WhisperEngine(Path dir, Supplier<String> modelChoice, Supplier<String> powerChoice) {
        this.dir = dir;
        this.modelChoice = modelChoice;
        this.powerChoice = powerChoice;
    }

    /** The model chosen now. */
    public Model chosen() {
        return Model.of(modelChoice.get());
    }

    /** What is still to download for the chosen model, in MB. */
    public long downloadMb() {
        return (Files.isRegularFile(cli()) ? 0 : BUILD_MB) + (Files.isRegularFile(model()) ? 0 : chosen().megabytes());
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
        return dir.resolve(chosen().file());
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
        Model model = chosen();
        Path file = dir.resolve(model.file());
        if (!Files.isRegularFile(file)) {
            download(http, model.url(), model.sha256(), file, 5, 100, percent);
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
        return transcribe(samples, 0, count, language(samples, count));
    }

    /** The same for {@code count} samples from {@code from} on, in a known language; times count from sample 0. */
    public List<List<Heard>> transcribe(short[] samples, int from, int count, String language)
            throws IOException, InterruptedException {
        Path wav = dir.resolve("hoergenau-" + ProcessHandle.current().pid() + ".wav");
        Path out = dir.resolve("hoergenau-" + ProcessHandle.current().pid());
        Path json = dir.resolve(out.getFileName() + ".json");
        long shift = from * 1000L / LoopbackRecorder.RATE;
        try {
            writeWav(wav, samples, from, count);
            // -sns: sung words instead of "*Musik*"; -mc 0: no reading on from its own text, which let it skip a verse.
            Model model = chosen();
            Process process = run(null, "-f", wav.toString(), "-l", language, "--dtw", model.dtw(), "-nfa", "-sns",
                    "-mc", "0", "-ojf", "-of", out.toString(), "-np");
            if (!process.waitFor(15, TimeUnit.MINUTES)) {
                process.destroyForcibly();
                throw new IOException("whisper.cpp took too long");
            }
            if (process.exitValue() != 0 || !Files.isRegularFile(json)) {
                throw new IOException("whisper.cpp ended with " + process.exitValue());
            }
            List<List<Heard>> segments = parse(Files.readAllBytes(json), model.lateMs());
            if (shift == 0) {
                return segments;
            }
            return segments.stream().map(segment -> segment.stream()
                    .map(word -> new Heard(word.text(), word.startMs() + shift)).toList()).toList();
        } finally {
            Files.deleteIfExists(wav);
            Files.deleteIfExists(json);
        }
    }

    /**
     * The song's language. whisper.cpp guesses it from the first 30 s only, often an intro without singing: a German rap
     * song came out as Khmer and was misheard for two minutes. So it is asked about 30 s from a quarter, the middle and
     * three quarters of the recording, and the language it is surest of in sum wins; "auto" when it tells nothing.
     */
    public String language(short[] samples, int count) throws IOException, InterruptedException {
        int window = 30 * LoopbackRecorder.RATE;
        Set<Integer> starts = new LinkedHashSet<>();
        for (double at : new double[] {0.25, 0.5, 0.75}) {
            starts.add(Math.max(0, Math.min(count - window, (int) (count * at))));
        }
        Map<String, Double> sureness = new HashMap<>();
        Path wav = dir.resolve("hoergenau-" + ProcessHandle.current().pid() + "-language.wav");
        Path log = dir.resolve("hoergenau-" + ProcessHandle.current().pid() + "-language.txt");
        try {
            for (int from : starts) {
                writeWav(wav, samples, from, Math.min(window, count - from));
                Process process = run(log, "-f", wav.toString(), "-l", "auto", "-dl");
                if (!process.waitFor(2, TimeUnit.MINUTES)) {
                    process.destroyForcibly();
                    continue;
                }
                Matcher detected = DETECTED.matcher(Files.readString(log, StandardCharsets.ISO_8859_1));
                if (detected.find()) {
                    sureness.merge(detected.group(1), Double.parseDouble(detected.group(2)), Double::sum);
                }
            }
        } finally {
            Files.deleteIfExists(wav);
            Files.deleteIfExists(log);
        }
        return sureness.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse("auto");
    }

    /** whisper-cli with the model and these arguments, below normal priority; what it prints goes to {@code log}. */
    private Process run(Path log, String... args) throws IOException {
        List<String> command = new ArrayList<>(List.of(cli().toString(), "-m", model().toString(),
                "-t", Integer.toString(threads(powerChoice.get(), Runtime.getRuntime().availableProcessors()))));
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(true)
                .redirectOutput(log == null ? ProcessBuilder.Redirect.DISCARD : ProcessBuilder.Redirect.to(log.toFile()))
                .start();
        lowerPriority(process.pid());
        return process;
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
     * DTW time (centiseconds), else its own estimate. A token holds raw bytes and may end in the middle of a letter
     * ("ö" split over two tokens), so the file is read byte by byte and each word decoded once it is whole.
     */
    static List<List<Heard>> parse(byte[] json) {
        return parse(json, DTW_LATE_MS);
    }

    /** The same, taking {@code lateMs} off every DTW time (see {@link Model#lateMs}). */
    static List<List<Heard>> parse(byte[] json, long lateMs) {
        List<List<Heard>> segments = new ArrayList<>();
        JsonElement transcription = JsonParser.parseString(new String(json, StandardCharsets.ISO_8859_1))
                .getAsJsonObject().get("transcription");
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
                long time = dtw >= 0 ? Math.max(0, dtw * 10 - lateMs)
                        : token.getAsJsonObject("offsets").get("from").getAsLong();
                if (text.startsWith(" ") || word.isEmpty()) {
                    if (!word.isEmpty()) {
                        words.add(new Heard(utf8(word), start));
                        word.setLength(0);
                    }
                    start = time;
                }
                word.append(text.strip());
            }
            if (!word.isEmpty()) {
                words.add(new Heard(utf8(word), start));
            }
            words = withoutTags(words);
            if (madeUp(words)) {
                continue;
            }
            if (words.size() >= 3) {
                LongSummaryStatistics times = words.stream().mapToLong(Heard::startMs).summaryStatistics();
                if (times.getMax() - times.getMin() < UNSUNG_MS_PER_WORD * (words.size() - 1)) {
                    continue;
                }
            }
            if (words.size() > 1 && words.get(1).startMs() - words.get(0).startMs() > FIRST_WORD_GAP_MS) {
                Heard first = words.get(0);
                words.set(0, new Heard(first.text(), Math.max(first.startMs(), words.get(1).startMs() - sung(first.text()))));
            }
            if (!words.isEmpty()) {
                segments.add(words);
            }
        }
        return segments;
    }

    /**
     * Whisper marks sounds that are no words as "*Musik*", "[Applaus]" or "(Lachen)". They are never sung words, also
     * when the lyrics contain the same word: a rap song about music got its third verse pinned to every "*Musik*".
     */
    static List<Heard> withoutTags(List<Heard> words) {
        List<Heard> kept = new ArrayList<>();
        char close = 0;
        for (Heard word : words) {
            String text = word.text();
            if (close == 0 && !text.isEmpty() && "*[(".indexOf(text.charAt(0)) >= 0) {
                close = text.charAt(0) == '*' ? '*' : text.charAt(0) == '[' ? ']' : ')';
                text = text.substring(1);
            }
            if (close == 0) {
                kept.add(word);
            } else if (text.indexOf(close) >= 0) {
                close = 0;
            }
        }
        return kept;
    }

    /** Whether a whole line is one Whisper makes up, never sung (see {@link #MADE_UP}). */
    static boolean madeUp(List<Heard> words) {
        StringBuilder line = new StringBuilder();
        for (Heard word : words) {
            line.append(' ').append(word.text().toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", ""));
        }
        return !words.isEmpty() && MADE_UP.matcher(line.toString().replaceAll(" +", " ").strip()).find();
    }

    /** How much louder the samples are made for Whisper: up to {@link #LOUD}, never quieter, at most {@link #MAX_GAIN}. */
    static double gain(short[] samples, int from, int count) {
        if (count <= 0) {
            return 1;
        }
        int[] histogram = new int[32_769];
        for (int i = from; i < from + count; i++) {
            histogram[Math.abs((int) samples[i])]++;
        }
        long below = 0;
        long wanted = (long) Math.ceil(count * 0.999);
        int level = 0;
        while (level < histogram.length - 1 && below + histogram[level] < wanted) {
            below += histogram[level++];
        }
        return level == 0 ? 1 : Math.max(1, Math.min(MAX_GAIN, (double) LOUD / level));
    }

    /** A word read byte by byte, as text; a letter Whisper left unfinished becomes the replacement character. */
    private static String utf8(CharSequence bytes) {
        return new String(bytes.toString().getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8);
    }

    /** How long a word takes to sing, roughly, from its letters (measured on sung German and English). */
    private static long sung(String word) {
        return 65L * word.codePoints().filter(Character::isLetter).count() + 50;
    }

    static void writeWav(Path path, short[] samples, int from, int count) throws IOException {
        ByteBuffer header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        header.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(36 + count * 2)
                .put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16).putShort((short) 1).putShort((short) 1)
                .putInt(LoopbackRecorder.RATE).putInt(LoopbackRecorder.RATE * 2).putShort((short) 2).putShort((short) 16)
                .put("data".getBytes(StandardCharsets.US_ASCII)).putInt(count * 2);
        ByteBuffer data = ByteBuffer.allocate(count * 2).order(ByteOrder.LITTLE_ENDIAN);
        double gain = gain(samples, from, count);
        for (int i = 0; i < count; i++) {
            data.putShort((short) Math.max(-32_768, Math.min(32_767, Math.round(samples[from + i] * gain))));
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
