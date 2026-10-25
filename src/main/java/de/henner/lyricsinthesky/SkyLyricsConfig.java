package de.henner.lyricsinthesky;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Settings stored in {@code config/lyricsinthesky.json}. Fields are public for Gson and the commands. */
public final class SkyLyricsConfig {
    private static final Logger LOGGER = LoggerFactory.getLogger("lyricsinthesky");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    /** "standard": rose in the sky, white to lavender around the player; "auto": a different palette per song. */
    public static final List<String> PALETTES = List.of("standard", "rose", "lavender", "auto", "aurora", "sunset", "gold",
            "sakura", "ocean", "white");
    /** "sans": a round modern sans-serif (Poppins), "script": handwriting (Kalam), "minecraft": the game font. */
    public static final List<String> FONTS = List.of("sans", "script", "minecraft");
    /** "sky": far away in the sky behind everything, like the sun. "around": close around the player, always visible. */
    public static final List<String> MODES = List.of("sky", "around");
    /**
     * "elegant": in the sky word groups building up a line near the horizon, around the player single words
     * floating at fixed spots. "flow": the current line in the middle, the next one faintly below, gliding up
     * from line to line. "classic": every line pops up at its own spot with varied animations.
     */
    public static final List<String> STYLES = List.of("elegant", "flow", "classic");
    public static final float DEFAULT_ELEVATION = 16f;

    public boolean enabled = true;
    /** One of {@link #MODES}. */
    public String mode = "sky";
    /** One of {@link #STYLES}. */
    public String style = "elegant";
    /** Positive values show lyrics earlier, negative values later. */
    public long offsetMs = 0;
    /** {@code null} until the player answered the first-start question about lrclib.net. */
    public Boolean lrclibAllowed = null;
    /** Base height of the lyrics above the horizon, in degrees. */
    public float elevationDeg = DEFAULT_ELEVATION;
    /** Size multiplier for the text. */
    public float scale = 1f;
    /** 0 = invisible, 1 = fully opaque. */
    public float opacity = 0.82f;
    /** One of {@link #PALETTES}. */
    public String palette = "standard";
    /** One of {@link #FONTS}. */
    public String font = "sans";
    /** Let new lines appear in front of the player after they turn away. */
    public boolean followView = true;
    /** Show title and artist in the sky when a new song starts. */
    public boolean showTitleCard = true;
    /** Show the "now playing" pill at the top of the screen. */
    public boolean showHud = true;
    /**
     * Client ID of the player's own Spotify developer app, needed for "Connect with Spotify". Not a secret:
     * the login uses PKCE, so no client secret is involved.
     */
    public String spotifyClientId = "";

    private transient Path file;

    public static SkyLyricsConfig load(Path file) {
        SkyLyricsConfig config = null;
        if (Files.exists(file)) {
            try {
                config = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), SkyLyricsConfig.class);
            } catch (IOException | JsonParseException e) {
                LOGGER.warn("Could not read {}, using defaults: {}", file, e.getMessage());
            }
        }
        if (config == null) {
            config = new SkyLyricsConfig();
        }
        config.file = file;
        config.clamp();
        if (config.spotifyClientId.isEmpty()) {
            config.spotifyClientId = defaultSpotifyClientId();
        }
        return config;
    }

    /** Client ID baked in at build time, so the Spotify login works without setting up an own app. */
    private static String defaultSpotifyClientId() {
        try (InputStream in = SkyLyricsConfig.class.getResourceAsStream("/lyricsinthesky/defaults.properties")) {
            if (in == null) {
                return "";
            }
            Properties properties = new Properties();
            properties.load(in);
            String id = properties.getProperty("spotify.clientId", "").trim();
            // An unexpanded placeholder means a build without a configured ID.
            return id.startsWith("$") ? "" : id;
        } catch (IOException e) {
            return "";
        }
    }

    public void save() {
        clamp();
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(this), StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOGGER.warn("Could not save {}: {}", file, e.getMessage());
        }
    }

    private void clamp() {
        offsetMs = Math.max(-10_000, Math.min(10_000, offsetMs));
        elevationDeg = Math.max(10f, Math.min(70f, elevationDeg));
        scale = Math.max(0.3f, Math.min(3f, scale));
        opacity = Math.max(0.1f, Math.min(1f, opacity));
        // Test builds of 1.2.0 called the default style and palette "video".
        if ("video".equals(palette)) {
            palette = "standard";
        }
        if ("video".equals(style)) {
            style = "elegant";
        }
        if (palette == null || !PALETTES.contains(palette)) {
            palette = "standard";
        }
        if (font == null || !FONTS.contains(font)) {
            font = "sans";
        }
        if (mode == null || !MODES.contains(mode)) {
            mode = "sky";
        }
        if (style == null || !STYLES.contains(style)) {
            style = "elegant";
        }
        spotifyClientId = spotifyClientId == null ? "" : spotifyClientId.trim();
    }
}
