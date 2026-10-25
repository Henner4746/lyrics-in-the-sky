package de.henner.lyricsinthesky.render;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.util.ARGB;

/**
 * Multiplies every vertex color with a tint and premultiplies alpha, so glyphs can be faded and colored
 * individually. The sky text pipelines blend with premultiplied alpha, which keeps the anti-aliased edges
 * of TrueType glyphs free of dark fringes.
 */
public final class TintingVertexConsumer implements VertexConsumer {
    private VertexConsumer delegate;
    private float red = 1f;
    private float green = 1f;
    private float blue = 1f;
    private float alpha = 1f;

    public TintingVertexConsumer wrap(VertexConsumer delegate, int argb) {
        this.delegate = delegate;
        this.red = ARGB.red(argb) / 255f;
        this.green = ARGB.green(argb) / 255f;
        this.blue = ARGB.blue(argb) / 255f;
        this.alpha = ARGB.alpha(argb) / 255f;
        return this;
    }

    @Override
    public VertexConsumer addVertex(float x, float y, float z) {
        delegate.addVertex(x, y, z);
        return this;
    }

    @Override
    public VertexConsumer setColor(int r, int g, int b, int a) {
        float outAlpha = a / 255f * alpha;
        delegate.setColor(channel(r * red * outAlpha), channel(g * green * outAlpha), channel(b * blue * outAlpha),
                channel(255f * outAlpha));
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
