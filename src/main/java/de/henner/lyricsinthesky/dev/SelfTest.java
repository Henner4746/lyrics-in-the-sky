package de.henner.lyricsinthesky.dev;

import de.henner.lyricsinthesky.LyricsController;
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
    private static final double[] SHOTS = {0.6, 2.0, 3.7, 4.6, 7.6, 9.6, 12.6, 16.2, 21.4, 24.9, 26.4, 29.0, 33.3, 35.6};
    private static final double NIGHT_FROM = 21.0;
    /** With {@code -Dlyricsinthesky.selftest.record=true}: demo seconds captured every tick. */
    private static final double RECORD_FROM = 1.5;
    private static final double RECORD_TO = 20.5;
    /** After the demo: a simulated track at this song position, photographed during the next seconds. */
    private static final double SIMULATION_FROM = 38.0;
    private static final long SIMULATED_POSITION_MS = 57_000;
    private static final double[] SIMULATION_SHOTS = {41.0, 44.0, 47.0};
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
        if (ticksInWorld == DEMO_START_TICK) {
            controller.startDemo();
        }
        if (atSecond(NIGHT_FROM)) {
            minecraft.player.connection.sendCommand("time set midnight");
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
        for (double shot : SIMULATION_SHOTS) {
            if (atSecond(shot)) {
                LOGGER.info("SELFTEST status: {}", controller.status());
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
        }
        if (atSecond(QUIT_AT)) {
            minecraft.stop();
        }
    }

    private static boolean atSecond(double second) {
        return ticksInWorld == DEMO_START_TICK + (int) Math.round(second * 20);
    }

    private static void shoot(Minecraft minecraft, String name) {
        Screenshot.grab(minecraft.gameDirectory, minecraft.gameRenderer.mainRenderTarget(),
                message -> LOGGER.info("SELFTEST screenshot {}: {}", name, message.getString()));
    }
}
