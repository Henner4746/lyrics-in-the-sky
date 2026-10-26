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
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Settings stored in {@code config/lyricsinthesky.json}. Fields are public for Gson and the commands. */
public final class SkyLyricsConfig {
    private static final Logger LOGGER = LoggerFactory.getLogger("lyricsinthesky");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    /**
     * "standard": rose in the sky, white to lavender around the player; "auto": a different palette per song; "custom":
     * the player's own colors ({@link #customTop} and the others); "rainbow": a new hue for every line.
     */
    public static final List<String> PALETTES = List.of("standard", "rose", "lavender", "auto", "aurora", "sunset", "gold",
            "sakura", "ocean", "white", "glass", "neon", "rainbow", "custom");
    /** What the lyrics do while the music is paused: "dim" fade to 40 %, "hide" fade out, "keep" stay as they are. */
    public static final List<String> PAUSE_MODES = List.of("dim", "hide", "keep");
    /** How the bar marks the word being sung: "box" behind it, "color" the word itself, "underline" a line below. */
    public static final List<String> HUD_HIGHLIGHTS = List.of("box", "color", "underline");
    /** The bar's type: "auto" as the lyrics (handwriting as "modern"), "sans" modern, "minecraft" the game's. */
    public static final List<String> HUD_FONTS = List.of("auto", "sans", "minecraft");
    /**
     * Hoergenau's speech model: "small" (190 MB, quick on any PC) or "turbo" (Whisper large-v3-turbo, 574 MB, hears
     * sung words far better and times them more closely, needs a few times as long).
     */
    public static final List<String> HOERGENAU_MODELS = List.of("small", "turbo");
    /** How much of the PC Hoergenau may use: "low" two cores, "normal" four, "high" all but one. */
    public static final List<String> HOERGENAU_POWERS = List.of("low", "normal", "high");
    /**
     * Bumped when a default changes for players who never chose: 2 made the glass menu the default, 3 the menu in the
     * old look ("minimal") with every option.
     */
    static final int CONFIG_VERSION = 3;
    /** "sans": a round modern sans-serif (Poppins), "script": handwriting (Kalam), "minecraft": the game font. */
    public static final List<String> FONTS = List.of("sans", "script", "minecraft");
    /**
     * "sky": far away in the sky behind everything, like the sun. "around": close around the player, always visible.
     * "screen": on the screen itself, as subtitles, karaoke or big lines as on a stage ({@link #SCREEN_STYLES}).
     */
    public static final List<String> MODES = List.of("sky", "around", "screen");
    public static final Map<String, String> MODE_NAMES = Map.of("sky", "Am Himmel", "around", "Um dich herum",
            "screen", "Bildschirm");
    /**
     * Looks of the lyrics on the screen: "subtitle" the line at the bottom like a film's subtitles, "karaoke" the line
     * filling up word by word with the next one below, "stage" big lines on the left, the line before and the next ones
     * dimmed, gliding up as the song goes on.
     */
    public static final List<String> SCREEN_STYLES = List.of("subtitle", "karaoke", "stage");
    public static final Map<String, String> SCREEN_STYLE_NAMES = Map.of("subtitle", "Untertitel", "karaoke", "Karaoke",
            "stage", "Bühne");
    /** Where the lyrics on the screen sit. */
    public static final List<String> SCREEN_POSITIONS = List.of("bottom", "center", "top");
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
    public static final List<String> PRESETS = List.of("standard", "sky", "around", "party", "calm", "glass", "subtitle",
            "karaoke", "stage", "neon", "rainbow", "cinema", "retro", "clean");
    public static final Map<String, String> PRESET_NAMES = Map.ofEntries(Map.entry("standard", "Standard"),
            Map.entry("sky", "Himmel 1:1"), Map.entry("around", "Um dich 1:1"), Map.entry("party", "Party"),
            Map.entry("calm", "Ruhig"), Map.entry("glass", "Glas"), Map.entry("subtitle", "Untertitel"),
            Map.entry("karaoke", "Karaoke"), Map.entry("stage", "Bühne"), Map.entry("neon", "Neon-Nacht"),
            Map.entry("rainbow", "Regenbogen"), Map.entry("cinema", "Kino"), Map.entry("retro", "Retro"),
            Map.entry("clean", "Schlicht"), Map.entry("", "Eigener Look"));

    /** What a preset gives, for the menus. */
    public static String presetHint(String preset) {
        return switch (preset) {
            case "standard" -> "Alles wie beim ersten Start: Elegant am Himmel.";
            case "sky" -> "Am Himmel im Stil 1:1: Zeilen knapp über den Hügeln, hinter Bergen und Wolken.";
            case "around" -> "Um dich herum im Stil 1:1: Wörter fest in der Welt, Spieler und Blöcke stehen davor.";
            case "party" -> "Große verspielte Wörter um dich herum, die im Takt wackeln und sich mit dir drehen.";
            case "calm" -> "Der ruhige weiße Stapel am Himmel, ohne Songtitel.";
            case "glass" -> "Liquid Glass: klarer Stapel in Eisblau mit Leuchten, Menü und Leiste aus Glas.";
            case "subtitle" -> "Wie Untertitel im Kino: die Zeile unten auf dem Bildschirm, weiß mit Kontur.";
            case "karaoke" -> "Karaoke auf dem Bildschirm: die Zeile füllt sich Wort für Wort, die nächste wartet darunter.";
            case "stage" -> "Große Zeilen links auf dem Bildschirm wie bei Spotify und Apple Music, alles gleitet weich hoch.";
            case "neon" -> "Leuchtendes Magenta und Cyan am Himmel, stark leuchtend und im Takt – am schönsten nachts.";
            case "rainbow" -> "Jede Zeile in einer neuen Farbe, verspielt um dich herum und kräftig im Takt.";
            case "cinema" -> "Groß, weiß und in Großbuchstaben mit Kontur, ruhig gleitend wie ein Filmtitel.";
            case "retro" -> "Die Minecraft-Schrift in Gold mit dunkler Kontur, verspielt am Himmel.";
            case "clean" -> "Nur der Text: schlicht weiß am Himmel, kaum Leuchten, ohne Leiste und ohne Titel.";
            default -> "Eine Vorlage setzt Anzeige, Stil, Farben, Schrift und Effekte auf einmal.";
        };
    }
    /**
     * Looks of the menu and the now-playing bar: "classic" the plain black-and-white panel and dark bar, "glass" Liquid
     * Glass, frosted see-through glass with a bright rim and a soft shadow, like Apple's iOS 26.
     */
    public static final List<String> THEMES = List.of("classic", "glass");
    /**
     * Looks of the menu itself: "minimal" every page and option drawn as the menu always looked (the plain panel, grey
     * labels, thin lines, nothing moving), "glass" the same as a black glass window with cards and a live preview,
     * "fancy" the lively older menu with a moving backdrop and sparkles.
     */
    public static final List<String> MENU_STYLES = List.of("minimal", "glass", "fancy");
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
    /**
     * Songs Hoergenau learned are shared with the tracker on henrik.click, and songs other players learned are taken
     * from there; on unless switched off.
     */
    public boolean shareLearned = true;
    /** The one-time chat note on sharing learned songs has been shown. */
    public boolean learnedNoticeShown = false;
    /** The introduction to Hoergenau has been shown; it comes once, never again. */
    public boolean hoergenauIntroShown = false;
    /** The chat offer of the word time services ("Wortgenau") has been shown; it comes once, never again. */
    public boolean wordTimesOffered = false;
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
    /** One of {@link #SCREEN_STYLES}: the look of the lyrics on the screen. */
    public String screenStyle = "subtitle";
    /** One of {@link #SCREEN_POSITIONS}. */
    public String screenPosition = "bottom";
    /** Size of the lyrics on the screen, 0.6 to 2. */
    public float screenScale = 1f;
    /** A dark band behind the lyrics on the screen, as behind video captions. */
    public boolean screenBackground = false;
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
    /** One of {@link #MENU_STYLES}. */
    public String menuStyle = "minimal";
    /** Version of the defaults this file was written with; see {@link #CONFIG_VERSION}. */
    public int configVersion = 0;
    /** Streamer mode: explicit words in lyrics, titles and artists show as their first letter and stars. */
    public boolean streamerMode = false;
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

    /** The palette "custom": color at the top and at the bottom of the letters, of the words being sung, of the glow. */
    public int customTop = 0xFFFFFF;
    public int customBottom = 0xC9B6FF;
    public int customAccent = 0xA855F7;
    public int customGlow = 0xB98CFF;
    /** A dark ring around every letter, so the lyrics read on any background. */
    public boolean outline = false;
    public int outlineColor = 0x000000;
    /** Width of that ring, 0.5 to 2. */
    public float outlineWidth = 1f;
    /** Strength of the soft shadow by day, 0 to 2. */
    public float shadow = 1f;
    /** Lyrics, titles and artists in capital letters. */
    public boolean uppercase = false;
    /** Speed of fading, gliding and building up, 0.5 (slow) to 2 (fast); the timing of the words stays exact. */
    public float animationSpeed = 1f;
    /** How strongly the lyrics kick on the beat, 0.25 to 2. */
    public float beatStrength = 1f;
    /** One of {@link #PAUSE_MODES}. */
    public String pauseMode = "dim";
    /** The lyrics in the sky further left (negative) or right (positive) of where the player looks, -60 to 60 degrees. */
    public float sideOffsetDeg = 0f;
    /** The bar's words being sung in an own color instead of the palette's. */
    public boolean hudOwnAccent = false;
    public int hudAccentColor = 0xA855F7;
    /** A thin line in the bar showing how far the song has played. */
    public boolean hudProgress = false;
    /** The artist next to the song's title in the bar. */
    public boolean hudArtist = true;
    /** The little equalizer at the right end of the pill. */
    public boolean hudEqualizer = true;
    /** One of {@link #HUD_HIGHLIGHTS}. */
    public String hudHighlight = "box";
    /** One of {@link #HUD_FONTS}. */
    public String hudFont = "auto";
    /** Accent of the glass menu: switches, sliders, the selected tab. */
    public int menuAccent = 0xA855F7;
    /** The world behind the glass menu softly blurred. */
    public boolean menuBlur = true;
    /** The glass menu's movements: opening, gliding switches, hovering cards. */
    public boolean menuAnimations = true;
    /** One of {@link #HOERGENAU_MODELS}. */
    public String hoergenauModel = "small";
    /** One of {@link #HOERGENAU_POWERS}. */
    public String hoergenauPower = "normal";
    /**
     * Hoergenau also listens briefly to songs that have word times and moves them to where they are really sung, when
     * the source was timed for another version of the song; the correction is kept per song and shared.
     */
    public boolean hoergenauSyncCheck = true;
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

    /** The Spotify app built into the mod; empty in a build without one. */
    public static String builtInSpotifyClientId() {
        return defaultSpotifyClientId();
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
        if (configVersion < 3 && ("fancy".equals(menuStyle) || "glass".equals(menuStyle))) {
            // The lively menu was the default up to 1.3.0-dev.10, the glass one in dev.11; players who kept either get
            // the default of now, the menu in its old look with every option.
            menuStyle = "minimal";
        }
        configVersion = CONFIG_VERSION;
        if (menuStyle == null || !MENU_STYLES.contains(menuStyle)) {
            menuStyle = "minimal";
        }
        if (hudHighlight == null || !HUD_HIGHLIGHTS.contains(hudHighlight)) {
            hudHighlight = "box";
        }
        if (hudFont == null || !HUD_FONTS.contains(hudFont)) {
            hudFont = "auto";
        }
        if (screenStyle == null || !SCREEN_STYLES.contains(screenStyle)) {
            screenStyle = "subtitle";
        }
        if (screenPosition == null || !SCREEN_POSITIONS.contains(screenPosition)) {
            screenPosition = "bottom";
        }
        screenScale = within(screenScale, 0.6f, 2f, 1f);
        if (pauseMode == null || !PAUSE_MODES.contains(pauseMode)) {
            pauseMode = "dim";
        }
        if (hoergenauModel == null || !HOERGENAU_MODELS.contains(hoergenauModel)) {
            hoergenauModel = "small";
        }
        if (hoergenauPower == null || !HOERGENAU_POWERS.contains(hoergenauPower)) {
            hoergenauPower = "normal";
        }
        customTop &= 0xFFFFFF;
        customBottom &= 0xFFFFFF;
        customAccent &= 0xFFFFFF;
        customGlow &= 0xFFFFFF;
        outlineColor &= 0xFFFFFF;
        hudAccentColor &= 0xFFFFFF;
        menuAccent &= 0xFFFFFF;
        outlineWidth = within(outlineWidth, 0.5f, 2f, 1f);
        shadow = within(shadow, 0f, 2f, 1f);
        animationSpeed = within(animationSpeed, 0.5f, 2f, 1f);
        beatStrength = within(beatStrength, 0.25f, 2f, 1f);
        sideOffsetDeg = within(sideOffsetDeg, -60f, 60f, 0f);
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

    private static float within(float value, float min, float max, float fallback) {
        return Float.isFinite(value) ? Math.max(min, Math.min(max, value)) : fallback;
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
        shadow = defaults.shadow;
        outline = defaults.outline;
        outlineColor = defaults.outlineColor;
        outlineWidth = defaults.outlineWidth;
        uppercase = defaults.uppercase;
        animationSpeed = defaults.animationSpeed;
        beatStrength = defaults.beatStrength;
        screenStyle = defaults.screenStyle;
        screenPosition = defaults.screenPosition;
        screenScale = defaults.screenScale;
        screenBackground = defaults.screenBackground;
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
            case "subtitle" -> {
                mode = "screen";
                screenStyle = "subtitle";
                palette = "white";
                outline = true;
                showHud = false;
            }
            case "karaoke" -> {
                mode = "screen";
                screenStyle = "karaoke";
                palette = "sunset";
                outline = true;
                outlineColor = 0x1A0B2E;
                showHud = false;
            }
            case "stage" -> {
                mode = "screen";
                screenStyle = "stage";
                screenPosition = "center";
                palette = "white";
                screenScale = 1.2f;
                showHud = false;
            }
            case "neon" -> {
                palette = "neon";
                glow = 1.8f;
                beatBounce = true;
                scale = 1.1f;
            }
            case "rainbow" -> {
                mode = "around";
                style = "classic";
                palette = "rainbow";
                beatBounce = true;
                beatStrength = 1.5f;
            }
            case "cinema" -> {
                style = "flow";
                palette = "white";
                uppercase = true;
                outline = true;
                scale = 1.2f;
                animationSpeed = 0.7f;
            }
            case "retro" -> {
                style = "classic";
                font = "minecraft";
                palette = "gold";
                outline = true;
                outlineColor = 0x2A1A00;
            }
            case "clean" -> {
                style = "flow";
                palette = "white";
                glow = 0.4f;
                shadow = 1.2f;
                showTitleCard = false;
                showHud = false;
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
                followAlong, followView, showTitleCard, showHud, glow, shadow, outline, outlineColor, outlineWidth, uppercase,
                animationSpeed, beatStrength, screenStyle, screenPosition, screenScale, screenBackground};
    }
}
