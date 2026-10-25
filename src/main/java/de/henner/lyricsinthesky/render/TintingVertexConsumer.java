package de.henner.lyricsinthesky.render;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.util.ARGB;

/**
 * Multiplies every vertex color with a tint and premultiplies alpha, so glyphs can be faded and colored
 * individually. The sky text pipelines blend with premultiplied alpha, which keeps the anti-aliased edges
 * of TrueType glyphs free of dark fringes.
 *
 * <p>The tint can differ between the top and the bottom of a glyph: Minecraft emits every glyph quad as
 * top-left, bottom-left, bottom-right, top-right, so the vertex index tells which edge a vertex is on.
 */
public final class TintingVertexConsumer implements VertexConsumer {
    private VertexConsumer delegate;
    private int top = 0xFFFFFFFF;
    private int bottom = 0xFFFFFFFF;
    private int vertex;

    public TintingVertexConsumer wrap(VertexConsumer delegate, int argb) {
        return wrap(delegate, argb, argb);
    }

    /** Tints each following glyph from {@code argbTop} at its top edge to {@code argbBottom} at its bottom edge. */
    public TintingVertexConsumer wrap(VertexConsumer delegate, int argbTop, int argbBottom) {
        this.delegate = delegate;
        this.top = argbTop;
        this.bottom = argbBottom;
        this.vertex = 0;
        return this;
    }

    @Override
    public VertexConsumer addVertex(float x, float y, float z) {
        delegate.addVertex(x, y, z);
        vertex++;
        return this;
    }

    @Override
    public VertexConsumer setColor(int r, int g, int b, int a) {
        // The color belongs to the vertex just added: 1st and 4th of each quad are on top, 2nd and 3rd below.
        int corner = (vertex - 1) & 3;
        int tint = corner == 1 || corner == 2 ? bottom : top;
        float outAlpha = a / 255f * ARGB.alpha(tint) / 255f;
        delegate.setColor(channel(r * ARGB.red(tint) / 255f * outAlpha), channel(g * ARGB.green(tint) / 255f * outAlpha),
                channel(b * ARGB.blue(tint) / 255f * outAlpha), channel(255f * outAlpha));
        return this;
    }

    @Override
    public VertexConsumer setColor(int color) {
        return setColor(ARGB.red(color), ARGB.green(color), ARGB.blue(color), ARGB.alpha(color));
    }

    @Override
    public VertexConsumer setUv(float u, float v) {
        delegate.setUv(u, v);
        return this;
    }

    @Override
    public VertexConsumer setUv1(int u, int v) {
        delegate.setUv1(u, v);
        return this;
    }

    @Override
    public VertexConsumer setUv2(int u, int v) {
        delegate.setUv2(u, v);
        return this;
    }

    // Abstract only from 26.3 on; text never writes this element, so it is dropped. No @Override so the
    // same source compiles against 26.2.
    public VertexConsumer setUv3(float u, float v) {
        return this;
    }

    @Override
    public VertexConsumer setNormal(float x, float y, float z) {
        delegate.setNormal(x, y, z);
        return this;
    }

    @Override
    public VertexConsumer setLineWidth(float width) {
        delegate.setLineWidth(width);
        return this;
    }

    private static int channel(float value) {
        return Math.max(0, Math.min(255, Math.round(value)));
    }
}
