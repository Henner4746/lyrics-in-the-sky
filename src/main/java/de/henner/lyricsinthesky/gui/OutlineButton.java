package de.henner.lyricsinthesky.gui;

import de.henner.lyricsinthesky.render.Glass;
import java.util.function.BooleanSupplier;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.util.ARGB;

/**
 * A button as a thin white frame; the main one ({@code filled}) white with black text. Hover inverts it. In the Liquid
 * Glass theme a capsule of glass instead, the main one frosted white. In the fancy menu look it also glows in the accent
 * color under the mouse, a streak of light runs across it, and it gives a little when pressed.
 */
final class OutlineButton extends AbstractButton {
    private static final int WHITE = 0xFFFFFFFF;
    private static final int DIM = 0xFF8E8E8E;

    private final Font font;
    private final Style style;
    private final boolean filled;
    private final Runnable action;
    private final BooleanSupplier glass;
    private final BooleanSupplier fancy;
    private float hover;
    private long lastNanos = System.nanoTime();
    private long pressedNanos;

    OutlineButton(Font font, Style style, int x, int y, int width, Component message, boolean filled, Runnable action,
            BooleanSupplier glass, BooleanSupplier fancy) {
        super(x, y, width, 14, message);
        this.font = font;
        this.style = style;
        this.filled = filled;
        this.action = action;
        this.glass = glass;
        this.fancy = fancy;
    }

    @Override
    public void onPress(InputWithModifiers input) {
        pressedNanos = System.nanoTime();
        action.run();
    }

    @Override
    protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        if (!fancy.getAsBoolean()) {
            extractPlain(graphics);
            return;
        }
        long now = System.nanoTime();
        float dt = Math.min(0.1f, (now - lastNanos) / 1e9f);
        lastNanos = now;
        hover = Fancy.approach(hover, active && isHoveredOrFocused() ? 1f : 0f, dt, 12f);
        int x0 = getX();
        int y0 = getY();
        int x1 = x0 + getWidth();
        int y1 = y0 + getHeight();
        int radius = glass.getAsBoolean() ? getHeight() / 2 : 0;
        // Pressed: squeezed a little and springing back.
        float press = 1f - Fancy.clamp01((now - pressedNanos) / 1e9f / 0.25f);
        float scale = 1f - 0.08f * press * press;
        graphics.pose().pushMatrix();
        graphics.pose().translate((x0 + x1) / 2f, (y0 + y1) / 2f);
        graphics.pose().scale(scale, scale);
        graphics.pose().translate(-(x0 + x1) / 2f, -(y0 + y1) / 2f);
        float t = Fancy.seconds();
        int accent = Fancy.accent(t * 0.08f + x0 * 0.002f);
        if (hover > 0.02f) {
            Fancy.glow(graphics, x0, y0, x1, y1, radius, 3, hover, accent);
        }
        extractPlain(graphics);
        if (hover > 0.02f) {
            // A slanted streak of light running across, clipped to the button.
            graphics.enableScissor(x0, y0, x1, y1);
            float along = ((t * 0.9f) % 1.6f - 0.3f) * getWidth();
            for (int row = 0; row < getHeight(); row++) {
                int x = Math.round(x0 + along + (getHeight() - row) * 0.6f);
                graphics.fill(x, y0 + row, x + 4, y0 + row + 1, ARGB.color(0.28f * hover, 0xFFFFFF));
            }
            graphics.disableScissor();
        }
        graphics.pose().popMatrix();
    }

    private void extractPlain(GuiGraphicsExtractor graphics) {
        if (glass.getAsBoolean()) {
            extractGlass(graphics);
            return;
        }
        boolean inverted = active && (filled != isHoveredOrFocused());
        int frame = active ? WHITE : DIM;
        if (inverted) {
            graphics.fill(getX(), getY(), getX() + getWidth(), getY() + getHeight(), WHITE);
        } else {
            graphics.outline(getX(), getY(), getWidth(), getHeight(), frame);
        }
        Component message = getMessage().copy().withStyle(style);
        int textX = getX() + (getWidth() - font.width(message)) / 2;
        graphics.text(font, message, textX, getY() + 3, inverted ? 0xFF000000 : frame, false);
    }

    private void extractGlass(GuiGraphicsExtractor graphics) {
        int x0 = getX();
        int y0 = getY();
        int x1 = x0 + getWidth();
        int y1 = y0 + getHeight();
        int radius = getHeight() / 2;
        boolean hover = active && isHoveredOrFocused();
        int textColor;
        if (filled && active) {
            Glass.pane(graphics, x0, y0, x1, y1, radius, 1f, 0.6f);
            Glass.fill(graphics, x0, y0, x1, y1, radius, ARGB.color(hover ? 0.96f : 0.84f, 0xFFFFFF));
            Glass.rim(graphics, x0, y0, x1, y1, radius, 1f);
            textColor = 0xFF101320;
        } else {
            Glass.pane(graphics, x0, y0, x1, y1, radius, active ? 1f : 0.5f, hover ? 1.3f : 0.8f);
            if (hover) {
                Glass.fill(graphics, x0, y0, x1, y1, radius, ARGB.color(0.14f, 0xFFFFFF));
            }
            textColor = active ? WHITE : DIM;
        }
        Component message = getMessage().copy().withStyle(style);
        graphics.text(font, message, x0 + (getWidth() - font.width(message)) / 2, y0 + 3, textColor, false);
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        defaultButtonNarrationText(output);
    }
}
