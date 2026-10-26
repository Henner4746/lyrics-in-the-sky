package de.henner.lyricsinthesky.dev;

import de.henner.lyricsinthesky.LyricsController;
import de.henner.lyricsinthesky.LyricsInTheSkyClient;
import de.henner.lyricsinthesky.SkyLyricsConfig;
import de.henner.lyricsinthesky.render.NowPlayingHud;
import de.henner.lyricsinthesky.render.Perf;
import de.henner.lyricsinthesky.spotify.SpotifyAccount;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.tutorial.TutorialSteps;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A long run with real music, enabled with {@code -Dlyricsinthesky.livetest=<minutes>}, for a machine without a screen
 * of its own. Once the world is loaded it starts the Spotify login and writes the consent page's address to the log and
 * to {@code spotify-login.txt}, since no browser opens here: whoever runs the test opens it on their own PC, with the
 * callback port tunnelled in. Then it switches every word time service on, goes through the looks one a minute, opens
 * the menu, switches streamer mode for a while, logs what plays every 10 s, saves a screenshot every 30 s and quits.
 */
public final class LiveTest {
    private static final Logger LOGGER = LoggerFactory.getLogger("lyricsinthesky-livetest");
    /** Mode, style and bar, one look a minute. */
    private static final String[][] LOOKS = {{"sky", "elegant", "pill"}, {"around", "elegant", "karaoke"},
            {"sky", "flow", "plain"}, {"sky", "natural", "karaoke"}, {"around", "classic", "pill"}, {"sky", "classic", "title"}};
    private static int ticksInWorld;

    private LiveTest() {}

    public static boolean isEnabled() {
        return minutes() > 0;
    }

    private static int minutes() {
        return Integer.getInteger("lyricsinthesky.livetest", 0);
    }

    public static void register(LyricsController controller, SpotifyAccount spotify) {
        ClientTickEvents.END_CLIENT_TICK.register(minecraft -> tick(minecraft, controller, spotify));
    }

    private static void tick(Minecraft minecraft, LyricsController controller, SpotifyAccount spotify) {
        if (minecraft.player == null || minecraft.level == null) {
            return;
        }
        ticksInWorld++;
        SkyLyricsConfig config = controller.config();
        if (ticksInWorld == 40) {
            minecraft.player.connection.sendCommand("gamemode spectator");
            minecraft.player.connection.sendCommand("tp @s ~ 130 ~ 0 -24");
            minecraft.player.connection.sendCommand("weather clear");
            // Nobody moves here, so the game's "Move with W, A, S and D" would cover the bar in every picture.
            minecraft.getTutorial().setStep(TutorialSteps.NONE);
            minecraft.gui.toastManager().clear();
            config.lrclibAllowed = true;
            config.setWordTimes(true);
            controller.refreshWordTimes();
        }
        // The login runs out after five minutes; while not connected, a new one starts, with a new address.
        if (ticksInWorld >= 60 && ticksInWorld % 200 == 60 && !spotify.isConnected()
                && spotify.state() != SpotifyAccount.State.WAITING_FOR_BROWSER) {
            spotify.connect(url -> {
                LOGGER.info("LIVETEST spotify login: {}", url);
                try {
                    Files.writeString(FabricLoader.getInstance().getGameDir().resolve("spotify-login.txt"), url,
                            StandardCharsets.UTF_8);
                } catch (IOException e) {
                    LOGGER.warn("LIVETEST login address not written: {}", e.toString());
                }
                return true;
            });
        }
        if (ticksInWorld % 20 != 0) {
            return;
        }
        int second = ticksInWorld / 20;
        if (second % 10 == 0) {
            NowPlayingHud.Shown bar = NowPlayingHud.lastShown();
            LOGGER.info("LIVETEST {} s: Spotify {}, {}; bar '{}'", second, spotify.state(), controller.status(),
                    bar == null ? "-" : bar.text());
        }
        if (second % 60 == 5) {
            String[] look = LOOKS[(second / 60) % LOOKS.length];
            config.mode = look[0];
            config.style = look[1];
            config.hudStyle = look[2];
            config.showHud = true;
            minecraft.player.connection.sendCommand((second / 60) % 2 == 0 ? "time set noon" : "time set midnight");
            LOGGER.info("LIVETEST look: {} {} with bar {}; perf {}", look[0], look[1], look[2], Perf.report());
        }
        // Streamer mode for half a minute every four minutes, switched on before the picture at 150 so it shows.
        if (second % 240 == 148) {
            LyricsInTheSkyClient.setStreamerMode(config, true);
        }
        if (second % 240 == 180) {
            LyricsInTheSkyClient.setStreamerMode(config, false);
        }
        // The menu every two minutes, in the fancy look.
        if (second % 120 == 40) {
            config.menuStyle = "fancy";
            LyricsInTheSkyClient.openSettings(minecraft);
        }
        if (second % 120 == 42) {
            shoot(minecraft, "menu " + second);
        }
        if (second % 120 == 44 && minecraft.gui.screen() != null) {
            minecraft.gui.screen().onClose();
        }
        if (second % 30 == 0) {
            shoot(minecraft, (config.streamerMode ? "streamer " : "") + config.mode + " " + config.style + " " + second);
        }
        if (second >= minutes() * 60) {
            LOGGER.info("LIVETEST done after {} min", minutes());
            minecraft.stop();
        }
    }

    private static void shoot(Minecraft minecraft, String name) {
        Screenshot.grab(minecraft.gameDirectory, minecraft.gameRenderer.mainRenderTarget(),
                message -> LOGGER.info("LIVETEST screenshot {}: {}", name, message.getString()));
    }
}
