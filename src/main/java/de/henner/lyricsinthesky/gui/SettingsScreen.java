package de.henner.lyricsinthesky.gui;

import de.henner.lyricsinthesky.LyricsController;
import de.henner.lyricsinthesky.LyricsInTheSkyClient;
import de.henner.lyricsinthesky.SkyLyricsConfig;
import de.henner.lyricsinthesky.WordSourceInfo;
import de.henner.lyricsinthesky.hoergenau.WhisperEngine;
import de.henner.lyricsinthesky.render.Glass;
import de.henner.lyricsinthesky.render.SkyLyricsRenderer;
import de.henner.lyricsinthesky.spotify.SpotifyAccount;
import de.henner.lyricsinthesky.timing.SongTimings;
import de.henner.lyricsinthesky.util.Browser;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleFunction;
import java.util.function.Supplier;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.util.ARGB;
import net.minecraft.util.FormattedCharSequence;

/**
 * Settings, opened with Right Shift: a minimal black-and-white panel – labels in grey, values in white, a thin
 * underline for the chosen option, switches and sliders drawn as plain lines. Changes apply immediately, so the
 * lyrics in the sky behind the menu show the result while adjusting; the file is saved when the screen closes.
 * Hovering an option explains it in the line above the buttons.
 */
public final class SettingsScreen extends Screen {
    /**
     * Height of an option row; {@link #COMPACT_ROW} when the screen is too short for the panel, {@link #TIGHT_ROW}
     * on the shortest ones (240 GUI pixels, e.g. 720p at the automatic GUI scale).
     */
    private static final int ROW = 15;
    private static final int COMPACT_ROW = 12;
    private static final int TIGHT_ROW = 11;
    private static final int LABEL_WIDTH = 74;
    private static final int COLUMN_GAP = 18;
    private static final int PANEL_PADDING = 14;
    /** Half see-through, so the world and the lyrics behind stay visible; the greys are light enough to read on it. */
    private static final int PANEL = 0x80000000;
    private static final int LINE = 0x55FFFFFF;
    private static final int WHITE = 0xFFFFFFFF;
    private static final int LABEL = 0xFFB4B4B4;
    private static final int DIM = 0xFF8E8E8E;
    private static final int HOVER = 0xFFE2E2E2;
    /** On glass over a bright sky the greys need more light to stand out. */
    private static final int GLASS_LABEL = 0xFFD2D6DE;
    private static final int GLASS_DIM = 0xFFAAB1BD;
    /** Tint of a switch that is on in the glass theme: a clear cyan, like the glass palette's accent. */
    private static final int ON_TINT = 0x4FC3F7;
    private static final Map<String, String> PALETTE_NAMES = Map.ofEntries(
            Map.entry("standard", "Standard"), Map.entry("rose", "Altrosa"), Map.entry("lavender", "Lavendel"),
            Map.entry("auto", "Je Song anders"), Map.entry("aurora", "Polarlicht"), Map.entry("sunset", "Sonnenuntergang"),
            Map.entry("gold", "Gold"), Map.entry("sakura", "Kirschblüte"), Map.entry("ocean", "Ozean"),
            Map.entry("white", "Weiß"), Map.entry("glass", "Glas"), Map.entry("neon", "Neon"));
    private static final Map<String, String> STYLE_NAMES = Map.of(
            "elegant", "Elegant", "natural", "Natürlich 1:1", "flow", "Stapel", "classic", "Verspielt");
    private static final Map<String, String> FONT_NAMES = Map.of(
            "sans", "Modern", "script", "Handschrift", "minecraft", "Minecraft");
    private static final Map<String, String> MODE_NAMES = Map.of("sky", "Am Himmel", "around", "Um dich herum");
    private static final Map<String, String> PRESET_NAMES = Map.of(
            "standard", "Standard", "sky", "Himmel 1:1", "around", "Um dich 1:1", "party", "Party", "calm", "Ruhig",
            "glass", "Glas");
    private static final Map<String, String> THEME_NAMES = Map.of("classic", "Klassisch", "glass", "Liquid Glass");
    private static final Map<String, String> MENU_STYLE_NAMES = Map.of("minimal", "Minimal", "fancy", "Prachtvoll");
    /** The pages of the menu, chosen with the tabs under the title. */
    private static final List<String> PAGES = List.of("general", "look", "sources");
    private static final Map<String, String> PAGE_NAMES = Map.of("general", "Allgemein", "look", "Aussehen",
            "sources", "Quellen");
    /** The bar's looks, and "off" for no bar. */
    private static final List<String> BAR_CHOICES = List.of("off", "pill", "karaoke", "plain", "title");
    private static final Map<String, String> BAR_NAMES = Map.of("off", "Aus", "pill", "Pille", "karaoke", "Karaoke",
            "plain", "Schlicht", "title", "Nur Titel");

    /** The page shown, kept while the game runs so the menu opens where it was left. */
    private static String page = "general";

    private final Screen parent;
    private final LyricsController controller;
    private final SkyLyricsConfig config;
    private final SpotifyAccount spotify;
    private final Style text = SkyLyricsRenderer.regularStyle("sans");
    /** Options with their grey label on the left, for drawing the labels and the hint of the hovered one. */
    private final List<Labelled> labelled = new ArrayList<>();
    private int panelLeft;
    private int panelTop;
    private int panelRight;
    private int panelBottom;
    private int titleY;
    private int spotifyHeaderY;
    /** The line at the bottom: what the hovered option does, else the Spotify or lookup status. */
    private int statusY;
    private int sourcesNoteY;
    private int row = ROW;
    /** A preset was picked: every option shows new values, so the widgets are built again on the next tick. */
    private boolean rebuildPending;
    private OutlineButton spotifyButton;
    private EditBox clientIdBox;
    private SpotifyAccount.State shownState;
    /** What moves in the fancy look; lives as long as the screen, so a page change does not open it again. */
    private final Fancy.Scene scene = new Fancy.Scene();
    /** The hint line's text and when it changed, so in the fancy look a new hint slides in. */
    private String shownHint = "";
    private long hintChangedNanos;

    private record Labelled(String label, int x, int y, Hinted widget) {}

    public SettingsScreen(Screen parent, LyricsController controller, SpotifyAccount spotify) {
        super(Component.literal("Lyrics in the Sky"));
        this.parent = parent;
        this.controller = controller;
        this.config = controller.config();
        this.spotify = spotify;
    }

    private Component text(String value) {
        return Component.literal(value).withStyle(text);
    }

    @Override
    protected void init() {
        labelled.clear();
        spotifyButton = null;
        clientIdBox = null;
        scene.pageShown();
        int contentWidth = Math.min(380, width - 2 * PANEL_PADDING - 8);
        boolean twoColumns = contentWidth >= 330;
        int left = (width - contentWidth) / 2;
        int columnWidth = twoColumns ? (contentWidth - COLUMN_GAP) / 2 : contentWidth;
        // Title, tabs, the choices across the full width, the switches and sliders, the Spotify block on the first
        // page and the hint line must fit the screen; on short screens the rows move closer together.
        int[] rows = switch (page) {
            case "look" -> new int[] {3, 5, 5};
            case "sources" -> new int[] {0, 4 + SkyLyricsConfig.WORD_SOURCES.size(), 0};
            default -> new int[] {3, 6, 6};
        };
        int optionRows = rows[0] + (twoColumns ? Math.max(rows[1], rows[2]) : rows[1] + rows[2]);
        // Everything but the option rows: title and tabs, the gaps between the blocks, the Spotify block, the hint line.
        int fixed = 10 + 18 + 16 + switch (page) {
            case "look" -> 6 + 6;
            case "sources" -> 2 * 11 + 2 + 6;
            default -> 4 + 6 + 6 + 32;
        } + 22 + 6;
        row = fixed + optionRows * ROW <= height ? ROW : fixed + optionRows * COMPACT_ROW <= height ? COMPACT_ROW : TIGHT_ROW;
        int y = 10;
        titleY = y;
        int small = 46;
        addRenderableWidget(new OutlineButton(font, text, left + contentWidth - small, y - 3, small, text("Fertig"), true,
                this::onClose, this::glass, this::fancy));
        addRenderableWidget(new OutlineButton(font, text, left + contentWidth - 2 * small - 6, y - 3, small, text("Demo"),
                false, () -> {
                    controller.startDemo();
                    onClose();
                }, this::glass, this::fancy));
        addRenderableWidget(new OutlineButton(font, text, left + contentWidth - 3 * small - 12, y - 3, small, text("Leiste"),
                false, () -> {
                    config.save();
                    minecraft.gui.setScreen(new HudEditScreen(this, config));
                }, this::glass, this::fancy));
        y += 18;
        Choice tabs = new Choice(contentWidth, PAGES, PAGE_NAMES, () -> page, value -> {
            page = value;
            rebuildPending = true;
        }, value -> "");
        tabs.setRow(0);
        tabs.setPosition(left, y);
        addRenderableWidget(tabs);
        y += 16;
        statusY = switch (page) {
            case "look" -> look(left, y, contentWidth, columnWidth, twoColumns);
            case "sources" -> sources(left, y, contentWidth);
            default -> general(left, y, contentWidth, columnWidth, twoColumns);
        };
        panelLeft = left - PANEL_PADDING;
        panelRight = left + contentWidth + PANEL_PADDING;
        panelTop = 2;
        panelBottom = statusY + 22 + 6;
        if (spotifyButton != null) {
            updateSpotifyButton();
        }
    }

    private boolean glass() {
        return "glass".equals(config.theme);
    }

    /** The lively look: animations, sparks and the aurora behind the panel. */
    private boolean fancy() {
        return "fancy".equals(config.menuStyle);
    }

    private int label() {
        return glass() ? GLASS_LABEL : LABEL;
    }

    private int dim() {
        return glass() ? GLASS_DIM : DIM;
    }

    /** Switches to one of the pages ("general", "look", "sources"); for the self test. */
    public void show(String name) {
        if (PAGES.contains(name)) {
            page = name;
            rebuildPending = true;
        }
    }

    /** The panel's bottom edge in GUI pixels, to check that it fits the screen; for the self test. */
    public int panelBottom() {
        return panelBottom;
    }

    /** What is shown and when: looks, timing, lookups, and the optional Spotify account. Returns the hint line's y. */
    private int general(int left, int y, int contentWidth, int columnWidth, boolean twoColumns) {
        // Choices across the full width: a whole look at once, then what is shown and how.
        int wide = contentWidth - LABEL_WIDTH;
        y = row("Vorlagen", left, y, new Choice(wide, SkyLyricsConfig.PRESETS, PRESET_NAMES,
                () -> Objects.requireNonNullElse(config.currentPreset(), ""), value -> {
                    config.applyPreset(value);
                    rebuildPending = true;
                }, value -> switch (value) {
                    case "standard" -> "Alles wie beim ersten Start: Elegant am Himmel.";
                    case "sky" -> "Am Himmel im Stil 1:1: Zeilen knapp über den Hügeln, hinter Bergen und Wolken.";
                    case "around" -> "Um dich herum im Stil 1:1: Wörter fest in der Welt, Spieler und Blöcke stehen davor.";
                    case "party" -> "Große verspielte Wörter um dich herum, die im Takt wackeln und sich mit dir drehen.";
                    case "calm" -> "Der ruhige weiße Stapel am Himmel, ohne Songtitel.";
                    case "glass" -> "Liquid Glass: klarer Stapel in Eisblau mit Leuchten, Menü und Leiste aus Glas.";
                    default -> "Eine Vorlage setzt Anzeige, Stil, Farben, Schrift und Schalter auf einmal.";
                }));
        y += 4;
        y = row("Anzeige", left, y, new Choice(wide, SkyLyricsConfig.MODES, MODE_NAMES, () -> config.mode,
                value -> config.mode = value, value -> "sky".equals(value)
                        ? "Am Himmel, weit hinten wie die Sonne."
                        : "Um dich herum: jedes Wort schwebt einzeln an einem festen Platz in deiner Nähe."));
        y = row("Stil", left, y, new Choice(wide, SkyLyricsConfig.STYLES, STYLE_NAMES, () -> config.style,
                value -> config.style = value, value -> switch (value) {
                    case "elegant" -> "Am Himmel bauen sich die Zeilen Wort für Wort auf, um dich herum schweben einzelne Wörter.";
                    case "natural" -> "1:1 wie echt in der Welt: Zeilen knapp über den Hügeln, Spieler und Blöcke stehen vor den "
                            + "Wörtern um dich herum, oben leuchten die gesungenen Wörter als Gruppe.";
                    case "flow" -> "Die aktuelle Zeile in der Mitte, die nächste schwach darunter; alles gleitet weich nach oben.";
                    default -> "Jede Zeile erscheint an einer anderen Stelle, mit wechselnden Animationen und Funkeln.";
                }));
        y += 6;

        // Sliders and switches, in two columns where there is room, else one below the other.
        int control = columnWidth - LABEL_WIDTH;
        int leftY = y;
        leftY = row("Timing", left, leftY, new Slider(control, -2000, 2000, 50, config.offsetMs,
                value -> (value > 0 ? "+" : "") + Math.round(value) + " ms", value -> config.offsetMs = Math.round(value),
                "Verschiebt alle Songtexte, falls dein Kopfhörer verzögert. Positiv zeigt sie früher."));
        leftY = row("Dieser Song", left, leftY, songTiming(control));
        leftY = row("Teilen", left, leftY, new Toggle(control, () -> config.shareTiming, value -> config.shareTiming = value,
                "Teilt Song-Korrekturen anonym mit henrik.click; ab 10 gleichen gelten sie für alle Spieler."));
        leftY = row("Songtexte", left, leftY, lrclibToggle(control));
        leftY = row("Wortgenau", left, leftY, wordTimesToggle(control));
        leftY = row("Streamer", left, leftY, new Toggle(control, () -> config.streamerMode,
                value -> LyricsInTheSkyClient.setStreamerMode(config, value),
                "Streamer-Modus: Schimpfwörter und Sexuelles in Songtexten und Titeln erscheinen als „F***“."));
        int rightX = twoColumns ? left + columnWidth + COLUMN_GAP : left;
        int rightY = twoColumns ? y : leftY;
        rightY = row("Lyrics", rightX, rightY, new Toggle(control, () -> config.enabled, value -> config.enabled = value,
                "Alle Songtexte ein- oder ausblenden."));
        rightY = row("Hinter Bergen", rightX, rightY, new Toggle(control, () -> config.skyBehindTerrain,
                value -> config.skyBehindTerrain = value,
                "An: Berge, Bäume, Häuser und Wolken stehen vor der Schrift am Himmel. Aus: sie bleibt immer sichtbar."));
        rightY = row("Mitdrehen", rightX, rightY, new Toggle(control, () -> config.followAlong, value -> config.followAlong = value,
                "Die Songtexte drehen sich schnell mit, wenn du dich umschaust, statt stehen zu bleiben."));
        rightY = row("Vor dem Blick", rightX, rightY, new Toggle(control, () -> config.followView, value -> config.followView = value,
                "Neue Zeilen erscheinen dort, wo du gerade hinschaust."));
        rightY = row("Songtitel", rightX, rightY, new Toggle(control, () -> config.showTitleCard,
                value -> config.showTitleCard = value, "Titel und Künstler, wenn ein neuer Song beginnt."));
        rightY = row("Leiste", rightX, rightY, barChoice(control));
        y = Math.max(leftY, rightY) + 6;

        // Optional Spotify account, for music that does not play on this PC.
        spotifyHeaderY = y + 4;
        y += 16;
        int buttonWidth = 76;
        clientIdBox = new EditBox(font, left, y + 3, contentWidth - 2 * buttonWidth - 18, 10, Component.literal("Spotify-Client-ID"));
        clientIdBox.setBordered(false);
        clientIdBox.setTextColor(WHITE);
        clientIdBox.setTextColorUneditable(label());
        clientIdBox.setMaxLength(64);
        clientIdBox.setValue(config.spotifyClientId);
        clientIdBox.setHint(text("Client-ID deiner Spotify-App").copy().withColor(dim()));
        clientIdBox.setResponder(value -> {
            config.spotifyClientId = value.trim();
            updateSpotifyButton();
        });
        addRenderableWidget(clientIdBox);
        spotifyButton = addRenderableWidget(new OutlineButton(font, text, left + contentWidth - buttonWidth, y, buttonWidth,
                Component.empty(), false, () -> {
                    if (spotify.isConnected()) {
                        spotify.disconnect();
                    } else {
                        config.save();
                        spotify.connect();
                    }
                    updateSpotifyButton();
                }, this::glass, this::fancy));
        addRenderableWidget(new OutlineButton(font, text, left + contentWidth - 2 * buttonWidth - 6, y, buttonWidth,
                text("App anlegen"), false, () -> Browser.open(SpotifyAccount.DASHBOARD_URL), this::glass, this::fancy));
        return y + 16;
    }

    /** How the lyrics and the bar look: theme, type, colors, sizes, glow. Returns the hint line's y. */
    private int look(int left, int y, int contentWidth, int columnWidth, boolean twoColumns) {
        int wide = contentWidth - LABEL_WIDTH;
        y = row("Theme", left, y, new Choice(wide, SkyLyricsConfig.THEMES, THEME_NAMES, () -> config.theme, value -> {
            config.theme = value;
            rebuildPending = true;
        }, value -> "glass".equals(value)
                ? "Menü und Leiste aus Glas wie bei iOS 26: durchscheinend, heller Rand, weicher Schatten. Die Welt hinter "
                        + "dem Menü wird weichgezeichnet."
                : "Schlichtes Schwarz-Weiß, die Welt hinter dem Menü bleibt scharf."));
        y = row("Schrift", left, y, new Choice(wide, SkyLyricsConfig.FONTS, FONT_NAMES, () -> config.font,
                value -> config.font = value, value -> "Schrift der Songtexte und der Leiste."));
        y = row("Farben", left, y, new Choice(wide, SkyLyricsConfig.PALETTES, PALETTE_NAMES, () -> config.palette,
                value -> config.palette = value, value -> switch (value) {
                    case "standard" -> "Altrosa am Himmel, Weiß zu Lavendel um dich herum.";
                    case "glass" -> "Klares Weiß zu Eisblau, gesungene Wörter in Cyan bis Flieder.";
                    case "neon" -> "Leuchtendes Magenta und Cyan wie Neonröhren, am schönsten nachts.";
                    default -> "Farbverlauf der Schrift. Klicken für die nächste Farbe.";
                }));
        y += 6;
        int control = columnWidth - LABEL_WIDTH;
        int leftY = y;
        leftY = row("Deckkraft", left, leftY, new Slider(control, 0.1, 1.0, 0.05, config.opacity,
                value -> Math.round(value * 100) + " %", value -> config.opacity = (float) value,
                "Wie durchsichtig die Schrift ist."));
        leftY = row("Größe", left, leftY, new Slider(control, 0.3, 3.0, 0.05, config.scale,
                value -> Math.round(value * 100) + " %", value -> config.scale = (float) value, "Größe der Schrift."));
        leftY = row("Höhe", left, leftY, new Slider(control, 10, 70, 1, config.elevationDeg,
                value -> Math.round(value) + "°", value -> config.elevationDeg = (float) value,
                "Wie hoch die Zeilen am Himmel stehen. Im Stil 1:1 sitzen sie 10° tiefer, knapp über den Hügeln."));
        leftY = row("Leuchten", left, leftY, new Slider(control, 0, 2, 0.05, config.glow,
                value -> Math.round(value * 100) + " %", value -> config.glow = (float) value,
                "Leuchten um die Schrift bei Nacht, weicher Schatten bei Tag. Über 100 % kommt eine dunkle Kontur dazu."));
        leftY = row("Im Takt", left, leftY, new Toggle(control, () -> config.beatBounce, value -> config.beatBounce = value,
                "Bei jedem gesungenen Wort wackelt der Text kurz mit, bei langen Wörtern stärker."));
        int rightX = twoColumns ? left + columnWidth + COLUMN_GAP : left;
        int rightY = twoColumns ? y : leftY;
        rightY = row("Leiste", rightX, rightY, barChoice(control));
        rightY = row("Leistengröße", rightX, rightY, new Slider(control, 0.75, 1.5, 0.05, config.hudScale,
                value -> Math.round(value * 100) + " %", value -> config.hudScale = (float) value,
                "Wie groß die Now-Playing-Leiste ist."));
        rightY = row("Hintergrund", rightX, rightY, new Slider(control, 0, 1, 0.05, config.hudOpacity,
                value -> Math.round(value * 100) + " %", value -> config.hudOpacity = (float) value,
                "Wie dicht der Hintergrund der Leiste ist; bei 0 % bleibt nur der Rand, die Schrift bleibt immer gleich."));
        rightY = row("Rundung", rightX, rightY, new Slider(control, 0, 1, 0.05, config.hudRoundness,
                value -> Math.round(value * 100) + " %", value -> config.hudRoundness = (float) value,
                "Wie rund die Ecken der Leiste sind: 0 % eckig, 100 % eine Kapsel."));
        rightY = row("Menü", rightX, rightY, new Choice(control, SkyLyricsConfig.MENU_STYLES, MENU_STYLE_NAMES,
                () -> config.menuStyle, value -> {
                    config.menuStyle = value;
                    rebuildPending = true;
                }, value -> "fancy".equals(value)
                        ? "Prachtvoll: bewegter Hintergrund, Funken, alles gleitet, leuchtet und federt."
                        : "Minimal: ruhiges Menü mit schlichten Linien, ohne Animationen."));
        return Math.max(leftY, rightY) + 6;
    }

    /** Where lyrics and word times come from: lrclib.net and the unofficial services. Returns the hint line's y. */
    private int sources(int left, int y, int contentWidth) {
        sourcesNoteY = y;
        y += 2 * 11 + 2;
        int control = contentWidth - LABEL_WIDTH;
        y = row("lrclib.net", left, y, lrclibToggle(control));
        y = row("Grauzone", left, y, wordTimesToggle(control));
        for (String name : SkyLyricsConfig.WORD_SOURCES) {
            y = row("  " + WordSourceInfo.label(name), left, y, new Toggle(control, () -> config.wordSource(name), value -> {
                config.setWordSource(name, value);
                controller.refreshWordTimes();
            }, WordSourceInfo.hint(name)));
        }
        y = row("Hörgenau", left, y, new Toggle(control, () -> config.hoergenau,
                value -> LyricsInTheSkyClient.setHoergenau(config, value),
                WhisperEngine.supported()
                        ? "Für Songs ohne Wortzeiten: hört ab Songanfang mit (nur im Speicher) und timt danach die Wörter "
                        + "mit whisper.cpp, je Song einmal ca. 1–2 Min. im Hintergrund, einmalig 190 MB Download."
                        : "Hörgenau gibt es nur unter Windows."));
        y = row("  Teilen", left, y, new Toggle(control, () -> config.shareLearned, value -> config.shareLearned = value,
                "Gelerntes gilt für alle: geht anonym an henrik.click (Titel, Künstler, Album, Länge, Text); Songs, die "
                        + "andere gelernt haben, laufen hier sofort Wort für Wort."));
        return y + 6;
    }

    private Toggle lrclibToggle(int width) {
        return new Toggle(width, () -> Boolean.TRUE.equals(config.lrclibAllowed), value -> {
            config.lrclibAllowed = value;
            controller.retryLookups();
        }, "Songtexte von lrclib.net, offen und frei. Gesendet werden nur Titel, Künstler, Album und Länge des Songs.");
    }

    private Toggle wordTimesToggle(int width) {
        return new Toggle(width, config::wordTimes, value -> {
            config.setWordTimes(value);
            controller.refreshWordTimes();
        }, "Echte Wortzeiten von inoffiziellen Diensten (Grauzone): schaltet alle unter „Quellen“ ein oder aus. "
                + "Sie bekommen Titel und Künstler und können jederzeit wegfallen.");
    }

    private Choice barChoice(int width) {
        return new Choice(width, BAR_CHOICES, BAR_NAMES, () -> config.showHud ? config.hudStyle : "off", value -> {
            config.showHud = !"off".equals(value);
            if (config.showHud) {
                config.hudStyle = value;
            }
        }, value -> switch (value) {
            case "off" -> "Keine Leiste. Verschieben kannst du sie über den Knopf „Leiste“ oben.";
            case "karaoke" -> "Zwei Zeilen in einem Kasten, jedes Wort füllt sich beim Singen mit Farbe.";
            case "plain" -> "Nur die Wörter mit Schatten, ohne Kasten; das gesungene Wort in Farbe.";
            case "title" -> "Nur Songtitel und Künstler, ohne Songtext.";
            default -> "Die runde Leiste mit der aktuellen Zeile; das gesungene Wort ist hinterlegt.";
        });
    }

    /**
     * Timing of the song playing now only, on top of the timing for all songs. Shows the correction many players
     * agreed on until the player sets an own one.
     */
    private Slider songTiming(int width) {
        LyricsController.Song song = controller.currentSong();
        SongTimings timings = controller.timings();
        String hint;
        if (song == null) {
            hint = "Korrigiert nur den laufenden Song. Gerade läuft keiner mit Songtext.";
        } else {
            SongTimings.Shared common = timings.shared(song.id());
            hint = "Nur für „" + song.title() + "“."
                    + (common != null && common.offsetMs() != null && timings.own(song.id()).isEmpty()
                            ? " Gemeinsame Korrektur von " + common.players() + " Spielern." : "")
                    + (song.shareable() ? "" : " Dieser Text hat keine Zeiten, die Korrektur bleibt bei dir.");
        }
        Slider slider = new Slider(width, -3000, 3000, 50, song == null ? 0 : timings.offsetFor(song.id()),
                value -> song == null ? "–" : (value > 0 ? "+" : "") + Math.round(value) + " ms",
                value -> controller.setSongOffset(song, (int) Math.round(value)), hint);
        slider.active = song != null;
        return slider;
    }

    /** Adds a labelled option at {@code x}, {@code y}; returns the y of the next row. */
    private int row(String label, int x, int y, Hinted widget) {
        AbstractWidget asWidget = (AbstractWidget) widget;
        widget.setRow(labelled.size() + 1);
        asWidget.setPosition(x + LABEL_WIDTH, y);
        addRenderableWidget(asWidget);
        labelled.add(new Labelled(label, x, y, widget));
        return y + row;
    }

    private void updateSpotifyButton() {
        shownState = spotify.state();
        boolean connected = shownState == SpotifyAccount.State.CONNECTED;
        spotifyButton.setMessage(text(connected ? "Trennen"
                : shownState == SpotifyAccount.State.WAITING_FOR_BROWSER ? "Warte auf Browser …" : "Verbinden"));
        spotifyButton.active = connected || (!config.spotifyClientId.isBlank()
                && shownState != SpotifyAccount.State.WAITING_FOR_BROWSER);
        clientIdBox.setEditable(!connected);
    }

    @Override
    public void tick() {
        if (rebuildPending) {
            rebuildPending = false;
            rebuildWidgets();
        }
        // The login finishes in the browser; pick up the new state when it does.
        if (spotifyButton != null && spotify.state() != shownState) {
            updateSpotifyButton();
        }
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        scene.frame();
        if (fancy()) {
            scene.backdrop(graphics, width, height);
            scene.notes(graphics, font, text, width, height);
        }
        boolean opening = fancy() && scene.openTransform(graphics, (panelLeft + panelRight) / 2f, (panelTop + panelBottom) / 2f);
        if (glass()) {
            // Liquid Glass: the world behind softly blurred (Minecraft blurs only the whole screen, and only as strong
            // as the game's own menu blur setting), the panel a pane of glass floating on it.
            graphics.blurBeforeThisStratum();
            Glass.pane(graphics, panelLeft, panelTop, panelRight, panelBottom, 16, 1f, 1.35f);
        } else {
            // A plain black panel instead of the usual blur, so the lyrics behind stay visible while adjusting.
            graphics.fill(panelLeft, panelTop, panelRight, panelBottom, PANEL);
        }
        if (fancy()) {
            scene.rim(graphics, panelLeft, panelTop, panelRight, panelBottom, glass() ? 16 : 0);
        }
        if (opening) {
            graphics.pose().popMatrix();
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        boolean opening = fancy() && scene.openTransform(graphics, (panelLeft + panelRight) / 2f, (panelTop + panelBottom) / 2f);
        super.extractRenderState(graphics, mouseX, mouseY, a);
        int left = panelLeft + PANEL_PADDING;
        int right = panelRight - PANEL_PADDING;
        if (fancy()) {
            scene.title(graphics, font, text, "Lyrics in the Sky", left, titleY);
        } else {
            graphics.text(font, text("Lyrics in the Sky"), left, titleY, WHITE, false);
        }
        if (!glass()) {
            graphics.fill(left, titleY + 13, right, titleY + 14, LINE);
        }

        Hinted hovered = null;
        int index = 1;
        for (Labelled entry : labelled) {
            boolean hover = ((AbstractWidget) entry.widget()).isHoveredOrFocused();
            if (fancy()) {
                // Labels slide in with their row; the hovered one lights up in the accent color.
                float shown = scene.appear(index);
                int color = hover ? Fancy.mix(WHITE, Fancy.accent(Fancy.seconds() * 0.08f + index * 0.07f), 0.45f) : label();
                graphics.pose().pushMatrix();
                graphics.pose().translate((1f - shown) * 16f + (hover ? 1f : 0f), 0f);
                graphics.text(font, text(entry.label()), entry.x(), entry.y() + 3, Fancy.fade(color, shown), false);
                graphics.pose().popMatrix();
            } else {
                graphics.text(font, text(entry.label()), entry.x(), entry.y() + 3, hover ? WHITE : label(), false);
            }
            if (hover) {
                hovered = entry.widget();
            }
            index++;
        }
        extractHints(graphics, left, right, hovered);
        if (fancy()) {
            scene.sparks(graphics, font, text);
        }
        if (opening) {
            graphics.pose().popMatrix();
        }
    }

    /** The notes on the sources page, the Spotify block's line, and the hint or status line at the bottom. */
    private void extractHints(GuiGraphicsExtractor graphics, int left, int right, Hinted hovered) {

        if ("sources".equals(page)) {
            graphics.text(font, fit("lrclib.net ist offen und frei. Grauzone: inoffizielle Dienste ohne Konto,",
                    right - left), left, sourcesNoteY, label(), false);
            graphics.text(font, fit("mit echten Wortzeiten. Sie bekommen Titel und Künstler, können wegfallen.",
                    right - left), left, sourcesNoteY + 11, label(), false);
        }
        if (clientIdBox != null) {
            graphics.fill(left, spotifyHeaderY - 5, right, spotifyHeaderY - 4, LINE);
            graphics.text(font, fit("Spotify · optional, für Musik vom Handy & Co.", right - left), left, spotifyHeaderY,
                    label(), false);
            graphics.fill(clientIdBox.getX(), clientIdBox.getY() + 11, clientIdBox.getX() + clientIdBox.getWidth(),
                    clientIdBox.getY() + 12, clientIdBox.isFocused() ? WHITE : dim());
        }
        if (hovered != null) {
            // What the hovered option does, in up to two lines instead of a coloured tooltip; in the fancy look a new
            // hint rises into place.
            String hint = hovered.hint();
            float shown = 1f;
            if (fancy()) {
                if (!hint.equals(shownHint)) {
                    shownHint = hint;
                    hintChangedNanos = System.nanoTime();
                }
                shown = Fancy.easeOut((System.nanoTime() - hintChangedNanos) / 1e9f / 0.22f);
                graphics.pose().pushMatrix();
                graphics.pose().translate(0f, (1f - shown) * 5f);
            }
            int split = hintStart(hint, right - left);
            int color = Fancy.fade(WHITE, shown);
            graphics.text(font, fit(hint.substring(0, split).strip(), right - left), left, statusY, color, false);
            if (split < hint.length()) {
                graphics.text(font, fit(hint.substring(split), right - left), left, statusY + 10, color, false);
            }
            if (fancy()) {
                graphics.pose().popMatrix();
            }
            return;
        }
        shownHint = "";
        if ("general".equals(page)) {
            boolean connected = spotify.isConnected();
            String status = connected ? spotify.statusText()
                    : spotify.statusText() + " · Ohne Konto läuft Musik auf diesem PC automatisch.";
            graphics.text(font, fit(status, right - left), left, statusY, connected ? WHITE : dim(), false);
        } else if ("sources".equals(page)) {
            graphics.text(font, fit(controller.status(), right - left), left, statusY, dim(), false);
        } else {
            graphics.text(font, fit("Änderungen siehst du sofort, am Himmel und an der Leiste.", right - left), left,
                    statusY, dim(), false);
        }
    }

    /** Where the second line of a hint starts: after as many words as fit the first line. */
    private int hintStart(String hint, int maxWidth) {
        if (font.width(text(hint)) <= maxWidth) {
            return hint.length();
        }
        int end = 0;
        int next;
        while ((next = hint.indexOf(' ', end + 1)) > 0 && font.width(text(hint.substring(0, next))) <= maxWidth) {
            end = next;
        }
        return end == 0 ? hint.length() : end + 1;
    }

    private FormattedCharSequence fit(String value, int maxWidth) {
        Component component = text(value);
        if (font.width(component) <= maxWidth) {
            return component.getVisualOrderText();
        }
        List<FormattedCharSequence> lines = font.split(text(value), maxWidth - font.width(text("…")));
        return lines.isEmpty() ? component.getVisualOrderText() : FormattedCharSequence.composite(lines.get(0),
                text("…").getVisualOrderText());
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

    /** An option that explains itself in the hint line. */
    private interface Hinted {
        String hint();

        /** Its row from the top, so in the fancy look the rows slide in one after the other. */
        void setRow(int index);
    }

    /** Base of the options: no sprite, text in the menu's type, a click sound. */
    private abstract class Option extends AbstractButton implements Hinted {
        int rowIndex;
        /** How far the row has slid in while drawing, 0 to 1; always 1 in the minimal look. */
        float shown = 1f;
        /** Hover, eased in and out, for the fancy look. */
        float hover;

        Option(int width) {
            super(0, 0, width, row - 1, Component.empty());
        }

        @Override
        public void setRow(int index) {
            rowIndex = index;
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            defaultButtonNarrationText(output);
        }

        @Override
        protected final void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
            shown = fancy() ? scene.appear(rowIndex) : 1f;
            hover = fancy() ? Fancy.approach(hover, isHovered() ? 1f : 0f, scene.dt(), 12f) : 0f;
            boolean sliding = shown < 1f;
            if (sliding) {
                graphics.pose().pushMatrix();
                graphics.pose().translate((1f - shown) * 16f, 0f);
            }
            extractOption(graphics, mouseX, mouseY);
            if (sliding) {
                graphics.pose().popMatrix();
            }
        }

        abstract void extractOption(GuiGraphicsExtractor graphics, int mouseX, int mouseY);

        /** A color faded with the row while it slides in. */
        int c(int color) {
            return Fancy.fade(color, shown);
        }

        /** The accent color of this row, drifting slowly. */
        int accent() {
            return Fancy.accent(Fancy.seconds() * 0.08f + rowIndex * 0.07f);
        }

        void draw(GuiGraphicsExtractor graphics, String value, int x, int color) {
            graphics.text(font, text(value), x, getY() + 3, c(color), false);
        }

        int widthOf(String value) {
            return font.width(text(value));
        }
    }

    /**
     * One of several values, all in a row; the chosen one white and underlined. When they do not fit, only the
     * chosen value shows and a click moves on to the next.
     */
    private final class Choice extends Option {
        private static final int SPACING = 12;
        private final List<String> values;
        private final Map<String, String> names;
        private final Supplier<String> current;
        private final Consumer<String> set;
        private final java.util.function.Function<String, String> hints;

        Choice(int width, List<String> values, Map<String, String> names, Supplier<String> current, Consumer<String> set,
                java.util.function.Function<String, String> hints) {
            super(width);
            this.values = values;
            this.names = names;
            this.current = current;
            this.set = set;
            this.hints = hints;
            setMessage(Component.literal(names.getOrDefault(current.get(), current.get())));
        }

        private String name(String value) {
            return names.getOrDefault(value, value);
        }

        private boolean allFit() {
            int total = -SPACING;
            for (String value : values) {
                total += widthOf(name(value)) + SPACING;
            }
            return total <= getWidth();
        }

        /** The value under the mouse, or null. */
        private String at(double mouseX) {
            if (!allFit()) {
                return null;
            }
            int x = getX();
            for (String value : values) {
                int w = widthOf(name(value));
                if (mouseX >= x - SPACING / 2.0 && mouseX < x + w + SPACING / 2.0) {
                    return value;
                }
                x += w + SPACING;
            }
            return null;
        }

        private String hovered;
        /** Where the capsule under the chosen value is drawn in the fancy look, gliding to the chosen one. */
        private float capsuleX = Float.NaN;
        private float capsuleWidth;

        @Override
        public void onClick(MouseButtonEvent event, boolean doubleClick) {
            String picked = at(event.x());
            if (picked != null) {
                set.accept(picked);
            } else {
                next();
            }
            setMessage(Component.literal(name(current.get())));
            if (fancy()) {
                scene.burst((float) event.x(), getY() + 6f);
            }
        }

        @Override
        public void onPress(InputWithModifiers input) {
            // Keyboard: Enter or Space moves on to the next value.
            next();
            setMessage(Component.literal(name(current.get())));
        }

        private void next() {
            int index = values.indexOf(current.get());
            set.accept(values.get((index + 1) % values.size()));
        }

        @Override
        void extractOption(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
            hovered = isHovered() ? at(mouseX) : null;
            String chosen = current.get();
            if (!allFit()) {
                String label = "‹ " + (values.contains(chosen) ? name(chosen) : "Eigene") + " ›";
                draw(graphics, label, getX(), isHovered() ? WHITE : HOVER);
                return;
            }
            if (fancy()) {
                extractFancy(graphics, chosen);
                return;
            }
            int x = getX();
            for (String value : values) {
                String name = name(value);
                int w = widthOf(name);
                boolean selected = value.equals(chosen);
                int color = selected ? WHITE : value.equals(hovered) ? HOVER : dim();
                if (selected && glass()) {
                    // The chosen value sits in a small capsule of glass.
                    Glass.pane(graphics, x - 5, getY(), x + w + 5, getY() + Math.min(12, row), 6, 1f, 1.3f);
                }
                draw(graphics, name, x, color);
                if (selected && !glass()) {
                    // Right under the letters, which on the tightest rows is the last pixel of the row.
                    int underline = getY() + Math.min(12, row);
                    graphics.fill(x, underline, x + w, underline + 1, WHITE);
                }
                x += w + SPACING;
            }
        }

        /** A glowing capsule in the accent color glides to the chosen value; the value under the mouse lifts. */
        private void extractFancy(GuiGraphicsExtractor graphics, String chosen) {
            int x = getX();
            int chosenX = -1;
            int chosenWidth = 0;
            for (String value : values) {
                int w = widthOf(name(value));
                if (value.equals(chosen)) {
                    chosenX = x;
                    chosenWidth = w;
                }
                x += w + SPACING;
            }
            if (chosenX >= 0) {
                if (Float.isNaN(capsuleX)) {
                    capsuleX = chosenX;
                    capsuleWidth = chosenWidth;
                }
                capsuleX = Fancy.approach(capsuleX, chosenX, scene.dt(), 16f);
                capsuleWidth = Fancy.approach(capsuleWidth, chosenWidth, scene.dt(), 16f);
                int height = Math.min(12, row);
                int w = Math.round(capsuleWidth) + 12;
                int color = accent();
                graphics.pose().pushMatrix();
                graphics.pose().translate(capsuleX - 6f, getY());
                Fancy.glow(graphics, 0, 0, w, height, 6, 2, shown * (0.6f + 0.4f * hover), color);
                Glass.fill(graphics, 0, 0, w, height, 6, c(ARGB.color(0.42f, color)));
                Glass.rim(graphics, 0, 0, w, height, 6, shown * 0.9f);
                graphics.pose().popMatrix();
            }
            x = getX();
            for (String value : values) {
                String name = name(value);
                boolean selected = value.equals(chosen);
                boolean lifted = value.equals(hovered) && !selected;
                if (lifted) {
                    graphics.pose().pushMatrix();
                    graphics.pose().translate(0f, -1f);
                }
                draw(graphics, name, x, selected || lifted ? WHITE : dim());
                if (lifted) {
                    graphics.pose().popMatrix();
                }
                x += widthOf(name) + SPACING;
            }
        }

        @Override
        public String hint() {
            return hints.apply(hovered != null ? hovered : current.get());
        }
    }

    /** On or off: a small switch, filled white when on, and the word next to it. */
    private final class Toggle extends Option {
        private final BooleanSupplier current;
        private final Consumer<Boolean> set;
        private final String hint;

        Toggle(int width, BooleanSupplier current, Consumer<Boolean> set, String hint) {
            super(width);
            this.current = current;
            this.set = set;
            this.hint = hint;
            setMessage(Component.literal(current.getAsBoolean() ? "An" : "Aus"));
        }

        /** Where the knob is in the fancy look, 0 (off) to 1 (on), gliding after each switch. */
        private float knob = Float.NaN;

        @Override
        public void onPress(InputWithModifiers input) {
            set.accept(!current.getAsBoolean());
            setMessage(Component.literal(current.getAsBoolean() ? "An" : "Aus"));
            if (fancy()) {
                scene.burst(getX() + (current.getAsBoolean() ? 14f : 5f), getY() + 7f);
            }
        }

        @Override
        void extractOption(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
            boolean on = current.getAsBoolean();
            int x = getX();
            int y = getY() + 3;
            if (fancy()) {
                extractFancy(graphics, on, x, y - 1);
                return;
            }
            if (glass()) {
                // An iOS-like switch: a capsule, tinted when on, with a round white knob.
                int top = y - 1;
                if (on) {
                    Glass.fill(graphics, x, top, x + 18, top + 10, 5, ARGB.color(0.9f, ON_TINT));
                    Glass.rim(graphics, x, top, x + 18, top + 10, 5, 0.9f);
                } else {
                    Glass.pane(graphics, x, top, x + 18, top + 10, 5, 1f, isHovered() ? 1.4f : 1f);
                }
                int knob = on ? x + 9 : x + 1;
                Glass.fill(graphics, knob, top + 1, knob + 8, top + 9, 4, 0xFFFFFFFF);
                draw(graphics, on ? "An" : "Aus", x + 24, on ? WHITE : isHovered() ? HOVER : dim());
                return;
            }
            int frame = on ? WHITE : isHovered() ? HOVER : dim();
            graphics.outline(x, y, 16, 8, frame);
            if (on) {
                graphics.fill(x + 8, y + 1, x + 15, y + 7, WHITE);
            } else {
                graphics.fill(x + 2, y + 2, x + 6, y + 6, frame);
            }
            draw(graphics, on ? "An" : "Aus", x + 22, on ? WHITE : isHovered() ? HOVER : dim());
        }

        /** A glowing capsule that fills with the accent color; the knob glides over and stretches on the way. */
        private void extractFancy(GuiGraphicsExtractor graphics, boolean on, int x, int top) {
            if (Float.isNaN(knob)) {
                knob = on ? 1f : 0f;
            }
            knob = Fancy.approach(knob, on ? 1f : 0f, scene.dt(), 15f);
            int accent = accent();
            int track = Fancy.mix(0xFF3A4152, accent, knob);
            float glow = Math.max(knob, 0.6f * hover);
            if (glow > 0.03f) {
                Fancy.glow(graphics, x, top, x + 18, top + 10, 5, 3, shown * glow, accent);
            }
            Glass.fill(graphics, x, top, x + 18, top + 10, 5, c(ARGB.color(0.5f + 0.45f * knob, track)));
            Glass.rim(graphics, x, top, x + 18, top + 10, 5, shown * 0.85f);
            float stretch = (1f - Math.abs(2f * knob - 1f)) * 3f;
            graphics.pose().pushMatrix();
            graphics.pose().translate(x + 1 + knob * (9f - stretch), 0f);
            Glass.fill(graphics, 0, top + 1, Math.round(8 + stretch), top + 9, 4, c(0xFFFFFFFF));
            graphics.pose().popMatrix();
            draw(graphics, on ? "An" : "Aus", x + 24, on ? WHITE : isHovered() ? HOVER : dim());
        }

        @Override
        public String hint() {
            return hint;
        }
    }

    /** A thin line with a white part up to the value and a short knob; the value in white at the end. */
    private final class Slider extends AbstractWidget implements Hinted {
        private static final int VALUE_WIDTH = 46;
        private final double min;
        private final double max;
        private final double step;
        private final DoubleFunction<String> label;
        private final DoubleConsumer apply;
        private final String hint;
        private double value;
        private int rowIndex;
        /** In the fancy look: the value the knob shows, gliding after the real one, the hover and the last change. */
        private float shownValue = Float.NaN;
        private float hover;
        private long changedNanos;

        Slider(int width, double min, double max, double step, double current, DoubleFunction<String> label,
                DoubleConsumer apply, String hint) {
            super(0, 0, width, row - 1, Component.empty());
            this.min = min;
            this.max = max;
            this.step = step;
            this.label = label;
            this.apply = apply;
            this.hint = hint;
            this.value = Math.max(min, Math.min(max, current));
            setMessage(Component.literal(label.apply(value)));
        }

        private int trackWidth() {
            return getWidth() - VALUE_WIDTH;
        }

        @Override
        public void setRow(int index) {
            rowIndex = index;
        }

        private void setFromMouse(double mouseX) {
            double u = Math.max(0, Math.min(1, (mouseX - getX()) / trackWidth()));
            double stepped = Math.round((min + u * (max - min)) / step) * step;
            double before = value;
            value = Math.max(min, Math.min(max, stepped));
            if (value != before) {
                changedNanos = System.nanoTime();
            }
            apply.accept(value);
            setMessage(Component.literal(label.apply(value)));
        }

        @Override
        public void onClick(MouseButtonEvent event, boolean doubleClick) {
            setFromMouse(event.x());
        }

        @Override
        protected void onDrag(MouseButtonEvent event, double dragX, double dragY) {
            setFromMouse(event.x());
        }

        @Override
        protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
            if (fancy()) {
                float shown = scene.appear(rowIndex);
                graphics.pose().pushMatrix();
                graphics.pose().translate((1f - shown) * 16f, 0f);
                extractFancy(graphics, shown);
                graphics.pose().popMatrix();
                return;
            }
            int x = getX();
            int lineY = getY() + 7;
            int track = trackWidth() - 8;
            int knob = x + (int) Math.round((value - min) / (max - min) * track);
            if (glass()) {
                // A thin glass groove, filled white up to a round glass knob.
                Glass.fill(graphics, x, lineY - 1, x + track + 2, lineY + 2, 1, ARGB.color(0.30f, 0xFFFFFF));
                Glass.fill(graphics, x, lineY - 1, knob + 1, lineY + 2, 1, isHovered() ? WHITE : HOVER);
                Glass.fill(graphics, knob - 3, lineY - 3, knob + 5, lineY + 5, 4, ARGB.color(0.25f, 0x000000));
                Glass.fill(graphics, knob - 3, lineY - 4, knob + 5, lineY + 4, 4, 0xFFFFFFFF);
                graphics.text(font, text(label.apply(value)), x + trackWidth(), getY() + 3, WHITE, false);
                return;
            }
            graphics.fill(x, lineY, x + track, lineY + 1, dim());
            graphics.fill(x, lineY, knob, lineY + 1, isHovered() ? WHITE : HOVER);
            graphics.fill(knob, lineY - 3, knob + 2, lineY + 4, WHITE);
            graphics.text(font, text(label.apply(value)), x + trackWidth(), getY() + 3, WHITE, false);
        }

        /**
         * A groove filled with a gradient through the accent colors up to a round knob that glides after the value,
         * glows and grows under the mouse; the value pops a little when it changes.
         */
        private void extractFancy(GuiGraphicsExtractor graphics, float shown) {
            if (Float.isNaN(shownValue)) {
                shownValue = (float) value;
            }
            shownValue = Fancy.approach(shownValue, (float) value, scene.dt(), 20f);
            hover = Fancy.approach(hover, isHovered() ? 1f : 0f, scene.dt(), 12f);
            int x = getX();
            int lineY = getY() + 7;
            int track = trackWidth() - 8;
            float knob = x + (float) ((shownValue - min) / (max - min)) * track;
            float t = Fancy.seconds();
            float phase = t * 0.08f + rowIndex * 0.07f;
            Glass.fill(graphics, x, lineY - 1, x + track + 2, lineY + 2, 1, Fancy.fade(ARGB.color(0.28f, 0xFFFFFF), shown));
            for (int sx = x; sx < knob; sx += 3) {
                int color = Fancy.accent(phase + (sx - x) / (float) Math.max(1, track) * 0.5f);
                graphics.fill(sx, lineY - 1, Math.min(sx + 3, Math.round(knob) + 1), lineY + 2,
                        Fancy.fade(ARGB.color(0.95f, color), shown));
            }
            int accent = Fancy.accent(phase + (knob - x) / Math.max(1, track) * 0.5f);
            graphics.pose().pushMatrix();
            graphics.pose().translate(knob + 1f, lineY);
            Fancy.glow(graphics, -4, -4, 4, 4, 4, 3, shown * (0.35f + 0.65f * hover), accent);
            float grow = 1f + 0.25f * hover;
            graphics.pose().scale(grow, grow);
            Glass.fill(graphics, -4, -4, 4, 4, 4, Fancy.fade(0xFFFFFFFF, shown));
            graphics.pose().popMatrix();
            float pop = 1f + 0.2f * (1f - Fancy.clamp01((System.nanoTime() - changedNanos) / 1e9f / 0.25f));
            int textX = x + trackWidth();
            graphics.pose().pushMatrix();
            graphics.pose().translate(textX, getY() + 7f);
            graphics.pose().scale(pop, pop);
            graphics.text(font, text(label.apply(value)), 0, -4, Fancy.fade(Fancy.mix(WHITE, accent, 0.5f * (pop - 1f) / 0.2f),
                    shown), false);
            graphics.pose().popMatrix();
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            defaultButtonNarrationText(output);
        }

        @Override
        public String hint() {
            return hint;
        }
    }

}
