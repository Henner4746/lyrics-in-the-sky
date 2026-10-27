package de.henner.lyricsinthesky.gui;

import de.henner.lyricsinthesky.Lang;
import de.henner.lyricsinthesky.SkyLyricsConfig;
import de.henner.lyricsinthesky.render.SkyLyricsRenderer;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;

/**
 * Asked the first time the menu opens: German or English, the game's language as the main button. Both questions stand
 * on it, since nothing is chosen yet; the choice opens the menu, and the page "Menü" changes it later. In the menu's
 * calm look, like the introduction to Hoergenau: a dark panel with text and two buttons.
 */
public final class LanguageScreen extends Screen {
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
    private static final String TITLE_TEXT = "Lyrics in the Sky";
    private static final List<String> QUESTION = List.of("In welcher Sprache soll die Mod mit dir reden?",
            "Which language should the mod speak?");
    private static final String HINT_TEXT = "Später im Menü auf der Seite „Menü“ · Later in the menu on the page “Menu”";

    private final SkyLyricsConfig config;
    /** Opens the menu once the language is chosen. */
    private final Runnable then;
    private final Style text = SkyLyricsRenderer.regularStyle("sans");
    private List<FormattedCharSequence> question = List.of();
    private List<FormattedCharSequence> hint = List.of();
    private int panelLeft;
    private int panelTop;
    private int panelWidth;
    private int panelHeight;

    public LanguageScreen(SkyLyricsConfig config, Runnable then) {
        super(Component.literal("Sprache · Language"));
        this.config = config;
        this.then = then;
    }

    private Component text(String value) {
        return Component.literal(value).withStyle(text);
    }

    @Override
    protected void init() {
        panelWidth = Math.min(300, width - 16);
        int inner = panelWidth - 2 * PAD;
        question = QUESTION.stream().flatMap(line -> font.split(text(line), inner).stream()).toList();
        hint = font.split(text(HINT_TEXT), inner);
        panelHeight = PAD + 12 + GAP + question.size() * LINE + GAP + hint.size() * LINE + GAP + 14 + PAD;
        panelLeft = (width - panelWidth) / 2;
        panelTop = Math.max(4, (height - panelHeight) / 2);
        int buttonWidth = (inner - BUTTON_GAP) / 2;
        int y = panelTop + panelHeight - PAD - 14;
        int x = panelLeft + PAD;
        boolean german = Lang.gameLanguage().startsWith("de");
        addRenderableWidget(new OutlineButton(font, text, x, y, buttonWidth, text("Deutsch"), german, () -> choose("de"),
                () -> false, () -> false));
        addRenderableWidget(new OutlineButton(font, text, x + buttonWidth + BUTTON_GAP, y, buttonWidth, text("English"),
                !german, () -> choose("en"), () -> false, () -> false));
    }

    private void choose(String language) {
        config.language = language;
        config.languageChosen = true;
        config.save();
        then.run();
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
        for (FormattedCharSequence line : question) {
            graphics.text(font, line, x, y, BODY, false);
            y += LINE;
        }
        y += GAP;
        for (FormattedCharSequence line : hint) {
            graphics.text(font, line, x, y, HINT, false);
            y += LINE;
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** Esc keeps the game's language, chosen as it is, and goes on to the menu. */
    @Override
    public void onClose() {
        config.languageChosen = true;
        config.save();
        then.run();
    }
}
