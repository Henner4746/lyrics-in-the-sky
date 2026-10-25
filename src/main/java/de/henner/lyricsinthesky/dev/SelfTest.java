package de.henner.lyricsinthesky.dev;

import de.henner.lyricsinthesky.LyricsController;
import de.henner.lyricsinthesky.LyricsInTheSkyClient;
import de.henner.lyricsinthesky.SkyLyricsConfig;
import de.henner.lyricsinthesky.gui.HudEditScreen;
import de.henner.lyricsinthesky.render.NowPlayingHud;
import de.henner.lyricsinthesky.lyrics.LrclibClient;
import de.henner.lyricsinthesky.lyrics.Lyrics;
import de.henner.lyricsinthesky.nowplaying.NowPlaying;
import java.util.Optional;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
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
    private static final double QUIT_AT = 49.0;
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
        if (atSecond(QUIT_AT)) {
            minecraft.stop();
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
