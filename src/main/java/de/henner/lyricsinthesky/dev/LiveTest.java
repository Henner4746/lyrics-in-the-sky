package de.henner.lyricsinthesky.dev;

import de.henner.lyricsinthesky.LyricsController;
import de.henner.lyricsinthesky.LyricsInTheSkyClient;
import de.henner.lyricsinthesky.SkyLyricsConfig;
import de.henner.lyricsinthesky.gui.GlassMenuScreen;
import de.henner.lyricsinthesky.lyrics.Lyrics;
import de.henner.lyricsinthesky.render.NowPlayingHud;
import de.henner.lyricsinthesky.render.Perf;
import de.henner.lyricsinthesky.spotify.SpotifyAccount;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
 * Alongside it logs what is needed to measure the lyrics against a recording of the music: the wall clock with the
 * song position twice a second, and each song's word times as shown.
 */
public final class LiveTest {
    private static final Logger LOGGER = LoggerFactory.getLogger("lyricsinthesky-livetest");
    /** Mode, style, bar and the extra options switched on, one look a minute. */
    private static final String[][] LOOKS = {{"sky", "elegant", "pill", ""}, {"around", "elegant", "karaoke", ""},
            {"sky", "flow", "plain", ""}, {"sky", "natural", "karaoke", "outline uppercase custom progress"},
            {"around", "classic", "pill", ""}, {"sky", "classic", "title", ""},
            {"sky", "elegant", "pill", "rainbow underline progress"}, {"screen", "elegant", "pill", "subtitle outline"},
            {"screen", "elegant", "title", "karaoke"}, {"screen", "elegant", "pill", "stage"}};
    /** Pages of the glass menu shown in turn; {@code null} is its start with the song playing. */
    private static final String[] MENU_PAGES = {null, "look", "bar", "hoergenau", "display", "motion"};
    private static int ticksInWorld;
    private static Lyrics dumped;

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
        if (ticksInWorld % 10 == 0) {
            logClock(minecraft, controller);
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
            extras(config, look[3]);
            minecraft.player.connection.sendCommand((second / 60) % 2 == 0 ? "time set noon" : "time set midnight");
            LOGGER.info("LIVETEST look: {} {} with bar {}{}; perf {}", look[0], look[1], look[2],
                    look[3].isEmpty() ? "" : " and " + look[3], Perf.report());
        }
        // Streamer mode for half a minute every four minutes, switched on before the picture at 150 so it shows.
        if (second % 240 == 148) {
            LyricsInTheSkyClient.setStreamerMode(config, true);
        }
        if (second % 240 == 180) {
            LyricsInTheSkyClient.setStreamerMode(config, false);
        }
        // The glass menu every two minutes, a different page each time.
        if (second % 120 == 40) {
            config.menuStyle = (second / 120) % 2 == 0 ? "minimal" : "glass";
            LyricsInTheSkyClient.openSettings(minecraft);
            if (minecraft.gui.screen() instanceof GlassMenuScreen menu) {
                menu.show(MENU_PAGES[(second / 120) % MENU_PAGES.length]);
            }
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

    /**
     * For measuring the lyrics against the sung audio recorded beside the game: twice a second the wall clock and the
     * song position the lyrics are shown at, and every set of lyrics the song gets (LRCLIB's first, word times later)
     * with the time of each word as the game shows it, in {@code livetest-lyrics}.
     */
    private static void logClock(Minecraft minecraft, LyricsController controller) {
        long wall = System.currentTimeMillis();
        LyricsController.Timeline now = controller.timeline(wall);
        if (now == null) {
            return;
        }
        String song = Integer.toHexString(now.trackKey().hashCode());
        if (now.playing()) {
            LOGGER.info("LIVETEST clock {} {} {}", wall, now.positionMs(), song);
        }
        Lyrics lyrics = controller.currentLyrics();
        if (lyrics == null || lyrics == dumped || lyrics.isEmpty()) {
            return;
        }
        dumped = lyrics;
        StringBuilder rows = new StringBuilder("# ").append(controller.status()).append('\n');
        for (int i = 0; i < lyrics.lines().size(); i++) {
            Lyrics.Line line = lyrics.lines().get(i);
            String[] words = line.words();
            long[] times = lyrics.wordTimes(i, now.durationMs());
            for (int w = 0; w < words.length && w < times.length; w++) {
                rows.append(i).append('\t').append(line.startMs()).append('\t').append(w).append('\t').append(times[w])
                        .append('\t').append(words[w]).append('\n');
            }
        }
        Path file = minecraft.gameDirectory.toPath().resolve("livetest-lyrics").resolve(wall + "-" + song + ".tsv");
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, rows, StandardCharsets.UTF_8);
            LOGGER.info("LIVETEST lyrics {} {} {}", wall, song, file.getFileName());
        } catch (IOException e) {
            LOGGER.warn("LIVETEST lyrics not written: {}", e.toString());
        }
    }

    /** The new looks: outline, capitals, own colors or the rainbow, the bar's progress line and underline. */
    private static void extras(SkyLyricsConfig config, String extras) {
        SkyLyricsConfig defaults = new SkyLyricsConfig();
        config.outline = extras.contains("outline");
        config.outlineColor = 0x1A0B2E;
        config.uppercase = extras.contains("uppercase");
        config.palette = extras.contains("custom") ? "custom" : extras.contains("rainbow") ? "rainbow" : defaults.palette;
        config.customTop = 0xFFFFFF;
        config.customBottom = 0xFFB8E8;
        config.customAccent = 0x38BDF8;
        config.customGlow = 0xA855F7;
        config.hudProgress = extras.contains("progress");
        config.hudHighlight = extras.contains("underline") ? "underline" : defaults.hudHighlight;
        config.screenStyle = extras.contains("karaoke") ? "karaoke" : extras.contains("stage") ? "stage" : "subtitle";
        config.screenPosition = extras.contains("stage") ? "center" : "bottom";
    }

    private static void shoot(Minecraft minecraft, String name) {
        Screenshot.grab(minecraft.gameDirectory, minecraft.gameRenderer.mainRenderTarget(),
                message -> LOGGER.info("LIVETEST screenshot {}: {}", name, message.getString()));
    }
}
