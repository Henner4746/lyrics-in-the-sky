package de.henner.lyricsinthesky.gui;

import de.henner.lyricsinthesky.Lang;
import de.henner.lyricsinthesky.SkyLyricsConfig;
import de.henner.lyricsinthesky.render.SkyLyricsRenderer;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;

/**
 * The introduction to Hoergenau, shown once: what the AI does, what it hears and keeps, what it costs, with a button
 * for each model and one for later. In the menu's calm look, a dark panel with text and three buttons.
 */
public final class HoergenauIntroScreen extends Screen {
    private static final int SHADE = 0x99000000;
    private static final int PANEL = 0xE6101012;
    private static final int FRAME = 0x33FFFFFF;
    private static final int TITLE = 0xFFFFFFFF;
    private static final int BODY = 0xFFCFCFCF;
    private static final int HINT = 0xFF8E8E8E;
    private static final int PAD = 12;
    private static final int LINE = 10;
    private static final int GAP = 5;
    private static final int BUTTON_GAP = 6;
    private static final String TITLE_TEXT = "Neu: Hörgenau – die KI, die mithört";
    private static final List<String> PARAGRAPHS = List.of(
            "Songs, die niemand Wort für Wort getimt hat, hört eine KI (Whisper) auf deinem PC mit und lernt, wann "
                    + "jedes Wort kommt. Danach laufen sie Wort für Wort, auch wenn du sie erst beim nächsten Mal zu "
                    + "Ende hörst.",
            "Songs mit Wortzeiten hört sie einmal 50 s an und rückt die Wörter zurecht, wenn sie zu früh oder zu spät "
                    + "kommen.",
            "Sie hört nur dein Musikprogramm, nicht Discord oder das Spiel. Die Aufnahme verlässt nie deinen PC; "
                    + "geteilt wird Gelerntes, Text nur bei Songs, für die es keinen gibt.",
            "Schnell: 190 MB, etwa 15 s Rechnen je 50 s Musik. Genau: 574 MB, versteht mehr, etwa eine Minute. Beides "
                    + "läuft im Hintergrund.");
    private static final String HINT_TEXT = "Später jederzeit im Menü unter „Hörgenau (KI)“.";

    private final SkyLyricsConfig config;
    /** Switches Hoergenau on with the chosen model; set by the mod. */
    private final BiConsumer<SkyLyricsConfig, String> switchOn;
    private final Style text = SkyLyricsRenderer.regularStyle("sans");
    private final List<List<FormattedCharSequence>> paragraphs = new ArrayList<>();
    private List<FormattedCharSequence> hint = List.of();
    private int panelLeft;
    private int panelTop;
    private int panelWidth;
    private int panelHeight;

    public HoergenauIntroScreen(SkyLyricsConfig config, BiConsumer<SkyLyricsConfig, String> switchOn) {
        super(Component.literal("Hörgenau"));
        this.config = config;
        this.switchOn = switchOn;
    }

    private Component text(String value) {
        return Component.literal(Lang.t(value)).withStyle(text);
    }

    @Override
    protected void init() {
        panelWidth = Math.min(360, width - 16);
        int inner = panelWidth - 2 * PAD;
        paragraphs.clear();
        int lines = 0;
        for (String paragraph : PARAGRAPHS) {
            List<FormattedCharSequence> wrapped = font.split(text(paragraph), inner);
            paragraphs.add(wrapped);
            lines += wrapped.size();
        }
        hint = font.split(text(HINT_TEXT), inner);
        panelHeight = PAD + 12 + GAP + lines * LINE + (PARAGRAPHS.size() - 1) * GAP + GAP + hint.size() * LINE + GAP + 14 + PAD;
        panelLeft = (width - panelWidth) / 2;
        panelTop = Math.max(4, (height - panelHeight) / 2);
        int buttonWidth = (inner - 2 * BUTTON_GAP) / 3;
        int y = panelTop + panelHeight - PAD - 14;
        int x = panelLeft + PAD;
        addRenderableWidget(new OutlineButton(font, text, x, y, buttonWidth, text("Schnell (190 MB)"), true,
                () -> choose("small"), () -> false, () -> false));
        addRenderableWidget(new OutlineButton(font, text, x + buttonWidth + BUTTON_GAP, y, buttonWidth,
                text("Genau (574 MB)"), false, () -> choose("turbo"), () -> false, () -> false));
        addRenderableWidget(new OutlineButton(font, text, x + 2 * (buttonWidth + BUTTON_GAP), y, buttonWidth,
                text("Später"), false, this::onClose, () -> false, () -> false));
    }

    private void choose(String model) {
        switchOn.accept(config, model);
        onClose();
    }

    /** Whether the whole panel is on the screen; the self test checks it at the smallest window. */
    public boolean fits() {
        return panelTop >= 0 && panelTop + panelHeight <= height;
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        graphics.fill(0, 0, width, height, SHADE);
        graphics.fill(panelLeft, panelTop, panelLeft + panelWidth, panelTop + panelHeight, PANEL);
        graphics.outline(panelLeft, panelTop, panelWidth, panelHeight, FRAME);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        super.extractRenderState(graphics, mouseX, mouseY, a);
        int x = panelLeft + PAD;
        int y = panelTop + PAD;
        graphics.text(font, text(TITLE_TEXT), x, y, TITLE, false);
        y += 12 + GAP;
        for (List<FormattedCharSequence> paragraph : paragraphs) {
            for (FormattedCharSequence line : paragraph) {
                graphics.text(font, line, x, y, BODY, false);
                y += LINE;
            }
            y += GAP;
        }
        for (FormattedCharSequence line : hint) {
            graphics.text(font, line, x, y, HINT, false);
            y += LINE;
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        config.save();
        minecraft.gui.setScreen(null);
    }
}
