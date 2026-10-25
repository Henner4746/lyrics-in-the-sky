package de.henner.lyricsinthesky.dev;

import de.henner.lyricsinthesky.LyricsController;
import de.henner.lyricsinthesky.LyricsInTheSkyClient;
import de.henner.lyricsinthesky.SkyLyricsConfig;
import de.henner.lyricsinthesky.render.SkyLyricsRenderer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.levelgen.Heightmap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Development only, enabled with {@code -Dlyricsinthesky.showcase=true}: plays the demo under a scripted
 * camera flight for the preview video – dusk sky, the settings screen, "around you" with a turn, then night –
 * and quits. The camera moves every frame on a smooth spline, so a recording of the window glides.
 */
public final class Showcase {
    private static final Logger LOGGER = LoggerFactory.getLogger("lyricsinthesky-showcase");
    private static final int SETUP_TICK = 20;
    /** Seven seconds for the chunks around the flight path to load. */
    private static final int DEMO_START_TICK = 160;
    private static final double SETTINGS_OPEN = 10.6;
    private static final double SETTINGS_CLOSE = 13.2;
    private static final double NIGHT_FROM = 23.5;
    private static final double QUIT_AT = 40.0;
    /** Facing south-west, towards the sunset. */
    private static final float YAW0 = 40f;

    /** Camera keyframes: seconds from demo start, offset forward in blocks, height above the treetops, yaw, pitch. */
    private static final double[][] DUSK = {
            {-3.0, 0.0, 16.0, 0, -13},
            {4.0, 2.4, 16.0, 9, -15},
            {10.5, 4.6, 15.5, 21, -17},
            {13.4, 6.0, 6.5, 34, -4},
            {18.8, 8.6, 6.5, 52, -5},
            {21.8, 9.6, 6.8, 190, -6},
            {23.6, 10.0, 7.0, 200, -7},
    };
    private static final double[][] NIGHT = {
            {23.4, 3.0, 13.0, 200, -24},
            {31.0, 4.2, 13.0, 214, -29},
            {40.0, 5.4, 13.0, 228, -33},
    };

    private static int ticksInWorld;
    private static long demoStartNanos;
    private static double baseX;
    private static double baseZ;
    private static double groundY;
    private static int clearChatAt = -1;
    private static boolean demoStarted;
    private static boolean settingsOpened;
    private static boolean settingsClosed;
    private static boolean night;
    private static boolean done;

    private Showcase() {}

    public static boolean isEnabled() {
        return Boolean.getBoolean("lyricsinthesky.showcase");
    }

    public static void register(LyricsController controller) {
        // Only the demo: nothing that happens to play on this PC or the connected Spotify account.
        controller.muteSources();
        ClientTickEvents.END_CLIENT_TICK.register(minecraft -> tick(minecraft, controller));
        // A HUD element is drawn every frame: the hook that moves the camera at frame rate, not at 20 ticks/s.
        HudElementRegistry.attachElementBefore(VanillaHudElements.BOSS_BAR,
                Identifier.fromNamespaceAndPath(LyricsInTheSkyClient.MOD_ID, "showcase_camera"),
                (graphics, deltaTracker) -> moveCamera(Minecraft.getInstance()));
    }

    private static void tick(Minecraft minecraft, LyricsController controller) {
        if (minecraft.player == null || minecraft.level == null) {
            return;
        }
        ticksInWorld++;
        SkyLyricsConfig config = controller.config();
        if (ticksInWorld == SETUP_TICK) {
            minecraft.player.connection.sendCommand("gamemode spectator");
            minecraft.player.connection.sendCommand("time set 12900");
            minecraft.player.connection.sendCommand("weather clear");
            // The defaults a new player sees.
            config.style = "elegant";
            config.mode = "sky";
            config.palette = "standard";
            config.font = "sans";
            config.opacity = 0.82f;
            config.scale = 1f;
            config.elevationDeg = SkyLyricsConfig.DEFAULT_ELEVATION;
            config.offsetMs = 0;
            config.followView = true;
            config.showTitleCard = true;
            config.showHud = true;
            baseX = minecraft.player.getX();
            baseZ = minecraft.player.getZ();
        }
        if (ticksInWorld == DEMO_START_TICK - 40) {
            groundY = treetops(minecraft);
            minecraft.gui.hud.getChat().clearMessages(false);
            minecraft.gui.toastManager().clear();
            // The camera clock starts now and counts up to 0 at the demo start, so the flight never jumps.
            demoStartNanos = System.nanoTime() + 2_000_000_000L;
            LOGGER.info("SHOWCASE capture now, treetops at {}", groundY);
        }
        if (demoStartNanos == 0) {
            return;
        }
        double t = seconds();
        if (!demoStarted && t >= 0) {
            demoStarted = true;
            controller.startDemo();
            LOGGER.info("SHOWCASE demo start");
        }
        if (!settingsOpened && t >= SETTINGS_OPEN) {
            settingsOpened = true;
            LyricsInTheSkyClient.openSettings(minecraft);
        }
        if (!settingsClosed && t >= SETTINGS_CLOSE) {
            settingsClosed = true;
            if (minecraft.gui.screen() != null) {
                minecraft.gui.screen().onClose();
            }
            config.mode = "around";
        }
        if (!night && t >= NIGHT_FROM) {
            night = true;
            minecraft.player.connection.sendCommand("time set 18000");
            config.mode = "sky";
            // The video cuts to night here: start the sky stack fresh instead of gliding the old one away.
            SkyLyricsRenderer.INSTANCE.reset();
            clearChatAt = ticksInWorld + 1;
        }
        if (clearChatAt >= 0 && ticksInWorld >= clearChatAt && ticksInWorld < clearChatAt + 40) {
            // The command's feedback arrives some ticks later; keep the chat empty for two seconds so it never
            // shows up in the video.
            minecraft.gui.hud.getChat().clearMessages(false);
        }
        if (!done && t >= QUIT_AT) {
            done = true;
            LOGGER.info("SHOWCASE done");
            minecraft.stop();
        }
    }

    private static double seconds() {
        return (System.nanoTime() - demoStartNanos) / 1e9;
    }

    /** Highest block (leaves included) along the flight path, so the camera never ends up inside a tree. */
    private static double treetops(Minecraft minecraft) {
        double yaw = Math.toRadians(YAW0);
        int top = minecraft.level.getMinY();
        for (double forward = -2; forward <= 14; forward += 1) {
            for (double side = -5; side <= 5; side += 1) {
                double x = baseX - Math.sin(yaw) * forward - Math.cos(yaw) * side;
                double z = baseZ + Math.cos(yaw) * forward - Math.sin(yaw) * side;
                top = Math.max(top, minecraft.level.getHeight(Heightmap.Types.MOTION_BLOCKING, (int) Math.floor(x), (int) Math.floor(z)));
            }
        }
        return top;
    }

    private static void moveCamera(Minecraft minecraft) {
        LocalPlayer player = minecraft.player;
        if (player == null || demoStartNanos == 0 || done) {
            return;
        }
        double t = seconds();
        double[] pose = t < NIGHT_FROM ? spline(DUSK, t) : spline(NIGHT, t);
        double yaw = Math.toRadians(YAW0);
        // Minecraft yaw 0 looks south (+Z), 90 west (-X).
        double x = baseX - Math.sin(yaw) * pose[0];
        double z = baseZ + Math.cos(yaw) * pose[0];
        double y = groundY + pose[1];
        player.setPos(x, y, z);
        player.xo = x;
        player.yo = y;
        player.zo = z;
        float yawDeg = (float) (YAW0 + pose[2]);
        float pitch = (float) pose[3];
        player.setYRot(yawDeg);
        player.yRotO = yawDeg;
        player.setXRot(pitch);
        player.xRotO = pitch;
    }

    /**
     * Cubic Hermite spline through the keyframes with tangents from the neighbours, so speed changes smoothly
     * through every keyframe instead of stopping at it. Returns forward, height, yaw, pitch.
     */
    static double[] spline(double[][] keys, double t) {
        double[] out = new double[4];
        if (t <= keys[0][0]) {
            System.arraycopy(keys[0], 1, out, 0, 4);
            return out;
        }
        if (t >= keys[keys.length - 1][0]) {
            System.arraycopy(keys[keys.length - 1], 1, out, 0, 4);
            return out;
        }
        int i = 0;
        while (keys[i + 1][0] < t) {
            i++;
        }
        double t0 = keys[i][0];
        double t1 = keys[i + 1][0];
        double h = t1 - t0;
        double u = (t - t0) / h;
        double h00 = 2 * u * u * u - 3 * u * u + 1;
        double h10 = u * u * u - 2 * u * u + u;
        double h01 = -2 * u * u * u + 3 * u * u;
        double h11 = u * u * u - u * u;
        for (int c = 1; c <= 4; c++) {
            double m0 = tangent(keys, i, c);
            double m1 = tangent(keys, i + 1, c);
            out[c - 1] = h00 * keys[i][c] + h10 * h * m0 + h01 * keys[i + 1][c] + h11 * h * m1;
        }
        return out;
    }

    private static double tangent(double[][] keys, int i, int c) {
        int previous = Math.max(0, i - 1);
        int next = Math.min(keys.length - 1, i + 1);
        if (previous == next) {
            return 0;
        }
        return (keys[next][c] - keys[previous][c]) / (keys[next][0] - keys[previous][0]);
    }
}
