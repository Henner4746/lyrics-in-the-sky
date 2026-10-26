package de.henner.lyricsinthesky;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Settings stored in {@code config/lyricsinthesky.json}. Fields are public for Gson and the commands. */
public final class SkyLyricsConfig {
    private static final Logger LOGGER = LoggerFactory.getLogger("lyricsinthesky");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    /** "standard": rose in the sky, white to lavender around the player; "auto": a different palette per song. */
    public static final List<String> PALETTES = List.of("standard", "rose", "lavender", "auto", "aurora", "sunset", "gold",
            "sakura", "ocean", "white", "glass", "neon");
    /** "sans": a round modern sans-serif (Poppins), "script": handwriting (Kalam), "minecraft": the game font. */
    public static final List<String> FONTS = List.of("sans", "script", "minecraft");
    /** "sky": far away in the sky behind everything, like the sun. "around": close around the player, always visible. */
    public static final List<String> MODES = List.of("sky", "around");
    /**
     * "elegant": in the sky word groups building up a line near the horizon, around the player single words
     * floating at fixed spots. "natural": the same as if it really stood in the world – lines right above the
     * hills, words around the player hidden by players and blocks in front of them, the words being sung
     * highlighted as a group. "flow": the current line in the middle, the next one faintly below, gliding up
     * from line to line. "classic": every line pops up at its own spot with varied animations.
     */
    public static final List<String> STYLES = List.of("elegant", "natural", "flow", "classic");
    /**
     * One-click looks for the settings screen and {@code /skylyrics preset}: "standard" the defaults, "sky" and
     * "around" the 1:1 style in each mode, "party" big playful words around the player that wobble on the beat,
     * "calm" the quiet white stack. They set the look only, never timing, lookups, the Spotify login or whether the
     * lyrics are on.
     */
    public static final List<String> PRESETS = List.of("standard", "sky", "around", "party", "calm", "glass");
    /**
     * Looks of the menu and the now-playing bar: "classic" the plain black-and-white panel and dark bar, "glass" Liquid
     * Glass, frosted see-through glass with a bright rim and a soft shadow, like Apple's iOS 26.
     */
    public static final List<String> THEMES = List.of("classic", "glass");
    /**
     * Looks of the now-playing bar: "pill" the rounded bar with the line and the word being sung, "karaoke" two lines
     * in a box with every word filling up as it is sung, "plain" just the words with a shadow, "title" only the song.
     */
    public static final List<String> HUD_STYLES = List.of("pill", "karaoke", "plain", "title");
    /** Edges the bar sits at: the top of the screen, or the bottom above the hotbar. */
    public static final List<String> HUD_ANCHORS = List.of("top", "bottom");
    public static final float DEFAULT_ELEVATION = 16f;
    /**
     * The word time services ("Grauzone"), in the order they are asked: all unofficial, free to reach without an
     * account, and liable to change or vanish any day. Each is off until the player switches it on.
     */
    public static final List<String> WORD_SOURCES = List.of("deezer", "lyricsplus", "netease", "kugou", "synclrc",
            "betterlyrics");

    public boolean enabled = true;
    /** One of {@link #MODES}. */
    public String mode = "sky";
    /** One of {@link #STYLES}. */
    public String style = "elegant";
    /** Positive values show lyrics earlier, negative values later. */
    public long offsetMs = 0;
    /** Lyrics lookups on lrclib.net; on unless the player switched them off. */
    public Boolean lrclibAllowed = true;
    /** The one-time chat note on what is sent to lrclib.net has been shown. */
    public boolean lrclibNoticeShown = false;
    /** The {@link #WORD_SOURCES} switched on; none unless the player switched them on ("Wortgenau"). */
    public List<String> wordSources = new ArrayList<>();
    /**
     * "Hoergenau": songs no service has timed are listened to on this PC and timed by whisper.cpp. Windows only; off
     * unless switched on, which downloads whisper.cpp and its model once.
     */
    public boolean hoergenau = false;
    /** Up to 1.3.0-dev.3 the only word time service; only read to carry the choice over to {@link #wordSources}. */
    public boolean lyricsPlus = false;
    /**
     * Timing corrections per song are shared anonymously with the timing tracker on henrik.click, and the ones many
     * players agreed on apply here too; on unless switched off.
     */
    public boolean shareTiming = true;
    /** The one-time chat note on sharing timing corrections has been shown. */
    public boolean timingNoticeShown = false;
    /** A random ID of this installation, so the tracker counts every player once; not linked to the Minecraft account. */
    public String installId = "";
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
    /** Show the "now playing" bar. */
    public boolean showHud = true;
    /** One of {@link #HUD_STYLES}. */
    public String hudStyle = "pill";
    /** One of {@link #HUD_ANCHORS}. */
    public String hudAnchor = "top";
    /** One of {@link #THEMES}. */
    public String theme = "glass";
    /** Size of the bar, 0.75 to 1.5. */
    public float hudScale = 1f;
    /** How solid the bar's background is, 0 (only the text) to 1. */
    public float hudOpacity = 1f;
    /** How round the bar's corners are, 0 (square) to 1 (a pill). */
    public float hudRoundness = 1f;
    /** Strength of the glow at night and the soft shadow by day around the lyrics, 0 to 2. */
    public float glow = 1f;
    /**
     * Where the bar was moved to by hand, in GUI pixels: its middle right of the screen's middle, and its edge away
     * from the anchor edge (down from the top, up from the bottom).
     */
    public int hudOffsetX = 0;
    public int hudOffsetY = 0;
    /** In the sky: mountains, trees and clouds stand in front of the text; off, it is drawn on top of everything. */
    public boolean skyBehindTerrain = true;
    /** The lyrics give a little kick on every sung word. */
    public boolean beatBounce = false;
    /** The lyrics turn along with the view, closely behind, instead of staying where they appeared. */
    public boolean followAlong = false;
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
        if (config.installId == null || config.installId.isBlank()) {
            // Kept at once: a new ID on every start would count the same player again.
            config.installId = UUID.randomUUID().toString();
            config.save();
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
        // Before 1.2.0 lookups waited for an answer in chat ("null"); now they are on unless switched off.
        if (lrclibAllowed == null) {
            lrclibAllowed = true;
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
        if (hudStyle == null || !HUD_STYLES.contains(hudStyle)) {
            hudStyle = "pill";
        }
        if (hudAnchor == null || !HUD_ANCHORS.contains(hudAnchor)) {
            hudAnchor = "top";
        }
        if (theme == null || !THEMES.contains(theme)) {
            theme = "glass";
        }
        hudScale = Float.isFinite(hudScale) ? Math.max(0.75f, Math.min(1.5f, hudScale)) : 1f;
        hudOpacity = Float.isFinite(hudOpacity) ? Math.max(0f, Math.min(1f, hudOpacity)) : 1f;
        hudRoundness = Float.isFinite(hudRoundness) ? Math.max(0f, Math.min(1f, hudRoundness)) : 1f;
        glow = Float.isFinite(glow) ? Math.max(0f, Math.min(2f, glow)) : 1f;
        // The bar is kept on screen when drawn; this only keeps the numbers sane.
        hudOffsetX = Math.max(-4000, Math.min(4000, hudOffsetX));
        hudOffsetY = Math.max(-4000, Math.min(4000, hudOffsetY));
        spotifyClientId = spotifyClientId == null ? "" : spotifyClientId.trim();
        Set<String> sources = new LinkedHashSet<>(wordSources == null ? List.of() : wordSources);
        if (lyricsPlus) {
            sources.add("lyricsplus");
            lyricsPlus = false;
        }
        sources.retainAll(WORD_SOURCES);
        wordSources = new ArrayList<>(WORD_SOURCES.stream().filter(sources::contains).toList());
    }

    /** Whether any word time service is switched on: the switch "Wortgenau". */
    public boolean wordTimes() {
        return !wordSources.isEmpty();
    }

    /** The main switch: all word time services on, or all off. */
    public void setWordTimes(boolean on) {
        wordSources = new ArrayList<>(on ? WORD_SOURCES : List.of());
    }

    public boolean wordSource(String name) {
        return wordSources.contains(name);
    }

    public void setWordSource(String name, boolean on) {
        Set<String> sources = new LinkedHashSet<>(wordSources);
        if (on) {
            sources.add(name);
        } else {
            sources.remove(name);
        }
        wordSources = new ArrayList<>(WORD_SOURCES.stream().filter(sources::contains).toList());
    }

    public void applyPreset(String preset) {
        SkyLyricsConfig defaults = new SkyLyricsConfig();
        mode = defaults.mode;
        style = defaults.style;
        palette = defaults.palette;
        font = defaults.font;
        opacity = defaults.opacity;
        scale = defaults.scale;
        elevationDeg = defaults.elevationDeg;
        skyBehindTerrain = defaults.skyBehindTerrain;
        beatBounce = defaults.beatBounce;
        followAlong = defaults.followAlong;
        followView = defaults.followView;
        showTitleCard = defaults.showTitleCard;
        showHud = defaults.showHud;
        glow = defaults.glow;
        switch (preset) {
            case "sky" -> style = "natural";
            case "around" -> {
                mode = "around";
                style = "natural";
            }
            case "party" -> {
                mode = "around";
                style = "classic";
                palette = "auto";
                scale = 1.15f;
                beatBounce = true;
                followAlong = true;
            }
            case "calm" -> {
                style = "flow";
                palette = "white";
                opacity = 0.7f;
                showTitleCard = false;
            }
            case "glass" -> {
                style = "flow";
                palette = "glass";
                glow = 1.4f;
                // Also the menu and the bar in glass; the other looks leave the theme as it is.
                theme = "glass";
            }
            default -> {
                // "standard": the defaults as set above.
            }
        }
    }

    /** The preset whose look is set right now, or {@code null} when the settings were changed by hand. */
    public String currentPreset() {
        for (String preset : PRESETS) {
            SkyLyricsConfig probe = new SkyLyricsConfig();
            probe.applyPreset(preset);
            if (Arrays.equals(probe.look(), look())) {
                return preset;
            }
        }
        return null;
    }

    private Object[] look() {
        return new Object[] {mode, style, palette, font, opacity, scale, elevationDeg, skyBehindTerrain, beatBounce,
                followAlong, followView, showTitleCard, showHud, glow};
    }
}
