package de.henner.lyricsinthesky.gui;

import de.henner.lyricsinthesky.SkyLyricsConfig;
import de.henner.lyricsinthesky.render.Glass;
import de.henner.lyricsinthesky.render.HudLayout;
import de.henner.lyricsinthesky.render.NowPlayingHud;
import de.henner.lyricsinthesky.render.SkyLyricsRenderer;
import java.util.Map;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;

/**
 * Moves the now-playing bar: drag it with the mouse anywhere; in the upper half it hangs from the top, in the lower
 * half it sits above the hotbar, and near the middle or its usual height it snaps there. The bar itself is drawn by
 * {@link NowPlayingHud} behind this screen, with a sample line while nothing plays. No background, so the game and
 * the bar stay fully visible.
 */
public final class HudEditScreen extends Screen {
    static final Map<String, String> STYLE_NAMES = Map.of(
            "pill", "Pille", "karaoke", "Karaoke", "plain", "Schlicht", "title", "Nur Titel");
    private static final int PANEL = 0xB0000000;
    private static final int FRAME = 0xAAFFFFFF;

    private final Screen parent;
    private final SkyLyricsConfig config;
    private final Style text = SkyLyricsRenderer.regularStyle("sans");
    private boolean dragging;
    private double grabX;
    private double grabY;
    private OutlineButton styleButton;
    private int panelTop;

    public HudEditScreen(Screen parent, SkyLyricsConfig config) {
        super(Component.literal("Leiste verschieben"));
        this.parent = parent;
        this.config = config;
    }

    private Component text(String value) {
        return Component.literal(value).withStyle(text);
    }

    @Override
    protected void init() {
        int buttonWidth = 74;
        int gap = 6;
        int total = 4 * buttonWidth + 3 * gap;
        int x = (width - total) / 2;
        // In the middle of the screen, where the bar never sits by default.
        panelTop = height / 2 - 20;
        int y = panelTop + 22;
        addRenderableWidget(new OutlineButton(font, text, x, y, buttonWidth, text("Oben"), false, () -> moveTo("top"),
                this::glass));
        addRenderableWidget(new OutlineButton(font, text, x + buttonWidth + gap, y, buttonWidth, text("Unten"), false,
                () -> moveTo("bottom"), this::glass));
        styleButton = addRenderableWidget(new OutlineButton(font, text, x + 2 * (buttonWidth + gap), y, buttonWidth,
                styleLabel(), false, this::nextStyle, this::glass));
        addRenderableWidget(new OutlineButton(font, text, x + 3 * (buttonWidth + gap), y, buttonWidth, text("Fertig"), true,
                this::onClose, this::glass));
    }

    private boolean glass() {
        return "glass".equals(config.theme);
    }

    private Component styleLabel() {
        return text(STYLE_NAMES.getOrDefault(config.hudStyle, config.hudStyle) + " ›");
    }

    private void moveTo(String anchor) {
        config.hudAnchor = anchor;
        config.hudOffsetX = 0;
        config.hudOffsetY = 0;
    }

    private void nextStyle() {
        int index = SkyLyricsConfig.HUD_STYLES.indexOf(config.hudStyle);
        config.hudStyle = SkyLyricsConfig.HUD_STYLES.get((index + 1) % SkyLyricsConfig.HUD_STYLES.size());
        config.showHud = true;
        styleButton.setMessage(styleLabel());
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        // Only a small panel behind the buttons; the rest stays clear so the bar and the game show as they are.
        int panelWidth = 4 * 74 + 3 * 6 + 24;
        int left = (width - panelWidth) / 2;
        if (glass()) {
            Glass.pane(graphics, left, panelTop, left + panelWidth, panelTop + 44, 14, 1f, 1.2f);
        } else {
            graphics.fill(left, panelTop, left + panelWidth, panelTop + 44, PANEL);
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        super.extractRenderState(graphics, mouseX, mouseY, a);
        Component hint = text("Leiste mit der Maus ziehen · Anzeige wechseln · Fertig");
        graphics.text(font, hint, (width - font.width(hint)) / 2, panelTop + 7, 0xFFE0E0E0, false);
        NowPlayingHud.Bounds bar = NowPlayingHud.lastBounds();
        if (bar != null) {
            boolean over = dragging || bar.contains(mouseX, mouseY);
            if (glass()) {
                Glass.outline(graphics, bar.x() - 3, bar.y() - 3, bar.x() + bar.width() + 3, bar.y() + bar.height() + 3,
                        Math.round((bar.height() + 6) / 2f * config.hudRoundness), over ? 0xFFFFFFFF : FRAME);
            } else {
                graphics.outline(bar.x() - 2, bar.y() - 2, bar.width() + 4, bar.height() + 4, over ? 0xFFFFFFFF : FRAME);
            }
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (super.mouseClicked(event, doubleClick)) {
            return true;
        }
        NowPlayingHud.Bounds bar = NowPlayingHud.lastBounds();
        if (bar != null && bar.contains(event.x(), event.y())) {
            dragging = true;
            grabX = event.x() - bar.x();
            grabY = event.y() - bar.y();
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        NowPlayingHud.Bounds bar = NowPlayingHud.lastBounds();
        if (!dragging || bar == null) {
            return super.mouseDragged(event, dragX, dragY);
        }
        HudLayout.Spot spot = HudLayout.spotFor((int) Math.round(event.x() - grabX), (int) Math.round(event.y() - grabY),
                width, height, bar.width(), bar.height());
        config.hudAnchor = spot.anchor();
        config.hudOffsetX = spot.offsetX();
        config.hudOffsetY = spot.offsetY();
        return true;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        dragging = false;
        return super.mouseReleased(event);
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
}
