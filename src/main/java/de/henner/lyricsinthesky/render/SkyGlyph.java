package de.henner.lyricsinthesky.render;

import net.minecraft.client.gui.font.TextRenderable;
import org.joml.Matrix4f;

/**
 * One glyph quad to draw this frame.
 *
 * @param argb       color and opacity multiplied onto the top of the glyph (straight alpha, not premultiplied)
 * @param argbBottom the same for the bottom of the glyph; the color runs smoothly from top to bottom
 * @param layer      draw order: lower layers (glow, blur) go first so the crisp text ends up on top
 * @param overlay    drawn after the world (the "around you" mode) instead of right after the sky
 * @param occluded   with {@code overlay}: still hidden by blocks and entities in front of it, like a thing in the
 *                   world; without, it is drawn on top of everything
 */
public record SkyGlyph(TextRenderable renderable, Matrix4f pose, int argb, int argbBottom, int layer, boolean overlay,
        boolean occluded) {
    public static final int LAYER_GLOW = 0;
    public static final int LAYER_BLUR = 1;
    public static final int LAYER_TEXT = 2;

    /** A glyph with a gradient, on top of everything when {@code overlay}. */
    public SkyGlyph(TextRenderable renderable, Matrix4f pose, int argb, int argbBottom, int layer, boolean overlay) {
        this(renderable, pose, argb, argbBottom, layer, overlay, false);
    }

    /** A glyph in one color. */
    public SkyGlyph(TextRenderable renderable, Matrix4f pose, int argb, int layer, boolean overlay) {
        this(renderable, pose, argb, argb, layer, overlay, false);
    }

    /** Whether the world's depth hides it: everything in the sky, and words around the player that are occluded. */
    public boolean depthTested() {
        return !overlay || occluded;
    }
}
