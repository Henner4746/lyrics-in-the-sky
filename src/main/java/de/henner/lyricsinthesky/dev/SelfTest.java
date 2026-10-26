package de.henner.lyricsinthesky.dev;

import de.henner.lyricsinthesky.LyricsController;
import de.henner.lyricsinthesky.LyricsInTheSkyClient;
import de.henner.lyricsinthesky.SkyLyricsConfig;
import de.henner.lyricsinthesky.gui.GlassMenuScreen;
import de.henner.lyricsinthesky.gui.HoergenauIntroScreen;
import de.henner.lyricsinthesky.gui.HudEditScreen;
import de.henner.lyricsinthesky.render.NowPlayingHud;
import de.henner.lyricsinthesky.render.Perf;
import de.henner.lyricsinthesky.lyrics.Explicit;
import de.henner.lyricsinthesky.lyrics.LrclibClient;
import de.henner.lyricsinthesky.lyrics.Lyrics;
import de.henner.lyricsinthesky.nowplaying.NowPlaying;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Development check, enabled with {@code -Dlyricsinthesky.selftest=true}: once a world is loaded it plays
 * the demo, then pretends a real song is playing so its lyrics come from LRCLIB, saves screenshots at fixed
 * times to {@code run/screenshots} and quits. Verifies rendering without clicking through the game and
 * without touching the media player on this PC.
 */
public final class SelfTest {
    private static final Logger LOGGER = LoggerFactory.getLogger("lyricsinthesky-selftest");
    private static final int DEMO_START_TICK = 60;
    /** Demo times (seconds) to photograph; from {@link #NIGHT_FROM} on it is midnight. */
    private static final double[] SHOTS = {0.6, 2.0, 3.7, 5.2, 7.6, 9.6, 13.4, 16.2, 19.0, 21.4, 24.9, 26.4, 29.0, 33.3, 35.6};
    private static final double NIGHT_FROM = 21.0;
    /** The settings screen is open in between, and lines show "around you" in between. */
    private static final double SETTINGS_OPEN = 4.0;
    private static final double SETTINGS_CLOSE = 6.0;
    private static final double AROUND_FROM = 12.0;
    private static final double AROUND_TO = 20.5;
    private static final double WALL_FROM = 16.8;
    private static final double WALL_SHOT = 17.6;
    private static final double WALL_TO = 18.2;
    /** With {@code -Dlyricsinthesky.selftest.record=true}: demo seconds captured every tick. */
    private static final double RECORD_FROM = 1.5;
    private static final double RECORD_TO = 20.5;
    /** After the demo: a simulated track at this song position, photographed during the next seconds. */
    private static final double SIMULATION_FROM = 38.0;
    private static final long SIMULATED_POSITION_MS = 57_000;
    private static final double[] SIMULATION_SHOTS = {41.0, 44.0, 47.0};
    /** Before each song shot the now-playing bar gets another look and place; then its editor is opened. */
    private static final double BAR_KARAOKE_BOTTOM = 40.5;
    private static final double BAR_PLAIN_TOP = 43.5;
    private static final double BAR_PILL_MOVED = 46.5;
    private static final double BAR_EDITOR_OPEN = 47.4;
    private static final double BAR_EDITOR_SHOT = 47.9;
    private static final double BAR_EDITOR_CLOSE = 48.5;
    private static String[] barBefore;
    /** While the song plays: its own timing is set and taken back, and the settings show its slider. */
    private static final double SONG_TIMING = 41.5;
    private static final double SONG_SETTINGS_OPEN = 42.0;
    private static final double SONG_SETTINGS_SHOT = 42.6;
    private static final double SONG_SETTINGS_CLOSE = 43.1;
    /** Then the same song in its intro, music without singing: the bar must show the song, readable. */
    private static final double INTRO_FROM = 49.0;
    private static final double INTRO_PILL_SHOT = 50.0;
    private static final double INTRO_KARAOKE = 50.2;
    private static final double INTRO_KARAOKE_SHOT = 50.8;
    /** Then the bar as Liquid Glass by day and at night, while the song is sung. */
    private static final double GLASS_FROM = 52.0;
    private static final String[][] GLASS_BARS = {{"pill", "noon"}, {"karaoke", "noon"}, {"plain", "noon"},
            {"title", "noon"}, {"pill", "midnight"}, {"karaoke", "midnight"}, {"plain", "midnight"},
            {"title", "midnight"}};
    /** Then "Wortgenau" with all services on: the song gets word times from one of them. */
    private static final double WORD_TIMES_ON = 67.0;
    private static final double WORD_TIMES_CHECK = 77.0;
    /**
     * Then the menu: in its old look ("minimal") its start, every page, two pages scrolled to their end and the color
     * picker, then in glass its start and a page; each a second after opening so everything has come in.
     */
    private static final double GLASS_MENU_FROM = 79.0;
    private static final String[][] GLASS_MENU_SHOTS = {{"minimal", "start"}, {"minimal", "display"},
            {"minimal", "display-end"}, {"minimal", "look"}, {"minimal", "look-end"}, {"minimal", "motion"},
            {"minimal", "bar"}, {"minimal", "timing"}, {"minimal", "sources"}, {"minimal", "hoergenau"},
            {"minimal", "hoergenau-end"}, {"minimal", "spotify"}, {"minimal", "streamer"}, {"minimal", "menu"},
            {"minimal", "picker"}, {"glass", "start"}, {"glass", "look"}, {"glass", "hoergenau-end"},
            {"fancy", "start"}, {"fancy", "menu"}, {"minimal", "intro"}};
    /**
     * Then the demo once more with the new looks: outline, capitals and own colors, the rainbow with the bar's underline
     * and progress line, the three looks on the screen and three of the new presets; each photographed while a line is
     * sung.
     */
    private static final double OPTIONS_FROM = GLASS_MENU_FROM + GLASS_MENU_SHOTS.length + 1.0;
    private static final double[] OPTION_SHOTS = {3.0, 6.0, 8.6, 11.2, 13.8, 16.6, 19.4, 22.2};
    /** The end of the fixed steps; the songs of {@link #SONG_LIST}, if any, come after it, then the game quits. */
    private static final double QUIT_AT = OPTIONS_FROM + 24.0;
    private static final String SONG_LIST = "selftest-songs.tsv";
    private static final double SONG_SECONDS = 6.0;
    private static final double SONG_CHECK_AFTER = 5.0;
    private static List<String[]> songs;
    private static String themeBefore;
    private static String menuStyleBefore;
    private static final NowPlaying PROBE = new NowPlaying("selftest", "Sonne", "Rammstein", "Mutter", true, 0, 272_000, 0);
    private static int ticksInWorld;

    private SelfTest() {}

    public static boolean isEnabled() {
        return Boolean.getBoolean("lyricsinthesky.selftest");
    }

    public static void register(LyricsController controller, LrclibClient lrclib) {
        Thread lookup = new Thread(() -> checkLrclib(lrclib), "LyricsInTheSky-SelfTest");
        lookup.setDaemon(true);
        lookup.start();
        ClientTickEvents.END_CLIENT_TICK.register(minecraft -> tick(minecraft, controller));
    }

    private static void checkLrclib(LrclibClient lrclib) {
        try {
            Optional<Lyrics> lyrics = lrclib.find(PROBE);
            LOGGER.info("SELFTEST lrclib: {}", lyrics.map(found -> found.lines().size() + " synced lines").orElse("not found"));
        } catch (Exception e) {
            LOGGER.error("SELFTEST lrclib failed", e);
        }
    }

    private static void tick(Minecraft minecraft, LyricsController controller) {
        if (minecraft.player == null || minecraft.level == null) {
            return;
        }
        ticksInWorld++;
        if (ticksInWorld == DEMO_START_TICK - 40) {
            // Spectator high above the trees: no falling, no mobs, a free view of the sky every run.
            minecraft.player.connection.sendCommand("gamemode spectator");
            minecraft.player.connection.sendCommand("tp @s ~ 130 ~ 0 -24");
            // The preview video looks best at dusk; the screenshot test checks plain daylight.
            boolean record = Boolean.getBoolean("lyricsinthesky.selftest.record");
            minecraft.player.connection.sendCommand(record ? "time set 12900" : "time set noon");
            minecraft.player.connection.sendCommand("weather clear");
        }
        if (ticksInWorld == DEMO_START_TICK - 1) {
            // What the real sources report before the demo takes over (OS media session or Spotify account).
            LOGGER.info("SELFTEST source before demo: {}", controller.status());
        }
        if (ticksInWorld == DEMO_START_TICK) {
            controller.startDemo();
            if (Boolean.getBoolean("lyricsinthesky.selftest.record")) {
                // Keep the preview clean: no command feedback or advancement toasts in the frames.
                minecraft.gui.hud.getChat().clearMessages(false);
                minecraft.gui.toastManager().clear();
            }
        }
        if (atSecond(NIGHT_FROM)) {
            minecraft.player.connection.sendCommand("time set midnight");
        }
        perfSteps();
        if (!Boolean.getBoolean("lyricsinthesky.selftest.record")) {
            if (atSecond(SETTINGS_OPEN)) {
                LyricsInTheSkyClient.openSettings(minecraft);
                LOGGER.info("SELFTEST settings screen: {}", minecraft.gui.screen() == null ? "NOT open"
                        : minecraft.gui.screen().getClass().getSimpleName());
            }
            if (atSecond(SETTINGS_CLOSE)) {
                minecraft.gui.screen().onClose();
            }
            if (atSecond(AROUND_FROM)) {
                controller.config().mode = "around";
            }
            if (atSecond(AROUND_TO)) {
                controller.config().mode = "sky";
            }
            // A stone wall in front of the left half of the view, while words float around the player: in the
            // natural style it hides the words behind it, in the others they stay on top.
            if (atSecond(WALL_FROM)) {
                minecraft.player.connection.sendCommand("fill ^1 ^-3 ^4 ^8 ^3 ^4 minecraft:stone");
            }
            if (atSecond(WALL_SHOT)) {
                shoot(minecraft, "wall (style " + controller.config().style + ")");
            }
            if (atSecond(WALL_TO)) {
                minecraft.player.connection.sendCommand("fill ^1 ^-3 ^4 ^8 ^3 ^4 minecraft:air");
            }
        }
        if (atSecond(SIMULATION_FROM)) {
            minecraft.player.connection.sendCommand("time set 23000");
            controller.config().lrclibAllowed = true;
            controller.simulate(new NowPlaying("selftest", PROBE.title(), PROBE.artist(), PROBE.album(), true,
                    SIMULATED_POSITION_MS, PROBE.durationMs(), System.currentTimeMillis()));
        }
        if (Boolean.getBoolean("lyricsinthesky.selftest.record")) {
            // Every tick for a stretch of the demo, for turning into a preview video with ffmpeg.
            int tick = ticksInWorld - DEMO_START_TICK;
            if (tick >= RECORD_FROM * 20 && tick < RECORD_TO * 20) {
                shoot(minecraft, "frame " + tick);
            }
        } else {
            for (double shot : SHOTS) {
                if (atSecond(shot)) {
                    shoot(minecraft, String.format("demo %04.1f", shot));
                }
            }
        }
        if (!Boolean.getBoolean("lyricsinthesky.selftest.record")) {
            barSteps(minecraft, controller.config());
            songTimingSteps(minecraft, controller);
            introSteps(minecraft, controller);
            glassSteps(minecraft, controller);
            glassMenuSteps(minecraft, controller);
            optionSteps(minecraft, controller);
            wordTimeSteps(controller);
            songSteps(minecraft, controller);
        }
        for (double shot : SIMULATION_SHOTS) {
            if (atSecond(shot)) {
                LOGGER.info("SELFTEST status: {}", controller.status());
                NowPlayingHud.Bounds bar = NowPlayingHud.lastBounds();
                LOGGER.info("SELFTEST bar: {} {} at {}", controller.config().hudStyle, controller.config().hudAnchor,
                        bar == null ? "hidden" : bar.x() + "," + bar.y() + " " + bar.width() + "x" + bar.height());
                shoot(minecraft, String.format("song %04.1f", shot));
            }
        }
        if (atSecond(QUIT_AT - 1)) {
            // The consent buttons in chat run "/skylyrics lrclib on" as an unattended command; check that
            // such a click reaches our client command.
            float before = controller.config().opacity;
            minecraft.player.connection.sendUnattendedCommand("skylyrics opacity 0.77", null);
            LOGGER.info("SELFTEST unattended command: {}", controller.config().opacity == 0.77f ? "ok"
                    : "NOT handled (opacity still " + before + ")");
            // A preset sets the whole look at once and is recognised as set.
            String style = controller.config().style;
            minecraft.player.connection.sendUnattendedCommand("skylyrics preset party", null);
            LOGGER.info("SELFTEST preset: {}", "party".equals(controller.config().currentPreset()) ? "ok"
                    : "NOT applied (preset " + controller.config().currentPreset() + ")");
            controller.config().style = style;
        }
        if (atSecond(QUIT_AT + songs().size() * SONG_SECONDS)) {
            minecraft.stop();
        }
    }

    /**
     * The songs listed in {@code selftest-songs.tsv} in the run folder, one per line, tab separated: artist, title,
     * album, length and a moment in seconds, and the line sung then. Each plays from a few seconds before that moment;
     * then the bar has to show that line, and the status tells where its lyrics came from. Meant for lyrics put into
     * {@code run/config/lyricsinthesky/lyrics} beforehand, e.g. ones Hörgenau learned on a real PC.
     */
    private static void songSteps(Minecraft minecraft, LyricsController controller) {
        for (int i = 0; i < songs().size(); i++) {
            String[] song = songs().get(i);
            double at = QUIT_AT + i * SONG_SECONDS;
            long durationMs = Math.round(Double.parseDouble(song[3]) * 1000);
            long momentMs = Math.round(Double.parseDouble(song[4]) * 1000);
            if (atSecond(at)) {
                controller.config().lrclibAllowed = true;
                controller.simulate(new NowPlaying("selftest", song[1], song[0], song[2], true,
                        momentMs - Math.round(SONG_CHECK_AFTER * 1000), durationMs, System.currentTimeMillis()));
            }
            if (atSecond(at + SONG_CHECK_AFTER)) {
                LyricsController.Timeline now = controller.timeline(System.currentTimeMillis());
                NowPlayingHud.Shown shown = NowPlayingHud.lastShown();
                String bar = shown == null ? "" : shown.text().trim().replaceAll("\\s+", " ");
                String expected = song[5].trim().replaceAll("\\s+", " ");
                boolean ok = shown != null && shown.lyricLine() && bar.equals(expected);
                LOGGER.info("SELFTEST song {} – {} at {} ms: {} – bar '{}', word {}; status: {}", song[0], song[1],
                        now == null ? -1 : now.positionMs(), ok ? "ok" : "WRONG (expected '" + expected + "')", bar,
                        wordNow(now), controller.status());
                shoot(minecraft, "song " + song[1]);
                LyricsInTheSkyClient.setStreamerMode(controller.config(), true);
            }
            // Streamer mode: the same line, explicit words now as first letter and stars, in the bar and the title.
            if (atSecond(at + SONG_CHECK_AFTER + 0.6)) {
                LyricsController.Timeline now = controller.timeline(System.currentTimeMillis());
                NowPlayingHud.Shown shown = NowPlayingHud.lastShown();
                String bar = shown == null ? "" : shown.text().trim().replaceAll("\\s+", " ");
                String expected = Explicit.ANY.hide(song[5].trim().replaceAll("\\s+", " "));
                boolean ok = bar.equals(expected) && now != null && now.title().equals(Explicit.ANY.hide(song[1]));
                LOGGER.info("SELFTEST streamer {}: {} – bar '{}', title '{}'", song[1], ok ? "ok"
                        : "WRONG (expected '" + expected + "')", bar, now == null ? "-" : now.title());
                shoot(minecraft, "streamer " + song[1]);
                LyricsInTheSkyClient.setStreamerMode(controller.config(), false);
            }
        }
    }

    /** The word being sung according to the lyrics' word times, or why there is none. */
    private static String wordNow(LyricsController.Timeline now) {
        if (now == null || now.lyrics() == null || now.lyrics().isEmpty()) {
            return "- (no lyrics)";
        }
        if (!now.lyrics().hasWordTiming()) {
            return "- (no word times)";
        }
        int index = now.lyrics().indexAt(now.positionMs());
        if (index < 0) {
            return "- (before the first line)";
        }
        String[] words = now.lyrics().lines().get(index).words();
        long[] times = now.lyrics().wordTimes(index, now.durationMs());
        int word = 0;
        while (word + 1 < times.length && times[word + 1] <= now.positionMs()) {
            word++;
        }
        return word < words.length ? "'" + words[word] + "' from " + times[word] + " ms" : "- (none)";
    }

    private static List<String[]> songs() {
        if (songs == null) {
            songs = new ArrayList<>();
            Path list = FabricLoader.getInstance().getGameDir().resolve(SONG_LIST);
            try {
                if (Files.exists(list)) {
                    for (String line : Files.readAllLines(list, StandardCharsets.UTF_8)) {
                        String[] fields = line.split("\t");
                        if (!line.startsWith("#") && fields.length == 6) {
                            songs.add(fields);
                        }
                    }
                }
            } catch (IOException e) {
                LOGGER.error("SELFTEST songs: {} not readable", list, e);
            }
            LOGGER.info("SELFTEST songs: {} from {}", songs.size(), list.getFileName());
        }
        return songs;
    }

    /**
     * How long the mod takes per frame in each part of the demo: in the sky (elegant), around the player, the sky
     * again by night, and while a song plays with the bar in its looks.
     */
    private static void perfSteps() {
        String[][] parts = {{"1.0", "start"}, {"11.9", "sky"}, {"20.4", "around"}, {"35.9", "sky at night"},
                {"48.9", "song and bars"}};
        for (String[] part : parts) {
            if (atSecond(Double.parseDouble(part[0]))) {
                String report = Perf.report();
                if (!"start".equals(part[1])) {
                    LOGGER.info("SELFTEST perf {}: {}", part[1], report);
                }
            }
        }
    }

    /** The bar in its looks and places, then its editor; the player's own bar settings are put back afterwards. */
    private static void barSteps(Minecraft minecraft, SkyLyricsConfig config) {
        if (atSecond(BAR_KARAOKE_BOTTOM)) {
            barBefore = new String[] {config.hudStyle, config.hudAnchor, Integer.toString(config.hudOffsetX),
                    Integer.toString(config.hudOffsetY), Boolean.toString(config.showHud)};
            setBar(config, "karaoke", "bottom", 0);
        }
        if (atSecond(BAR_PLAIN_TOP)) {
            setBar(config, "plain", "top", 0);
        }
        if (atSecond(BAR_PILL_MOVED)) {
            setBar(config, "pill", "bottom", -120);
        }
        if (atSecond(BAR_EDITOR_OPEN)) {
            minecraft.gui.setScreen(new HudEditScreen(minecraft.gui.screen(), config));
        }
        if (atSecond(BAR_EDITOR_SHOT)) {
            NowPlayingHud.Bounds bar = NowPlayingHud.lastBounds();
            LOGGER.info("SELFTEST bar editor: {}", minecraft.gui.screen() instanceof HudEditScreen && bar != null
                    ? "open, bar at " + bar.x() + "," + bar.y() : "NOT open or bar hidden");
            shoot(minecraft, "bar editor");
        }
        if (atSecond(BAR_EDITOR_CLOSE) && barBefore != null) {
            config.hudStyle = barBefore[0];
            config.hudAnchor = barBefore[1];
            config.hudOffsetX = Integer.parseInt(barBefore[2]);
            config.hudOffsetY = Integer.parseInt(barBefore[3]);
            config.showHud = Boolean.parseBoolean(barBefore[4]);
            if (minecraft.gui.screen() instanceof HudEditScreen editor) {
                editor.onClose();
            }
        }
    }

    /** A timing for this song only moves its lyrics by exactly that much; nothing is shared from the self test. */
    private static void songTimingSteps(Minecraft minecraft, LyricsController controller) {
        if (atSecond(SONG_TIMING)) {
            LyricsController.Song song = controller.currentSong();
            if (song == null) {
                LOGGER.info("SELFTEST song timing: NO song with lyrics");
                return;
            }
            long nowMs = System.currentTimeMillis();
            long before = controller.timeline(nowMs).positionMs();
            controller.setSongOffset(song, 500);
            long after = controller.timeline(nowMs).positionMs();
            String status = controller.status();
            controller.setSongOffset(song, null);
            long back = controller.timeline(nowMs).positionMs();
            LOGGER.info("SELFTEST song timing: {} (+{} ms, back {} ms), id {}, status: {}",
                    after - before == 500 && back == before ? "ok" : "WRONG", after - before, back - before, song.id(), status);
        }
        if (atSecond(SONG_SETTINGS_OPEN)) {
            LyricsInTheSkyClient.openSettings(minecraft);
        }
        if (atSecond(SONG_SETTINGS_SHOT)) {
            shoot(minecraft, "settings with song");
        }
        if (atSecond(SONG_SETTINGS_CLOSE) && minecraft.gui.screen() != null) {
            minecraft.gui.screen().onClose();
        }
    }

    /**
     * Music without singing (the intro, before the first line): the bar shows the song instead of a line, fully
     * faded in, as a pill and as karaoke. The bar settings are put back afterwards.
     */
    private static void introSteps(Minecraft minecraft, LyricsController controller) {
        SkyLyricsConfig config = controller.config();
        if (atSecond(INTRO_FROM)) {
            LyricsController.Timeline now = controller.timeline(System.currentTimeMillis());
            long firstLine = now == null || now.lyrics() == null || now.lyrics().isEmpty() ? 0
                    : now.lyrics().lines().get(0).startMs();
            long intro = Math.max(0, firstLine - 4_000);
            LOGGER.info("SELFTEST intro: first line at {} ms, playing from {} ms", firstLine, intro);
            barBefore = new String[] {config.hudStyle, config.hudAnchor, Integer.toString(config.hudOffsetX),
                    Integer.toString(config.hudOffsetY), Boolean.toString(config.showHud)};
            setBar(config, "pill", "top", 0);
            controller.simulate(new NowPlaying("selftest", PROBE.title(), PROBE.artist(), PROBE.album(), true,
                    intro, PROBE.durationMs(), System.currentTimeMillis()));
        }
        if (atSecond(INTRO_KARAOKE)) {
            config.hudStyle = "karaoke";
        }
        if (atSecond(INTRO_PILL_SHOT) || atSecond(INTRO_KARAOKE_SHOT)) {
            NowPlayingHud.Shown shown = NowPlayingHud.lastShown();
            boolean ok = shown != null && !shown.lyricLine() && shown.text().contains(PROBE.title()) && shown.opacity() > 0.9f;
            LOGGER.info("SELFTEST bar without singing ({}): {} – '{}', opacity {}", config.hudStyle, ok ? "ok" : "WRONG",
                    shown == null ? "hidden" : shown.text(), shown == null ? 0 : String.format("%.2f", shown.opacity()));
            shoot(minecraft, "intro " + config.hudStyle);
        }
        if (atSecond(INTRO_KARAOKE_SHOT) && barBefore != null) {
            config.hudStyle = barBefore[0];
            config.hudAnchor = barBefore[1];
            config.hudOffsetX = Integer.parseInt(barBefore[2]);
            config.hudOffsetY = Integer.parseInt(barBefore[3]);
            config.showHud = Boolean.parseBoolean(barBefore[4]);
        }
    }

    /** The bar in all its looks as Liquid Glass, over a bright noon sky and at midnight. */
    private static void glassSteps(Minecraft minecraft, LyricsController controller) {
        SkyLyricsConfig config = controller.config();
        for (int i = 0; i < GLASS_BARS.length; i++) {
            double at = GLASS_FROM + i;
            if (atSecond(at)) {
                clearScreen(minecraft);
                if (i == 0) {
                    themeBefore = config.theme;
                    barBefore = new String[] {config.hudStyle, config.hudAnchor, Integer.toString(config.hudOffsetX),
                            Integer.toString(config.hudOffsetY), Boolean.toString(config.showHud)};
                    config.theme = "glass";
                    controller.simulate(new NowPlaying("selftest", PROBE.title(), PROBE.artist(), PROBE.album(), true,
                            SIMULATED_POSITION_MS, PROBE.durationMs(), System.currentTimeMillis()));
                }
                setBar(config, GLASS_BARS[i][0], "top", 0);
                minecraft.player.connection.sendCommand("time set " + GLASS_BARS[i][1]);
            }
            if (atSecond(at + 0.5)) {
                clearScreen(minecraft);
            }
            if (atSecond(at + 0.7)) {
                shoot(minecraft, "glass bar " + GLASS_BARS[i][0] + " " + GLASS_BARS[i][1]);
            }
        }
        if (atSecond(GLASS_FROM + GLASS_BARS.length) && barBefore != null) {
            config.hudStyle = barBefore[0];
            config.hudAnchor = barBefore[1];
            config.hudOffsetX = Integer.parseInt(barBefore[2]);
            config.hudOffsetY = Integer.parseInt(barBefore[3]);
            config.showHud = Boolean.parseBoolean(barBefore[4]);
            config.theme = themeBefore != null ? themeBefore : config.theme;
        }
    }

    /** The glass menu, page by page; each has to fit the screen. */
    private static void glassMenuSteps(Minecraft minecraft, LyricsController controller) {
        SkyLyricsConfig config = controller.config();
        for (int i = 0; i < GLASS_MENU_SHOTS.length; i++) {
            double at = GLASS_MENU_FROM + i;
            String style = GLASS_MENU_SHOTS[i][0];
            String shot = GLASS_MENU_SHOTS[i][1];
            if (atSecond(at)) {
                clearScreen(minecraft);
                if (i == 0) {
                    menuStyleBefore = config.menuStyle;
                    minecraft.player.connection.sendCommand("time set noon");
                }
                if (!style.equals(config.menuStyle) && minecraft.gui.screen() != null) {
                    minecraft.gui.screen().onClose();
                }
                config.menuStyle = style;
                if ("intro".equals(shot)) {
                    // The introduction to Hoergenau, as the first world with it shows it once; switching on is not
                    // clicked here.
                    minecraft.gui.setScreen(new HoergenauIntroScreen(config, (c, model) -> { }));
                } else if (!(minecraft.gui.screen() instanceof GlassMenuScreen)) {
                    LyricsInTheSkyClient.openSettings(minecraft);
                }
                if (minecraft.gui.screen() instanceof GlassMenuScreen menu) {
                    switch (shot) {
                        case "start" -> menu.show(null);
                        case "look-end", "display-end", "hoergenau-end" -> {
                            menu.show(shot.substring(0, shot.indexOf('-')));
                            menu.scrollToEnd();
                        }
                        case "picker" -> {
                            menu.show("look");
                            menu.openFirstColor();
                        }
                        default -> menu.show(shot);
                    }
                }
            }
            if (atSecond(at + 0.7)) {
                clearScreen(minecraft);
            }
            if (atSecond(at + 0.85)) {
                if (minecraft.gui.screen() instanceof GlassMenuScreen menu) {
                    LOGGER.info("SELFTEST menu {} {}: {}", style, shot, menu.fits() ? "fits" : "TOO BIG");
                } else if (minecraft.gui.screen() instanceof HoergenauIntroScreen intro) {
                    LOGGER.info("SELFTEST menu {} {}: {}", style, shot, intro.fits() ? "fits" : "TOO BIG");
                } else {
                    LOGGER.info("SELFTEST menu {} {}: NOT open", style, shot);
                }
                shoot(minecraft, "menu " + style + " " + shot);
            }
        }
        if (atSecond(GLASS_MENU_FROM + GLASS_MENU_SHOTS.length)) {
            if (minecraft.gui.screen() instanceof GlassMenuScreen menu) {
                menu.show(null);
                menu.onClose();
            } else if (minecraft.gui.screen() instanceof HoergenauIntroScreen intro) {
                intro.onClose();
            }
            config.menuStyle = menuStyleBefore != null ? menuStyleBefore : config.menuStyle;
        }
    }

    /** The new looks in the sky and the bar, with the demo singing. */
    private static void optionSteps(Minecraft minecraft, LyricsController controller) {
        SkyLyricsConfig config = controller.config();
        if (atSecond(OPTIONS_FROM)) {
            clearScreen(minecraft);
            minecraft.player.connection.sendCommand("time set noon");
            config.outline = true;
            config.outlineColor = 0x1A0B2E;
            config.uppercase = true;
            config.palette = "custom";
            config.customTop = 0xFFFFFF;
            config.customBottom = 0xFFB8E8;
            config.customAccent = 0x38BDF8;
            config.customGlow = 0xA855F7;
            config.showHud = true;
            config.hudStyle = "pill";
            controller.startDemo();
        }
        String[] names = {"options outline uppercase custom", "options rainbow underline progress", "screen subtitle",
                "screen karaoke", "screen stage", "preset neon", "preset retro", "preset cinema"};
        for (int i = 0; i < OPTION_SHOTS.length; i++) {
            if (atSecond(OPTIONS_FROM + OPTION_SHOTS[i])) {
                shoot(minecraft, names[i]);
            }
            // Right after each picture the next look, so it has settled when its own picture is taken.
            if (atSecond(OPTIONS_FROM + OPTION_SHOTS[i] + 0.1)) {
                switch (i) {
                    case 0 -> {
                        config.outline = false;
                        config.uppercase = false;
                        config.palette = "rainbow";
                        config.hudHighlight = "underline";
                        config.hudProgress = true;
                        config.hudStyle = "pill";
                    }
                    case 1 -> {
                        config.applyPreset("subtitle");
                        config.showHud = true;
                    }
                    case 2 -> {
                        config.applyPreset("karaoke");
                        config.showHud = true;
                    }
                    case 3 -> {
                        config.applyPreset("stage");
                        config.showHud = true;
                    }
                    case 4 -> {
                        config.applyPreset("neon");
                        minecraft.player.connection.sendCommand("time set midnight");
                    }
                    case 5 -> {
                        config.applyPreset("retro");
                        minecraft.player.connection.sendCommand("time set noon");
                    }
                    case 6 -> config.applyPreset("cinema");
                    default -> {
                        controller.stopDemo();
                        config.applyPreset("standard");
                        SkyLyricsConfig defaults = new SkyLyricsConfig();
                        config.hudHighlight = defaults.hudHighlight;
                        config.hudProgress = defaults.hudProgress;
                    }
                }
            }
        }
    }

    /** "Wortgenau" on: the song playing gets word times from one of the services; switched off again afterwards. */
    private static void wordTimeSteps(LyricsController controller) {
        SkyLyricsConfig config = controller.config();
        if (atSecond(WORD_TIMES_ON)) {
            wordSourcesBefore = List.copyOf(config.wordSources);
            config.setWordTimes(true);
            controller.refreshWordTimes();
        }
        if (atSecond(WORD_TIMES_CHECK)) {
            String status = controller.status();
            LOGGER.info("SELFTEST word times: {} – {}", status.contains("wortgenau") ? "ok" : "NONE (estimated)", status);
            config.wordSources = new ArrayList<>(wordSourcesBefore);
            controller.refreshWordTimes();
        }
    }

    private static List<String> wordSourcesBefore = List.of();

    /** No command feedback or tutorial toast over the bar and the menu in the screenshots. */
    private static void clearScreen(Minecraft minecraft) {
        minecraft.gui.hud.getChat().clearMessages(false);
        minecraft.gui.toastManager().clear();
    }

    private static void setBar(SkyLyricsConfig config, String style, String anchor, int offsetX) {
        config.showHud = true;
        config.hudStyle = style;
        config.hudAnchor = anchor;
        config.hudOffsetX = offsetX;
        config.hudOffsetY = 0;
    }

    private static boolean atSecond(double second) {
        return ticksInWorld == DEMO_START_TICK + (int) Math.round(second * 20);
    }

    private static void shoot(Minecraft minecraft, String name) {
        Screenshot.grab(minecraft.gameDirectory, minecraft.gameRenderer.mainRenderTarget(),
                message -> LOGGER.info("SELFTEST screenshot {}: {}", name, message.getString()));
    }
}
