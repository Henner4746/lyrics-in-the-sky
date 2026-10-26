package de.henner.lyricsinthesky.nowplaying;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads "now playing" on Linux from MPRIS over D-Bus, using the {@code gdbus} tool that ships with GLib on
 * practically every desktop. Players: Spotify, browsers (YouTube Music, Amazon Music web), VLC, mpv and more.
 */
public final class MprisNowPlayingSource implements NowPlayingSource {
    private static final Logger LOGGER = LoggerFactory.getLogger("lyricsinthesky");
    private static final String PLAYER_PREFIX = "org.mpris.MediaPlayer2.";
    private static final Pattern PLAYER_NAME = Pattern.compile("'(org\\.mpris\\.MediaPlayer2\\.[^']+)'");
    private static final long POLL_MS = 400;
    private static final long MISSING_TOOL_RETRY_MS = 30_000;

    record Snapshot(String player, String title, String artist, String album, boolean playing, long positionUs,
            long lengthUs) {}

    private volatile NowPlaying current;
    private volatile boolean running;
    private Thread worker;
    // The Spotify Linux client is known to report Position 0; then we keep our own clock per track.
    private String clockTrack;
    private long clockStartMs;
    private long clockPausedAtMs = -1;

    @Override
    public NowPlaying current() {
        return current;
    }

    /** The player as its MPRIS name ends ({@code spotify}), while it plays; its stream is what Hoergenau records. */
    @Override
    public String localPlayer() {
        NowPlaying now = current;
        return now != null && now.playing() ? now.app() : null;
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        worker = new Thread(this::loop, "LyricsInTheSky-MPRIS");
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

    private void loop() {
        boolean warned = false;
        while (running) {
            long delay = POLL_MS;
            try {
                current = poll();
            } catch (IOException e) {
                current = null;
                if (!warned) {
                    LOGGER.warn("Cannot read MPRIS (is gdbus installed?): {}", e.getMessage());
                    warned = true;
                }
                delay = MISSING_TOOL_RETRY_MS;
            } catch (InterruptedException e) {
                return;
            }
            try {
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private NowPlaying poll() throws IOException, InterruptedException {
        String names = gdbus("--dest", "org.freedesktop.DBus", "--object-path", "/org/freedesktop/DBus",
                "--method", "org.freedesktop.DBus.ListNames");
        Snapshot best = null;
        for (String player : playerNames(names)) {
            String properties = gdbus("--dest", player, "--object-path", "/org/mpris/MediaPlayer2",
                    "--method", "org.freedesktop.DBus.Properties.GetAll", "org.mpris.MediaPlayer2.Player");
            Snapshot snapshot = parse(player, properties);
            if (snapshot == null) {
                continue;
            }
            if (snapshot.playing()) {
                best = snapshot;
                break;
            }
            if (best == null) {
                best = snapshot;
            }
        }
        return best == null ? null : toNowPlaying(best, System.currentTimeMillis());
    }

    private NowPlaying toNowPlaying(Snapshot snapshot, long now) {
        long positionMs = snapshot.positionUs() / 1000;
        String track = snapshot.player() + '\u0000' + snapshot.artist() + '\u0000' + snapshot.title();
        if (!track.equals(clockTrack)) {
            clockTrack = track;
            clockStartMs = now - positionMs;
            clockPausedAtMs = snapshot.playing() ? -1 : now;
        }
        if (!snapshot.playing() && clockPausedAtMs < 0) {
            clockPausedAtMs = now;
        } else if (snapshot.playing() && clockPausedAtMs >= 0) {
            clockStartMs += now - clockPausedAtMs;
            clockPausedAtMs = -1;
        }
        if (positionMs == 0 && snapshot.playing()) {
            positionMs = (clockPausedAtMs >= 0 ? clockPausedAtMs : now) - clockStartMs;
        } else {
            clockStartMs = now - positionMs;
        }
        String app = snapshot.player().startsWith(PLAYER_PREFIX)
                ? snapshot.player().substring(PLAYER_PREFIX.length()) : snapshot.player();
        return new NowPlaying(app, snapshot.title(), snapshot.artist(), snapshot.album(), snapshot.playing(),
                Math.max(0, positionMs), snapshot.lengthUs() / 1000, now);
    }

    private static String gdbus(String... args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of("gdbus", "call", "--session", "--timeout", "2"));
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        try (InputStream in = process.getInputStream()) {
            String output = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            if (!process.waitFor(3, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return "";
            }
            return output;
        }
    }

    static List<String> playerNames(String listNamesOutput) {
        List<String> players = new ArrayList<>();
        Matcher matcher = PLAYER_NAME.matcher(listNamesOutput);
        while (matcher.find()) {
            players.add(matcher.group(1));
        }
        return players;
    }

    /** Parses the GVariant text that {@code gdbus} prints for the MPRIS player properties. */
    static Snapshot parse(String player, String properties) {
        String title = stringValue(properties, "xesam:title");
        if (title.isEmpty()) {
            return null;
        }
        return new Snapshot(player, title, stringValue(properties, "xesam:artist"), stringValue(properties, "xesam:album"),
                "Playing".equals(stringValue(properties, "PlaybackStatus")),
                numberValue(properties, "Position"), numberValue(properties, "mpris:length"));
    }

    /** First string of a property, whether it is a plain string or a string array (as for artists). */
    static String stringValue(String text, String key) {
        String marker = "'" + key + "': <";
        int index = text.indexOf(marker);
        if (index < 0) {
            return "";
        }
        index += marker.length();
        if (text.startsWith("@as ", index)) {
            index += 4;
        }
        if (index < text.length() && text.charAt(index) == '[') {
            index++;
        }
        if (index >= text.length() || (text.charAt(index) != '\'' && text.charAt(index) != '"')) {
            return "";
        }
        return readString(text, index);
    }

    static long numberValue(String text, String key) {
        Matcher matcher = Pattern.compile(Pattern.quote("'" + key + "': <") + "(?:[a-z0-9]+ )?(-?\\d+)>").matcher(text);
        return matcher.find() ? Long.parseLong(matcher.group(1)) : 0;
    }

    /** Reads a GVariant string literal starting at its opening quote. */
    private static String readString(String text, int start) {
        char quote = text.charAt(start);
        StringBuilder out = new StringBuilder();
        for (int i = start + 1; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == quote) {
                break;
            }
            if (c != '\\' || i + 1 >= text.length()) {
                out.append(c);
                continue;
            }
            char next = text.charAt(++i);
            switch (next) {
                case 'n' -> out.append('\n');
                case 't' -> out.append('\t');
                case 'u' -> {
                    if (i + 4 < text.length()) {
                        out.append((char) Integer.parseInt(text.substring(i + 1, i + 5), 16));
                        i += 4;
                    }
                }
                case 'U' -> {
                    if (i + 8 < text.length()) {
                        out.appendCodePoint(Integer.parseInt(text.substring(i + 1, i + 9), 16));
                        i += 8;
                    }
                }
                default -> out.append(next);
            }
        }
        return out.toString();
    }
}
