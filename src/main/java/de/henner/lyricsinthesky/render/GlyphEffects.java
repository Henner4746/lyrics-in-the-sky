package de.henner.lyricsinthesky.render;

import java.util.List;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.font.TextRenderable;
import net.minecraft.util.ARGB;
import net.minecraft.util.FormattedCharSequence;
import org.joml.Matrix4f;

/** How one glyph turns into quads: a shadow by day, a soft glow at night, an optional blur ring, the glyph. */
final class GlyphEffects {
    /** Dark halo color by day, so light text stays readable in front of a bright sky and white clouds. */
    static final int DAY_HALO = 0x0A0C18;
    private static final float GLOW_PX = 0.6f;
    private static final int GLOW_COPIES = 8;
    private static final int BLUR_COPIES = 6;
    private static final float[] GLOW_COS = new float[GLOW_COPIES];
    private static final float[] GLOW_SIN = new float[GLOW_COPIES];

    static {
        for (int i = 0; i < GLOW_COPIES; i++) {
            GLOW_COS[i] = (float) Math.cos(i * Math.PI * 2 / GLOW_COPIES);
            GLOW_SIN[i] = (float) Math.sin(i * Math.PI * 2 / GLOW_COPIES);
        }
    }

    private GlyphEffects() {}

    /**
     * @param alpha     opacity of the glyph itself
     * @param glowAlpha opacity of each glow copy, relative to {@code alpha}
     * @param dayness   0 at night (no shadow) to 1 by day (full shadow)
     * @param blur      radius of the blur ring in font pixels; below 0.05 there is none
     */
    static void emit(TextRenderable glyph, Matrix4f pose, int color, float alpha, int glowColor, float glowAlpha,
            float dayness, float blur, boolean overlay, List<SkyGlyph> out) {
        if (dayness > 0.05f) {
            out.add(new SkyGlyph(glyph, new Matrix4f(pose).translate(0.55f, 0.75f, 0f),
                    ARGB.color(alpha * 0.38f * dayness, DAY_HALO), SkyGlyph.LAYER_GLOW, overlay));
            out.add(new SkyGlyph(glyph, new Matrix4f(pose).translate(1.0f, 1.3f, 0f),
                    ARGB.color(alpha * 0.2f * dayness, DAY_HALO), SkyGlyph.LAYER_GLOW, overlay));
        }
        int glow = ARGB.color(alpha * glowAlpha, glowColor);
        for (int i = 0; i < GLOW_COPIES; i++) {
            out.add(new SkyGlyph(glyph, new Matrix4f(pose).translate(GLOW_COS[i] * GLOW_PX, GLOW_SIN[i] * GLOW_PX, 0f),
                    glow, SkyGlyph.LAYER_GLOW, overlay));
        }
        if (blur > 0.05f) {
            // Cheap blur: a ring of faint copies that collapses onto the glyph as it comes into focus.
            int ghost = ARGB.color(alpha * 0.22f, color);
            for (int i = 0; i < BLUR_COPIES; i++) {
                double angle = i * Math.PI * 2 / BLUR_COPIES + 0.4;
                out.add(new SkyGlyph(glyph, new Matrix4f(pose).translate((float) Math.cos(angle) * blur,
                        (float) Math.sin(angle) * blur, 0f), ghost, SkyGlyph.LAYER_BLUR, overlay));
            }
        }
        out.add(new SkyGlyph(glyph, pose, ARGB.color(alpha, color), SkyGlyph.LAYER_TEXT, overlay));
    }

    /**
     * The plain look of the video styles: no glow, just the glyph with its top-to-bottom gradient, a faint
     * shadow for readability and an optional blur ring while it comes into focus or fades away.
     *
     * @param shadow opacity of the soft shadow relative to {@code alpha}; 0 for none
     */
    static void emitPlain(TextRenderable glyph, Matrix4f pose, int top, int bottom, float alpha, float shadow, float blur,
            boolean overlay, List<SkyGlyph> out) {
        if (shadow > 0.01f) {
            out.add(new SkyGlyph(glyph, new Matrix4f(pose).translate(0.45f, 0.6f, 0f),
                    ARGB.color(alpha * shadow, DAY_HALO), SkyGlyph.LAYER_GLOW, overlay));
        }
        if (blur > 0.05f) {
            int ghostTop = ARGB.color(alpha * 0.22f, top);
            int ghostBottom = ARGB.color(alpha * 0.22f, bottom);
            for (int i = 0; i < BLUR_COPIES; i++) {
                double angle = i * Math.PI * 2 / BLUR_COPIES + 0.4;
                out.add(new SkyGlyph(glyph, new Matrix4f(pose).translate((float) Math.cos(angle) * blur,
                        (float) Math.sin(angle) * blur, 0f), ghostTop, ghostBottom, SkyGlyph.LAYER_BLUR, overlay));
            }
        }
        out.add(new SkyGlyph(glyph, pose, ARGB.color(alpha, top), ARGB.color(alpha, bottom), SkyGlyph.LAYER_TEXT, overlay));
    }

    /** The glyphs of a text, laid out from 0/0. */
    static void collect(Font font, FormattedCharSequence text, List<TextRenderable> out) {
        font.prepareText(text, 0f, 0f, 0xFFFFFFFF, false, false, 0).visit(new Font.GlyphVisitor() {
            @Override
            public void acceptRenderable(TextRenderable renderable) {
                out.add(renderable);
            }
        });
    }
}
