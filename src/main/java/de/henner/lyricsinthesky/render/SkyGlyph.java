package de.henner.lyricsinthesky.render;

import net.minecraft.client.gui.font.TextRenderable;
import org.joml.Matrix4f;

/**
 * One glyph quad to draw this frame.
 *
 * @param argb       color and opacity multiplied onto the top of the glyph (straight alpha, not premultiplied)
 * @param argbBottom the same for the bottom of the glyph; the color runs smoothly from top to bottom
 * @param layer      draw order: lower layers (glow, blur) go first so the crisp text ends up on top
 * @param overlay    drawn without depth test, on top of the world (the "around you" mode)
 */
public record SkyGlyph(TextRenderable renderable, Matrix4f pose, int argb, int argbBottom, int layer, boolean overlay) {
    public static final int LAYER_GLOW = 0;
    public static final int LAYER_BLUR = 1;
    public static final int LAYER_TEXT = 2;

    /** A glyph in one color. */
    public SkyGlyph(TextRenderable renderable, Matrix4f pose, int argb, int layer, boolean overlay) {
        this(renderable, pose, argb, argb, layer, overlay);
    }
}
