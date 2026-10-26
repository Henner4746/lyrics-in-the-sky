package de.henner.lyricsinthesky.hoergenau;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * What the music player plays, through a small PowerShell helper that reads the Windows audio loopback (WASAPI) and
 * passes it on as 16 kHz mono: only the player's own sound when Windows can tell it apart (process loopback), else all
 * the default speakers play. Runs only while Hoergenau listens to a song; the audio stays in memory.
 */
final class LoopbackRecorder implements Recorder {
    private static final Logger LOGGER = LoggerFactory.getLogger("lyricsinthesky");
    static final int RATE = 16_000;
    /** Longer songs are not listened to: ten minutes are about 19 MB. */
    private static final int MAX_SAMPLES = 10 * 60 * RATE;
    private static final String HELPER_RESOURCE = "/lyricsinthesky/loopback-helper.ps1";

    private final Path helper;
    private Process process;
    private Thread reader;
    private volatile short[] samples = new short[RATE * 30];
    private volatile int count;
    /** {@link System#nanoTime()} when the helper started sending; sample {@code i} is about {@code i / RATE} s later. */
    private volatile long startNanos;
    private volatile String failure;

    LoopbackRecorder(Path workDir) {
        this.helper = workDir.resolve("loopback-helper.ps1");
    }

    @Override
    public void start(String player) throws IOException {
        Files.createDirectories(helper.getParent());
        try (InputStream in = LoopbackRecorder.class.getResourceAsStream(HELPER_RESOURCE)) {
            if (in == null) {
                throw new IOException("loopback helper missing from mod jar");
            }
            Files.copy(in, helper, StandardCopyOption.REPLACE_EXISTING);
        }
        String systemRoot = System.getenv().getOrDefault("SystemRoot", "C:\\Windows");
        process = new ProcessBuilder(systemRoot + "\\System32\\WindowsPowerShell\\v1.0\\powershell.exe", "-NoProfile",
                "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File", helper.toString(),
                Long.toString(ProcessHandle.current().pid()), player == null || player.isBlank() ? "-" : player)
                .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        count = 0;
        startNanos = 0;
        failure = null;
        reader = new Thread(this::read, "LyricsInTheSky-Hoergenau-Audio");
        reader.setDaemon(true);
        reader.start();
    }

    private void read() {
        try (InputStream in = process.getInputStream()) {
            ByteArrayOutputStream header = new ByteArrayOutputStream();
            for (int b; (b = in.read()) != '\n'; ) {
                if (b < 0) {
                    throw new IOException("loopback helper ended before sending");
                }
                header.write(b);
            }
            Header told = Header.of(header.toString(StandardCharsets.US_ASCII));
            if (told == null) {
                throw new IOException("unexpected loopback header " + header);
            }
            // The helper may first hear for a moment which sound to take; the samples count from its first one.
            startNanos = System.nanoTime() - told.sinceMs() * 1_000_000;
            LOGGER.info("Hoergenau records {}", told.what());
            byte[] buffer = new byte[8192];
            int odd = -1;
            for (int n; (n = in.read(buffer)) > 0; ) {
                int i = 0;
                if (odd >= 0) {
                    append((short) ((odd & 0xFF) | (buffer[0] << 8)));
                    odd = -1;
                    i = 1;
                }
                for (; i + 1 < n; i += 2) {
                    append((short) ((buffer[i] & 0xFF) | (buffer[i + 1] << 8)));
                }
                if (i < n) {
                    odd = buffer[i];
                }
                if (count >= MAX_SAMPLES) {
                    break;
                }
            }
        } catch (IOException e) {
            if (process.isAlive()) {
                failure = e.getMessage();
            }
        }
    }

    /**
     * The helper's first line: {@code LOOPBACK 16000 <rate> <channels> <bits> <float|int> <ms since the first sample>
     * <only <process>|all <why>>}.
     */
    record Header(long sinceMs, String what) {
        static Header of(String line) {
            String[] parts = line.trim().split(" ");
            if (parts.length < 2 || !"LOOPBACK".equals(parts[0]) || !Integer.toString(RATE).equals(parts[1])) {
                return null;
            }
            if (parts.length < 9 || !parts[6].matches("\\d{1,6}")) {
                return new Header(0, "all the PC plays");
            }
            long since = Long.parseLong(parts[6]);
            return "only".equals(parts[7]) ? new Header(since, "only " + parts[8])
                    : new Header(since, "all the PC plays (" + parts[8] + ")");
        }
    }

    private void append(short sample) {
        if (count == samples.length) {
            samples = Arrays.copyOf(samples, Math.min(MAX_SAMPLES, samples.length * 2));
        }
        if (count < samples.length) {
            samples[count++] = sample;
        }
    }

    @Override
    public long startNanos() {
        return startNanos;
    }

    @Override
    public int count() {
        return count;
    }

    @Override
    public String failure() {
        return failure;
    }

    /** Stops the helper and hands over what was recorded. */
    @Override
    public short[] stop() {
        if (process != null) {
            process.destroyForcibly();
        }
        if (reader != null) {
            try {
                reader.join(2_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        return Arrays.copyOf(samples, count);
    }
}
