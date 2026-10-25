package de.henner.lyricsinthesky.render;

import de.henner.lyricsinthesky.lyrics.Lyrics;
import java.util.ArrayList;
import java.util.List;

/**
 * The timing behind {@link LyricFlow}, free of Minecraft types: which entries the gliding stack shows, when
 * each one becomes the current line, and how lines look depending on their distance from the current one.
 */
final class FlowSchedule {
    enum Kind { TITLE, LINE, DOTS }

    /**
     * @param lineIndex index into {@link Lyrics#lines()}, or -1 for the title and dots
     * @param startMs   song position at which the entry becomes the current one
     * @param wordTimes start of every word (empty for the title and dots)
     * @param endMs     when the last word should be done lighting up
     */
    record Item(Kind kind, int lineIndex, long startMs, long[] wordTimes, long endMs) {}

    /** A break without lyrics shows three breathing dots once it is at least this long. */
    static final long DOTS_GAP_MS = 4_500;
    /** Opacity of the next line and the one after it, relative to the current line. */
    static final float NEXT_ALPHA = 0.38f;
    static final float AFTER_NEXT_ALPHA = 0.13f;
    static final float SIDE_SCALE = 0.86f;
    static final float SIDE_BLUR_PX = 1.5f;

    private FlowSchedule() {}

    static List<Item> build(Lyrics lyrics, long durationMs, boolean withTitle) {
        List<Item> items = new ArrayList<>();
        if (withTitle) {
            items.add(new Item(Kind.TITLE, -1, Long.MIN_VALUE, new long[0], Long.MIN_VALUE));
        }
        if (lyrics == null) {
            return items;
        }
        List<Lyrics.Line> lines = lyrics.lines();
        for (int i = 0; i < lines.size(); i++) {
            Lyrics.Line line = lines.get(i);
            long next = i + 1 < lines.size() ? lines.get(i + 1).startMs() : Math.max(durationMs, line.startMs());
            if (line.text().isBlank()) {
                // LRC marks the end of a phrase with an empty line; only a long break is worth showing.
                if (next - line.startMs() >= DOTS_GAP_MS) {
                    items.add(new Item(Kind.DOTS, -1, line.startMs(), new long[0], next));
                }
                continue;
            }
            long end = lyrics.endOf(i, durationMs);
            items.add(new Item(Kind.LINE, i, line.startMs(), lyrics.wordTimes(i, durationMs), end));
            // A long instrumental part right after a line, without an empty line marking it.
            if (i + 1 < lines.size() && !lines.get(i + 1).text().isBlank() && next - end >= DOTS_GAP_MS) {
                items.add(new Item(Kind.DOTS, -1, end, new long[0], next));
            }
        }
        return items;
    }

    /** The entry that is current at {@code positionMs}: the last one that has started, or the first one. */
    static int currentIndex(List<Item> items, long positionMs) {
        int current = items.isEmpty() ? -1 : 0;
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).startMs() <= positionMs) {
                current = i;
            } else {
                break;
            }
        }
        return current;
    }

    /**
     * Opacity of an entry {@code d} places below the current one (negative: above, already sung). The line
     * above fades out as it glides away; the lines below wait faintly.
     */
    static float alpha(float d) {
        if (d < 0f) {
            return Easing.smoothstep(-1.1f, 0f, d);
        }
        if (d <= 1f) {
            return lerp(1f, NEXT_ALPHA, Easing.smoothstep(0f, 1f, d));
        }
        if (d <= 2f) {
            return lerp(NEXT_ALPHA, AFTER_NEXT_ALPHA, Easing.smoothstep(1f, 2f, d));
        }
        return lerp(AFTER_NEXT_ALPHA, 0f, Easing.smoothstep(2f, 3f, d));
    }

    /** Lines other than the current one are a little smaller ... */
    static float scale(float d) {
        return 1f - (1f - SIDE_SCALE) * Easing.smoothstep(0f, 1f, Math.abs(d));
    }

    /** ... and slightly out of focus. */
    static float blur(float d) {
        return SIDE_BLUR_PX * Easing.smoothstep(0.1f, 1.2f, Math.abs(d));
    }

    /** How long the letters of word {@code i} take to light up one after another. */
    static long sweepMs(long[] wordTimes, int i, long endMs) {
        long until = i + 1 < wordTimes.length ? wordTimes[i + 1] : endMs;
        return Math.max(120, Math.min(700, until - wordTimes[i]));
    }

    private static float lerp(float from, float to, float t) {
        return from + (to - from) * t;
    }

    /**
     * Critically damped spring: follows a moving target as fast as possible without overshooting, so the
     * stack glides into place and never bounces.
     */
    static final class Spring {
        private final float omega;
        private float value;
        private float velocity;
        private boolean started;

        /** @param omega stiffness in rad/s; the spring is mostly settled after about 4 / omega seconds */
        Spring(float omega) {
            this.omega = omega;
        }

        float value() {
            return value;
        }

        /** Forgets the state; the next {@link #step} starts right at its target. */
        void reset() {
            started = false;
            velocity = 0f;
        }

        void snap(float target) {
            value = target;
            velocity = 0f;
            started = true;
        }

        /** Advances by {@code dt} seconds towards {@code target}; the first call jumps straight there. */
        float step(float target, float dt) {
            if (!started) {
                snap(target);
                return value;
            }
            // Exact solution of x'' = -omega² x - 2 omega x' over dt, so the result does not depend on frame rate.
            float x = value - target;
            float decay = (float) Math.exp(-omega * dt);
            float a = velocity + omega * x;
            value = target + (x + a * dt) * decay;
            velocity = (velocity - omega * a * dt) * decay;
            return value;
        }
    }
}
