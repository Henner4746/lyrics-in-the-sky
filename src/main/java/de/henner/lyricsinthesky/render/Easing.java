package de.henner.lyricsinthesky.render;

/** Easing curves on 0..1 (see easings.net for the shapes). */
final class Easing {
    private Easing() {}

    static float clamp01(float x) {
        return x < 0f ? 0f : (x > 1f ? 1f : x);
    }

    static float outCubic(float t) {
        float inv = 1f - clamp01(t);
        return 1f - inv * inv * inv;
    }

    static float outQuint(float t) {
        float inv = 1f - clamp01(t);
        return 1f - inv * inv * inv * inv * inv;
    }

    static float inCubic(float t) {
        float c = clamp01(t);
        return c * c * c;
    }

    static float inOutSine(float t) {
        return (float) (-(Math.cos(Math.PI * clamp01(t)) - 1) / 2);
    }

    /** Overshoots a little before settling, the classic "pop". */
    static float outBack(float t) {
        float c = clamp01(t) - 1f;
        float c1 = 1.70158f;
        float c3 = c1 + 1f;
        return 1f + c3 * c * c * c + c1 * c * c;
    }

    static float outBounce(float t) {
        float x = clamp01(t);
        float n1 = 7.5625f;
        float d1 = 2.75f;
        if (x < 1f / d1) {
            return n1 * x * x;
        } else if (x < 2f / d1) {
            x -= 1.5f / d1;
            return n1 * x * x + 0.75f;
        } else if (x < 2.5f / d1) {
            x -= 2.25f / d1;
            return n1 * x * x + 0.9375f;
        }
        x -= 2.625f / d1;
        return n1 * x * x + 0.984375f;
    }

    static float smoothstep(float edge0, float edge1, float x) {
        float t = clamp01((x - edge0) / (edge1 - edge0));
        return t * t * (3f - 2f * t);
    }

    /** Deterministic pseudo-random value in 0..1 for an index, stable across frames. */
    static float hash(long seed, int index) {
        long h = seed * 0x9E3779B97F4A7C15L + index * 0xC2B2AE3D27D4EB4FL;
        h ^= h >>> 31;
        h *= 0xBF58476D1CE4E5B9L;
        h ^= h >>> 29;
        return (h >>> 40) / (float) (1L << 24);
    }
}
