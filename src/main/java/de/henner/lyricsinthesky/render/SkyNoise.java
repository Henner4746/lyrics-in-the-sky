package de.henner.lyricsinthesky.render;

import java.util.Random;

/**
 * Smooth 3D gradient noise for the painted skies. Sampled at a direction on the unit sphere it has no seam where the
 * panorama's left and right edges meet and no pinch at the zenith, which 2D noise over the panorama would have.
 */
final class SkyNoise {
    private final int[] perm = new int[512];

    SkyNoise(long seed) {
        int[] p = new int[256];
        for (int i = 0; i < 256; i++) {
            p[i] = i;
        }
        Random random = new Random(seed);
        for (int i = 255; i > 0; i--) {
            int j = random.nextInt(i + 1);
            int t = p[i];
            p[i] = p[j];
            p[j] = t;
        }
        for (int i = 0; i < 512; i++) {
            perm[i] = p[i & 255];
        }
    }

    /** Noise from about -1 to 1, smooth and with no straight lines. */
    float noise(float x, float y, float z) {
        int xi = floor(x);
        int yi = floor(y);
        int zi = floor(z);
        float xf = x - xi;
        float yf = y - yi;
        float zf = z - zi;
        int X = xi & 255;
        int Y = yi & 255;
        int Z = zi & 255;
        float u = fade(xf);
        float v = fade(yf);
        float w = fade(zf);
        int a = perm[X] + Y;
        int aa = perm[a] + Z;
        int ab = perm[a + 1] + Z;
        int b = perm[X + 1] + Y;
        int ba = perm[b] + Z;
        int bb = perm[b + 1] + Z;
        float x1 = lerp(u, grad(perm[aa], xf, yf, zf), grad(perm[ba], xf - 1, yf, zf));
        float x2 = lerp(u, grad(perm[ab], xf, yf - 1, zf), grad(perm[bb], xf - 1, yf - 1, zf));
        float y1 = lerp(v, x1, x2);
        x1 = lerp(u, grad(perm[aa + 1], xf, yf, zf - 1), grad(perm[ba + 1], xf - 1, yf, zf - 1));
        x2 = lerp(u, grad(perm[ab + 1], xf, yf - 1, zf - 1), grad(perm[bb + 1], xf - 1, yf - 1, zf - 1));
        float y2 = lerp(v, x1, x2);
        return lerp(w, y1, y2) * 1.05f;
    }

    /** Layers of noise, each finer and fainter: clouds, nebulae, the milky way. About 0 to 1. */
    float fbm(float x, float y, float z, int octaves) {
        float sum = 0f;
        float amplitude = 0.5f;
        float total = 0f;
        for (int i = 0; i < octaves; i++) {
            sum += amplitude * noise(x, y, z);
            total += amplitude;
            x = x * 2.03f + 17.1f;
            y = y * 2.03f - 9.7f;
            z = z * 2.03f + 4.3f;
            amplitude *= 0.5f;
        }
        return 0.5f + 0.5f * sum / total;
    }

    /** Sharp bright ridges where the noise crosses zero: veins of gas, rays of an aurora. About 0 to 1. */
    float ridged(float x, float y, float z, int octaves) {
        float sum = 0f;
        float amplitude = 0.5f;
        float total = 0f;
        for (int i = 0; i < octaves; i++) {
            float n = 1f - Math.abs(noise(x, y, z));
            sum += amplitude * n * n;
            total += amplitude;
            x = x * 2.1f + 3.3f;
            y = y * 2.1f + 7.9f;
            z = z * 2.1f - 1.7f;
            amplitude *= 0.5f;
        }
        return sum / total;
    }

    /** A fixed pseudo-random value from 0 to 1 for a whole number: where a star is, how bright, how fast. */
    static float hash(int n) {
        n = (n << 13) ^ n;
        n = n * (n * n * 15731 + 789221) + 1376312589;
        return (n & 0x7fffffff) / (float) 0x7fffffff;
    }

    private static int floor(float value) {
        int i = (int) value;
        return value < i ? i - 1 : i;
    }

    private static float fade(float t) {
        return t * t * t * (t * (t * 6 - 15) + 10);
    }

    private static float lerp(float t, float a, float b) {
        return a + t * (b - a);
    }

    private static float grad(int hash, float x, float y, float z) {
        int h = hash & 15;
        float u = h < 8 ? x : y;
        float v = h < 4 ? y : h == 12 || h == 14 ? x : z;
        return ((h & 1) == 0 ? u : -u) + ((h & 2) == 0 ? v : -v);
    }
}
