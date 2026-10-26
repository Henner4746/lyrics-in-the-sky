package de.henner.lyricsinthesky.hoergenau;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * What the music player plays on Linux, from PulseAudio (or PipeWire's PulseAudio server) with {@code parec}: only the
 * player's own stream when it is found among the streams playing, else all the default output plays. 16 kHz mono, like
 * {@link LoopbackRecorder}; runs only while Hoergenau listens to a song, and the audio stays in memory.
 */
final class PulseRecorder implements Recorder {
    private static final Logger LOGGER = LoggerFactory.getLogger("lyricsinthesky");
    private static final int RATE = LoopbackRecorder.RATE;
    private static final int MAX_SAMPLES = 10 * 60 * RATE;
    /** Asked of parec, so the first sample is this little older than when it arrives. */
    private static final int LATENCY_MS = 20;

    private Process process;
    private Thread reader;
    private volatile short[] samples = new short[RATE * 30];
    private volatile int count;
    private volatile long startNanos;
    private volatile String failure;
    private volatile boolean onlyPlayer;

    @Override
    public void start(String player) throws IOException {
        String stream = player == null ? null : streamOf(player, pactl("list", "sink-inputs"));
        List<String> command = new ArrayList<>(List.of("parec", "--raw", "--format=s16le", "--rate=" + RATE,
                "--channels=1", "--latency-msec=" + LATENCY_MS));
        command.add(stream != null ? "--monitor-stream=" + stream : "--device=@DEFAULT_MONITOR@");
        ProcessBuilder builder = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD);
        builder.environment().put("LC_ALL", "C");
        process = builder.start();
        count = 0;
        startNanos = 0;
        failure = null;
        onlyPlayer = stream != null;
        String what = stream != null ? "only " + player + " (stream " + stream + ")" : "all the PC plays (default output)";
        reader = new Thread(() -> read(what), "LyricsInTheSky-Hoergenau-Audio");
        reader.setDaemon(true);
        reader.start();
    }

    /**
     * The stream a player plays into, from {@code pactl list sink-inputs}: the first whose app or program name holds
     * the player's name ({@code spotify} for MPRIS' {@code org.mpris.MediaPlayer2.spotify}, {@code chromium} for
     * {@code chromium.instance42}), one that is not paused first; {@code null} when there is none.
     */
    static String streamOf(String player, String listing) {
        String name = player.toLowerCase(Locale.ROOT);
        int dot = name.indexOf('.');
        if (dot > 0) {
            name = name.substring(0, dot);
        }
        String found = null;
        String index = null;
        boolean corked = false;
        boolean matches = false;
        for (String raw : (listing + "\nSink Input #end").split("\n")) {
            String line = raw.trim();
            if (line.startsWith("Sink Input #")) {
                if (index != null && matches && (!corked || found == null)) {
                    found = index;
                    if (!corked) {
                        return found;
                    }
                }
                index = line.substring("Sink Input #".length());
                corked = false;
                matches = false;
            } else if (line.startsWith("Corked:")) {
                corked = line.endsWith("yes");
            } else if ((line.startsWith("application.name = ") || line.startsWith("application.process.binary = ")
                    || line.startsWith("application.id = ")) && line.toLowerCase(Locale.ROOT).contains(name)) {
                matches = !name.isEmpty();
            }
        }
        return found;
    }

    private static String pactl(String... args) {
        List<String> command = new ArrayList<>(List.of("pactl"));
        command.addAll(List.of(args));
        try {
            ProcessBuilder builder = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD);
            builder.environment().put("LC_ALL", "C");
            Process process = builder.start();
            byte[] out = process.getInputStream().readAllBytes();
            process.waitFor(3, TimeUnit.SECONDS);
            return new String(out, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "";
        }
    }

    private void read(String what) {
        try (InputStream in = process.getInputStream()) {
            byte[] buffer = new byte[8192];
            int odd = -1;
            for (int n; (n = in.read(buffer)) > 0; ) {
                if (startNanos == 0) {
                    // The first samples were taken a little before they arrive: what came, and parec's latency.
                    startNanos = System.nanoTime() - (n / 2) * 1_000_000_000L / RATE - LATENCY_MS * 1_000_000L;
                    LOGGER.info("Hoergenau records {}", what);
                }
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
            if (startNanos == 0 && !process.isAlive()) {
                failure = "parec ended without sound (is PulseAudio or PipeWire running?)";
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

    @Override
    public long startNanos() {
        return startNanos;
    }

    @Override
    public int count() {
        return count;
    }

    @Override
    public boolean onlyPlayer() {
        return onlyPlayer;
    }

    @Override
    public String failure() {
        return failure;
    }

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
