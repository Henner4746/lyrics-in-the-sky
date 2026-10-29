package de.henner.lyricsinthesky.render;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import net.minecraft.resources.Identifier;

/**
 * This frame's custom sky: quads grouped by texture and blending, drawn in the order given (the panorama first, the
 * moon's far side before the moon, its near side after). The batches are kept from frame to frame and only refilled.
 */
final class SkyMesh {
    /** Quads of one texture, blended one way, at one place in the drawing order. */
    static final class Batch {
        final Identifier texture;
        /** Light added onto what is behind (glows, beams, stars) instead of covering it. */
        final boolean glow;
        /** The texture repeats sideways (the panorama around the sky) instead of ending at its edges. */
        final boolean repeat;
        final int order;
        /** Per vertex: x, y, z, u, v. */
        float[] data = new float[5 * 256];
        int[] colors = new int[256];
        int vertices;

        Batch(Identifier texture, boolean glow, boolean repeat, int order) {
            this.texture = texture;
            this.glow = glow;
            this.repeat = repeat;
            this.order = order;
        }

        void vertex(float x, float y, float z, float u, float v, int argb) {
            if (vertices == colors.length) {
                colors = Arrays.copyOf(colors, vertices * 2);
                data = Arrays.copyOf(data, vertices * 10);
            }
            int at = vertices * 5;
            data[at] = x;
            data[at + 1] = y;
            data[at + 2] = z;
            data[at + 3] = u;
            data[at + 4] = v;
            colors[vertices++] = argb;
        }
    }

    private final List<Batch> batches = new ArrayList<>();

    void clear() {
        for (Batch batch : batches) {
            batch.vertices = 0;
        }
    }

    Batch batch(Identifier texture, boolean glow, boolean repeat, int order) {
        for (Batch batch : batches) {
            if (batch.order == order && batch.glow == glow && batch.repeat == repeat && batch.texture.equals(texture)) {
                return batch;
            }
        }
        Batch batch = new Batch(texture, glow, repeat, order);
        batches.add(batch);
        return batch;
    }

    /** The batches with quads this frame, in drawing order; within one place, in the order they were first used. */
    List<Batch> drawn() {
        List<Batch> drawn = new ArrayList<>();
        for (Batch batch : batches) {
            if (batch.vertices >= 4) {
                drawn.add(batch);
            }
        }
        drawn.sort(Comparator.comparingInt(batch -> batch.order));
        return drawn;
    }

    /** All vertices this frame, for the self test. */
    int vertexCount() {
        int count = 0;
        for (Batch batch : batches) {
            count += batch.vertices;
        }
        return count;
    }
}
