package de.henner.lyricsinthesky.gui;

import de.henner.lyricsinthesky.LyricsController;
import de.henner.lyricsinthesky.LyricsInTheSkyClient;
import de.henner.lyricsinthesky.SkyLyricsConfig;
import de.henner.lyricsinthesky.WordSourceInfo;
import de.henner.lyricsinthesky.hoergenau.Hoergenau;
import de.henner.lyricsinthesky.hoergenau.WhisperEngine;
import de.henner.lyricsinthesky.render.Glass;
import de.henner.lyricsinthesky.render.LookPreview;
import de.henner.lyricsinthesky.render.SkyLyricsRenderer;
import de.henner.lyricsinthesky.spotify.SpotifyAccount;
import de.henner.lyricsinthesky.util.Browser;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleFunction;
import java.util.function.Function;
import java.util.function.ObjDoubleConsumer;
import java.util.function.ObjIntConsumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.function.ToDoubleFunction;
import java.util.function.ToIntFunction;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;

/**
 * The glass menu: a black glass window over the softly blurred world. It opens on cards, one for each part of the mod,
 * with switches right on the cards for what is switched most; a card opens its page, with a live preview of the look,
 * sections and rows of a title, a grey line on what it does and a switch, slider, choice or color on the right. Every
 * change applies at once, in the sky behind and in the preview; "Auf Standard zurücksetzen" resets the page, Esc goes
 * back and then closes. The settings file is written when the menu closes.
 */
public final class GlassMenuScreen extends Screen {
    private static final int MAX_WIDTH = 452;
    private static final int MAX_HEIGHT = 380;
    private static final int RADIUS = 14;
    private static final int PAD = 16;
    private static final int HEADER = 52;
    private static final int FOOTER = 26;
    private static final int ROW = 31;
    private static final int SECTION = 22;
    private static final int PREVIEW = 104;
    private static final int LIVE_CARD = 42;
    private static final int CARD = 42;
    private static final int CARD_GAP = 8;
    private static final int NOW_CARD = 40;
    private static final int CONTROL = 104;

    private static final int WINDOW_TOP = 0x15141C;
    private static final int WINDOW_BOTTOM = 0x07070A;
    private static final int TEXT = 0xFFFFFF;
    private static final int DESC = 0x8E8C9C;
    private static final int SECTION_TEXT = 0x9C9AAB;
    /** Hairlines between rows and around the header: white at this opacity. */
    private static final float LINE = 0.08f;
    private static final FontDescription UI = new FontDescription.Resource(
            Identifier.fromNamespaceAndPath(LyricsInTheSkyClient.MOD_ID, "ui"));
    private static final FontDescription UI_MEDIUM = new FontDescription.Resource(
            Identifier.fromNamespaceAndPath(LyricsInTheSkyClient.MOD_ID, "ui_medium"));
    private static final int[] SWATCHES = {0xFFFFFF, 0x0B0B10, 0xA855F7, 0x7C3AED, 0xEC4899, 0xF43F5E, 0xF97316,
            0xFACC15, 0x22C55E, 0x14B8A6, 0x38BDF8, 0x6366F1};

    private static final Map<String, String> PALETTE_NAMES = Map.ofEntries(
            Map.entry("standard", "Standard"), Map.entry("rose", "Altrosa"), Map.entry("lavender", "Lavendel"),
            Map.entry("auto", "Je Song anders"), Map.entry("aurora", "Polarlicht"), Map.entry("sunset", "Sonnenuntergang"),
            Map.entry("gold", "Gold"), Map.entry("sakura", "Kirschblüte"), Map.entry("ocean", "Ozean"),
            Map.entry("white", "Weiß"), Map.entry("glass", "Glas"), Map.entry("neon", "Neon"),
            Map.entry("rainbow", "Regenbogen"), Map.entry("custom", "Eigene Farben"));

    /** The page left open, so the menu opens where it was left while the game runs. */
    private static String remembered;

    private final Screen parent;
    private final LyricsController controller;
    private final SkyLyricsConfig config;
    private final SpotifyAccount spotify;
    private final Style title = SkyLyricsRenderer.regularStyle("sans");
    private final Style label = Style.EMPTY.withFont(UI_MEDIUM);
    private final Style small = Style.EMPTY.withFont(UI);
    private final List<Category> categories = new ArrayList<>();
    private final List<Row> rows = new ArrayList<>();

    private Category category;
    private int x0;
    private int y0;
    private int x1;
    private int y1;
    private int contentTop;
    private int contentBottom;
    private float scroll;
    private float scrollTarget;
    private int contentHeight;
    private long openedNanos;
    private long viewNanos;
    private long lastNanos;
    private Row dragging;
    private Row hovered;
    private long hoverNanos;
    private ColorPicker picker;
    private boolean peeking;
    private float hoverBack;
    private float hoverClose;
    private float hoverReset;

    public GlassMenuScreen(Screen parent, LyricsController controller, SpotifyAccount spotify) {
        super(Component.literal("Lyrics in the Sky"));
        this.parent = parent;
        this.controller = controller;
        this.config = controller.config();
        this.spotify = spotify;
        buildCategories();
        openedNanos = System.nanoTime();
        category = categories.stream().filter(c -> c.id().equals(remembered)).findFirst().orElse(null);
    }

    // ---------------------------------------------------------------------------------------------------------- pages

    private record Category(String id, String icon, String name, String subtitle, int hueShift, boolean preview,
            Supplier<List<Row>> rows, Predicate<SkyLyricsConfig> master, BiConsumer<SkyLyricsConfig, Boolean> setMaster) {}

    private void buildCategories() {
        categories.add(new Category("display", "☁", "Anzeige", "Wo und wie die Songtexte erscheinen", 0, false,
                this::displayRows, c -> c.enabled, (c, on) -> c.enabled = on));
        categories.add(new Category("look", "✎", "Schrift & Farben", "Schrift, Farben, Leuchten und Kontur", 1, true,
                this::lookRows, null, null));
        categories.add(new Category("motion", "✦", "Animation", "Tempo, Takt und Verhalten bei Pause", 2, true,
                this::motionRows, null, null));
        categories.add(new Category("bar", "▬", "Leiste", "Die Now-Playing-Leiste am Bildschirmrand", 3, false,
                this::barRows, c -> c.showHud, (c, on) -> c.showHud = on));
        categories.add(new Category("timing", "⏱", "Timing", "Früher oder später, für alle oder einen Song", 4, false,
                this::timingRows, null, null));
        categories.add(new Category("sources", "⇄", "Quellen", "Woher Songtexte und Wortzeiten kommen", 5, false,
                this::sourceRows, null, null));
        categories.add(new Category("hoergenau", "◉", "Hörgenau (KI)", "Die KI hört mit und timt jedes Wort", 6, false,
                this::hoergenauRows, c -> c.hoergenau, (c, on) -> LyricsInTheSkyClient.setHoergenau(c, on)));
        categories.add(new Category("spotify", "♫", "Spotify", "Musik vom Handy, Tablet oder Lautsprecher", 7, false,
                this::spotifyRows, null, null));
        categories.add(new Category("streamer", "✪", "Streamer", "Schimpfwörter als „F***“ zeigen", 8, false,
                this::streamerRows, c -> c.streamerMode, (c, on) -> LyricsInTheSkyClient.setStreamerMode(c, on)));
        categories.add(new Category("menu", "⚙", "Menü", "Aussehen dieses Menüs", 9, false, this::menuRows, null, null));
    }

    private List<Row> displayRows() {
        return List.of(
                new Section("Songtexte"),
                new Toggle("Songtexte zeigen", "Alle Songtexte ein- oder ausblenden", c -> c.enabled, (c, on) -> c.enabled = on),
                new Choice("Vorlage", "Ein ganzer Look mit einem Klick", SkyLyricsConfig.PRESETS, Map.of("standard", "Standard",
                        "sky", "Himmel 1:1", "around", "Um dich 1:1", "party", "Party", "calm", "Ruhig", "glass", "Glas",
                        "", "Eigener Look"),
                        c -> Objects.requireNonNullElse(c.currentPreset(), ""), SkyLyricsConfig::applyPreset),
                new Choice("Anzeige", "Am Himmel oder um dich herum", SkyLyricsConfig.MODES,
                        Map.of("sky", "Am Himmel", "around", "Um dich herum"), c -> c.mode, (c, v) -> c.mode = v),
                new Choice("Stil", "Wie die Zeilen erscheinen und gehen", SkyLyricsConfig.STYLES, Map.of("elegant", "Elegant",
                        "natural", "Natürlich 1:1", "flow", "Stapel", "classic", "Verspielt"), c -> c.style, (c, v) -> c.style = v),
                new Section("Position"),
                new Slider("Höhe", "Wie hoch die Zeilen am Himmel stehen", 10, 70, 1, c -> c.elevationDeg,
                        (c, v) -> c.elevationDeg = (float) v, v -> Math.round(v) + "°"),
                new Slider("Seitlich", "Links oder rechts von deinem Blick", -60, 60, 1, c -> c.sideOffsetDeg,
                        (c, v) -> c.sideOffsetDeg = (float) v, v -> (v > 0 ? "+" : "") + Math.round(v) + "°"),
                new Toggle("Hinter Bergen", "Berge, Bäume und Wolken stehen davor", c -> c.skyBehindTerrain,
                        (c, on) -> c.skyBehindTerrain = on),
                new Toggle("Vor dem Blick", "Neue Zeilen erscheinen, wo du hinschaust", c -> c.followView,
                        (c, on) -> c.followView = on),
                new Toggle("Mitdrehen", "Die Texte drehen sich mit deinem Blick", c -> c.followAlong, (c, on) -> c.followAlong = on),
                new Section("Song"),
                new Toggle("Songtitel", "Titel und Künstler, wenn ein Song beginnt", c -> c.showTitleCard,
                        (c, on) -> c.showTitleCard = on));
    }

    private List<Row> lookRows() {
        return List.of(
                new Preview(),
                new LiveCard(),
                new Section("Schrift"),
                new Choice("Schrift", "Schriftart der Songtexte und der Leiste", SkyLyricsConfig.FONTS,
                        Map.of("sans", "Modern", "script", "Handschrift", "minecraft", "Minecraft"), c -> c.font, (c, v) -> c.font = v),
                new Slider("Größe", "Wie groß die Schrift ist", 0.3, 3.0, 0.05, c -> c.scale, (c, v) -> c.scale = (float) v,
                        GlassMenuScreen::percent),
                new Slider("Deckkraft", "Wie durchsichtig die Schrift ist", 0.1, 1.0, 0.05, c -> c.opacity,
                        (c, v) -> c.opacity = (float) v, GlassMenuScreen::percent),
                new Toggle("Großbuchstaben", "Songtexte, Titel und Künstler in GROSS", c -> c.uppercase, (c, on) -> c.uppercase = on),
                new Section("Farben"),
                new Choice("Farben", "Farbverlauf der Schrift", SkyLyricsConfig.PALETTES, PALETTE_NAMES, c -> c.palette,
                        (c, v) -> c.palette = v),
                new ColorRow("Eigene Farbe oben", "Oberer Teil der Buchstaben", c -> c.customTop, (c, v) -> {
                    c.customTop = v;
                    c.palette = "custom";
                }),
                new ColorRow("Eigene Farbe unten", "Unterer Teil der Buchstaben", c -> c.customBottom, (c, v) -> {
                    c.customBottom = v;
                    c.palette = "custom";
                }),
                new ColorRow("Gesungenes Wort", "Farbe des Wortes, das gerade läuft", c -> c.customAccent, (c, v) -> {
                    c.customAccent = v;
                    c.palette = "custom";
                }),
                new ColorRow("Leuchtfarbe", "Farbe des Leuchtens bei Nacht", c -> c.customGlow, (c, v) -> {
                    c.customGlow = v;
                    c.palette = "custom";
                }),
                new Section("Effekte"),
                new Slider("Leuchten", "Leuchten um die Schrift bei Nacht", 0, 2, 0.05, c -> c.glow, (c, v) -> c.glow = (float) v,
                        GlassMenuScreen::percent),
                new Slider("Schatten", "Weicher Schatten bei Tag", 0, 2, 0.05, c -> c.shadow, (c, v) -> c.shadow = (float) v,
                        GlassMenuScreen::percent),
                new Toggle("Kontur", "Ein Rand um jeden Buchstaben", c -> c.outline, (c, on) -> c.outline = on),
                new ColorRow("Konturfarbe", "Farbe des Randes", c -> c.outlineColor, (c, v) -> {
                    c.outlineColor = v;
                    c.outline = true;
                }),
                new Slider("Konturstärke", "Wie breit der Rand ist", 0.5, 2, 0.05, c -> c.outlineWidth,
                        (c, v) -> c.outlineWidth = (float) v, GlassMenuScreen::percent));
    }

    private List<Row> motionRows() {
        return List.of(
                new Preview(),
                new LiveCard(),
                new Section("Bewegung"),
                new Slider("Animationstempo", "Einblenden, Gleiten und Aufbauen", 0.5, 2, 0.05, c -> c.animationSpeed,
                        (c, v) -> c.animationSpeed = (float) v, v -> String.format(Locale.ROOT, "%.2f×", v)),
                new Toggle("Im Takt", "Bei jedem Wort wackelt der Text mit", c -> c.beatBounce, (c, on) -> c.beatBounce = on),
                new Slider("Takt-Stärke", "Wie stark der Text im Takt wackelt", 0.25, 2, 0.05, c -> c.beatStrength,
                        (c, v) -> {
                            c.beatStrength = (float) v;
                            c.beatBounce = true;
                        }, GlassMenuScreen::percent),
                new Section("Pause"),
                new Choice("Bei Pause", "Was die Texte tun, wenn die Musik pausiert", SkyLyricsConfig.PAUSE_MODES,
                        Map.of("dim", "Abdunkeln", "hide", "Ausblenden", "keep", "Weiter zeigen"), c -> c.pauseMode,
                        (c, v) -> c.pauseMode = v));
    }

    private List<Row> barRows() {
        return List.of(
                new Section("Leiste"),
                new Choice("Leiste", "Wie die Leiste aussieht", List.of("off", "pill", "karaoke", "plain", "title"),
                        Map.of("off", "Aus", "pill", "Pille", "karaoke", "Karaoke", "plain", "Schlicht", "title", "Titel"),
                        c -> c.showHud ? c.hudStyle : "off", (c, v) -> {
                            c.showHud = !"off".equals(v);
                            if (c.showHud) {
                                c.hudStyle = v;
                            }
                        }),
                new Choice("Position", "Oben oder unten über der Hotbar", SkyLyricsConfig.HUD_ANCHORS,
                        Map.of("top", "Oben", "bottom", "Unten"), c -> c.hudAnchor, (c, v) -> {
                            c.hudAnchor = v;
                            c.hudOffsetY = 0;
                        }),
                new Action("Verschieben", "Leiste mit der Maus an ihren Platz ziehen", () -> "Verschieben", () -> {
                    config.save();
                    minecraft.gui.setScreen(new HudEditScreen(this, config));
                }, () -> true, c -> {
                    c.hudOffsetX = 0;
                    c.hudOffsetY = 0;
                }),
                new Slider("Größe", "Wie groß die Leiste ist", 0.75, 1.5, 0.05, c -> c.hudScale, (c, v) -> c.hudScale = (float) v,
                        GlassMenuScreen::percent),
                new Slider("Hintergrund", "Wie dicht der Hintergrund ist", 0, 1, 0.05, c -> c.hudOpacity,
                        (c, v) -> c.hudOpacity = (float) v, GlassMenuScreen::percent),
                new Slider("Rundung", "Von eckig bis zur Kapsel", 0, 1, 0.05, c -> c.hudRoundness,
                        (c, v) -> c.hudRoundness = (float) v, GlassMenuScreen::percent),
                new Section("Inhalt"),
                new Toggle("Künstler", "Der Künstler neben dem Songtitel", c -> c.hudArtist, (c, on) -> c.hudArtist = on),
                new Toggle("Fortschritt", "Eine feine Linie, wie weit der Song ist", c -> c.hudProgress,
                        (c, on) -> c.hudProgress = on),
                new Toggle("Equalizer", "Die tanzenden Balken am Ende", c -> c.hudEqualizer, (c, on) -> c.hudEqualizer = on),
                new Choice("Hervorhebung", "Wie das gesungene Wort markiert ist", SkyLyricsConfig.HUD_HIGHLIGHTS,
                        Map.of("box", "Hinterlegt", "color", "Farbe", "underline", "Linie"), c -> c.hudHighlight,
                        (c, v) -> c.hudHighlight = v),
                new Choice("Schrift", "Schrift der Leiste", SkyLyricsConfig.HUD_FONTS,
                        Map.of("auto", "Wie Text", "sans", "Modern", "minecraft", "Minecraft"), c -> c.hudFont,
                        (c, v) -> c.hudFont = v),
                new Section("Farbe"),
                new Toggle("Eigene Akzentfarbe", "Das gesungene Wort in deiner Farbe", c -> c.hudOwnAccent,
                        (c, on) -> c.hudOwnAccent = on),
                new ColorRow("Akzentfarbe", "Farbe des gesungenen Wortes in der Leiste", c -> c.hudAccentColor, (c, v) -> {
                    c.hudAccentColor = v;
                    c.hudOwnAccent = true;
                }),
                new Choice("Theme", "Leiste aus Glas oder schlicht dunkel", SkyLyricsConfig.THEMES,
                        Map.of("classic", "Klassisch", "glass", "Liquid Glass"), c -> c.theme, (c, v) -> c.theme = v));
    }

    private List<Row> timingRows() {
        return List.of(
                new Section("Timing"),
                new Slider("Alle Songs", "Positiv zeigt die Texte früher", -2000, 2000, 50, c -> c.offsetMs,
                        (c, v) -> c.offsetMs = Math.round(v), v -> (v > 0 ? "+" : "") + Math.round(v) + " ms"),
                songTiming(),
                new Toggle("Korrekturen teilen", "Anonym, gelten ab 10 gleichen für alle", c -> c.shareTiming,
                        (c, on) -> c.shareTiming = on),
                new Info("So geht's", () -> "Erscheint ein Text zu spät, schieb „Dieser Song“ nach rechts."));
    }

    /** Timing of the song playing now only, on top of the timing for all songs. */
    private Row songTiming() {
        LyricsController.Song song = controller.currentSong();
        String desc = song == null ? "Gerade läuft kein Song mit Text" : "Nur für „" + song.title() + "“";
        Slider slider = new Slider("Dieser Song", desc, -3000, 3000, 50,
                c -> song == null ? 0 : controller.timings().offsetFor(song.id()),
                (c, v) -> {
                    if (song != null) {
                        controller.setSongOffset(song, (int) Math.round(v));
                    }
                }, v -> song == null ? "–" : (v > 0 ? "+" : "") + Math.round(v) + " ms");
        slider.enabled = song != null;
        slider.reset = c -> {
            if (song != null) {
                controller.setSongOffset(song, null);
            }
        };
        return slider;
    }

    private List<Row> sourceRows() {
        List<Row> list = new ArrayList<>();
        list.add(new Section("Songtexte"));
        list.add(new Toggle("lrclib.net", "Offen und frei; bekommt nur Titel und Künstler",
                c -> Boolean.TRUE.equals(c.lrclibAllowed), (c, on) -> {
                    c.lrclibAllowed = on;
                    controller.retryLookups();
                }));
        list.add(new Section("Wortzeiten (Grauzone)"));
        list.add(new Toggle("Wortgenau", "Alle Dienste unten an oder aus", SkyLyricsConfig::wordTimes, (c, on) -> {
            c.setWordTimes(on);
            controller.refreshWordTimes();
        }));
        for (String name : SkyLyricsConfig.WORD_SOURCES) {
            Toggle toggle = new Toggle(WordSourceInfo.label(name), WordSourceInfo.hint(name), c -> c.wordSource(name),
                    (c, on) -> {
                        c.setWordSource(name, on);
                        controller.refreshWordTimes();
                    });
            toggle.indent = true;
            list.add(toggle);
        }
        list.add(new Info("Gerade", controller::status));
        return list;
    }

    private List<Row> hoergenauRows() {
        boolean supported = WhisperEngine.supported();
        return List.of(
                new Info("Status", () -> !supported ? "Hörgenau gibt es nur unter Windows."
                        : !config.hoergenau ? "Aus. Schalte es ein, die KI lädt sich einmalig." : hoergenauStatus()),
                new Section("KI"),
                new Toggle("Hörgenau", "Hört mit und timt Songs ohne Wortzeiten", c -> c.hoergenau,
                        (c, on) -> LyricsInTheSkyClient.setHoergenau(c, on)),
                new Toggle("Sync prüfen", "Hört kurz mit und rückt Texte genau auf den Gesang", c -> c.hoergenauSyncCheck,
                        (c, on) -> c.hoergenauSyncCheck = on),
                new Choice("Modell", "Schnell: 190 MB · Genau: hört besser, 574 MB", SkyLyricsConfig.HOERGENAU_MODELS,
                        Map.of("small", "Schnell", "turbo", "Genau"), c -> c.hoergenauModel, (c, v) -> c.hoergenauModel = v),
                new Choice("Leistung", "Wie viel vom PC die KI nutzen darf", SkyLyricsConfig.HOERGENAU_POWERS,
                        Map.of("low", "Sparsam", "normal", "Normal", "high", "Schnell"), c -> c.hoergenauPower,
                        (c, v) -> c.hoergenauPower = v),
                new Section("Teilen"),
                new Toggle("Gelerntes teilen", "Gelernte Songs gelten für alle Spieler", c -> c.shareLearned,
                        (c, on) -> c.shareLearned = on));
    }

    private String hoergenauStatus() {
        Hoergenau listener = controller.hoergenau();
        return listener == null ? "An" : listener.statusText();
    }

    private List<Row> spotifyRows() {
        return List.of(
                new Info("Status", () -> spotify.isConnected() ? spotify.statusText()
                        : spotify.statusText() + " · Ohne Konto läuft Musik auf diesem PC von selbst."),
                new Action("Konto", "Für Musik, die nicht auf diesem PC läuft", () -> spotify.isConnected() ? "Trennen"
                        : spotify.state() == SpotifyAccount.State.WAITING_FOR_BROWSER ? "Warte …" : "Verbinden", () -> {
                            if (spotify.isConnected()) {
                                spotify.disconnect();
                            } else {
                                config.save();
                                spotify.connect();
                            }
                        }, () -> spotify.isConnected() || !config.spotifyClientId.isBlank()
                        && spotify.state() != SpotifyAccount.State.WAITING_FOR_BROWSER, null),
                new Section("App"),
                new Info("Spotify-App", () -> config.spotifyClientId.isBlank() ? "Keine – lege eine eigene an"
                        : config.spotifyClientId.equals(SkyLyricsConfig.builtInSpotifyClientId()) ? "Eingebaute App"
                        : "Eigene App (…" + config.spotifyClientId.substring(Math.max(0, config.spotifyClientId.length() - 4)) + ")"),
                new Action("Eigene App", "Client-ID aus der Zwischenablage einsetzen", () -> "Einfügen", () -> {
                    String id = minecraft.keyboardHandler.getClipboard().trim();
                    if (id.matches("[0-9a-fA-F]{32}") && !spotify.isConnected()) {
                        config.spotifyClientId = id.toLowerCase(Locale.ROOT);
                    }
                }, () -> !spotify.isConnected(), c -> c.spotifyClientId = SkyLyricsConfig.builtInSpotifyClientId()),
                new Action("App anlegen", "Öffnet Spotifys Seite für Entwickler", () -> "Öffnen",
                        () -> Browser.open(SpotifyAccount.DASHBOARD_URL), () -> true, null));
    }

    private List<Row> streamerRows() {
        return List.of(
                new Toggle("Streamer-Modus", "Schimpfwörter als „F***“, auch in Titeln", c -> c.streamerMode,
                        (c, on) -> LyricsInTheSkyClient.setStreamerMode(c, on)),
                new Info("Gilt für", () -> "Himmel, Leiste, Songtitel und Chat · Deutsch und Englisch"));
    }

    private List<Row> menuRows() {
        return List.of(
                new Section("Menü"),
                new Choice("Menüstil", "Glas, prachtvoll oder minimal", SkyLyricsConfig.MENU_STYLES,
                        Map.of("glass", "Glas", "fancy", "Prachtvoll", "minimal", "Minimal"), c -> c.menuStyle, (c, v) -> {
                            c.menuStyle = v;
                            if (!"glass".equals(v)) {
                                c.save();
                                minecraft.gui.setScreen(new SettingsScreen(parent, controller, spotify));
                            }
                        }),
                new ColorRow("Akzentfarbe", "Schalter, Regler und Hervorhebungen", c -> c.menuAccent, (c, v) -> c.menuAccent = v),
                new Toggle("Unschärfe", "Die Welt hinter dem Menü weichzeichnen", c -> c.menuBlur, (c, on) -> c.menuBlur = on),
                new Toggle("Animationen", "Öffnen, Gleiten und Leuchten", c -> c.menuAnimations, (c, on) -> c.menuAnimations = on));
    }

    private static String percent(double value) {
        return Math.round(value * 100) + "%";
    }

    // ------------------------------------------------------------------------------------------------------- layout

    @Override
    protected void init() {
        int w = Math.min(MAX_WIDTH, width - 20);
        int h = Math.min(MAX_HEIGHT, height - 16);
        x0 = (width - w) / 2;
        y0 = (height - h) / 2;
        x1 = x0 + w;
        y1 = y0 + h;
        contentTop = y0 + HEADER;
        contentBottom = y1 - FOOTER;
        layout();
    }

    /** Makes the rows of the page shown, or the cards of the start, and lays them out. */
    private void layout() {
        rows.clear();
        if (category == null) {
            rows.add(new NowPlaying());
            rows.add(new Section("Einstellungen"));
            rows.add(new Cards());
        } else {
            rows.addAll(category.rows().get());
        }
        positions();
    }

    /** Places the rows one below the other; again whenever a row's height may have changed. */
    private void positions() {
        int y = 4;
        for (Row row : rows) {
            row.top = y;
            y += row.height();
        }
        contentHeight = y + 6;
        scrollTarget = Mth.clamp(scrollTarget, 0, maxScroll());
        scroll = Mth.clamp(scroll, 0, maxScroll());
    }

    private int maxScroll() {
        return Math.max(0, contentHeight - (contentBottom - contentTop));
    }

    private void open(Category next) {
        category = next;
        remembered = next == null ? null : next.id();
        scroll = 0;
        scrollTarget = 0;
        viewNanos = System.nanoTime();
        picker = null;
        layout();
    }

    /** Switches to a page by its id ("display", "look", ...), or the start for {@code null}; for the self test. */
    public void show(String id) {
        open(categories.stream().filter(c -> c.id().equals(id)).findFirst().orElse(null));
    }

    /** Scrolls to the end of the page; for the self test. */
    public void scrollToEnd() {
        scrollTarget = maxScroll();
        scroll = scrollTarget;
    }

    /** Opens the color picker of the page's first color; for the self test. */
    public void openFirstColor() {
        for (Row row : rows) {
            if (row instanceof ColorRow color) {
                color.click(0, 0, 0, 0, 0);
                return;
            }
        }
    }

    /** Whether the whole window fits the screen; for the self test. */
    public boolean fits() {
        return y0 >= 0 && y1 <= height && x0 >= 0 && x1 <= width;
    }

    /** While the live preview runs the menu steps aside and the bar shows. */
    public boolean peeking() {
        return peeking;
    }

    private int accent() {
        return config.menuAccent;
    }

    private int accentLight() {
        return ARGB.srgbLerp(0.45f, config.menuAccent, 0xFFFFFF);
    }

    private float animated(long sinceNanos, float seconds) {
        if (!config.menuAnimations) {
            return 1f;
        }
        return easeOut((System.nanoTime() - sinceNanos) / 1e9f / seconds);
    }

    private static float easeOut(float t) {
        float clamped = Mth.clamp(t, 0f, 1f);
        return 1f - (1f - clamped) * (1f - clamped) * (1f - clamped);
    }

    private static float approach(float value, float target, float dt, float seconds) {
        return value + (target - value) * (1f - (float) Math.exp(-dt / seconds));
    }

    // ------------------------------------------------------------------------------------------------------ drawing

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        if (peeking) {
            return;
        }
        float open = animated(openedNanos, 0.28f);
        if (config.menuBlur) {
            graphics.blurBeforeThisStratum();
        }
        graphics.fill(0, 0, width, height, ARGB.color(0.55f * open, 0x000000));
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        long now = System.nanoTime();
        float dt = lastNanos == 0 ? 0f : Math.min(0.1f, (now - lastNanos) / 1e9f);
        lastNanos = now;
        if (peeking) {
            drawPeekHint(graphics, mouseX, mouseY, now);
            return;
        }
        scroll = config.menuAnimations ? approach(scroll, scrollTarget, dt, 0.07f) : scrollTarget;
        float open = animated(openedNanos, 0.28f);
        graphics.pose().pushMatrix();
        float cx = (x0 + x1) / 2f;
        float cy = (y0 + y1) / 2f;
        float grow = 0.965f + 0.035f * open;
        graphics.pose().translate(cx, cy + (1f - open) * 10f);
        graphics.pose().scale(grow, grow);
        graphics.pose().translate(-cx, -cy);
        drawWindow(graphics, open);
        drawHeader(graphics, mouseX, mouseY, open, dt);
        drawContent(graphics, mouseX, mouseY, open, dt, now);
        drawFooter(graphics, mouseX, mouseY, open, dt);
        if (picker != null) {
            picker.draw(graphics, mouseX, mouseY, open);
        } else {
            drawBubble(graphics, mouseX, mouseY, now);
        }
        graphics.pose().popMatrix();
    }

    /** The black glass window: a soft shadow, a body darkening downwards, a hairline rim lit at the top. */
    private void drawWindow(GuiGraphicsExtractor graphics, float open) {
        for (int i = 1; i <= 6; i++) {
            Glass.fill(graphics, x0 - i, y0 - i + 4, x1 + i, y1 + i + 4, RADIUS + i, ARGB.color(open * 0.06f, 0x000000));
        }
        // The body: rows with rounded ends at the corners, one gradient in between.
        for (int row = 0; row < RADIUS; row++) {
            int inset = cornerInset(row, RADIUS);
            graphics.fill(x0 + inset, y0 + row, x1 - inset, y0 + row + 1, ARGB.color(open * 0.95f, windowColor(y0 + row)));
            graphics.fill(x0 + inset, y1 - row - 1, x1 - inset, y1 - row, ARGB.color(open * 0.95f, windowColor(y1 - row - 1)));
        }
        graphics.fillGradient(x0, y0 + RADIUS, x1, y1 - RADIUS, ARGB.color(open * 0.95f, windowColor(y0 + RADIUS)),
                ARGB.color(open * 0.95f, windowColor(y1 - RADIUS)));
        Glass.outline(graphics, x0, y0, x1, y1, RADIUS, ARGB.color(open * 0.12f, 0xFFFFFF));
        graphics.fill(x0 + RADIUS, y0, x1 - RADIUS, y0 + 1, ARGB.color(open * 0.22f, 0xFFFFFF));
        // A faint glow of the accent from the top left, as light through tinted glass.
        graphics.fillGradient(x0 + 1, y0 + 1, x1 - 1, y0 + HEADER, ARGB.color(open * 0.07f, accent()), 0);
    }

    private void drawHeader(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float open, float dt) {
        int x = x0 + PAD;
        if (category != null) {
            boolean over = inBack(mouseX, mouseY);
            hoverBack = approach(hoverBack, over ? 1f : 0f, dt, 0.08f);
            Glass.fill(graphics, x, y0 + 17, x + 18, y0 + 35, 9, ARGB.color(open * (0.06f + 0.1f * hoverBack), 0xFFFFFF));
            graphics.centeredText(font, Component.literal("‹").withStyle(label), x + 9, y0 + 21, ARGB.color(open, TEXT));
            x += 26;
        }
        Category shown = category;
        iconTile(graphics, x, y0 + 12, 28, shown == null ? "♫" : shown.icon(), shown == null ? 0 : shown.hueShift(), open, 1f);
        int textX = x + 38;
        String heading = shown == null ? "Lyrics in the Sky" : shown.name();
        graphics.pose().pushMatrix();
        graphics.pose().translate(textX, y0 + 13);
        graphics.pose().scale(1.32f, 1.32f);
        graphics.text(font, Component.literal(heading).withStyle(title), 0, 0, ARGB.color(open, TEXT), false);
        graphics.pose().popMatrix();
        int headingWidth = Math.round(font.width(Component.literal(heading).withStyle(title)) * 1.32f);
        if (live()) {
            livePill(graphics, textX + headingWidth + 8, y0 + 14, open);
        }
        String sub = shown == null ? "Songtexte am Himmel, Wort für Wort" : shown.subtitle();
        graphics.text(font, fit(sub, small, x1 - PAD - 30 - textX), textX, y0 + 31, ARGB.color(open, DESC), false);
        boolean over = inClose(mouseX, mouseY);
        hoverClose = approach(hoverClose, over ? 1f : 0f, dt, 0.08f);
        int cx = x1 - PAD - 18;
        Glass.fill(graphics, cx, y0 + 14, cx + 20, y0 + 34, 10, ARGB.color(open * (0.07f + 0.12f * hoverClose), 0xFFFFFF));
        graphics.centeredText(font, Component.literal("×").withStyle(label), cx + 10, y0 + 19,
                ARGB.color(open * (0.7f + 0.3f * hoverClose), TEXT));
        graphics.fill(x0 + PAD, y0 + HEADER - 1, x1 - PAD, y0 + HEADER, ARGB.color(open * LINE, 0xFFFFFF));
    }

    /** A rounded tile with a symbol, its color the accent turned a little round the color wheel per card. */
    private void iconTile(GuiGraphicsExtractor graphics, int x, int y, int size, String icon, int hueShift, float alpha,
            float lift) {
        int base = shifted(accent(), hueShift * 0.035f);
        int top = ARGB.srgbLerp(0.25f, base, 0xFFFFFF);
        int bottom = ARGB.srgbLerp(0.35f, base, 0x000000);
        int r = size / 3;
        Glass.fill(graphics, x, y, x + size, y + r, r, ARGB.color(alpha * 0.9f * lift, top));
        graphics.fillGradient(x, y + r / 2, x + size, y + size - r / 2, ARGB.color(alpha * 0.9f * lift, top),
                ARGB.color(alpha * 0.9f * lift, bottom));
        Glass.fill(graphics, x, y + size - r, x + size, y + size, r, ARGB.color(alpha * 0.9f * lift, bottom));
        Glass.outline(graphics, x, y, x + size, y + size, r, ARGB.color(alpha * 0.28f, 0xFFFFFF));
        graphics.pose().pushMatrix();
        float scale = size / 18f;
        graphics.pose().translate(x + size / 2f, y + size / 2f - 4.5f * scale);
        graphics.pose().scale(scale, scale);
        graphics.centeredText(font, Component.literal(icon), 0, 0, ARGB.color(alpha, 0xFFFFFF));
        graphics.pose().popMatrix();
    }

    private static int shifted(int rgb, float hueTurn) {
        float[] hsv = hsv(rgb);
        float hue = (hsv[0] + hueTurn) % 1f;
        return Mth.hsvToRgb(hue < 0 ? hue + 1 : hue, Math.max(0.35f, hsv[1]), Math.max(0.55f, hsv[2]));
    }

    /** Hue, saturation and brightness of an RGB color, each 0 to 1. */
    static float[] hsv(int rgb) {
        float r = ((rgb >> 16) & 0xFF) / 255f;
        float g = ((rgb >> 8) & 0xFF) / 255f;
        float b = (rgb & 0xFF) / 255f;
        float max = Math.max(r, Math.max(g, b));
        float min = Math.min(r, Math.min(g, b));
        float delta = max - min;
        float hue = 0f;
        if (delta > 0f) {
            if (max == r) {
                hue = ((g - b) / delta) / 6f;
            } else if (max == g) {
                hue = ((b - r) / delta + 2f) / 6f;
            } else {
                hue = ((r - g) / delta + 4f) / 6f;
            }
            hue = hue < 0f ? hue + 1f : hue;
        }
        return new float[] {hue, max == 0f ? 0f : delta / max, max};
    }

    private boolean live() {
        LyricsController.Timeline timeline = controller.timeline(System.currentTimeMillis());
        return timeline != null && timeline.playing() && timeline.lyrics() != null;
    }

    private void livePill(GuiGraphicsExtractor graphics, int x, int y, float alpha) {
        Component text = Component.literal("LIVE").withStyle(label);
        int w = 14 + font.width(text) + 8;
        Glass.fill(graphics, x, y, x + w, y + 13, 6, ARGB.color(alpha * 0.18f, accent()));
        Glass.outline(graphics, x, y, x + w, y + 13, 6, ARGB.color(alpha * 0.55f, accent()));
        float pulse = 0.55f + 0.45f * (float) Math.sin(System.nanoTime() / 1e9 * Math.PI * 1.6);
        Glass.fill(graphics, x + 6, y + 5, x + 10, y + 9, 2, ARGB.color(alpha * (config.menuAnimations ? pulse : 1f), 0xFF5FD2));
        graphics.text(font, text, x + 14, y + 3, ARGB.color(alpha, accentLight()), false);
    }

    private void drawContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float open, float dt, long now) {
        float view = animated(viewNanos, 0.22f);
        Row over = picker == null && mouseX >= x0 && mouseX < x1 && mouseY >= contentTop && mouseY < contentBottom
                ? rowAt(mouseY) : null;
        if (over != hovered) {
            hovered = over;
            hoverNanos = now;
        }
        graphics.enableScissor(x0 + 1, contentTop, x1 - 1, contentBottom);
        graphics.pose().pushMatrix();
        graphics.pose().translate(0f, (1f - view) * 10f);
        int left = x0 + PAD;
        int right = x1 - PAD;
        for (Row row : rows) {
            int y = contentTop + row.top - Math.round(scroll);
            if (y + row.height() < contentTop - 2 || y > contentBottom + 2) {
                continue;
            }
            row.hover = approach(row.hover, row == over ? 1f : 0f, dt, 0.08f);
            row.draw(graphics, left, y, right - left, mouseX, mouseY, open * view, dt);
        }
        graphics.pose().popMatrix();
        graphics.disableScissor();
        // Edges fading into the window where the list scrolls on.
        if (scroll > 1f) {
            graphics.fillGradient(x0 + 1, contentTop, x1 - 1, contentTop + 10, ARGB.color(open, 0x121118), 0);
        }
        if (scroll < maxScroll() - 1f) {
            graphics.fillGradient(x0 + 1, contentBottom - 10, x1 - 1, contentBottom, 0, ARGB.color(open, 0x09090C));
        }
        if (maxScroll() > 0) {
            int track = contentBottom - contentTop - 8;
            int thumb = Math.max(18, track * (contentBottom - contentTop) / contentHeight);
            int at = contentTop + 4 + Math.round((track - thumb) * (scroll / maxScroll()));
            Glass.fill(graphics, x1 - 6, at, x1 - 3, at + thumb, 1, ARGB.color(open * 0.25f, 0xFFFFFF));
        }
    }

    private Row rowAt(int mouseY) {
        int y = mouseY - contentTop + Math.round(scroll);
        for (Row row : rows) {
            if (y >= row.top && y < row.top + row.height()) {
                return row;
            }
        }
        return null;
    }

    private void drawFooter(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float open, float dt) {
        int y = y1 - FOOTER;
        graphics.fill(x0 + PAD, y, x1 - PAD, y + 1, ARGB.color(open * LINE, 0xFFFFFF));
        if (category != null) {
            boolean over = inReset(mouseX, mouseY);
            hoverReset = approach(hoverReset, over ? 1f : 0f, dt, 0.08f);
            int color = ARGB.srgbLerp(hoverReset, DESC, TEXT);
            graphics.text(font, Component.literal("Auf Standard zurücksetzen").withStyle(small), x0 + PAD, y + 9,
                    ARGB.color(open, color), false);
        } else {
            graphics.text(font, Component.literal("Änderungen siehst du sofort").withStyle(small), x0 + PAD, y + 9,
                    ARGB.color(open, DESC), false);
        }
        Component hint = Component.literal(category == null ? "zum Schließen" : "zurück").withStyle(small);
        int hintWidth = font.width(hint);
        Component key = Component.literal("Esc").withStyle(small);
        int keyWidth = font.width(key) + 10;
        int hx = x1 - PAD - hintWidth;
        int kx = hx - 6 - keyWidth;
        Glass.fill(graphics, kx, y + 6, kx + keyWidth, y + 19, 4, ARGB.color(open * 0.1f, 0xFFFFFF));
        Glass.outline(graphics, kx, y + 6, kx + keyWidth, y + 19, 4, ARGB.color(open * 0.14f, 0xFFFFFF));
        graphics.text(font, key, kx + 5, y + 9, ARGB.color(open, 0xD8D6E2), false);
        graphics.text(font, hint, hx, y + 9, ARGB.color(open, DESC), false);
    }

    /** The full description of the row under the mouse after a moment, in a small glass bubble. */
    private void drawBubble(GuiGraphicsExtractor graphics, int mouseX, int mouseY, long now) {
        if (hovered == null || hovered.more() == null || dragging != null || (now - hoverNanos) / 1e9f < 0.55f) {
            return;
        }
        float shown = config.menuAnimations ? easeOut(((now - hoverNanos) / 1e9f - 0.55f) / 0.18f) : 1f;
        int w = Math.min(220, x1 - x0 - 2 * PAD);
        List<FormattedCharSequence> lines = font.split(Component.literal(hovered.more()).withStyle(small), w - 16);
        int h = 10 + lines.size() * 10;
        int bx = Mth.clamp(mouseX + 10, x0 + 6, x1 - w - 6);
        int by = mouseY + 14 + h > y1 - 4 ? mouseY - h - 8 : mouseY + 14;
        Glass.fill(graphics, bx, by, bx + w, by + h, 8, ARGB.color(shown * 0.96f, 0x1B1A24));
        Glass.outline(graphics, bx, by, bx + w, by + h, 8, ARGB.color(shown * 0.18f, 0xFFFFFF));
        int ly = by + 6;
        for (FormattedCharSequence line : lines) {
            graphics.text(font, line, bx + 8, ly, ARGB.color(shown, 0xE6E4EE), false);
            ly += 10;
        }
    }

    private void drawPeekHint(GuiGraphicsExtractor graphics, int mouseX, int mouseY, long now) {
        Component text = Component.literal("Live-Vorschau  ·  Klick oder Esc zurück zum Menü").withStyle(label);
        int w = font.width(text) + 28;
        int x = (width - w) / 2;
        int y = height - 48;
        Glass.fill(graphics, x, y, x + w, y + 20, 10, ARGB.color(0.82f, 0x0B0B10));
        Glass.outline(graphics, x, y, x + w, y + 20, 10, ARGB.color(0.5f, accent()));
        float pulse = 0.55f + 0.45f * (float) Math.sin(now / 1e9 * Math.PI * 1.6);
        Glass.fill(graphics, x + 9, y + 8, x + 13, y + 12, 2, ARGB.color(pulse, 0xFF5FD2));
        graphics.text(font, text, x + 18, y + 6, 0xFFFFFFFF, false);
    }

    private FormattedCharSequence fit(String value, Style style, int maxWidth) {
        Component component = Component.literal(value).withStyle(style);
        if (font.width(component) <= maxWidth) {
            return component.getVisualOrderText();
        }
        Component dots = Component.literal("…").withStyle(style);
        List<FormattedCharSequence> lines = font.split(component, Math.max(10, maxWidth - font.width(dots)));
        return lines.isEmpty() ? component.getVisualOrderText()
                : FormattedCharSequence.composite(lines.get(0), dots.getVisualOrderText());
    }

    private boolean fitsIn(String value, Style style, int maxWidth) {
        return font.width(Component.literal(value).withStyle(style)) <= maxWidth;
    }

    // -------------------------------------------------------------------------------------------------------- input

    private boolean inBack(double mx, double my) {
        int x = x0 + PAD;
        return category != null && mx >= x && mx < x + 18 && my >= y0 + 17 && my < y0 + 35;
    }

    private boolean inClose(double mx, double my) {
        int cx = x1 - PAD - 18;
        return mx >= cx && mx < cx + 20 && my >= y0 + 14 && my < y0 + 34;
    }

    private boolean inReset(double mx, double my) {
        int y = y1 - FOOTER;
        return category != null && mx >= x0 + PAD && mx < x0 + PAD + 130 && my >= y + 4 && my < y + 22;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        double mx = event.x();
        double my = event.y();
        if (peeking) {
            peeking = false;
            openedNanos = System.nanoTime();
            return true;
        }
        if (picker != null) {
            if (!picker.click(mx, my)) {
                picker = null;
            }
            return true;
        }
        if (inClose(mx, my)) {
            onClose();
            return true;
        }
        if (inBack(mx, my)) {
            open(null);
            return true;
        }
        if (inReset(mx, my)) {
            DefaultsHolder.reset(rows, config);
            layout();
            return true;
        }
        if (my >= contentTop && my < contentBottom && mx >= x0 && mx < x1) {
            Row row = rowAt((int) my);
            if (row != null) {
                int y = contentTop + row.top - Math.round(scroll);
                if (row.click(mx, my, x0 + PAD, y, x1 - x0 - 2 * PAD)) {
                    if (row instanceof Slider) {
                        dragging = row;
                    }
                    return true;
                }
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (picker != null) {
            picker.drag(event.x(), event.y());
            return true;
        }
        if (dragging != null) {
            dragging.drag(event.x());
            return true;
        }
        return super.mouseDragged(event, dx, dy);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        dragging = null;
        if (picker != null) {
            picker.release();
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        if (picker != null || peeking) {
            return true;
        }
        scrollTarget = Mth.clamp(scrollTarget - (float) scrollY * 24f, 0, maxScroll());
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        int key = event.key();
        if (key == 256) {
            if (peeking) {
                peeking = false;
                openedNanos = System.nanoTime();
            } else if (picker != null) {
                picker = null;
            } else if (category != null) {
                open(null);
            } else {
                onClose();
            }
            return true;
        }
        if (picker != null && picker.key(key)) {
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        if (picker != null) {
            picker.typed(event.codepoint());
            return true;
        }
        return super.charTyped(event);
    }

    @Override
    public void tick() {
        // Rows that show live state (status lines, buttons) read it when drawn; the layout only changes with the page.
        if (category == null) {
            positions();
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        config.save();
        minecraft.gui.setScreen(parent);
    }

    /** Resets the rows of a page to the values of a fresh config. */
    private static final class DefaultsHolder {
        private static final SkyLyricsConfig DEFAULTS = new SkyLyricsConfig();

        static void reset(List<Row> rows, SkyLyricsConfig config) {
            for (Row row : rows) {
                row.resetTo(config, DEFAULTS);
            }
        }
    }

    // --------------------------------------------------------------------------------------------------------- rows

    /** One entry of a page; {@link #top} is its place in the scrolled list. */
    private abstract class Row {
        int top;
        float hover;

        abstract int height();

        abstract void draw(GuiGraphicsExtractor graphics, int x, int y, int w, int mouseX, int mouseY, float alpha, float dt);

        boolean click(double mx, double my, int x, int y, int w) {
            return false;
        }

        void drag(double mx) {
        }

        /** The longer text shown in a bubble when hovering, or {@code null}. */
        String more() {
            return null;
        }

        void resetTo(SkyLyricsConfig config, SkyLyricsConfig defaults) {
        }
    }

    /** A small heading: an accent dot and the name in spaced capitals. */
    private final class Section extends Row {
        private final String name;

        Section(String name) {
            this.name = name;
        }

        @Override
        int height() {
            return SECTION;
        }

        @Override
        void draw(GuiGraphicsExtractor graphics, int x, int y, int w, int mouseX, int mouseY, float alpha, float dt) {
            Glass.fill(graphics, x, y + 10, x + 4, y + 14, 2, ARGB.color(alpha, accent()));
            int cursor = x + 10;
            for (char letter : name.toUpperCase(Locale.ROOT).toCharArray()) {
                Component c = Component.literal(String.valueOf(letter)).withStyle(label);
                graphics.text(font, c, cursor, y + 8, ARGB.color(alpha, SECTION_TEXT), false);
                cursor += font.width(c) + 1;
            }
        }
    }

    /** A titled row with a grey description; subclasses draw their control on the right. */
    private abstract class Option extends Row {
        final String name;
        final String desc;
        boolean enabled = true;
        boolean indent;
        /** The description did not fit its line; the bubble shows it whole. */
        boolean cut;
        /** Instead of taking the default from a fresh config: what "Auf Standard zurücksetzen" does here. */
        java.util.function.Consumer<SkyLyricsConfig> reset;

        Option(String name, String desc) {
            this.name = name;
            this.desc = desc;
        }

        @Override
        int height() {
            return ROW;
        }

        /** Room the control takes on the right. */
        int controlWidth(int w) {
            return CONTROL;
        }

        @Override
        String more() {
            return cut ? desc : null;
        }

        @Override
        void draw(GuiGraphicsExtractor graphics, int x, int y, int w, int mouseX, int mouseY, float alpha, float dt) {
            if (hover > 0.01f) {
                Glass.fill(graphics, x - 6, y + 1, x + w + 6, y + ROW - 1, 8, ARGB.color(alpha * 0.045f * hover, 0xFFFFFF));
            }
            int tx = x + (indent ? 12 : 0);
            int textWidth = w - controlWidth(w) - 12 - (tx - x);
            float dim = enabled ? 1f : 0.45f;
            graphics.text(font, fit(name, label, textWidth), tx, y + 5, ARGB.color(alpha * dim, TEXT), false);
            cut = !fitsIn(desc, small, textWidth) || !fitsIn(name, label, textWidth);
            graphics.text(font, fit(desc, small, textWidth), tx, y + 17, ARGB.color(alpha * dim, DESC), false);
            graphics.fill(x, y + ROW - 1, x + w, y + ROW, ARGB.color(alpha * LINE, 0xFFFFFF));
            drawControl(graphics, x + w - controlWidth(w), y, controlWidth(w), mouseX, mouseY, alpha * dim, dt);
        }

        abstract void drawControl(GuiGraphicsExtractor graphics, int x, int y, int w, int mouseX, int mouseY, float alpha,
                float dt);
    }

    private final class Toggle extends Option {
        private final Predicate<SkyLyricsConfig> get;
        private final BiConsumer<SkyLyricsConfig, Boolean> set;
        private float knob = -1f;
        private long changedNanos;

        Toggle(String name, String desc, Predicate<SkyLyricsConfig> get, BiConsumer<SkyLyricsConfig, Boolean> set) {
            super(name, desc);
            this.get = get;
            this.set = set;
        }

        @Override
        int controlWidth(int w) {
            return 34;
        }

        @Override
        void drawControl(GuiGraphicsExtractor graphics, int x, int y, int w, int mouseX, int mouseY, float alpha, float dt) {
            boolean on = get.test(config);
            knob = knob < 0 ? (on ? 1f : 0f) : config.menuAnimations ? approach(knob, on ? 1f : 0f, dt, 0.07f) : (on ? 1f : 0f);
            switchAt(graphics, x + w - 30, y + 8, knob, hover, alpha, changedNanos);
        }

        @Override
        boolean click(double mx, double my, int x, int y, int w) {
            if (!enabled) {
                return false;
            }
            set.accept(config, !get.test(config));
            changedNanos = System.nanoTime();
            return true;
        }

        @Override
        void resetTo(SkyLyricsConfig config, SkyLyricsConfig defaults) {
            if (reset != null) {
                reset.accept(config);
            } else if (get.test(config) != get.test(defaults)) {
                set.accept(config, get.test(defaults));
            }
        }
    }

    /** A switch: a capsule that fills with the accent, its white knob gliding and stretching as it moves. */
    private void switchAt(GuiGraphicsExtractor graphics, int x, int y, float knob, float hover, float alpha, long changed) {
        int w = 30;
        int h = 15;
        int off = ARGB.color(alpha * (0.16f + 0.06f * hover), 0xFFFFFF);
        Glass.fill(graphics, x, y, x + w, y + h, h / 2, off);
        if (knob > 0.01f) {
            hGradient(graphics, x, y, x + w, y + h, ARGB.color(alpha * knob, accent()),
                    ARGB.color(alpha * knob, ARGB.srgbLerp(0.35f, accent(), 0xFF6AD5)), h / 2);
        }
        float moving = 1f - Math.abs(knob * 2f - 1f);
        int size = h - 4;
        int stretch = Math.round(moving * 4f);
        int kx = x + 2 + Math.round((w - 4 - size - stretch) * knob);
        if (config.menuAnimations && changed > 0 && (System.nanoTime() - changed) / 1e9f < 0.35f) {
            // A soft ring flashing out from the knob when switched.
            float t = (System.nanoTime() - changed) / 1e9f / 0.35f;
            int grow = Math.round(t * 5f);
            Glass.fill(graphics, kx - grow, y + 2 - grow, kx + size + stretch + grow, y + 2 + size + grow, (size + 2 * grow) / 2,
                    ARGB.color(alpha * 0.25f * (1f - t), accent()));
        }
        Glass.fill(graphics, kx, y + 2, kx + size + stretch, y + 2 + size, size / 2, ARGB.color(alpha, 0xFFFFFF));
    }

    /** A capsule filled with a horizontal gradient, column by column. */
    private static void hGradient(GuiGraphicsExtractor graphics, int x0, int y0, int x1, int y1, int left, int right, int radius) {
        int w = x1 - x0;
        int h = y1 - y0;
        radius = Math.min(radius, Math.min(w, h) / 2);
        for (int i = 0; i < w; i++) {
            int fromEdge = Math.min(i, w - 1 - i);
            int inset = 0;
            if (fromEdge < radius) {
                double d = radius - fromEdge - 0.5;
                inset = (int) Math.round(radius - Math.sqrt(Math.max(0, radius * radius - d * d)));
            }
            graphics.fill(x0 + i, y0 + inset, x0 + i + 1, y1 - inset, ARGB.srgbLerp(w <= 1 ? 0f : i / (float) (w - 1), left, right));
        }
    }

    private final class Slider extends Option {
        private final double min;
        private final double max;
        private final double step;
        private final ToDoubleFunction<SkyLyricsConfig> get;
        private final ObjDoubleConsumer<SkyLyricsConfig> set;
        private final DoubleFunction<String> format;
        private float shown = Float.NaN;
        private int trackX;
        private int trackW;

        Slider(String name, String desc, double min, double max, double step, ToDoubleFunction<SkyLyricsConfig> get,
                ObjDoubleConsumer<SkyLyricsConfig> set, DoubleFunction<String> format) {
            super(name, desc);
            this.min = min;
            this.max = max;
            this.step = step;
            this.get = get;
            this.set = set;
            this.format = format;
        }

        @Override
        int controlWidth(int w) {
            return Math.min(150, Math.max(116, w / 3));
        }

        @Override
        void drawControl(GuiGraphicsExtractor graphics, int x, int y, int w, int mouseX, int mouseY, float alpha, float dt) {
            double value = get.applyAsDouble(config);
            float target = (float) ((value - min) / (max - min));
            shown = Float.isNaN(shown) || !config.menuAnimations ? target : approach(shown, target, dt, 0.06f);
            Component text = Component.literal(format.apply(value)).withStyle(label);
            int valueWidth = 38;
            trackX = x;
            trackW = w - valueWidth - 8;
            int ty = y + 14;
            Glass.fill(graphics, trackX, ty, trackX + trackW, ty + 4, 2, ARGB.color(alpha * 0.16f, 0xFFFFFF));
            int filled = Math.round(trackW * Mth.clamp(shown, 0f, 1f));
            if (filled > 1) {
                hGradient(graphics, trackX, ty, trackX + filled, ty + 4, ARGB.color(alpha, accent()),
                        ARGB.color(alpha, accentLight()), 2);
            }
            int kx = trackX + filled;
            boolean active = dragging == this;
            int r = active ? 6 : 5;
            if (hover > 0.02f || active) {
                Glass.fill(graphics, kx - r - 3, ty + 2 - r - 3, kx + r + 3, ty + 2 + r + 3, r + 3,
                        ARGB.color(alpha * (active ? 0.3f : 0.18f * hover), accent()));
            }
            Glass.fill(graphics, kx - r, ty + 2 - r, kx + r, ty + 2 + r, r, ARGB.color(alpha, 0xFFFFFF));
            graphics.text(font, text, x + w - font.width(text), y + 11, ARGB.color(alpha, TEXT), false);
        }

        @Override
        boolean click(double mx, double my, int x, int y, int w) {
            if (!enabled || mx < trackX - 8 || mx > trackX + trackW + 8) {
                return false;
            }
            drag(mx);
            return true;
        }

        @Override
        void drag(double mx) {
            if (!enabled || trackW <= 0) {
                return;
            }
            double t = Mth.clamp((mx - trackX) / trackW, 0, 1);
            double value = min + Math.round((t * (max - min)) / step) * step;
            set.accept(config, Mth.clamp(value, min, max));
        }

        @Override
        void resetTo(SkyLyricsConfig config, SkyLyricsConfig defaults) {
            if (reset != null) {
                reset.accept(config);
            } else if (get.applyAsDouble(config) != get.applyAsDouble(defaults)) {
                set.accept(config, get.applyAsDouble(defaults));
            }
        }
    }

    /** One of a few values: a segmented capsule when all fit, else the value between arrows to step through. */
    private final class Choice extends Option {
        private final List<String> values;
        private final Map<String, String> names;
        private final Function<SkyLyricsConfig, String> get;
        private final BiConsumer<SkyLyricsConfig, String> set;
        private float glide = -1f;
        private int segX;
        private int segW;
        private boolean segmented;

        Choice(String name, String desc, List<String> values, Map<String, String> names, Function<SkyLyricsConfig, String> get,
                BiConsumer<SkyLyricsConfig, String> set) {
            super(name, desc);
            this.values = values;
            this.names = names;
            this.get = get;
            this.set = set;
        }

        private String name(String value) {
            return names.getOrDefault(value, value);
        }

        private int segmentWidth() {
            int widest = 0;
            for (String value : values) {
                widest = Math.max(widest, font.width(Component.literal(name(value)).withStyle(small)));
            }
            return widest + 12;
        }

        @Override
        int controlWidth(int w) {
            int all = segmentWidth() * values.size() + 4;
            segmented = values.size() <= 5 && all <= w * 0.62f;
            return segmented ? all : Math.min(150, Math.max(124, w / 3));
        }

        @Override
        void drawControl(GuiGraphicsExtractor graphics, int x, int y, int w, int mouseX, int mouseY, float alpha, float dt) {
            String current = get.apply(config);
            int index = values.indexOf(current);
            if (segmented) {
                int seg = segmentWidth();
                segX = x;
                segW = seg;
                Glass.fill(graphics, x, y + 7, x + w, y + 24, 8, ARGB.color(alpha * 0.08f, 0xFFFFFF));
                Glass.outline(graphics, x, y + 7, x + w, y + 24, 8, ARGB.color(alpha * 0.08f, 0xFFFFFF));
                if (index >= 0) {
                    glide = glide < 0 || !config.menuAnimations ? index : approach(glide, index, dt, 0.07f);
                    int gx = x + 2 + Math.round(glide * seg);
                    hGradient(graphics, gx, y + 9, gx + seg, y + 22, ARGB.color(alpha, accent()),
                            ARGB.color(alpha, ARGB.srgbLerp(0.3f, accent(), 0xFF6AD5)), 6);
                }
                for (int i = 0; i < values.size(); i++) {
                    Component text = Component.literal(name(values.get(i))).withStyle(small);
                    int tx = x + 2 + i * seg + (seg - font.width(text)) / 2;
                    boolean over = mouseX >= x + 2 + i * seg && mouseX < x + 2 + (i + 1) * seg && mouseY >= y + 7 && mouseY < y + 24;
                    int color = i == index ? TEXT : over ? 0xDCDAE6 : DESC;
                    graphics.text(font, text, tx, y + 12, ARGB.color(alpha, color), false);
                }
                return;
            }
            // Stepper: ‹ value ›, the value centred in a soft capsule.
            segX = x;
            segW = w;
            Glass.fill(graphics, x, y + 7, x + w, y + 24, 8, ARGB.color(alpha * 0.08f, 0xFFFFFF));
            boolean overLeft = mouseX >= x && mouseX < x + 18 && mouseY >= y + 7 && mouseY < y + 24;
            boolean overRight = mouseX >= x + w - 18 && mouseX < x + w && mouseY >= y + 7 && mouseY < y + 24;
            graphics.centeredText(font, Component.literal("‹").withStyle(label), x + 9, y + 11,
                    ARGB.color(alpha, overLeft ? accentLight() : DESC));
            graphics.centeredText(font, Component.literal("›").withStyle(label), x + w - 9, y + 11,
                    ARGB.color(alpha, overRight ? accentLight() : DESC));
            String shown = names.getOrDefault(current, index >= 0 ? current : "Eigene");
            graphics.centeredText(font, fit(shown, label, w - 40), x + w / 2, y + 11, ARGB.color(alpha, TEXT));
            // Dots for where in the list it is.
            if (values.size() <= 16) {
                int dots = values.size() * 4;
                int dx = x + (w - dots) / 2;
                for (int i = 0; i < values.size(); i++) {
                    graphics.fill(dx + i * 4, y + 26, dx + i * 4 + 2, y + 27,
                            ARGB.color(alpha * (i == index ? 0.9f : 0.22f), i == index ? accentLight() : 0xFFFFFF));
                }
            }
        }

        @Override
        boolean click(double mx, double my, int x, int y, int w) {
            if (!enabled || mx < segX || mx >= segX + (segmented ? segW * values.size() + 4 : segW)) {
                return false;
            }
            int index = values.indexOf(get.apply(config));
            String next;
            if (segmented) {
                int i = Mth.clamp((int) ((mx - segX - 2) / segW), 0, values.size() - 1);
                next = values.get(i);
            } else {
                boolean back = mx < segX + segW / 2.0;
                next = values.get(Math.floorMod((index < 0 ? 0 : index) + (back ? -1 : 1), values.size()));
            }
            set.accept(config, next);
            return true;
        }

        @Override
        void resetTo(SkyLyricsConfig config, SkyLyricsConfig defaults) {
            if (reset != null) {
                reset.accept(config);
            } else if (!Objects.equals(get.apply(config), get.apply(defaults))) {
                set.accept(config, get.apply(defaults));
            }
        }
    }

    /** A color: its hex code and a swatch; a click opens the color picker. */
    private final class ColorRow extends Option {
        private final ToIntFunction<SkyLyricsConfig> get;
        private final ObjIntConsumer<SkyLyricsConfig> set;

        ColorRow(String name, String desc, ToIntFunction<SkyLyricsConfig> get, ObjIntConsumer<SkyLyricsConfig> set) {
            super(name, desc);
            this.get = get;
            this.set = set;
        }

        @Override
        int controlWidth(int w) {
            return 92;
        }

        @Override
        void drawControl(GuiGraphicsExtractor graphics, int x, int y, int w, int mouseX, int mouseY, float alpha, float dt) {
            int color = get.applyAsInt(config);
            Component hex = Component.literal(String.format(Locale.ROOT, "#%06X", color)).withStyle(small);
            int sx = x + w - 22;
            graphics.text(font, hex, sx - 8 - font.width(hex), y + 11, ARGB.color(alpha, 0xC9C7D4), false);
            Glass.fill(graphics, sx, y + 5, sx + 22, y + 26, 7, ARGB.color(alpha, color));
            Glass.outline(graphics, sx, y + 5, sx + 22, y + 26, 7, ARGB.color(alpha * (0.22f + 0.3f * hover), 0xFFFFFF));
        }

        @Override
        boolean click(double mx, double my, int x, int y, int w) {
            picker = new ColorPicker(name, () -> get.applyAsInt(config), value -> set.accept(config, value));
            return true;
        }

        @Override
        void resetTo(SkyLyricsConfig config, SkyLyricsConfig defaults) {
            if (get.applyAsInt(config) != get.applyAsInt(defaults)) {
                // Straight into the field: the setter would also switch the palette or outline on.
                set.accept(config, get.applyAsInt(defaults));
            }
        }
    }

    /** A button on the right, e.g. "Verbinden". */
    private final class Action extends Option {
        private final Supplier<String> button;
        private final Runnable action;
        private final BooleanSupplier active;
        private final java.util.function.Consumer<SkyLyricsConfig> resetAction;
        private float press;

        Action(String name, String desc, Supplier<String> button, Runnable action, BooleanSupplier active,
                java.util.function.Consumer<SkyLyricsConfig> resetAction) {
            super(name, desc);
            this.button = button;
            this.action = action;
            this.active = active;
            this.resetAction = resetAction;
        }

        @Override
        int controlWidth(int w) {
            return font.width(Component.literal(button.get()).withStyle(label)) + 34;
        }

        @Override
        void drawControl(GuiGraphicsExtractor graphics, int x, int y, int w, int mouseX, int mouseY, float alpha, float dt) {
            boolean on = active.getAsBoolean();
            press = approach(press, 0f, dt, 0.12f);
            float a = alpha * (on ? 1f : 0.4f);
            int top = y + 6 + Math.round(press);
            pillButton(graphics, x, top, w, 19, button.get(), a, hover);
        }

        @Override
        boolean click(double mx, double my, int x, int y, int w) {
            if (!active.getAsBoolean() || mx < x + w - controlWidth(w)) {
                return false;
            }
            press = 1.5f;
            action.run();
            return true;
        }

        @Override
        void resetTo(SkyLyricsConfig config, SkyLyricsConfig defaults) {
            if (resetAction != null) {
                resetAction.accept(config);
            }
        }
    }

    /** The accent pill with a chevron, as "Ansehen ›" in the live preview card. */
    private void pillButton(GuiGraphicsExtractor graphics, int x, int y, int w, int h, String text, float alpha, float hover) {
        hGradient(graphics, x, y, x + w, y + h, ARGB.color(alpha, ARGB.srgbLerp(0.12f * hover, accent(), 0xFFFFFF)),
                ARGB.color(alpha, ARGB.srgbLerp(0.12f * hover, ARGB.srgbLerp(0.3f, accent(), 0xFF6AD5), 0xFFFFFF)), h / 2);
        Glass.outline(graphics, x, y, x + w, y + h, h / 2, ARGB.color(alpha * 0.3f, 0xFFFFFF));
        Component label = Component.literal(text + "  ›").withStyle(this.label);
        graphics.centeredText(font, label, x + w / 2, y + (h - 8) / 2, ARGB.color(alpha, 0xFFFFFF));
    }

    /** A line of live state, e.g. Spotify's status, wrapped to two lines if needed. */
    private final class Info extends Option {
        private final Supplier<String> text;

        Info(String name, Supplier<String> text) {
            super(name, "");
            this.text = text;
        }

        @Override
        int height() {
            return 34;
        }

        @Override
        String more() {
            return cut ? text.get() : null;
        }

        @Override
        int controlWidth(int w) {
            return 0;
        }

        @Override
        void draw(GuiGraphicsExtractor graphics, int x, int y, int w, int mouseX, int mouseY, float alpha, float dt) {
            graphics.text(font, fit(name, label, w), x, y + 5, ARGB.color(alpha, TEXT), false);
            List<FormattedCharSequence> lines = font.split(Component.literal(text.get()).withStyle(small), w);
            cut = lines.size() > 2;
            for (int i = 0; i < Math.min(2, lines.size()); i++) {
                graphics.text(font, lines.get(i), x, y + 16 + i * 9, ARGB.color(alpha, DESC), false);
            }
            graphics.fill(x, y + height() - 1, x + w, y + height(), ARGB.color(alpha * LINE, 0xFFFFFF));
        }

        @Override
        void drawControl(GuiGraphicsExtractor graphics, int x, int y, int w, int mouseX, int mouseY, float alpha, float dt) {
        }
    }

    /** The night sky with the sample line in the player's look. */
    private final class Preview extends Row {
        @Override
        int height() {
            return PREVIEW + 22;
        }

        @Override
        void draw(GuiGraphicsExtractor graphics, int x, int y, int w, int mouseX, int mouseY, float alpha, float dt) {
            graphics.text(font, Component.literal("Vorschau").withStyle(label), x, y + 2, ARGB.color(alpha, TEXT), false);
            Component hint = Component.literal("Ändert sich sofort mit").withStyle(small);
            graphics.text(font, hint, x + w - font.width(hint), y + 3, ARGB.color(alpha, DESC), false);
            int top = y + 16;
            graphics.enableScissor(x, top, x + w, top + PREVIEW);
            LookPreview.draw(graphics, font, config, x, top, x + w, top + PREVIEW, alpha, accent());
            graphics.disableScissor();
            // Rounded corners: the window's color over the box's corners, then a hairline rim.
            cornerMask(graphics, x, top, x + w, top + PREVIEW, 10, alpha);
            Glass.outline(graphics, x, top, x + w, top + PREVIEW, 10, ARGB.color(alpha * 0.14f, 0xFFFFFF));
        }
    }

    /** Covers the four corners of a box with the window's color, so the box looks rounded. */
    private void cornerMask(GuiGraphicsExtractor graphics, int x0, int y0, int x1, int y1, int radius, float alpha) {
        for (int row = 0; row < radius; row++) {
            int inset = cornerInset(row, radius);
            if (inset <= 0) {
                continue;
            }
            int top = ARGB.color(alpha, windowColor(y0 + row));
            int bottom = ARGB.color(alpha, windowColor(y1 - row - 1));
            graphics.fill(x0, y0 + row, x0 + inset, y0 + row + 1, top);
            graphics.fill(x1 - inset, y0 + row, x1, y0 + row + 1, top);
            graphics.fill(x0, y1 - row - 1, x0 + inset, y1 - row, bottom);
            graphics.fill(x1 - inset, y1 - row - 1, x1, y1 - row, bottom);
        }
    }

    /** How far row {@code row} of a corner of this radius is inset from the side. */
    private static int cornerInset(int row, int radius) {
        double d = radius - row - 0.5;
        return (int) Math.round(radius - Math.sqrt(Math.max(0, radius * radius - d * d)));
    }

    /** The window's color at screen height {@code y}: lighter at the top, near black at the bottom. */
    private int windowColor(int y) {
        float t = Mth.clamp((y - y0) / (float) Math.max(1, y1 - y0), 0f, 1f);
        return ARGB.srgbLerp(t, WINDOW_TOP, WINDOW_BOTTOM);
    }

    /** "Live-Vorschau": the menu steps aside and the demo sings in the sky until a click. */
    private final class LiveCard extends Row {
        @Override
        int height() {
            return LIVE_CARD + 10;
        }

        @Override
        void draw(GuiGraphicsExtractor graphics, int x, int y, int w, int mouseX, int mouseY, float alpha, float dt) {
            int top = y + 4;
            int bottom = top + LIVE_CARD;
            Glass.fill(graphics, x, top, x + w, bottom, 12, ARGB.color(alpha * (0.07f + 0.03f * hover), accent()));
            Glass.outline(graphics, x, top, x + w, bottom, 12, ARGB.color(alpha * (0.22f + 0.2f * hover), accent()));
            iconTile(graphics, x + 9, top + 8, 26, "◎", 2, alpha, 1f);
            graphics.text(font, Component.literal("Live-Vorschau").withStyle(label), x + 44, top + 10, ARGB.color(alpha, TEXT),
                    false);
            graphics.text(font, fit("Menü weg, das Demo singt am Himmel", small, w - 44 - 98), x + 44, top + 22,
                    ARGB.color(alpha, DESC), false);
            pillButton(graphics, x + w - 92, top + 11, 82, 20, "Ansehen", alpha, hover);
        }

        @Override
        boolean click(double mx, double my, int x, int y, int w) {
            if (controller.timeline(System.currentTimeMillis()) == null || !live()) {
                controller.startDemo();
            }
            config.save();
            peeking = true;
            return true;
        }
    }

    /** The song playing now, on the start page. */
    private final class NowPlaying extends Row {
        @Override
        int height() {
            return timeline() == null ? 0 : NOW_CARD + 8;
        }

        private LyricsController.Timeline timeline() {
            return controller.timeline(System.currentTimeMillis());
        }

        @Override
        void draw(GuiGraphicsExtractor graphics, int x, int y, int w, int mouseX, int mouseY, float alpha, float dt) {
            LyricsController.Timeline timeline = timeline();
            if (timeline == null) {
                return;
            }
            int top = y + 2;
            Glass.fill(graphics, x, top, x + w, top + NOW_CARD, 12, ARGB.color(alpha * 0.06f, 0xFFFFFF));
            Glass.outline(graphics, x, top, x + w, top + NOW_CARD, 12, ARGB.color(alpha * 0.1f, 0xFFFFFF));
            // An equalizer tile.
            int tile = 28;
            iconTile(graphics, x + 9, top + 8, tile, "", 0, alpha, 1f);
            double t = System.nanoTime() / 1e9;
            for (int bar = 0; bar < 4; bar++) {
                float level = timeline.playing() && config.menuAnimations
                        ? 0.3f + 0.7f * (float) Math.abs(Math.sin(t * (4.6 + bar * 1.5) + bar * 1.2)) : 0.35f;
                int bh = Math.max(2, Math.round(level * 14f));
                int bx = x + 9 + 7 + bar * 4;
                graphics.fill(bx, top + 8 + tile - 7 - bh, bx + 2, top + 8 + tile - 7, ARGB.color(alpha, 0xFFFFFF));
            }
            int tx = x + 9 + tile + 10;
            int textWidth = w - (tx - x) - 12;
            graphics.text(font, fit(timeline.title(), label, textWidth), tx, top + 9, ARGB.color(alpha, TEXT), false);
            String line = timeline.artist() + (timeline.lyrics() == null ? "" : " · Text gefunden");
            graphics.text(font, fit(line, small, textWidth), tx, top + 21, ARGB.color(alpha, DESC), false);
            if (timeline.durationMs() > 0) {
                float played = Mth.clamp(timeline.positionMs() / (float) timeline.durationMs(), 0f, 1f);
                int bx0 = tx;
                int bx1 = x + w - 12;
                graphics.fill(bx0, top + 33, bx1, top + 34, ARGB.color(alpha * 0.16f, 0xFFFFFF));
                hGradient(graphics, bx0, top + 33, bx0 + Math.max(1, Math.round((bx1 - bx0) * played)), top + 34,
                        ARGB.color(alpha, accent()), ARGB.color(alpha, accentLight()), 0);
            }
        }
    }

    /** The cards of the start page, two in a row where there is room. */
    private final class Cards extends Row {
        private final float[] hovers = new float[categories.size()];

        private int columns() {
            return x1 - x0 - 2 * PAD >= 340 ? 2 : 1;
        }

        @Override
        int height() {
            int rowsOfCards = (categories.size() + columns() - 1) / columns();
            return rowsOfCards * (CARD + CARD_GAP);
        }

        @Override
        void draw(GuiGraphicsExtractor graphics, int x, int y, int w, int mouseX, int mouseY, float alpha, float dt) {
            int columns = columns();
            int cardW = (w - (columns - 1) * CARD_GAP) / columns;
            for (int i = 0; i < categories.size(); i++) {
                Category c = categories.get(i);
                int cx = x + (i % columns) * (cardW + CARD_GAP);
                int cy = y + (i / columns) * (CARD + CARD_GAP);
                boolean over = picker == null && mouseX >= cx && mouseX < cx + cardW && mouseY >= cy && mouseY < cy + CARD
                        && mouseY >= contentTop && mouseY < contentBottom;
                hovers[i] = approach(hovers[i], over ? 1f : 0f, dt, 0.09f);
                float h = hovers[i];
                // Cards come in one after the other when the menu opens.
                float in = config.menuAnimations ? easeOut((System.nanoTime() - openedNanos) / 1e9f / 0.32f - i * 0.04f) : 1f;
                float a = alpha * in;
                int lift = Math.round(h * -1.5f + (1f - in) * 8f);
                cy += lift;
                Glass.fill(graphics, cx, cy, cx + cardW, cy + CARD, 12, ARGB.color(a * (0.05f + 0.04f * h), 0xFFFFFF));
                Glass.outline(graphics, cx, cy, cx + cardW, cy + CARD, 12,
                        ARGB.color(a * (0.1f + 0.4f * h), ARGB.srgbLerp(h, 0xFFFFFF, accent())));
                iconTile(graphics, cx + 10, cy + 10, 28, c.icon(), c.hueShift(), a, 1f);
                int tx = cx + 48;
                int right = cx + cardW - (c.master() != null ? 44 : 22);
                graphics.text(font, fit(c.name(), label, right - tx), tx, cy + 12, ARGB.color(a, TEXT), false);
                graphics.text(font, fit(c.subtitle(), small, right - tx), tx, cy + 25, ARGB.color(a, DESC), false);
                if (c.master() != null) {
                    boolean on = c.master().test(config);
                    switchAt(graphics, cx + cardW - 40, cy + 16, on ? 1f : 0f, h, a, 0);
                } else {
                    graphics.text(font, Component.literal("›").withStyle(label), cx + cardW - 16, cy + 18,
                            ARGB.color(a, ARGB.srgbLerp(h, DESC, TEXT)), false);
                }
            }
        }

        @Override
        boolean click(double mx, double my, int x, int y, int w) {
            int columns = columns();
            int cardW = (w - (columns - 1) * CARD_GAP) / columns;
            int col = (int) ((mx - x) / (cardW + CARD_GAP));
            int row = (int) ((my - y) / (CARD + CARD_GAP));
            int i = row * columns + col;
            if (col < 0 || col >= columns || i < 0 || i >= categories.size()) {
                return false;
            }
            int cx = x + col * (cardW + CARD_GAP);
            int cy = y + row * (CARD + CARD_GAP);
            if (mx >= cx + cardW || my >= cy + CARD) {
                return false;
            }
            Category c = categories.get(i);
            if (c.master() != null && mx >= cx + cardW - 46) {
                c.setMaster().accept(config, !c.master().test(config));
                return true;
            }
            open(c);
            return true;
        }
    }

    // ------------------------------------------------------------------------------------------------- color picker

    /**
     * A small window for one color: a square of saturation and brightness, a hue strip, swatches to pick from and the
     * hex code, which can also be typed. The color applies while dragging.
     */
    private final class ColorPicker {
        private static final int W = 214;
        private static final int H = 176;
        private static final int SQUARE = 118;
        private static final int SQUARE_H = 86;
        private final String name;
        private final java.util.function.IntSupplier get;
        private final java.util.function.IntConsumer set;
        private final long openedAt = System.nanoTime();
        private float hue;
        private float saturation;
        private float brightness;
        private int draggingPart;
        private String typed = "";

        ColorPicker(String name, java.util.function.IntSupplier get, java.util.function.IntConsumer set) {
            this.name = name;
            this.get = get;
            this.set = set;
            fromColor(get.getAsInt());
        }

        private void fromColor(int rgb) {
            float[] hsv = hsv(rgb);
            hue = hsv[0];
            saturation = hsv[1];
            brightness = hsv[2];
        }

        private int px() {
            return (width - W) / 2;
        }

        private int py() {
            return (height - H) / 2;
        }

        void draw(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float alpha) {
            float in = config.menuAnimations ? easeOut((System.nanoTime() - openedAt) / 1e9f / 0.18f) : 1f;
            float a = alpha * in;
            graphics.fill(x0, y0, x1, y1, ARGB.color(a * 0.45f, 0x000000));
            int x = px();
            int y = py() + Math.round((1f - in) * 8f);
            Glass.fill(graphics, x, y, x + W, y + H, 14, ARGB.color(a * 0.98f, 0x15141C));
            Glass.outline(graphics, x, y, x + W, y + H, 14, ARGB.color(a * 0.18f, 0xFFFFFF));
            graphics.text(font, fit(name, label, W - 60), x + 12, y + 10, ARGB.color(a, TEXT), false);
            Component done = Component.literal("Fertig").withStyle(small);
            graphics.text(font, done, x + W - 12 - font.width(done), y + 11, ARGB.color(a, accentLight()), false);
            int sx = x + 12;
            int sy = y + 26;
            // Saturation left to right in columns, brightness top to bottom as a dark gradient over them.
            for (int i = 0; i < SQUARE; i++) {
                int column = Mth.hsvToRgb(hue, i / (float) (SQUARE - 1), 1f);
                graphics.fillGradient(sx + i, sy, sx + i + 1, sy + SQUARE_H, ARGB.color(a, column), ARGB.color(a, 0x000000));
            }
            int kx = sx + Math.round(saturation * (SQUARE - 1));
            int ky = sy + Math.round((1f - brightness) * (SQUARE_H - 1));
            Glass.outline(graphics, kx - 4, ky - 4, kx + 5, ky + 5, 4, ARGB.color(a, 0xFFFFFF));
            int hx = sx + SQUARE + 10;
            for (int i = 0; i < SQUARE_H; i++) {
                graphics.fill(hx, sy + i, hx + 12, sy + i + 1, ARGB.color(a, Mth.hsvToRgb(i / (float) (SQUARE_H - 1), 1f, 1f)));
            }
            int hy = sy + Math.round(hue * (SQUARE_H - 1));
            Glass.outline(graphics, hx - 2, hy - 2, hx + 14, hy + 3, 2, ARGB.color(a, 0xFFFFFF));
            int color = get.getAsInt();
            int swx = hx + 22;
            Glass.fill(graphics, swx, sy, x + W - 12, sy + 34, 8, ARGB.color(a, color));
            Glass.outline(graphics, swx, sy, x + W - 12, sy + 34, 8, ARGB.color(a * 0.25f, 0xFFFFFF));
            String hex = typed.isEmpty() ? String.format(Locale.ROOT, "%06X", color)
                    : (typed + "______").substring(0, 6);
            graphics.text(font, Component.literal("#" + hex).withStyle(label), swx, sy + 42,
                    ARGB.color(a, typed.isEmpty() ? 0xD8D6E2 : accentLight()), false);
            graphics.text(font, fit("tippen", small, x + W - 12 - swx), swx, sy + 55, ARGB.color(a, DESC), false);
            int rowY = sy + SQUARE_H + 10;
            int size = 13;
            int gap = (W - 24 - SWATCHES.length * size) / (SWATCHES.length - 1);
            for (int i = 0; i < SWATCHES.length; i++) {
                int bx = x + 12 + i * (size + gap);
                Glass.fill(graphics, bx, rowY, bx + size, rowY + size, 4, ARGB.color(a, SWATCHES[i]));
                boolean chosen = (color & 0xFFFFFF) == SWATCHES[i];
                Glass.outline(graphics, bx, rowY, bx + size, rowY + size, 4, ARGB.color(a * (chosen ? 0.9f : 0.2f), 0xFFFFFF));
            }
        }

        /** @return false when the click was outside and the picker should close */
        boolean click(double mx, double my) {
            int x = px();
            int y = py();
            if (mx < x || mx >= x + W || my < y || my >= y + H) {
                return false;
            }
            if (my < y + 24 && mx > x + W - 60) {
                picker = null;
                return true;
            }
            int sx = x + 12;
            int sy = y + 26;
            if (mx >= sx && mx < sx + SQUARE && my >= sy && my < sy + SQUARE_H) {
                draggingPart = 1;
                drag(mx, my);
                return true;
            }
            int hx = sx + SQUARE + 10;
            if (mx >= hx - 3 && mx < hx + 15 && my >= sy && my < sy + SQUARE_H) {
                draggingPart = 2;
                drag(mx, my);
                return true;
            }
            int rowY = sy + SQUARE_H + 10;
            int size = 13;
            int gap = (W - 24 - SWATCHES.length * size) / (SWATCHES.length - 1);
            if (my >= rowY && my < rowY + size) {
                for (int i = 0; i < SWATCHES.length; i++) {
                    int bx = x + 12 + i * (size + gap);
                    if (mx >= bx && mx < bx + size) {
                        set.accept(SWATCHES[i]);
                        fromColor(SWATCHES[i]);
                    }
                }
            }
            return true;
        }

        void drag(double mx, double my) {
            int sx = px() + 12;
            int sy = py() + 26;
            if (draggingPart == 1) {
                saturation = (float) Mth.clamp((mx - sx) / (SQUARE - 1), 0, 1);
                brightness = 1f - (float) Mth.clamp((my - sy) / (SQUARE_H - 1), 0, 1);
            } else if (draggingPart == 2) {
                hue = (float) Mth.clamp((my - sy) / (SQUARE_H - 1), 0, 0.999);
            } else {
                return;
            }
            typed = "";
            set.accept(Mth.hsvToRgb(hue, saturation, brightness));
        }

        void release() {
            draggingPart = 0;
        }

        void typed(int codepoint) {
            char c = Character.toUpperCase((char) codepoint);
            if (Character.digit(c, 16) < 0 || typed.length() >= 6) {
                return;
            }
            typed += c;
            if (typed.length() == 6) {
                int rgb = Integer.parseInt(typed, 16);
                set.accept(rgb);
                fromColor(rgb);
                typed = "";
            }
        }

        /** Backspace edits the typed code, Enter closes. */
        boolean key(int key) {
            if (key == 259 && !typed.isEmpty()) {
                typed = typed.substring(0, typed.length() - 1);
                return true;
            }
            if (key == 257 || key == 335) {
                picker = null;
                return true;
            }
            return false;
        }
    }
}
