package de.henner.lyricsinthesky.render;

import de.henner.lyricsinthesky.SkyLyricsConfig;
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

    /**
     * The player's "Leuchten": 1 as designed, 0 for none, 2 twice as strong. Read while the glyphs of a frame are made,
     * set once per frame from the config.
     */
    static volatile float strength = 1f;
    /** The player's "Schatten": the soft dark shadow by day, 0 to 2. */
    static volatile float shadowStrength = 1f;
    /** The player's "Kontur": a ring of copies in {@link #outlineColor} around every letter, {@link #outlineWidth} wide. */
    static volatile boolean outline;
    static volatile int outlineColor;
    static volatile float outlineWidth = 1f;
    private static final float OUTLINE_PX = 0.55f;

    private GlyphEffects() {}

    /** Takes this frame's look from the config. */
    static void configure(SkyLyricsConfig config) {
        strength = config.glow;
        shadowStrength = config.shadow;
        outline = config.outline;
        outlineColor = config.outlineColor;
        outlineWidth = config.outlineWidth;
    }

    /** The outline ring, drawn with the glow so the letters stay on top of it. */
    private static void outline(TextRenderable glyph, Matrix4f pose, float alpha, boolean overlay, boolean occluded,
            List<SkyGlyph> out) {
        if (!outline) {
            return;
        }
        int ring = ARGB.color(Math.min(1f, alpha * 0.9f), outlineColor);
        float width = OUTLINE_PX * outlineWidth;
        for (int i = 0; i < GLOW_COPIES; i++) {
            out.add(new SkyGlyph(glyph, new Matrix4f(pose).translate(GLOW_COS[i] * width, GLOW_SIN[i] * width, 0f),
                    ring, ring, SkyGlyph.LAYER_GLOW, overlay, occluded));
        }
    }

    /**
     * @param alpha     opacity of the glyph itself
     * @param glowAlpha opacity of each glow copy, relative to {@code alpha}
     * @param dayness   0 at night (no shadow) to 1 by day (full shadow)
     * @param blur      radius of the blur ring in font pixels; below 0.05 there is none
     */
    static void emit(TextRenderable glyph, Matrix4f pose, int color, float alpha, int glowColor, float glowAlpha,
            float dayness, float blur, boolean overlay, List<SkyGlyph> out) {
        float strength = GlyphEffects.strength;
        float shadow = shadowStrength;
        if (dayness > 0.05f && shadow > 0.01f) {
            out.add(new SkyGlyph(glyph, new Matrix4f(pose).translate(0.55f, 0.75f, 0f),
                    ARGB.color(Math.min(1f, alpha * 0.38f * dayness * shadow), DAY_HALO), SkyGlyph.LAYER_GLOW, overlay));
            out.add(new SkyGlyph(glyph, new Matrix4f(pose).translate(1.0f, 1.3f, 0f),
                    ARGB.color(Math.min(1f, alpha * 0.2f * dayness * shadow), DAY_HALO), SkyGlyph.LAYER_GLOW, overlay));
        }
        outline(glyph, pose, alpha, overlay, false, out);
        int glow = ARGB.color(Math.min(1f, alpha * glowAlpha * strength), glowColor);
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
     * @param shadow   opacity of the soft shadow relative to {@code alpha}; 0 for none
     * @param occluded with {@code overlay}: hidden by blocks and entities in front of it
     */
    static void emitPlain(TextRenderable glyph, Matrix4f pose, int top, int bottom, float alpha, float shadow, float blur,
            boolean overlay, boolean occluded, List<SkyGlyph> out) {
        float strength = GlyphEffects.strength;
        shadow *= shadowStrength;
        outline(glyph, pose, alpha, overlay, occluded, out);
        if (strength > 1.01f) {
            // Above the designed strength: a soft dark ring as well, which reads like an outline on a bright sky.
            int ring = ARGB.color(Math.min(1f, alpha * 0.14f * (strength - 1f)), DAY_HALO);
            for (int i = 0; i < GLOW_COPIES; i++) {
                out.add(new SkyGlyph(glyph, new Matrix4f(pose).translate(GLOW_COS[i] * GLOW_PX, GLOW_SIN[i] * GLOW_PX, 0f),
                        ring, ring, SkyGlyph.LAYER_GLOW, overlay, occluded));
            }
        }
        if (shadow > 0.01f) {
            int dark = ARGB.color(Math.min(1f, alpha * shadow), DAY_HALO);
            out.add(new SkyGlyph(glyph, new Matrix4f(pose).translate(0.45f, 0.6f, 0f), dark, dark, SkyGlyph.LAYER_GLOW,
                    overlay, occluded));
        }
        if (blur > 0.05f) {
            int ghostTop = ARGB.color(alpha * 0.22f, top);
            int ghostBottom = ARGB.color(alpha * 0.22f, bottom);
            for (int i = 0; i < BLUR_COPIES; i++) {
                double angle = i * Math.PI * 2 / BLUR_COPIES + 0.4;
                out.add(new SkyGlyph(glyph, new Matrix4f(pose).translate((float) Math.cos(angle) * blur,
                        (float) Math.sin(angle) * blur, 0f), ghostTop, ghostBottom, SkyGlyph.LAYER_BLUR, overlay, occluded));
            }
        }
        out.add(new SkyGlyph(glyph, pose, ARGB.color(alpha, top), ARGB.color(alpha, bottom), SkyGlyph.LAYER_TEXT, overlay,
                occluded));
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
