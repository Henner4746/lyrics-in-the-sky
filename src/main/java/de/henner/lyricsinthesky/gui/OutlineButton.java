package de.henner.lyricsinthesky.gui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;

/** A button as a thin white frame; the main one ({@code filled}) white with black text. Hover inverts it. */
final class OutlineButton extends AbstractButton {
    private static final int WHITE = 0xFFFFFFFF;
    private static final int DIM = 0xFF8E8E8E;

    private final Font font;
    private final Style style;
    private final boolean filled;
    private final Runnable action;

    OutlineButton(Font font, Style style, int x, int y, int width, Component message, boolean filled, Runnable action) {
        super(x, y, width, 14, message);
        this.font = font;
        this.style = style;
        this.filled = filled;
        this.action = action;
    }

    @Override
    public void onPress(InputWithModifiers input) {
        action.run();
    }

    @Override
    protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
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

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        defaultButtonNarrationText(output);
    }
}
