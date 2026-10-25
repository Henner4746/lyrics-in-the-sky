package de.henner.lyricsinthesky.gui;

import de.henner.lyricsinthesky.LyricsController;
import de.henner.lyricsinthesky.SkyLyricsConfig;
import de.henner.lyricsinthesky.spotify.SpotifyAccount;
import de.henner.lyricsinthesky.util.Browser;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleFunction;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Settings, opened with Right Shift. Changes apply immediately, so the lyrics in the sky behind the menu show
 * the result while adjusting; the file is saved when the screen closes.
 */
public final class SettingsScreen extends Screen {
    private static final int ROW = 22;
    private static final int GAP = 6;
    private static final int PANEL_COLOR = 0xE00E0F1A;
    private static final int TEXT = 0xFFEAF4FF;
    private static final int MUTED = 0xFF9AA0B4;
    private static final int ACCENT = 0xFF7CF7E4;
    private static final Map<String, String> PALETTE_NAMES = Map.of(
            "standard", "Standard", "rose", "Altrosa", "lavender", "Lavendel", "auto", "Je Song anders",
            "aurora", "Polarlicht", "sunset", "Sonnenuntergang", "gold", "Gold", "sakura", "Kirschblüte",
            "ocean", "Ozean", "white", "Weiß");
    private static final Map<String, String> STYLE_NAMES = Map.of(
            "elegant", "Elegant", "flow", "Stapel", "classic", "Verspielt");
    private static final Map<String, String> FONT_NAMES = Map.of(
            "sans", "Modern", "script", "Handschrift", "minecraft", "Minecraft");

    private final Screen parent;
    private final LyricsController controller;
    private final SkyLyricsConfig config;
    private final SpotifyAccount spotify;
    private int panelLeft;
    private int panelTop;
    private int panelRight;
    private int panelBottom;
    private int spotifyHeaderY;
    private int spotifyStatusY;
    private Button spotifyButton;
    private EditBox clientIdBox;
    private SpotifyAccount.State shownState;

    public SettingsScreen(Screen parent, LyricsController controller, SpotifyAccount spotify) {
        super(Component.literal("Lyrics in the Sky"));
        this.parent = parent;
        this.controller = controller;
        this.config = controller.config();
        this.spotify = spotify;
    }

    @Override
    protected void init() {
        int columns = width >= 380 ? 3 : 2;
        int columnWidth = Math.min(150, (width - 40 - (columns - 1) * GAP) / columns);
        int contentWidth = columns * columnWidth + (columns - 1) * GAP;
        int left = (width - contentWidth) / 2;
        int top = 28;

        List<AbstractWidget> options = new ArrayList<>();
        options.add(CycleButton.onOffBuilder(config.enabled)
                .create(0, 0, columnWidth, 20, Component.literal("Lyrics"), (button, value) -> config.enabled = value));
        options.add(CycleButton.<String>builder(mode -> Component.literal("sky".equals(mode) ? "Am Himmel" : "Um dich herum"), config.mode)
                .withValues(SkyLyricsConfig.MODES)
                .withTooltip(mode -> Tooltip.create(Component.literal("sky".equals(mode)
                        ? "Weit hinten am Himmel, wie die Sonne: Berge, Bäume, Häuser und Wolken stehen davor."
                        : "Um dich herum: jedes Wort schwebt einzeln an einem festen Platz in deiner Nähe.")))
                .create(0, 0, columnWidth, 20, Component.literal("Anzeige"), (button, value) -> config.mode = value));
        options.add(CycleButton.<String>builder(style -> Component.literal(STYLE_NAMES.getOrDefault(style, style)), config.style)
                .withValues(SkyLyricsConfig.STYLES)
                .withTooltip(style -> Tooltip.create(Component.literal(switch (style) {
                    case "elegant" -> "Am Himmel bauen sich die Zeilen Wort für Wort auf, um dich herum schweben "
                            + "einzelne Wörter.";
                    case "flow" -> "Die aktuelle Zeile in der Mitte, die nächste schwach darunter; alles gleitet weich nach oben.";
                    default -> "Jede Zeile erscheint an einer anderen Stelle, mit wechselnden Animationen und Funkeln.";
                })))
                .create(0, 0, columnWidth, 20, Component.literal("Stil"), (button, value) -> config.style = value));
        options.add(CycleButton.<String>builder(name -> Component.literal(PALETTE_NAMES.getOrDefault(name, name)), config.palette)
                .withValues(SkyLyricsConfig.PALETTES)
                .withTooltip(name -> "standard".equals(name)
                        ? Tooltip.create(Component.literal("Altrosa am Himmel, Weiß zu Lavendel um dich herum."))
                        : null)
                .create(0, 0, columnWidth, 20, Component.literal("Farben"), (button, value) -> config.palette = value));
        options.add(CycleButton.<String>builder(font -> Component.literal(FONT_NAMES.getOrDefault(font, font)), config.font)
                .withValues(SkyLyricsConfig.FONTS)
                .create(0, 0, columnWidth, 20, Component.literal("Schrift"), (button, value) -> config.font = value));
        options.add(new Slider(columnWidth, 0.1, 1.0, 0.05, config.opacity,
                value -> Component.literal("Deckkraft: " + Math.round(value * 100) + " %"),
                value -> config.opacity = (float) value));
        options.add(new Slider(columnWidth, 0.3, 3.0, 0.05, config.scale,
                value -> Component.literal("Größe: " + Math.round(value * 100) + " %"),
                value -> config.scale = (float) value));
        options.add(new Slider(columnWidth, 10, 70, 1, config.elevationDeg,
                value -> Component.literal("Höhe am Himmel: " + Math.round(value) + "°"),
                value -> config.elevationDeg = (float) value));
        options.add(new Slider(columnWidth, -2000, 2000, 50, config.offsetMs,
                value -> Component.literal("Timing: " + (value > 0 ? "+" : "") + Math.round(value) + " ms"),
                value -> config.offsetMs = Math.round(value)));
        options.add(CycleButton.onOffBuilder(config.followView)
                .withTooltip(value -> Tooltip.create(Component.literal("Neue Zeilen erscheinen dort, wo du gerade hinschaust.")))
                .create(0, 0, columnWidth, 20, Component.literal("Vor deinem Blick"), (button, value) -> config.followView = value));
        options.add(CycleButton.onOffBuilder(config.showTitleCard)
                .create(0, 0, columnWidth, 20, Component.literal("Songtitel"), (button, value) -> config.showTitleCard = value));
        options.add(CycleButton.onOffBuilder(config.showHud)
                .create(0, 0, columnWidth, 20, Component.literal("Anzeige oben"), (button, value) -> config.showHud = value));
        options.add(CycleButton.onOffBuilder(Boolean.TRUE.equals(config.lrclibAllowed))
                .withTooltip(value -> Tooltip.create(Component.literal(
                        "Songtexte von lrclib.net. Gesendet werden nur Titel, Künstler, Album und Länge.")))
                .create(0, 0, columnWidth, 20, Component.literal("Songtexte"), (button, value) -> {
                    config.lrclibAllowed = value;
                    controller.retryLookups();
                }));

        int y = top;
        for (int i = 0; i < options.size(); i++) {
            AbstractWidget widget = options.get(i);
            widget.setPosition(left + (i % columns) * (columnWidth + GAP), y);
            addRenderableWidget(widget);
            if (i % columns == columns - 1) {
                y += ROW;
            }
        }
        if (options.size() % columns != 0) {
            y += ROW;
        }

        // Optional Spotify account, for music that does not play on this PC.
        y += 6;
        spotifyHeaderY = y;
        y += 12;
        int boxWidth = contentWidth - columnWidth - GAP;
        clientIdBox = new EditBox(font, left, y, boxWidth, 20, Component.literal("Spotify-Client-ID"));
        clientIdBox.setMaxLength(64);
        clientIdBox.setValue(config.spotifyClientId);
        clientIdBox.setHint(Component.literal("Client-ID deiner Spotify-App").withColor(MUTED));
        clientIdBox.setResponder(value -> {
            config.spotifyClientId = value.trim();
            updateSpotifyButton();
        });
        addRenderableWidget(clientIdBox);
        spotifyButton = addRenderableWidget(Button.builder(Component.empty(), button -> {
                    if (spotify.isConnected()) {
                        spotify.disconnect();
                    } else {
                        config.save();
                        spotify.connect();
                    }
                    updateSpotifyButton();
                })
                .bounds(left + boxWidth + GAP, y, columnWidth, 20)
                .build());
        y += ROW;
        spotifyStatusY = y + 2;
        y += 26;

        int buttonWidth = (contentWidth - 2 * GAP) / 3;
        addRenderableWidget(Button.builder(Component.literal("Spotify-App anlegen …"), button -> Browser.open(SpotifyAccount.DASHBOARD_URL))
                .tooltip(Tooltip.create(Component.literal("Öffnet developer.spotify.com. Lege eine App an, trage als Redirect-URI "
                        + SpotifyAccount.REDIRECT_URI + " ein, wähle „Web API“ und kopiere die Client-ID hierher.")))
                .bounds(left, y, buttonWidth, 20)
                .build());
        addRenderableWidget(Button.builder(Component.literal("Demo abspielen"), button -> {
                    controller.startDemo();
                    onClose();
                })
                .bounds(left + buttonWidth + GAP, y, buttonWidth, 20)
                .build());
        addRenderableWidget(Button.builder(Component.literal("Fertig"), button -> onClose())
                .bounds(left + 2 * (buttonWidth + GAP), y, buttonWidth, 20)
                .build());

        panelLeft = left - 10;
        panelRight = left + contentWidth + 10;
        panelTop = 8;
        panelBottom = y + 20 + 8;
        updateSpotifyButton();
    }

    private void updateSpotifyButton() {
        shownState = spotify.state();
        boolean connected = shownState == SpotifyAccount.State.CONNECTED;
        spotifyButton.setMessage(Component.literal(connected ? "Spotify trennen"
                : shownState == SpotifyAccount.State.WAITING_FOR_BROWSER ? "Warte auf Browser …" : "Mit Spotify verbinden"));
        spotifyButton.active = connected || (!config.spotifyClientId.isBlank()
                && shownState != SpotifyAccount.State.WAITING_FOR_BROWSER);
        clientIdBox.setEditable(!connected);
    }

    @Override
    public void tick() {
        // The login finishes in the browser; pick up the new state when it does.
        if (spotify.state() != shownState) {
            updateSpotifyButton();
        }
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        // A panel instead of the usual blur, so the lyrics behind stay visible while adjusting.
        graphics.fill(panelLeft, panelTop, panelRight, panelBottom, PANEL_COLOR);
        graphics.fill(panelLeft, panelTop, panelRight, panelTop + 1, 0x30FFFFFF);
        graphics.fill(panelLeft, panelBottom - 1, panelRight, panelBottom, 0x30FFFFFF);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        super.extractRenderState(graphics, mouseX, mouseY, a);
        graphics.centeredText(font, title, width / 2, 13, ACCENT);
        int left = panelLeft + 10;
        int maxWidth = panelRight - panelLeft - 20;
        graphics.text(font, fit("Spotify-Konto – optional, für Musik vom Handy & Co.", maxWidth), left, spotifyHeaderY, TEXT);
        boolean connected = spotify.isConnected();
        graphics.text(font, fit(spotify.statusText(), maxWidth), left, spotifyStatusY, connected ? ACCENT : MUTED);
        graphics.text(font, fit("Ohne Konto läuft Musik auf diesem PC automatisch.", maxWidth), left, spotifyStatusY + 11, MUTED);
    }

    private String fit(String text, int maxWidth) {
        return font.width(text) <= maxWidth ? text : font.plainSubstrByWidth(text, maxWidth - font.width("…")) + "…";
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

    /** Slider over a value range with a fixed step. */
    private static final class Slider extends AbstractSliderButton {
        private final double min;
        private final double max;
        private final double step;
        private final DoubleFunction<Component> label;
        private final DoubleConsumer apply;

        Slider(int width, double min, double max, double step, double current, DoubleFunction<Component> label,
                DoubleConsumer apply) {
            super(0, 0, width, 20, Component.empty(), (Math.max(min, Math.min(max, current)) - min) / (max - min));
            this.min = min;
            this.max = max;
            this.step = step;
            this.label = label;
            this.apply = apply;
            updateMessage();
        }

        private double current() {
            double raw = min + value * (max - min);
            return Math.max(min, Math.min(max, Math.round(raw / step) * step));
        }

        @Override
        protected void updateMessage() {
            setMessage(label.apply(current()));
        }

        @Override
        protected void applyValue() {
            apply.accept(current());
        }
    }
}
