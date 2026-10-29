package de.henner.lyricsinthesky.render;

import java.util.Map;
import java.util.Random;

/**
 * Paints the custom skies, pixel by pixel and in plain Java, so it runs in the background and in tests: the panorama
 * behind everything (colors, nebulae, the milky way, clouds), the shapes drawn on top of it each frame (stars, aurora
 * curtains, beams, the moon, a spinning record) and a small postcard of every sky for the menu. Colors are ARGB with
 * straight alpha.
 */
final class SkyArt {
    static final int PANORAMA_W = 1024;
    static final int PANORAMA_H = 512;
    static final int POSTCARD_W = 192;
    static final int POSTCARD_H = 108;

    /** The shapes drawn on top of the panorama; white unless noted, colored per vertex when drawn. */
    enum Sprite {
        DOT(64, 64), SPIKE(128, 128), BEAM(32, 256), CURTAIN(256, 128), CLOUD(128, 128), PETAL(32, 32),
        MOON(256, 256), PLANET(256, 256), RING(512, 512), DISK(512, 512), CORE(128, 128), PHOTON_RING(256, 256),
        VINYL(512, 512), LABEL(128, 128), SHEEN(512, 512), RETRO_SUN(256, 256), RAINBOW(512, 256), EQ_BAR(32, 128),
        STREAK(256, 32);

        final int w;
        final int h;

        Sprite(int w, int h) {
            this.w = w;
            this.h = h;
        }
    }

    private SkyArt() {}

    // ------------------------------------------------------------------------------------------------------ panorama

    /** The whole sky around and above, equirectangular: left to right once around, top the zenith, bottom the nadir. */
    static int[] panorama(String style) {
        SkyNoise noise = new SkyNoise(style.hashCode());
        int[] pixels = new int[PANORAMA_W * PANORAMA_H];
        for (int y = 0; y < PANORAMA_H; y++) {
            float el = (float) ((0.5 - (y + 0.5) / PANORAMA_H) * Math.PI);
            float cosEl = (float) Math.cos(el);
            float dy = (float) Math.sin(el);
            for (int x = 0; x < PANORAMA_W; x++) {
                float az = (float) ((x + 0.5) / PANORAMA_W * Math.PI * 2);
                pixels[y * PANORAMA_W + x] = skyColor(style, noise, (float) -Math.sin(az) * cosEl, dy,
                        (float) Math.cos(az) * cosEl);
            }
        }
        return pixels;
    }

    /**
     * The sky's color in a direction (a unit vector, y up). The thing it is named after stands at azimuth 0 a little
     * above the horizon, where {@link CustomSky} also puts its moon, planet or sun.
     */
    static int skyColor(String style, SkyNoise noise, float x, float y, float z) {
        float el = (float) Math.toDegrees(Math.asin(Math.max(-1f, Math.min(1f, y))));
        // Angle to the side of the sky the style faces (azimuth 0, a bit above the horizon).
        float front = Math.max(0f, z);
        return switch (style) {
            case "northern" -> {
                int c = gradient(el, -40, 0x02040A, 0, 0x0C2733, 12, 0x081A2C, 45, 0x050C1F, 90, 0x03050F);
                float haze = noise.fbm(x * 2f, y * 4f, z * 2f, 4);
                c = add(c, 0x1B6B55, 0.35f * band(el, 0, 10) * haze);
                yield add(c, 0x2C1E5C, 0.25f * band(el, 18, 22) * noise.fbm(x * 3f + 4, y * 3f, z * 3f, 4));
            }
            case "stars" -> {
                int c = gradient(el, -40, 0x020309, 0, 0x121A33, 20, 0x070B1C, 90, 0x02030A);
                // The milky way: a band across the sky, with dark lanes of dust.
                float d = x * 0.46f + y * 0.58f + z * 0.67f;
                float bandShape = (float) Math.exp(-d * d / 0.045f);
                float cloud = noise.fbm(x * 4f, y * 4f, z * 4f, 6);
                float dust = noise.ridged(x * 6f + 3, y * 6f, z * 6f, 4);
                float glow = bandShape * smooth(0.3f, 0.85f, cloud) * (1f - 0.75f * dust * bandShape);
                c = add(c, mix(0xB8C6FF, 0xFFD6B0, noise.fbm(x * 1.5f, y * 1.5f, z * 1.5f, 3)), 0.75f * glow);
                yield add(c, 0x6A7CC9, 0.18f * bandShape);
            }
            case "nebula" -> {
                int c = gradient(el, -40, 0x030208, 0, 0x150A26, 30, 0x0B0618, 90, 0x050310);
                float body = noise.fbm(x * 2.1f, y * 2.1f, z * 2.1f, 5);
                float tint = noise.fbm(x * 3.7f + 11, y * 3.7f, z * 3.7f - 5, 4);
                float veins = noise.ridged(x * 5f, y * 5f, z * 5f, 5);
                float density = smooth(0.42f, 0.78f, body);
                int gas = mix(mix(0x7B2CBF, 0x16C2B5, tint), 0xFF4FA3, smooth(0.6f, 0.95f, veins) * 0.7f);
                c = add(c, gas, 0.95f * density * (0.6f + 0.4f * veins));
                yield add(c, 0xFFFFFF, 0.25f * density * smooth(0.85f, 1f, veins));
            }
            case "lightshow" -> {
                int c = gradient(el, -40, 0x040208, 0, 0x2B0E3D, 10, 0x150828, 40, 0x080414, 90, 0x040209);
                float smoke = noise.fbm(x * 2.5f, y * 5f, z * 2.5f, 5);
                yield add(c, 0x8A2B9C, 0.45f * band(el, 0, 16) * smoke);
            }
            case "fireworks" -> {
                int c = gradient(el, -40, 0x05060E, 0, 0x231C34, 15, 0x0E1022, 90, 0x04060F);
                // Warm light of a town far away, under the rockets.
                yield add(c, 0xFF9A55, 0.4f * band(el, -2, 5) * (0.6f + 0.4f * noise.fbm(x * 8f, 0f, z * 8f, 3)));
            }
            case "storm" -> {
                int c = gradient(el, -40, 0x101216, 0, 0x3A3F47, 25, 0x2A2E36, 90, 0x1B1E25);
                float clouds = noise.fbm(x * 2.2f, y * 3.5f, z * 2.2f, 6);
                float lit = noise.fbm(x * 2.2f + 0.08f, y * 3.5f + 0.1f, z * 2.2f, 6);
                c = mix(c, 0x1A1D23, smooth(0.45f, 0.7f, clouds) * 0.85f);
                yield add(c, 0x5A6170, 0.35f * Math.max(0f, lit - clouds) * 6f * smooth(0.4f, 0.6f, clouds));
            }
            case "equalizer" -> {
                int c = gradient(el, -40, 0x020208, 0, 0x1D0D3A, 12, 0x0D0723, 90, 0x02020C);
                yield add(c, mix(0x2EF2FF, 0xFF45D8, 0.5f + 0.5f * x), 0.22f * band(el, -1, 7));
            }
            case "retro" -> {
                int c = gradient(el, -40, 0x12051F, -1, 0x401060, 0, 0xFF3E8A, 4, 0xFF7A4A, 12, 0x9A2A7A, 35,
                        0x34105A, 90, 0x0B0425);
                float lines = (float) Math.pow(Math.abs(Math.sin(el * 0.9f)), 30f);
                yield add(c, 0xFF7AD9, 0.12f * lines * band(el, 2, 30));
            }
            case "afterglow" -> {
                int c = gradient(el, -40, 0x2A1A30, 0, 0xFF8A4C, 6, 0xF2985E, 18, 0x9A6A8E, 40, 0x405C9C, 90, 0x1E2E5E);
                // The sun's glow, strongest towards the side the sun sets on.
                float toSun = (float) Math.exp(-(1f - front) * 6f) * band(el, -3, 25);
                c = add(c, 0xFFD08A, 0.6f * toSun);
                float streaks = noise.fbm(x * 1.6f, y * 14f, z * 1.6f, 5);
                yield mix(c, mix(0x8A4C7A, 0xFFB070, toSun), 0.55f * smooth(0.55f, 0.75f, streaks) * band(el, 3, 22));
            }
            case "petals" -> {
                int c = gradient(el, -40, 0x2A1530, 0, 0xF2A6C0, 8, 0xC97AA6, 25, 0x6E3E7E, 90, 0x1E1638);
                float clouds = noise.fbm(x * 2f, y * 6f, z * 2f, 5);
                yield mix(c, 0xFFD3E6, 0.4f * smooth(0.55f, 0.8f, clouds) * band(el, 2, 30));
            }
            case "fullmoon" -> {
                int c = gradient(el, -40, 0x02040B, 0, 0x14233F, 20, 0x0A1428, 90, 0x02050F);
                float moonGlow = (float) Math.exp(-(1f - front) * 9f) * band(el, 5, 50);
                c = add(c, 0x3C5A8C, 0.5f * moonGlow);
                float clouds = noise.fbm(x * 2.5f, y * 7f, z * 2.5f, 5);
                yield mix(c, 0x9AAAC8, 0.35f * smooth(0.58f, 0.82f, clouds) * band(el, 4, 28) * (0.4f + moonGlow));
            }
            case "ringworld" -> {
                int c = gradient(el, -40, 0x010207, 0, 0x101A36, 15, 0x060B1C, 90, 0x010207);
                float gas = noise.fbm(x * 1.8f + 7, y * 1.8f, z * 1.8f, 5);
                yield add(c, mix(0x2A4C9A, 0xC2603A, noise.fbm(x * 3f, y * 3f, z * 3f, 3)), 0.25f * smooth(0.5f, 0.85f, gas));
            }
            case "blackhole" -> {
                int c = gradient(el, -40, 0x000000, 0, 0x0B0812, 20, 0x050409, 90, 0x010103);
                float gas = noise.fbm(x * 2.4f, y * 2.4f, z * 2.4f, 5);
                yield add(c, 0x8A2A1A, 0.22f * smooth(0.55f, 0.85f, gas));
            }
            case "vinyl" -> {
                int c = gradient(el, -40, 0x05030A, 0, 0x251134, 12, 0x140A22, 90, 0x05030B);
                float haze = noise.fbm(x * 2f, y * 3f, z * 2f, 4);
                yield add(c, 0x6A2A8A, 0.3f * smooth(0.5f, 0.8f, haze) * band(el, 0, 35));
            }
            case "pastel" -> {
                int c = gradient(el, -40, 0xD8B8C8, 0, 0xFFE6CC, 10, 0xFAD0DC, 35, 0xC6D8F2, 90, 0x8EBCEC);
                float clouds = noise.fbm(x * 2.2f, y * 5f, z * 2.2f, 6);
                float shade = noise.fbm(x * 2.2f, y * 5f - 0.12f, z * 2.2f, 6);
                float cover = smooth(0.5f, 0.68f, clouds) * band(el, 3, 45);
                yield mix(c, mix(0xFFFFFF, 0xF2C8DA, Math.max(0f, shade - clouds) * 5f), 0.85f * cover);
            }
            case "rainbow" -> {
                int c = gradient(el, -40, 0x9AB8D0, 0, 0xD2E8FA, 20, 0x8EC0F0, 90, 0x4A8EE0);
                float clouds = noise.fbm(x * 2f, y * 5f, z * 2f, 6);
                yield mix(c, 0xFFFFFF, 0.9f * smooth(0.55f, 0.72f, clouds) * band(el, 2, 50));
            }
            default -> gradient(el, -40, 0x050510, 0, 0x14182C, 90, 0x05060F);
        };
    }

    // ------------------------------------------------------------------------------------------------------ postcard

    /**
     * A small picture of the sky for the menu: what the player sees looking at the side the sky is named after, with
     * its moon, aurora, beams or fireworks frozen in a good moment.
     */
    static int[] postcard(String style, Map<Sprite, int[]> sprites) {
        int w = POSTCARD_W;
        int h = POSTCARD_H;
        SkyNoise noise = new SkyNoise(style.hashCode());
        int[] out = new int[w * h];
        float pitch = (float) Math.toRadians(14);
        float focal = w / 2f / (float) Math.tan(Math.toRadians(45));
        for (int py = 0; py < h; py++) {
            for (int px = 0; px < w; px++) {
                float cx = (px + 0.5f - w / 2f) / focal;
                float cy = (h / 2f - py - 0.5f) / focal;
                // Camera looking to azimuth 0 (+z), tilted up by pitch.
                float dz = (float) (Math.cos(pitch) - cy * Math.sin(pitch));
                float dy = (float) (Math.sin(pitch) + cy * Math.cos(pitch));
                float dx = -cx;
                float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
                out[py * w + px] = skyColor(style, noise, dx / len, dy / len, dz / len);
            }
        }
        Postcard card = new Postcard(out, w, h, focal, pitch);
        Random random = new Random(style.hashCode() * 31L + 7);
        boolean night = !"pastel".equals(style) && !"rainbow".equals(style) && !"afterglow".equals(style);
        if (night) {
            for (int i = 0; i < 90; i++) {
                float sx = random.nextFloat() * w;
                float sy = random.nextFloat() * h * 0.75f;
                card.glow(sx, sy, 0.6f + random.nextFloat() * 0.9f, 0xFFFFFF, 0.35f + random.nextFloat() * 0.6f);
            }
        }
        switch (style) {
            case "northern" -> {
                for (int px = 0; px < w; px++) {
                    float base = h * 0.62f + (float) Math.sin(px * 0.045f) * 6f + (float) Math.sin(px * 0.11f + 1) * 3f;
                    float rays = 0.55f + 0.45f * noise.ridged(px * 0.08f, 0.3f, 1.1f, 3);
                    for (int py = 0; py < h; py++) {
                        float up = base - py;
                        if (up < -2) {
                            continue;
                        }
                        float a = up < 0 ? (2 + up) / 2f : (float) Math.exp(-up / 22f);
                        int color = mix(0x3CFFB0, 0xB070FF, Math.min(1f, Math.max(0f, up / 40f)));
                        card.add(px, py, color, 0.75f * a * rays);
                    }
                }
            }
            case "lightshow" -> {
                int[] colors = {0xFF45D8, 0x2EF2FF, 0xFFE36B, 0xFF45D8, 0x2EF2FF};
                for (int i = 0; i < 5; i++) {
                    float bx = w * (0.12f + i * 0.19f);
                    float angle = (float) Math.toRadians(-28 + i * 14);
                    card.beam(bx, h * 0.98f, bx + (float) Math.sin(angle) * h * 1.2f, h * 0.98f - (float) Math.cos(angle) * h * 1.2f,
                            5f, colors[i], 0.55f);
                }
            }
            case "fireworks" -> {
                card.burst(w * 0.3f, h * 0.32f, 22f, 0xFF5C8A, random);
                card.burst(w * 0.68f, h * 0.24f, 26f, 0xFFD36B, random);
                card.burst(w * 0.52f, h * 0.5f, 15f, 0x6BE0FF, random);
            }
            case "storm" -> card.bolt(w * 0.58f, h * 0.05f, w * 0.5f, h * 0.86f, random);
            case "equalizer" -> {
                int bars = 24;
                for (int i = 0; i < bars; i++) {
                    float level = 0.25f + 0.75f * Math.abs((float) Math.sin(i * 0.9f) * (float) Math.cos(i * 0.37f));
                    float bx = (i + 0.5f) * w / bars;
                    int color = mix(0x2EF2FF, 0xFF45D8, i / (float) bars);
                    card.bar(bx, h * 0.92f, w / (float) bars * 0.55f, level * h * 0.45f, color);
                }
            }
            case "retro" -> card.stamp(sprites.get(Sprite.RETRO_SUN), Sprite.RETRO_SUN, w / 2f, h * 0.8f, 30f, 30f, 0xFFFFFF, false);
            case "afterglow" -> card.glow(w / 2f, h * 0.86f, 26f, 0xFFD890, 0.9f);
            case "petals" -> {
                for (int i = 0; i < 40; i++) {
                    card.glow(random.nextFloat() * w, random.nextFloat() * h, 1.2f, 0xFFC4DC, 0.9f);
                }
            }
            case "fullmoon" -> {
                card.glow(w / 2f, h * 0.38f, 34f, 0x7A9ACC, 0.45f);
                card.stamp(sprites.get(Sprite.MOON), Sprite.MOON, w / 2f, h * 0.38f, 17f, 17f, 0xFFF6E8, false);
            }
            case "ringworld" -> {
                card.ellipse(w * 0.55f, h * 0.48f, 44f, 9f, 0xE8D2A0, 0.7f);
                card.stamp(sprites.get(Sprite.PLANET), Sprite.PLANET, w * 0.55f, h * 0.48f, 24f, 24f, 0xFFFFFF, false);
                card.ellipseFront(w * 0.55f, h * 0.48f, 44f, 9f, 0xE8D2A0, 0.8f);
            }
            case "blackhole" -> {
                card.glow(w / 2f, h * 0.42f, 30f, 0xFF8A3A, 0.4f);
                card.ellipse(w / 2f, h * 0.42f, 46f, 7f, 0xFFC27A, 0.95f);
                card.stamp(sprites.get(Sprite.CORE), Sprite.CORE, w / 2f, h * 0.42f, 13f, 13f, 0x000000, false);
                card.ring(w / 2f, h * 0.42f, 15f, 0xFFD9A0, 0.8f);
            }
            case "vinyl" -> {
                card.glow(w / 2f, h * 0.42f, 40f, 0x8A3AB8, 0.35f);
                card.stamp(sprites.get(Sprite.VINYL), Sprite.VINYL, w / 2f, h * 0.42f, 30f, 30f, 0xFFFFFF, false);
                card.stamp(sprites.get(Sprite.LABEL), Sprite.LABEL, w / 2f, h * 0.42f, 9f, 9f, 0xFF5CB0, false);
                card.stamp(sprites.get(Sprite.SHEEN), Sprite.SHEEN, w / 2f, h * 0.42f, 30f, 30f, 0xFFFFFF, true);
            }
            case "pastel" -> card.glow(w * 0.7f, h * 0.35f, 22f, 0xFFF4D8, 0.8f);
            case "rainbow" -> card.stamp(sprites.get(Sprite.RAINBOW), Sprite.RAINBOW, w / 2f, h * 0.75f, 70f, 35f, 0xFFFFFF, false);
            case "nebula" -> {
                for (int i = 0; i < 6; i++) {
                    card.glow(random.nextFloat() * w, random.nextFloat() * h * 0.7f, 2.5f, 0xFFFFFF, 0.9f);
                }
            }
            default -> { }
        }
        return out;
    }

    /** Painting on the postcard: soft glows, beams, bars, sprites, all with straight alpha onto opaque pixels. */
    private record Postcard(int[] px, int w, int h, float focal, float pitch) {
        void add(int x, int y, int color, float alpha) {
            if (x < 0 || y < 0 || x >= w || y >= h || alpha <= 0f) {
                return;
            }
            px[y * w + x] = SkyArt.add(px[y * w + x], color, Math.min(1f, alpha));
        }

        void blend(int x, int y, int color, float alpha) {
            if (x < 0 || y < 0 || x >= w || y >= h || alpha <= 0f) {
                return;
            }
            px[y * w + x] = mix(px[y * w + x], color, Math.min(1f, alpha));
        }

        void glow(float cx, float cy, float radius, int color, float strength) {
            int r = (int) Math.ceil(radius * 3);
            for (int y = (int) cy - r; y <= cy + r; y++) {
                for (int x = (int) cx - r; x <= cx + r; x++) {
                    float d2 = ((x + 0.5f - cx) * (x + 0.5f - cx) + (y + 0.5f - cy) * (y + 0.5f - cy)) / (radius * radius);
                    add(x, y, color, strength * (float) Math.exp(-d2 * 1.6f));
                }
            }
        }

        void beam(float x0, float y0, float x1, float y1, float width, int color, float strength) {
            int minX = (int) Math.min(x0, x1) - (int) width * 3;
            int maxX = (int) Math.max(x0, x1) + (int) width * 3;
            int minY = (int) Math.min(y0, y1) - (int) width * 3;
            int maxY = (int) Math.max(y0, y1) + (int) width * 3;
            float vx = x1 - x0;
            float vy = y1 - y0;
            float len2 = vx * vx + vy * vy;
            for (int y = Math.max(0, minY); y <= Math.min(h - 1, maxY); y++) {
                for (int x = Math.max(0, minX); x <= Math.min(w - 1, maxX); x++) {
                    float t = Math.max(0f, Math.min(1f, ((x - x0) * vx + (y - y0) * vy) / len2));
                    float dx = x - (x0 + vx * t);
                    float dy = y - (y0 + vy * t);
                    float spread = width * (0.4f + 1.6f * t);
                    float a = (float) Math.exp(-(dx * dx + dy * dy) / (spread * spread)) * (1f - t);
                    add(x, y, color, strength * a);
                }
            }
        }

        void burst(float cx, float cy, float radius, int color, Random random) {
            glow(cx, cy, radius * 0.5f, color, 0.25f);
            for (int i = 0; i < 26; i++) {
                double angle = i / 26.0 * Math.PI * 2 + random.nextFloat() * 0.2;
                float reach = radius * (0.75f + 0.25f * random.nextFloat());
                for (int s = 0; s < 6; s++) {
                    float t = (s + 1) / 6f;
                    glow(cx + (float) Math.cos(angle) * reach * t, cy + (float) Math.sin(angle) * reach * t + t * t * 3f,
                            0.7f + t * 0.5f, color, 0.25f + 0.6f * t);
                }
            }
        }

        void bolt(float x0, float y0, float x1, float y1, Random random) {
            glow((x0 + x1) / 2, (y0 + y1) / 2, 40f, 0x9AB0FF, 0.25f);
            float x = x0;
            float y = y0;
            int steps = 12;
            for (int i = 1; i <= steps; i++) {
                float t = i / (float) steps;
                float nx = x0 + (x1 - x0) * t + (random.nextFloat() - 0.5f) * 14f;
                float ny = y0 + (y1 - y0) * t;
                beamSegment(x, y, nx, ny);
                x = nx;
                y = ny;
            }
        }

        private void beamSegment(float x0, float y0, float x1, float y1) {
            int n = (int) Math.ceil(Math.hypot(x1 - x0, y1 - y0) * 2);
            for (int i = 0; i <= n; i++) {
                float t = i / (float) n;
                glow(x0 + (x1 - x0) * t, y0 + (y1 - y0) * t, 0.9f, 0xE8EEFF, 0.5f);
            }
        }

        void bar(float cx, float bottom, float width, float height, int color) {
            for (int y = (int) (bottom - height); y < bottom; y++) {
                for (int x = (int) (cx - width / 2); x < cx + width / 2; x++) {
                    boolean gap = ((int) (bottom - y)) % 4 == 0;
                    add(x, y, color, gap ? 0.2f : 0.85f);
                }
            }
            glow(cx, bottom - height / 2, Math.max(width, height / 3), color, 0.12f);
        }

        void ellipse(float cx, float cy, float rx, float ry, int color, float strength) {
            for (int y = (int) (cy - ry * 2); y <= cy + ry * 2; y++) {
                for (int x = (int) (cx - rx * 1.2f); x <= cx + rx * 1.2f; x++) {
                    float nx = (x + 0.5f - cx) / rx;
                    float ny = (y + 0.5f - cy) / ry;
                    float r = (float) Math.sqrt(nx * nx + ny * ny);
                    float a = band01(r, 0.62f, 1f);
                    add(x, y, color, strength * a);
                }
            }
        }

        /** The near half of a ring, drawn again after the planet so it passes in front of it. */
        void ellipseFront(float cx, float cy, float rx, float ry, int color, float strength) {
            for (int y = (int) cy; y <= cy + ry * 2; y++) {
                for (int x = (int) (cx - rx * 1.2f); x <= cx + rx * 1.2f; x++) {
                    float nx = (x + 0.5f - cx) / rx;
                    float ny = (y + 0.5f - cy) / ry;
                    float r = (float) Math.sqrt(nx * nx + ny * ny);
                    blend(x, y, color, strength * band01(r, 0.62f, 1f));
                }
            }
        }

        void ring(float cx, float cy, float radius, int color, float strength) {
            for (int y = (int) (cy - radius * 1.5f); y <= cy + radius * 1.5f; y++) {
                for (int x = (int) (cx - radius * 1.5f); x <= cx + radius * 1.5f; x++) {
                    float r = (float) Math.hypot(x + 0.5f - cx, y + 0.5f - cy) / radius;
                    add(x, y, color, strength * (float) Math.exp(-(r - 1f) * (r - 1f) / 0.012f));
                }
            }
        }

        void stamp(int[] sprite, Sprite kind, float cx, float cy, float halfW, float halfH, int tint, boolean additive) {
            if (sprite == null) {
                return;
            }
            for (int y = (int) (cy - halfH); y < cy + halfH; y++) {
                for (int x = (int) (cx - halfW); x < cx + halfW; x++) {
                    float u = (x + 0.5f - (cx - halfW)) / (halfW * 2);
                    float v = (y + 0.5f - (cy - halfH)) / (halfH * 2);
                    int sx = Math.min(kind.w - 1, Math.max(0, (int) (u * kind.w)));
                    int sy = Math.min(kind.h - 1, Math.max(0, (int) (v * kind.h)));
                    int texel = sprite[sy * kind.w + sx];
                    float a = (texel >>> 24) / 255f;
                    int color = multiply(texel, tint);
                    if (additive) {
                        add(x, y, color, a);
                    } else {
                        blend(x, y, color, a);
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------------------------------------------- sprites

    static int[] sprite(Sprite sprite) {
        int w = sprite.w;
        int h = sprite.h;
        int[] out = new int[w * h];
        SkyNoise noise = new SkyNoise(sprite.ordinal() * 7919L + 13);
        Random random = new Random(sprite.ordinal() * 104729L);
        float[][] craters = new float[40][];
        for (int i = 0; i < craters.length; i++) {
            craters[i] = new float[] {random.nextFloat() * 1.8f - 0.9f, random.nextFloat() * 1.8f - 0.9f,
                    0.03f + random.nextFloat() * random.nextFloat() * 0.16f};
        }
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                // Centered coordinates from -1 to 1; u and v from 0 to 1.
                float u = (x + 0.5f) / w;
                float v = (y + 0.5f) / h;
                float cx = u * 2 - 1;
                float cy = v * 2 - 1;
                float r = (float) Math.sqrt(cx * cx + cy * cy);
                float angle = (float) Math.atan2(cy, cx);
                out[y * w + x] = switch (sprite) {
                    case DOT -> white((float) Math.exp(-r * r * 4.5f) * edge(r));
                    case SPIKE -> white(Math.min(1f, (float) Math.exp(-r * r * 40f)
                            + 0.7f * (float) (Math.exp(-Math.abs(cx) * 45) * Math.exp(-Math.abs(cy) * 2.5))
                            + 0.7f * (float) (Math.exp(-Math.abs(cy) * 45) * Math.exp(-Math.abs(cx) * 2.5))) * edge(r));
                    case BEAM -> white((float) Math.exp(-cx * cx * 7f) * (float) Math.pow(1f - v, 1.3f)
                            * smooth(0f, 0.04f, v));
                    case CURTAIN -> {
                        // v = 0 at the bottom edge of the curtain, where it glows brightest.
                        float up = 1f - v;
                        float circle = (float) (u * Math.PI * 2);
                        float rays = 0.35f + 0.65f * noise.ridged((float) Math.cos(circle) * 3f, (float) Math.sin(circle) * 3f,
                                up * 0.6f, 4);
                        float profile = smooth(0f, 0.06f, up) * (float) Math.exp(-up * 2.8f);
                        yield white(Math.min(1f, profile * rays * 1.4f));
                    }
                    case CLOUD -> {
                        float puff = noise.fbm(cx * 2.2f + 3, cy * 2.2f, 0.5f, 5);
                        float fall = Math.max(0f, 1f - r * r);
                        yield white(smooth(0.35f, 0.72f, puff * (0.55f + 0.6f * fall)) * fall * fall);
                    }
                    case PETAL -> {
                        float e = (cx / 0.55f) * (cx / 0.55f) + ((cy + 0.1f) / 0.95f) * ((cy + 0.1f) / 0.95f);
                        float notch = cy < -0.7f ? smooth(0.08f, 0.2f, Math.abs(cx)) : 1f;
                        int shade = mix(0xFFFFFF, 0xFFD8E6, v);
                        yield argb(smooth(1f, 0.82f, e) * notch, shade);
                    }
                    case MOON -> {
                        float disc = smooth(0.985f, 0.955f, r);
                        float maria = noise.fbm(cx * 2.2f, cy * 2.2f, 1.3f, 5);
                        float shade = 0.92f - 0.42f * smooth(0.5f, 0.7f, maria);
                        for (float[] crater : craters) {
                            float d = (float) Math.hypot(cx - crater[0], cy - crater[1]) / crater[2];
                            if (d < 1.25f) {
                                shade += d < 0.85f ? -0.1f * (1f - d) : 0.08f * (1.25f - d) / 0.4f;
                            }
                        }
                        float limb = 1f - 0.35f * r * r;
                        int color = scale(0xF4EEDF, Math.max(0.25f, Math.min(1f, shade * limb)));
                        yield argb(disc, color);
                    }
                    case PLANET -> {
                        float disc = smooth(0.985f, 0.955f, r);
                        float nz = (float) Math.sqrt(Math.max(0f, 1f - r * r));
                        float lat = cy + 0.08f * noise.fbm(cx * 3f, cy * 3f, 0.7f, 4);
                        float bands = 0.5f + 0.5f * (float) Math.sin(lat * 15f + noise.fbm(cx * 2f, cy * 6f, 2f, 3) * 4f);
                        int surface = mix(mix(0xE8D2A8, 0xC27A4A, bands), 0x8A4A3A, smooth(0.75f, 0.95f, bands) * 0.5f);
                        float light = Math.max(0f, -0.55f * cx - 0.35f * cy + 0.76f * nz);
                        yield argb(disc, scale(surface, 0.12f + 0.95f * light));
                    }
                    case RING -> {
                        float ringBands = 0.55f + 0.45f * noise.fbm(r * 18f, 0.3f, 0.9f, 4);
                        float inBand = smooth(0.52f, 0.56f, r) * smooth(0.99f, 0.95f, r);
                        float cassini = 1f - 0.85f * (float) Math.exp(-(r - 0.78f) * (r - 0.78f) / 0.0004f);
                        yield argb(inBand * ringBands * cassini * 0.85f, mix(0xF2E0B8, 0xC9A27A, ringBands));
                    }
                    case DISK -> {
                        float inBand = smooth(0.26f, 0.32f, r) * smooth(1f, 0.9f, r);
                        float heat = (float) Math.pow(Math.max(0f, 1f - (r - 0.3f) / 0.7f), 1.4f);
                        float swirl = noise.fbm((float) Math.cos(angle * 3 + r * 9) * r * 3f,
                                (float) Math.sin(angle * 3 + r * 9) * r * 3f, r * 6f, 5);
                        int color = mix(mix(0xC2301A, 0xFF9A3A, heat), 0xFFF2D0, heat * heat);
                        yield argb(inBand * Math.min(1f, (0.35f + 0.9f * swirl) * (0.3f + 0.9f * heat)), color);
                    }
                    case CORE -> argb(smooth(0.97f, 0.9f, r), 0x000000);
                    case PHOTON_RING -> white((float) Math.exp(-(r - 0.8f) * (r - 0.8f) / 0.004f) * edge(r)
                            + 0.25f * (float) Math.exp(-(r - 0.8f) * (r - 0.8f) / 0.04f) * edge(r));
                    case VINYL -> {
                        float disc = smooth(0.985f, 0.96f, r) * smooth(0.02f, 0.03f, r);
                        float grooves = 0.5f + 0.5f * (float) Math.sin(r * 820f + noise.noise(r * 40f, 0f, 0f) * 3f);
                        float deadWax = smooth(0.30f, 0.33f, r) * smooth(0.95f, 0.92f, r);
                        float tone = 0.05f + 0.035f * grooves * deadWax + 0.06f * smooth(0.94f, 0.985f, r);
                        yield argb(disc, scale(0xFFFFFF, tone));
                    }
                    case LABEL -> {
                        float disc = smooth(1f, 0.96f, r);
                        float hole = smooth(0.07f, 0.09f, r);
                        // A lighter half and two lines, so the label shows the record turning.
                        float half = cx > 0 ? 1f : 0.82f;
                        float lines = 1f - 0.35f * (float) Math.exp(-(r - 0.62f) * (r - 0.62f) / 0.0008f)
                                - 0.35f * (float) Math.exp(-(r - 0.72f) * (r - 0.72f) / 0.0005f);
                        yield argb(disc * hole, scale(0xFFFFFF, half * lines));
                    }
                    case SHEEN -> {
                        float wedge = (float) Math.pow(Math.abs(Math.cos(angle - Math.PI / 4)), 26);
                        float ring = smooth(0.32f, 0.36f, r) * smooth(0.97f, 0.93f, r);
                        yield white(0.4f * wedge * ring);
                    }
                    case RETRO_SUN -> {
                        float disc = smooth(0.985f, 0.95f, r);
                        int color = mix(0xFFE36B, 0xFF3E8A, v);
                        float stripes = 1f;
                        if (cy > -0.05f) {
                            float t = (cy + 0.05f) / 1.05f;
                            float phase = (float) ((t * 7.5 + t * t * 2) % 1.0);
                            stripes = phase < 0.12f + 0.45f * t ? 0f : 1f;
                        }
                        yield argb(disc * stripes, color);
                    }
                    case RAINBOW -> {
                        float ax = cx;
                        float ay = (v - 1f) * 2f;
                        float rr = (float) Math.sqrt(ax * ax + ay * ay);
                        float t = (rr - 0.72f) / 0.24f;
                        float a = smooth(0f, 0.15f, t) * smooth(1f, 0.85f, t) * 0.6f;
                        yield argb(a, spectrum(1f - Math.max(0f, Math.min(1f, t))));
                    }
                    case EQ_BAR -> {
                        float core = (float) Math.exp(-cx * cx * 3f);
                        boolean gap = (y % 8) < 2;
                        yield white(Math.min(1f, core * (gap ? 0.25f : 0.9f) * (0.55f + 0.45f * (1f - v))));
                    }
                    case STREAK -> white(Math.min(1f, (float) Math.pow(u, 2.4f) * (float) Math.exp(-cy * cy * 18f)
                            + (float) Math.exp(-((u - 0.94f) * (u - 0.94f) * 900f + cy * cy * 30f))));
                };
            }
        }
        return out;
    }

    // -------------------------------------------------------------------------------------------------------- colors

    /** Color along the elevation: pairs of elevation in degrees and color, from low to high. */
    static int gradient(float el, Object... stops) {
        float first = ((Number) stops[0]).floatValue();
        if (el <= first) {
            return (int) stops[1];
        }
        for (int i = 2; i < stops.length; i += 2) {
            float at = ((Number) stops[i]).floatValue();
            if (el <= at) {
                float from = ((Number) stops[i - 2]).floatValue();
                float t = (el - from) / (at - from);
                return mix((int) stops[i - 1], (int) stops[i + 1], t * t * (3 - 2 * t));
            }
        }
        return (int) stops[stops.length - 1];
    }

    /** 1 between the two elevations, fading out softly over a third of their span on either side. */
    static float band(float el, float from, float to) {
        float soft = Math.max(1f, (to - from) / 3f);
        return smooth(from - soft, from, el) * smooth(to + soft, to, el);
    }

    static float smooth(float edge0, float edge1, float x) {
        float t = Math.max(0f, Math.min(1f, (x - edge0) / (edge1 - edge0)));
        return t * t * (3 - 2 * t);
    }

    private static float band01(float r, float inner, float outer) {
        return smooth(inner - 0.04f, inner, r) * smooth(outer, outer - 0.06f, r);
    }

    private static float edge(float r) {
        return smooth(1f, 0.85f, r);
    }

    static int mix(int a, int b, float t) {
        t = Math.max(0f, Math.min(1f, t));
        int r = (int) (((a >> 16) & 0xFF) + (((b >> 16) & 0xFF) - ((a >> 16) & 0xFF)) * t);
        int g = (int) (((a >> 8) & 0xFF) + (((b >> 8) & 0xFF) - ((a >> 8) & 0xFF)) * t);
        int bl = (int) ((a & 0xFF) + ((b & 0xFF) - (a & 0xFF)) * t);
        return 0xFF000000 | r << 16 | g << 8 | bl;
    }

    /** {@code base} lit up by {@code color} as strong as {@code amount}, as light adds up. */
    static int add(int base, int color, float amount) {
        amount = Math.max(0f, amount);
        int r = Math.min(255, (int) (((base >> 16) & 0xFF) + ((color >> 16) & 0xFF) * amount));
        int g = Math.min(255, (int) (((base >> 8) & 0xFF) + ((color >> 8) & 0xFF) * amount));
        int b = Math.min(255, (int) ((base & 0xFF) + (color & 0xFF) * amount));
        return 0xFF000000 | r << 16 | g << 8 | b;
    }

    static int scale(int color, float factor) {
        int r = Math.min(255, (int) (((color >> 16) & 0xFF) * factor));
        int g = Math.min(255, (int) (((color >> 8) & 0xFF) * factor));
        int b = Math.min(255, (int) ((color & 0xFF) * factor));
        return 0xFF000000 | r << 16 | g << 8 | b;
    }

    static int multiply(int a, int b) {
        int r = ((a >> 16) & 0xFF) * ((b >> 16) & 0xFF) / 255;
        int g = ((a >> 8) & 0xFF) * ((b >> 8) & 0xFF) / 255;
        int bl = (a & 0xFF) * (b & 0xFF) / 255;
        return 0xFF000000 | r << 16 | g << 8 | bl;
    }

    /** Red at 0 through yellow, green and blue to violet at 1. */
    static int spectrum(float t) {
        int[] stops = {0xFF3B3B, 0xFF9A3B, 0xFFE83B, 0x4BE36B, 0x3BA8FF, 0x7A5BFF};
        float scaled = Math.max(0f, Math.min(1f, t)) * (stops.length - 1);
        int i = Math.min(stops.length - 2, (int) scaled);
        return mix(stops[i], stops[i + 1], scaled - i);
    }

    private static int white(float alpha) {
        return argb(alpha, 0xFFFFFF);
    }

    private static int argb(float alpha, int rgb) {
        int a = Math.round(Math.max(0f, Math.min(1f, alpha)) * 255);
        return a << 24 | (rgb & 0xFFFFFF);
    }
}
