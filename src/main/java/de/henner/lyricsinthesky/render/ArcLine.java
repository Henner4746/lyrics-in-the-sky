package de.henner.lyricsinthesky.render;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.font.TextRenderable;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.util.ARGB;
import net.minecraft.util.FormattedCharSequence;
import org.joml.Matrix4f;

/**
 * One line in the sky in the look the mod is modelled on: word groups of different sizes on staggered,
 * gently rising rows, each group fading in when it is sung, and the whole line turned a little away from the
 * viewer so it reads with depth – one end nearer and bigger than the other. When the next line starts, this
 * one fades out softly.
 */
final class ArcLine {
    private static final long FADE_IN_MS = 260;
    /** Words start fading in this much before they are sung, so they are readable right on the beat. */
    private static final long LEAD_MS = 80;
    static final long EXIT_MS = 520;
    private static final float ROW_WIDTH_PX = 104f;
    private static final float ROW_INDENT_PX = 20f;
    private static final float ROW_SPACING_PX = 2f;
    private static final float GROUP_GAP_PX = 5f;
    private static final float RISE_PX = 3.5f;
    /** How far the line is turned away from the viewer, degrees, at most. */
    private static final float MAX_TWIST = 34f;
    /** The lowest row keeps at least this many degrees above the horizon, so the ground does not cover it. */
    static final float HORIZON_MARGIN_DEG = 2.5f;
    /** Closer for the lines sitting right above the hills ("natural" style). */
    static final float LOW_HORIZON_MARGIN_DEG = 1f;

    private static final class Piece {
        final FormattedCharSequence text;
        final float width;
        final float scale;
        final long appearMs;
        final float jitterY;
        final float rotation;
        float x;
        float y;

        Piece(FormattedCharSequence text, float width, float scale, long appearMs, float jitterY, float rotation) {
            this.text = text;
            this.width = width;
            this.scale = scale;
            this.appearMs = appearMs;
            this.jitterY = jitterY;
            this.rotation = rotation;
        }
    }

    final String key;
    private final Placements.Placement placement;
    private final SkyLine.Look look;
    private final Palette palette;
    private final float twist;
    private final float slope;
    private final List<Piece> pieces;
    private final float horizonMargin;
    /** Half the size of the laid-out line in font pixels. */
    private final float halfWidth;
    private final float halfHeight;
    private final List<TextRenderable> glyphs = new ArrayList<>();
    private long exitStartNanos = -1;
    private long frozenSongMs;

    private ArcLine(String key, Placements.Placement placement, SkyLine.Look look, Palette palette, long seed,
            List<Piece> pieces, float lineHeight, float horizonMargin) {
        this.key = key;
        this.placement = placement;
        this.look = look;
        this.palette = palette;
        this.pieces = pieces;
        this.horizonMargin = horizonMargin;
        float w = 0f;
        float h = 0f;
        for (Piece piece : pieces) {
            w = Math.max(w, Math.max(Math.abs(piece.x), Math.abs(piece.x + piece.width * piece.scale)));
            h = Math.max(h, Math.max(Math.abs(piece.y), Math.abs(piece.y + lineHeight * piece.scale)));
        }
        this.halfWidth = w;
        this.halfHeight = h;
        // Turned to the left or right by a different amount every line, rows rising slightly to the right.
        float side = Easing.hash(seed, 21) < 0.5f ? -1f : 1f;
        this.twist = side * (MAX_TWIST * (0.55f + 0.45f * Easing.hash(seed, 22)));
        this.slope = -2f - 7f * Easing.hash(seed, 23);
    }

    /** @param horizonMargin degrees the lowest row keeps above the horizon */
    static ArcLine create(String key, String text, long[] wordTimes, Placements.Placement placement, SkyLine.Look look,
            Palette palette, long seed, Font font, Style style, float horizonMargin) {
        String[] words = text.trim().split("\\s+");
        List<Piece> pieces = new ArrayList<>();
        for (VideoLayout.Group group : VideoLayout.groups(words, seed)) {
            StringBuilder joined = new StringBuilder();
            for (int w = group.first(); w < group.first() + group.count(); w++) {
                joined.append(w > group.first() ? " " : "").append(words[w]);
            }
            Component component = Component.literal(joined.toString()).withStyle(style);
            long appear = group.first() < wordTimes.length ? wordTimes[group.first()]
                    : wordTimes.length > 0 ? wordTimes[wordTimes.length - 1] : 0;
            int index = pieces.size();
            pieces.add(new Piece(component.getVisualOrderText(), font.width(component), group.scale(), appear,
                    (Easing.hash(seed, 300 + index) - 0.5f) * 2.4f, (Easing.hash(seed, 400 + index) - 0.5f) * 5f));
        }
        layout(pieces, font.lineHeight);
        return new ArcLine(key, placement, look, palette, seed, pieces, font.lineHeight, horizonMargin);
    }

    /** Fills rows left to right, each row indented a little more, groups standing on a common baseline. */
    private static void layout(List<Piece> pieces, float lineHeight) {
        List<List<Piece>> rows = new ArrayList<>();
        List<Piece> row = new ArrayList<>();
        float cursor = 0f;
        for (Piece piece : pieces) {
            float width = piece.width * piece.scale;
            if (!row.isEmpty() && cursor + width > ROW_WIDTH_PX) {
                rows.add(row);
                row = new ArrayList<>();
                cursor = 0f;
            }
            row.add(piece);
            cursor += width + GROUP_GAP_PX;
        }
        if (!row.isEmpty()) {
            rows.add(row);
        }
        float top = 0f;
        float minX = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE;
        for (int r = 0; r < rows.size(); r++) {
            float rowHeight = 0f;
            for (Piece piece : rows.get(r)) {
                rowHeight = Math.max(rowHeight, lineHeight * piece.scale);
            }
            float x = r * ROW_INDENT_PX;
            for (Piece piece : rows.get(r)) {
                piece.x = x;
                // Bottom-aligned: small words sit on the same line as the big ones.
                piece.y = top + rowHeight - lineHeight * piece.scale + piece.jitterY;
                x += piece.width * piece.scale + GROUP_GAP_PX;
                minX = Math.min(minX, piece.x);
                maxX = Math.max(maxX, x - GROUP_GAP_PX);
            }
            top += rowHeight + ROW_SPACING_PX;
        }
        float centerX = (minX + maxX) / 2f;
        float centerY = (top - ROW_SPACING_PX) / 2f;
        for (Piece piece : pieces) {
            piece.x -= centerX;
            piece.y -= centerY;
        }
    }

    void startExit(long nowNanos, long songPositionMs) {
        if (exitStartNanos < 0) {
            exitStartNanos = nowNanos;
            frozenSongMs = songPositionMs;
        }
    }

    boolean isExiting() {
        return exitStartNanos >= 0;
    }

    boolean isFinished(long nowNanos) {
        return exitStartNanos >= 0 && (nowNanos - exitStartNanos) / 1_000_000L > EXIT_MS;
    }

    void render(SkyLine.Frame frame, List<SkyGlyph> out) {
        float exitU = exitStartNanos < 0 ? 0f : Easing.clamp01((frame.nowNanos() - exitStartNanos) / 1e6f / EXIT_MS);
        float leave = Easing.inOutSine(exitU);
        long song = exitStartNanos >= 0 ? frozenSongMs : frame.songPositionMs();
        float atDistance = look.distance() / 100f;
        float blocksPerPixel = frame.blocksPerPixelAt100() * look.size() * atDistance;
        // A touch lighter at night, so the rose still reads against a dark sky.
        int top = ARGB.srgbLerp(0.22f * frame.nightness(), palette.text, 0xFFFFFF);
        int bottom = ARGB.srgbLerp(0.22f * frame.nightness(), palette.textBottom, 0xFFFFFF);
        // The line stands behind everything, so a row dipping below the horizon would vanish behind the ground.
        // Its lowest point: the rotated half extents, a little more for the nearer, bigger end of the turned line.
        double tilt = Math.toRadians(Math.abs(slope + placement.roll()));
        float drop = (float) (halfWidth * Math.sin(tilt) + halfHeight * Math.cos(tilt)) * blocksPerPixel * 1.15f;
        float lowest = (float) Math.toDegrees(Math.atan2(drop, look.distance())) + horizonMargin;
        Matrix4f base = SkyLine.bannerBasis(placement.yaw(), Math.max(placement.elevation(), lowest), look.distance())
                .rotateY((float) Math.toRadians(twist))
                .rotateZ((float) Math.toRadians(slope + placement.roll()))
                .scale(blocksPerPixel)
                .translate(0f, -6f * leave, 0f);
        for (Piece piece : pieces) {
            float t = song + LEAD_MS - piece.appearMs;
            if (t < 0f) {
                continue;
            }
            float in = Easing.outCubic(t / FADE_IN_MS);
            float alpha = frame.opacity() * in * (1f - leave);
            if (alpha < 0.01f) {
                continue;
            }
            Matrix4f pose = new Matrix4f(base)
                    .translate(piece.x, piece.y + (1f - in) * RISE_PX, 0f)
                    .rotateZ((float) Math.toRadians(piece.rotation))
                    .scale(piece.scale * (0.94f + 0.06f * in));
            float blur = (1f - in) * 1.4f + 2f * leave;
            glyphs.clear();
            GlyphEffects.collect(frame.font(), piece.text, glyphs);
            for (TextRenderable glyph : glyphs) {
                GlyphEffects.emitPlain(glyph, pose, top, bottom, alpha, 0f, blur, look.overlay(), false, out);
            }
        }
    }
}
