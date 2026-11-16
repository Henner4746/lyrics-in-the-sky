package de.henner.lyricsinthesky.nowplaying;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads "now playing" from the Windows media session (SMTC) through a small PowerShell helper.
 *
 * <p>Windows PowerShell 5.1 ships with every Windows 10/11 and can call the WinRT media API, so the
 * prototype needs no native code. The helper prints one JSON line per poll on stdout.
 */
public final class SmtcNowPlayingSource implements NowPlayingSource {
    private static final Logger LOGGER = LoggerFactory.getLogger("lyricsinthesky");
    private static final String HELPER_RESOURCE = "/lyricsinthesky/smtc-helper.ps1";
    private static final long RESTART_DELAY_MS = 5_000;
    /**
     * The helper prints a line every quarter second, or every two when Windows is slow to answer. With none for this
     * long what it said last no longer counts: a helper that hangs kept its last song on screen while another played.
     */
    static final long STALE_MS = 6_000;
    /** With no line for this long the helper hangs and is started anew. */
    static final long HUNG_MS = 20_000;

    private final Path helperScript;
    private volatile NowPlaying current;
    private volatile String otherPlaying;
    /** When the helper last printed a line, or was started. */
    private volatile long lastLineAtMs;
    private volatile Process helper;
    private volatile boolean running;
    private Thread worker;

    public SmtcNowPlayingSource(Path workDir) {
        this.helperScript = workDir.resolve("smtc-helper.ps1");
    }

    @Override
    public NowPlaying current() {
        long now = System.currentTimeMillis();
        Process started = helper;
        if (started != null && now - lastLineAtMs > HUNG_MS) {
            LOGGER.warn("SMTC helper sent nothing for {} s, starting it anew", (now - lastLineAtMs) / 1000);
            lastLineAtMs = now;
            started.destroyForcibly();  // the supervisor sees its output end and starts a new one
        }
        return fresh(lastLineAtMs, now) ? current : null;
    }

    @Override
    public String otherPlaying() {
        return fresh(lastLineAtMs, System.currentTimeMillis()) ? otherPlaying : null;
    }

    @Override
    public String localPlayer() {
        NowPlaying now = current();
        return now != null && now.playing() ? now.app() : null;
    }

    /** Whether the helper's last line, printed at {@code lineAtMs}, still tells what plays. */
    static boolean fresh(long lineAtMs, long nowMs) {
        return nowMs - lineAtMs <= STALE_MS;
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        worker = new Thread(this::superviseHelper, "LyricsInTheSky-SMTC");
        worker.setDaemon(true);
        worker.start();
    }

    @Override
    public synchronized void stop() {
        running = false;
        if (worker != null) {
            worker.interrupt();
        }
    }

    private void superviseHelper() {
        while (running) {
            Process process = null;
            try {
                extractHelper();
                process = launchHelper();
                readHelperOutput(process);
            } catch (IOException e) {
                LOGGER.warn("SMTC helper failed: {}", e.getMessage());
            } finally {
                if (process != null) {
                    process.destroyForcibly();
                }
                helper = null;
                current = null;
                otherPlaying = null;
            }
            if (!running) {
                break;
            }
            try {
                Thread.sleep(RESTART_DELAY_MS);
            } catch (InterruptedException e) {
                break;
            }
        }
    }

    private void extractHelper() throws IOException {
        // Rewritten on every start so a mod update also updates the script.
        Files.createDirectories(helperScript.getParent());
        try (InputStream in = SmtcNowPlayingSource.class.getResourceAsStream(HELPER_RESOURCE)) {
            if (in == null) {
                throw new IOException("helper script missing from mod jar");
            }
            Files.copy(in, helperScript, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private Process launchHelper() throws IOException {
        // Explicit path: pwsh 7 may be first on PATH and cannot load WinRT types.
        String systemRoot = System.getenv().getOrDefault("SystemRoot", "C:\\Windows");
        String powershell = systemRoot + "\\System32\\WindowsPowerShell\\v1.0\\powershell.exe";
        ProcessBuilder builder = new ProcessBuilder(
                powershell, "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
                "-File", helperScript.toString(),
                Long.toString(ProcessHandle.current().pid()), "250");
        builder.redirectError(ProcessBuilder.Redirect.DISCARD);
        LOGGER.info("Starting SMTC helper");
        lastLineAtMs = System.currentTimeMillis();  // PowerShell takes a moment to start; that is no hang
        Process started = builder.start();
        helper = started;
        return started;
    }

    private void readHelperOutput(Process process) throws IOException {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while (running && (line = reader.readLine()) != null) {
                current = parse(line);
                otherPlaying = othersOf(line);
                lastLineAtMs = System.currentTimeMillis();
            }
        }
    }

    private static NowPlaying parse(String line) {
        try {
            JsonObject json = JsonParser.parseString(line).getAsJsonObject();
            if (!json.has("ok") || !json.get("ok").getAsBoolean()) {
                return null;
            }
            String title = string(json, "title");
            if (title.isEmpty()) {
                return null;
            }
            return new NowPlaying(
                    string(json, "app"),
                    title,
                    string(json, "artist"),
                    string(json, "album"),
                    "Playing".equals(string(json, "status")),
                    json.get("positionMs").getAsLong(),
                    json.get("durationMs").getAsLong(),
                    json.get("updatedAtMs").getAsLong());
        } catch (RuntimeException e) {
            LOGGER.debug("Unreadable SMTC line: {}", line);
            return null;
        }
    }

    /** The other players the helper saw playing beside the chosen one, comma separated, or {@code null}. */
    static String othersOf(String line) {
        try {
            JsonObject json = JsonParser.parseString(line).getAsJsonObject();
            if (!json.has("othersPlaying") || !json.get("othersPlaying").isJsonArray()) {
                return null;
            }
            List<String> apps = new ArrayList<>();
            json.getAsJsonArray("othersPlaying").forEach(app -> apps.add(app.getAsString()));
            return apps.isEmpty() ? null : String.join(", ", apps);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String string(JsonObject json, String key) {
        return json.has(key) && !json.get(key).isJsonNull() ? json.get(key).getAsString() : "";
    }
}
