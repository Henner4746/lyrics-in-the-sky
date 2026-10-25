package de.henner.lyricsinthesky.gui;

import de.henner.lyricsinthesky.LyricsController;
import de.henner.lyricsinthesky.SkyLyricsConfig;
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
    private static final Map<String, String> PALETTE_NAMES = Map.of(
            "standard", "Standard", "rose", "Altrosa", "lavender", "Lavendel", "auto", "Je Song anders",
            "aurora", "Polarlicht", "sunset", "Sonnenuntergang", "gold", "Gold", "sakura", "Kirschblüte",
            "ocean", "Ozean", "white", "Weiß");
    private static final Map<String, String> STYLE_NAMES = Map.of(
            "elegant", "Elegant", "natural", "Natürlich 1:1", "flow", "Stapel", "classic", "Verspielt");
    private static final Map<String, String> FONT_NAMES = Map.of(
            "sans", "Modern", "script", "Handschrift", "minecraft", "Minecraft");
    private static final Map<String, String> MODE_NAMES = Map.of("sky", "Am Himmel", "around", "Um dich herum");
    private static final Map<String, String> PRESET_NAMES = Map.of(
            "standard", "Standard", "sky", "Himmel 1:1", "around", "Um dich 1:1", "party", "Party", "calm", "Ruhig");
    /** The bar's looks, and "off" for no bar. */
    private static final List<String> BAR_CHOICES = List.of("off", "pill", "karaoke", "plain", "title");
    private static final Map<String, String> BAR_NAMES = Map.of("off", "Aus", "pill", "Pille", "karaoke", "Karaoke",
            "plain", "Schlicht", "title", "Nur Titel");

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
    private int spotifyStatusY;
    private int row = ROW;
    /** A preset was picked: every option shows new values, so the widgets are built again on the next tick. */
    private boolean rebuildPending;
    private OutlineButton spotifyButton;
    private EditBox clientIdBox;
    private SpotifyAccount.State shownState;

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
        int contentWidth = Math.min(380, width - 2 * PANEL_PADDING - 8);
        boolean twoColumns = contentWidth >= 330;
        int left = (width - contentWidth) / 2;
        int columnWidth = twoColumns ? (contentWidth - COLUMN_GAP) / 2 : contentWidth;
        // Title, four choices, the switches and sliders, the Spotify block and the hint line must fit the screen;
        // on short screens the rows move closer together.
        int optionRows = 5 + (twoColumns ? 8 : 16);
        int fixed = 10 + 18 + 16 + 16 + 16 + 12 + 6;
        row = fixed + optionRows * ROW <= height ? ROW : fixed + optionRows * COMPACT_ROW <= height ? COMPACT_ROW : TIGHT_ROW;
        int y = 10;
        titleY = y;
        int small = 46;
        addRenderableWidget(new OutlineButton(font, text, left + contentWidth - small, y - 3, small, text("Fertig"), true,
                this::onClose));
        addRenderableWidget(new OutlineButton(font, text, left + contentWidth - 2 * small - 6, y - 3, small, text("Demo"),
                false, () -> {
                    controller.startDemo();
                    onClose();
                }));
        addRenderableWidget(new OutlineButton(font, text, left + contentWidth - 3 * small - 12, y - 3, small, text("Leiste"),
                false, () -> {
                    config.save();
                    minecraft.gui.setScreen(new HudEditScreen(this, config));
                }));
        y += 18;

        // Choices across the full width: a whole look at once, then what is shown, how, and in which colors and type.
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
        y = row("Schrift", left, y, new Choice(wide, SkyLyricsConfig.FONTS, FONT_NAMES, () -> config.font,
                value -> config.font = value, value -> "Schrift der Songtexte und der Leiste."));
        y = row("Farben", left, y, new Choice(wide, SkyLyricsConfig.PALETTES, PALETTE_NAMES, () -> config.palette,
                value -> config.palette = value, value -> "standard".equals(value)
                        ? "Altrosa am Himmel, Weiß zu Lavendel um dich herum."
                        : "Farbverlauf der Schrift. Klicken für die nächste Farbe."));
        y += 6;

        // Sliders and switches, in two columns where there is room, else one below the other.
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
        leftY = row("Timing", left, leftY, new Slider(control, -2000, 2000, 50, config.offsetMs,
                value -> (value > 0 ? "+" : "") + Math.round(value) + " ms", value -> config.offsetMs = Math.round(value),
                "Verschiebt alle Songtexte, falls dein Kopfhörer verzögert. Positiv zeigt sie früher."));
        leftY = row("Dieser Song", left, leftY, songTiming(control));
        leftY = row("Teilen", left, leftY, new Toggle(control, () -> config.shareTiming, value -> config.shareTiming = value,
                "Teilt Song-Korrekturen anonym mit henrik.click; ab 10 gleichen gelten sie für alle Spieler."));
        leftY = row("Songtexte", left, leftY, new Toggle(control, () -> Boolean.TRUE.equals(config.lrclibAllowed), value -> {
            config.lrclibAllowed = value;
            controller.retryLookups();
        }, "Songtexte von lrclib.net. Gesendet werden nur Titel, Künstler, Album und Länge des Songs."));
        leftY = row("Wortgenau", left, leftY, new Toggle(control, () -> config.lyricsPlus, value -> {
            config.lyricsPlus = value;
            controller.refreshWordTimes();
        }, "Wortzeiten aus Apple/QQ Music über LyricsPlus (inoffiziell). Sendet Titel, Künstler, Album, Länge."));
        int rightX = twoColumns ? left + columnWidth + COLUMN_GAP : left;
        int rightY = twoColumns ? y : leftY;
        rightY = row("Lyrics", rightX, rightY, new Toggle(control, () -> config.enabled, value -> config.enabled = value,
                "Alle Songtexte ein- oder ausblenden."));
        rightY = row("Hinter Bergen", rightX, rightY, new Toggle(control, () -> config.skyBehindTerrain,
                value -> config.skyBehindTerrain = value,
                "An: Berge, Bäume, Häuser und Wolken stehen vor der Schrift am Himmel. Aus: sie bleibt immer sichtbar."));
        rightY = row("Im Takt", rightX, rightY, new Toggle(control, () -> config.beatBounce, value -> config.beatBounce = value,
                "Bei jedem gesungenen Wort wackelt der Text kurz mit, bei langen Wörtern stärker."));
        rightY = row("Mitdrehen", rightX, rightY, new Toggle(control, () -> config.followAlong, value -> config.followAlong = value,
                "Die Songtexte drehen sich schnell mit, wenn du dich umschaust, statt stehen zu bleiben."));
        rightY = row("Vor dem Blick", rightX, rightY, new Toggle(control, () -> config.followView, value -> config.followView = value,
                "Neue Zeilen erscheinen dort, wo du gerade hinschaust."));
        rightY = row("Songtitel", rightX, rightY, new Toggle(control, () -> config.showTitleCard,
                value -> config.showTitleCard = value, "Titel und Künstler, wenn ein neuer Song beginnt."));
        rightY = row("Leiste", rightX, rightY, new Choice(control, BAR_CHOICES, BAR_NAMES,
                () -> config.showHud ? config.hudStyle : "off", value -> {
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
                }));
        y = Math.max(leftY, rightY) + 6;

        // Optional Spotify account, for music that does not play on this PC.
        spotifyHeaderY = y + 4;
        y += 16;
        int buttonWidth = 76;
        clientIdBox = new EditBox(font, left, y + 3, contentWidth - 2 * buttonWidth - 18, 10, Component.literal("Spotify-Client-ID"));
        clientIdBox.setBordered(false);
        clientIdBox.setTextColor(WHITE);
        clientIdBox.setTextColorUneditable(LABEL);
        clientIdBox.setMaxLength(64);
        clientIdBox.setValue(config.spotifyClientId);
        clientIdBox.setHint(text("Client-ID deiner Spotify-App").copy().withColor(DIM));
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
                }));
        addRenderableWidget(new OutlineButton(font, text, left + contentWidth - 2 * buttonWidth - 6, y, buttonWidth,
                text("App anlegen"), false, () -> Browser.open(SpotifyAccount.DASHBOARD_URL)));
        y += 16;
        // One line for the Spotify status, or for what the hovered option does while one is hovered.
        spotifyStatusY = y;
        y += 12;

        panelLeft = left - PANEL_PADDING;
        panelRight = left + contentWidth + PANEL_PADDING;
        panelTop = 2;
        panelBottom = y + 6;
        updateSpotifyButton();
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
        if (spotify.state() != shownState) {
            updateSpotifyButton();
        }
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        // A plain black panel instead of the usual blur, so the lyrics behind stay visible while adjusting.
        graphics.fill(panelLeft, panelTop, panelRight, panelBottom, PANEL);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        super.extractRenderState(graphics, mouseX, mouseY, a);
        int left = panelLeft + PANEL_PADDING;
        int right = panelRight - PANEL_PADDING;
        graphics.text(font, text("Lyrics in the Sky"), left, titleY, WHITE, false);
        graphics.fill(left, titleY + 13, right, titleY + 14, LINE);

        Hinted hovered = null;
        for (Labelled entry : labelled) {
            boolean hover = ((AbstractWidget) entry.widget()).isHoveredOrFocused();
            graphics.text(font, text(entry.label()), entry.x(), entry.y() + 3, hover ? WHITE : LABEL, false);
            if (hover) {
                hovered = entry.widget();
            }
        }

        graphics.fill(left, spotifyHeaderY - 5, right, spotifyHeaderY - 4, LINE);
        graphics.text(font, fit("Spotify · optional, für Musik vom Handy & Co.", right - left), left, spotifyHeaderY, LABEL, false);
        graphics.fill(clientIdBox.getX(), clientIdBox.getY() + 11, clientIdBox.getX() + clientIdBox.getWidth(),
                clientIdBox.getY() + 12, clientIdBox.isFocused() ? WHITE : DIM);
        if (hovered != null) {
            // What the hovered option does, in one line instead of a coloured tooltip.
            graphics.text(font, fit(hovered.hint(), right - left), left, spotifyStatusY, WHITE, false);
        } else {
            boolean connected = spotify.isConnected();
            String status = connected ? spotify.statusText()
                    : spotify.statusText() + " · Ohne Konto läuft Musik auf diesem PC automatisch.";
            graphics.text(font, fit(status, right - left), left, spotifyStatusY, connected ? WHITE : DIM, false);
        }
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
    }

    /** Base of the options: no sprite, text in the menu's type, a click sound. */
    private abstract class Option extends AbstractButton implements Hinted {
        Option(int width) {
            super(0, 0, width, row - 1, Component.empty());
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            defaultButtonNarrationText(output);
        }

        void draw(GuiGraphicsExtractor graphics, String value, int x, int color) {
            graphics.text(font, text(value), x, getY() + 3, color, false);
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

        @Override
        public void onClick(MouseButtonEvent event, boolean doubleClick) {
            String picked = at(event.x());
            if (picked != null) {
                set.accept(picked);
            } else {
                next();
            }
            setMessage(Component.literal(name(current.get())));
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
        protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
            hovered = isHovered() ? at(mouseX) : null;
            String chosen = current.get();
            if (!allFit()) {
                String label = "‹ " + name(chosen) + " ›";
                draw(graphics, label, getX(), isHovered() ? WHITE : HOVER);
                return;
            }
            int x = getX();
            for (String value : values) {
                String name = name(value);
                int w = widthOf(name);
                boolean selected = value.equals(chosen);
                int color = selected ? WHITE : value.equals(hovered) ? HOVER : DIM;
                draw(graphics, name, x, color);
                if (selected) {
                    // Right under the letters, which on the tightest rows is the last pixel of the row.
                    int underline = getY() + Math.min(12, row);
                    graphics.fill(x, underline, x + w, underline + 1, WHITE);
                }
                x += w + SPACING;
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

        @Override
        public void onPress(InputWithModifiers input) {
            set.accept(!current.getAsBoolean());
            setMessage(Component.literal(current.getAsBoolean() ? "An" : "Aus"));
        }

        @Override
        protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
            boolean on = current.getAsBoolean();
            int x = getX();
            int y = getY() + 3;
            int frame = on ? WHITE : isHovered() ? HOVER : DIM;
            graphics.outline(x, y, 16, 8, frame);
            if (on) {
                graphics.fill(x + 8, y + 1, x + 15, y + 7, WHITE);
            } else {
                graphics.fill(x + 2, y + 2, x + 6, y + 6, frame);
            }
            draw(graphics, on ? "An" : "Aus", x + 22, on ? WHITE : isHovered() ? HOVER : DIM);
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

        private void setFromMouse(double mouseX) {
            double u = Math.max(0, Math.min(1, (mouseX - getX()) / trackWidth()));
            double stepped = Math.round((min + u * (max - min)) / step) * step;
            value = Math.max(min, Math.min(max, stepped));
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
            int x = getX();
            int lineY = getY() + 7;
            int track = trackWidth() - 8;
            int knob = x + (int) Math.round((value - min) / (max - min) * track);
            graphics.fill(x, lineY, x + track, lineY + 1, DIM);
            graphics.fill(x, lineY, knob, lineY + 1, isHovered() ? WHITE : HOVER);
            graphics.fill(knob, lineY - 3, knob + 2, lineY + 4, WHITE);
            graphics.text(font, text(label.apply(value)), x + trackWidth(), getY() + 3, WHITE, false);
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
