package de.henner.lyricsinthesky.render;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.util.ARGB;

/**
 * Rounded shapes for the GUI, plain and as "Liquid Glass" in the manner of Apple's iOS 26: a soft shadow below, a body
 * of frosted glass that is clear at the top and a little denser at the bottom, a glossy band over its upper half, a
 * bright rim where light catches the top edge and a faint dark one along the bottom, as if the glass had thickness.
 *
 * <p>Minecraft blurs only the whole screen behind a menu, never a part of it, so in the game the frost is built from
 * layers: a dark tint keeps white text readable before a bright sky, the white layers make it glass at night. GUI
 * rendering has no rounded rectangles, so every shape is drawn as one-pixel rows.
 */
public final class Glass {
    /** Dark tint inside the glass; a blue-black so the glass reads cool, not grey. */
    private static final int TINT = 0x0A1020;

    private Glass() {}

    /** A rounded rectangle; {@code radius} half the height makes a capsule. Rows of the same width are one fill. */
    public static void fill(GuiGraphicsExtractor graphics, int x0, int y0, int x1, int y1, int radius, int color) {
        if (ARGB.alpha(color) == 0 || x1 <= x0 || y1 <= y0) {
            return;
        }
        int height = y1 - y0;
        radius = clampRadius(radius, x1 - x0, height);
        for (int row = 0; row < height; ) {
            int inset = inset(row, height, radius);
            int end = row + 1;
            while (end < height && inset(end, height, radius) == inset) {
                end++;
            }
            graphics.fill(x0 + inset, y0 + row, x1 - inset, y0 + end, color);
            row = end;
        }
    }

    /** The one-pixel edge of a rounded rectangle. */
    public static void outline(GuiGraphicsExtractor graphics, int x0, int y0, int x1, int y1, int radius, int color) {
        if (ARGB.alpha(color) == 0 || x1 <= x0 || y1 <= y0) {
            return;
        }
        int height = y1 - y0;
        radius = clampRadius(radius, x1 - x0, height);
        for (int row = 0; row < height; row++) {
            edgeRow(graphics, x0, x1, y0 + row, row, height, radius, color, color, color);
        }
    }

    /**
     * A pane of Liquid Glass.
     *
     * @param alpha   fade of the whole pane, 0 to 1
     * @param density how solid the glass is: 1 as designed, 0 only its rim, up to 1.5 for more contrast
     */
    public static void pane(GuiGraphicsExtractor graphics, int x0, int y0, int x1, int y1, int radius, float alpha,
            float density) {
        if (alpha <= 0.01f || x1 <= x0 || y1 <= y0) {
            return;
        }
        int height = y1 - y0;
        radius = clampRadius(radius, x1 - x0, height);
        float body = alpha * Math.min(1.5f, density);
        // Shadow: the pane floats a little above what is behind it.
        for (int spread = 3; spread >= 1; spread--) {
            fill(graphics, x0 - spread + 1, y0 + spread, x1 + spread - 1, y1 + spread, radius + spread,
                    ARGB.color(body * 0.07f, 0x000000));
        }
        // The rounded rows one by one; the straight middle, where tint and frost change linearly from row to row, as
        // gradients (split where the gloss over the upper half ends), the same picture with a handful of fills.
        int straightFrom = radius;
        int straightTo = height - radius;
        int glossEnd = Math.round(0.48f * (height - 1));
        for (int row = 0; row < height; row++) {
            if (row == straightFrom && straightTo - straightFrom > 2) {
                int split = Math.max(straightFrom, Math.min(straightTo, glossEnd));
                bodyGradient(graphics, x0, x1, y0, straightFrom, split, height, body);
                bodyGradient(graphics, x0, x1, y0, split, straightTo, height, body);
                row = straightTo - 1;
                continue;
            }
            int inset = inset(row, height, radius);
            bodyRow(graphics, x0 + inset, x1 - inset, y0 + row, v(row, height), body);
        }
        rim(graphics, x0, y0, x1, y1, radius, alpha);
    }

    private static float v(int row, int height) {
        return height <= 1 ? 0f : row / (float) (height - 1);
    }

    /** Clear at the top, a touch milkier at the bottom, plus the gloss over the upper half. */
    private static float frost(float v) {
        return 0.07f + 0.05f * v + 0.11f * Math.max(0f, 1f - v / 0.48f);
    }

    private static void bodyRow(GuiGraphicsExtractor graphics, int left, int right, int y, float v, float body) {
        graphics.fill(left, y, right, y + 1, ARGB.color(body * (0.30f + 0.10f * v), TINT));
        graphics.fill(left, y, right, y + 1, ARGB.color(body * frost(v), 0xFFFFFF));
    }

    /** Rows {@code from} to {@code to} (exclusive) of the body without corners, as two vertical gradients. */
    private static void bodyGradient(GuiGraphicsExtractor graphics, int x0, int x1, int y0, int from, int to, int height,
            float body) {
        if (to <= from) {
            return;
        }
        // A gradient runs from the top edge of its first row to the bottom edge of its last; the rows' own values sit
        // in their middles, so the ends are taken half a row further out.
        float top = v(from, height) - 0.5f / Math.max(1, height - 1);
        float bottom = v(to - 1, height) + 0.5f / Math.max(1, height - 1);
        graphics.fillGradient(x0, y0 + from, x1, y0 + to, ARGB.color(body * (0.30f + 0.10f * top), TINT),
                ARGB.color(body * (0.30f + 0.10f * bottom), TINT));
        graphics.fillGradient(x0, y0 + from, x1, y0 + to, ARGB.color(body * frost(Math.max(top, 0f)), 0xFFFFFF),
                ARGB.color(body * frost(bottom), 0xFFFFFF));
    }

    /** Only the rim of a glass pane: bright along the top, fading down the sides, faintly dark along the bottom. */
    public static void rim(GuiGraphicsExtractor graphics, int x0, int y0, int x1, int y1, int radius, float alpha) {
        int height = y1 - y0;
        radius = clampRadius(radius, x1 - x0, height);
        int top = ARGB.color(alpha * 0.62f, 0xFFFFFF);
        int bottom = ARGB.color(alpha * 0.20f, 0x000000);
        for (int row = 0; row < height; row++) {
            if (row == Math.max(1, radius) && height - radius - row > 2) {
                // The straight sides between the corners: one gradient each, fading down as the rows would.
                int end = height - Math.max(1, radius);
                int from = ARGB.color(alpha * (0.38f - 0.26f * v(row, height)), 0xFFFFFF);
                int to = ARGB.color(alpha * (0.38f - 0.26f * v(end - 1, height)), 0xFFFFFF);
                graphics.fillGradient(x0, y0 + row, x0 + 1, y0 + end, from, to);
                graphics.fillGradient(x1 - 1, y0 + row, x1, y0 + end, from, to);
                row = end - 1;
                continue;
            }
            int side = ARGB.color(alpha * (0.38f - 0.26f * v(row, height)), 0xFFFFFF);
            edgeRow(graphics, x0, x1, y0 + row, row, height, radius, top, side, bottom);
        }
        // A second, fainter line just inside the top edge: the light inside the glass.
        if (height > 4) {
            int inset = inset(1, height, radius);
            graphics.fill(x0 + inset + 2, y0 + 1, x1 - inset - 2, y0 + 2, ARGB.color(alpha * 0.16f, 0xFFFFFF));
        }
    }

    /** Draws one row of a rounded rectangle's edge, with its own colors for the top row, the sides and the bottom row. */
    private static void edgeRow(GuiGraphicsExtractor graphics, int x0, int x1, int y, int row, int height, int radius,
            int top, int side, int bottom) {
        int inset = inset(row, height, radius);
        if (row == 0 || row == height - 1) {
            graphics.fill(x0 + inset, y, x1 - inset, y + 1, row == 0 ? top : bottom);
            return;
        }
        // Where the corner curves, the edge spans the pixels between this row's inset and the next one's.
        int next = row < height / 2 ? inset(row - 1, height, radius) : inset(row + 1, height, radius);
        int width = Math.max(1, next - inset);
        graphics.fill(x0 + inset, y, x0 + inset + width, y + 1, side);
        graphics.fill(x1 - inset - width, y, x1 - inset, y + 1, side);
    }

    private static int clampRadius(int radius, int width, int height) {
        return Math.max(0, Math.min(radius, Math.min(width / 2, height / 2)));
    }

    /** How far a row of a rounded rectangle starts in from its sides. */
    static int inset(int row, int height, int radius) {
        if (radius <= 0 || row < 0 || row >= height) {
            return 0;
        }
        float dy = Math.max(0f, Math.max(radius - row - 0.5f, row + 0.5f - (height - radius)));
        return Math.round(radius - (float) Math.sqrt(Math.max(0f, radius * radius - dy * dy)));
    }
}
