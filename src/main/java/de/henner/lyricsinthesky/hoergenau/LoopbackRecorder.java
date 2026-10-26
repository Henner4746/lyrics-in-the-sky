package de.henner.lyricsinthesky.hoergenau;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;

/**
 * What the PC's default speakers play, through a small PowerShell helper that reads the Windows audio loopback
 * (WASAPI) and passes it on as 16 kHz mono. Runs only while Hoergenau listens to a song; the audio stays in memory.
 */
final class LoopbackRecorder {
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

    void start() throws IOException {
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
                Long.toString(ProcessHandle.current().pid()))
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
            if (!header.toString(StandardCharsets.US_ASCII).startsWith("LOOPBACK " + RATE)) {
                throw new IOException("unexpected loopback header " + header);
            }
            startNanos = System.nanoTime();
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

    private void append(short sample) {
        if (count == samples.length) {
            samples = Arrays.copyOf(samples, Math.min(MAX_SAMPLES, samples.length * 2));
        }
        if (count < samples.length) {
            samples[count++] = sample;
        }
    }

    long startNanos() {
        return startNanos;
    }

    int count() {
        return count;
    }

    String failure() {
        return failure;
    }

    /** Stops the helper and hands over what was recorded. */
    short[] stop() {
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
