package de.henner.lyricsinthesky.render;

import de.henner.lyricsinthesky.SkyLyricsConfig;
import java.util.Locale;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.util.ARGB;

/**
 * A small night sky for the menu with a sample line sung word by word in the player's look: font, palette or own
 * colors, size, opacity, glow, shadow, outline, capitals, the beat and the animation speed. Everything changes at once
 * when a setting does, so the effect of each option shows before the menu is closed.
 */
public final class LookPreview {
    private static final String[] LINE = "Wort für Wort am Himmel".split(" ");
    /** One word every this long, a pause, then the line starts over. */
    private static final long WORD_MS = 520;
    private static final long CYCLE_MS = LINE.length * WORD_MS + 1_400;
    private static final int STARS = 46;

    private LookPreview() {}

    /** Colors of the player's palette: text, text at the bottom, accent start and end, glow; for the menu's swatches. */
    public static int[] colors(SkyLyricsConfig config) {
        Palette palette = Palette.pick(config, "preview", "around".equals(config.mode));
        return new int[] {palette.text, palette.textBottom, palette.accentStart, palette.accentEnd, palette.nightGlow};
    }

    /** Draws the preview into the box; {@code alpha} fades all of it, {@code accent} tints sky and floor. */
    public static void draw(GuiGraphicsExtractor graphics, Font font, SkyLyricsConfig config, int x0, int y0, int x1, int y1,
            float alpha, int accent) {
        draw(graphics, font, config, x0, y0, x1, y1, alpha, accent, true);
    }

    /** @param scenery the night sky with sun and floor; without, only the sample line on what is behind the box */
    public static void draw(GuiGraphicsExtractor graphics, Font font, SkyLyricsConfig config, int x0, int y0, int x1, int y1,
            float alpha, int accent, boolean scenery) {
        long nowMs = System.nanoTime() / 1_000_000L;
        if (!scenery) {
            sample(graphics, font, config, (x0 + x1) / 2, (y0 + y1) / 2 - 2, x0, x1, alpha, nowMs);
            return;
        }
        int width = x1 - x0;
        int height = y1 - y0;
        int horizon = y0 + Math.round(height * 0.68f);
        // Sky: deep night at the top, glowing in the accent towards the horizon.
        graphics.fillGradient(x0, y0, x1, horizon - height / 5, ARGB.color(alpha, 0x050409),
                ARGB.color(alpha, ARGB.srgbLerp(0.18f, 0x0B0818, accent)));
        graphics.fillGradient(x0, horizon - height / 5, x1, horizon, ARGB.color(alpha, ARGB.srgbLerp(0.18f, 0x0B0818, accent)),
                ARGB.color(alpha, ARGB.srgbLerp(0.55f, 0x1A1030, accent)));
        for (int i = 0; i < STARS; i++) {
            int sx = x0 + 2 + (int) (Easing.hash(7, i) * (width - 4));
            int sy = y0 + 2 + (int) (Easing.hash(13, i) * (horizon - y0 - 14));
            float twinkle = 0.35f + 0.65f * (0.5f + 0.5f * (float) Math.sin(nowMs / 700.0 + i * 1.7));
            graphics.fill(sx, sy, sx + 1, sy + 1, ARGB.color(alpha * twinkle * 0.8f, 0xFFFFFF));
        }
        sun(graphics, (x0 + x1) / 2, horizon, Math.min(height / 3, 26), alpha, accent);
        // Floor: a dark plane with a perspective grid in the accent, running towards the viewer.
        graphics.fillGradient(x0, horizon, x1, y1, ARGB.color(alpha, 0x0A0614), ARGB.color(alpha, 0x020104));
        graphics.fill(x0, horizon, x1, horizon + 1, ARGB.color(alpha * 0.85f, ARGB.srgbLerp(0.4f, accent, 0xFFFFFF)));
        float scroll = (nowMs % 1600) / 1600f;
        for (int k = 0; k < 7; k++) {
            float t = (k + scroll) / 7f;
            int gy = horizon + Math.round((y1 - horizon) * t * t);
            graphics.fill(x0, gy, x1, gy + 1, ARGB.color(alpha * (0.15f + 0.5f * t), accent));
        }
        int center = (x0 + x1) / 2;
        for (int k = -6; k <= 6; k++) {
            // Lines fanning out from the vanishing point, drawn as short steps.
            float spread = k / 6f;
            for (int gy = horizon + 1; gy < y1; gy += 1) {
                float t = (gy - horizon) / (float) (y1 - horizon);
                int gx = center + Math.round(spread * width * (0.08f + 0.9f * t));
                if (gx >= x0 && gx < x1) {
                    graphics.fill(gx, gy, gx + 1, gy + 1, ARGB.color(alpha * (0.12f + 0.45f * t), accent));
                }
            }
        }
        sample(graphics, font, config, center, y0 + Math.round(height * 0.34f), x0, x1, alpha, nowMs);
    }

    /** A striped synthwave sun sinking into the horizon. */
    private static void sun(GuiGraphicsExtractor graphics, int cx, int horizon, int radius, float alpha, int accent) {
        for (int dy = -radius; dy < 0; dy++) {
            int half = (int) Math.round(Math.sqrt(radius * radius - dy * dy));
            float t = (dy + radius) / (float) radius;
            // Stripes widen towards the horizon, as on the posters.
            if (t > 0.45f && ((dy + radius) % 5) >= 5 - Math.round((t - 0.45f) * 6f)) {
                continue;
            }
            int color = ARGB.srgbLerp(t, 0xFFE3A6, ARGB.srgbLerp(0.5f, accent, 0xFF4FA3));
            graphics.fill(cx - half, horizon + dy, cx + half, horizon + dy + 1, ARGB.color(alpha * 0.9f, color));
        }
    }

    /** The sample line, built up word by word, the word being sung in the accent. */
    private static void sample(GuiGraphicsExtractor graphics, Font font, SkyLyricsConfig config, int cx, int cy, int left,
            int right, float alpha, long nowMs) {
        Palette palette = Palette.pick(config, "preview", "around".equals(config.mode));
        Style style = SkyLyricsRenderer.regularStyle(config.font);
        long at = nowMs % CYCLE_MS;
        int sung = (int) Math.min(LINE.length - 1, at / WORD_MS);
        boolean pause = at >= LINE.length * WORD_MS;
        String[] words = new String[LINE.length];
        int total = 0;
        int space = font.width(Component.literal(" ").withStyle(style));
        for (int i = 0; i < LINE.length; i++) {
            words[i] = config.uppercase ? LINE[i].toUpperCase(Locale.ROOT) : LINE[i];
            total += font.width(Component.literal(words[i]).withStyle(style)) + (i > 0 ? space : 0);
        }
        float scale = Math.max(0.7f, Math.min(1.8f, config.scale)) * 1.25f;
        // The beat: a short swell on every new word, as strong as set.
        float sinceWord = pause ? 1e9f : (at - sung * WORD_MS);
        float kick = config.beatBounce ? config.beatStrength * 0.07f * Beat.envelope((long) sinceWord) : 0f;
        graphics.pose().pushMatrix();
        graphics.pose().translate(cx, cy);
        graphics.pose().scale(scale * (1f + kick), scale * (1f + kick));
        int x = -total / 2;
        float opacity = alpha * config.opacity;
        // Each word fades in over its first moments, faster or slower with the animation speed.
        float fadeMs = 320f / config.animationSpeed;
        for (int i = 0; i < words.length; i++) {
            Component word = Component.literal(words[i]).withStyle(style);
            int w = font.width(word);
            float shown = pause ? 1f - Easing.clamp01((at - LINE.length * WORD_MS) / (fadeMs * 2f))
                    : i < sung ? 1f : i == sung ? Easing.clamp01(sinceWord / fadeMs) : 0f;
            float a = opacity * shown;
            if (a > 0.01f) {
                int color = i == sung && !pause ? palette.accent(i / (float) (words.length - 1)) : palette.text;
                int y = -4 + Math.round((1f - shown) * 4f);
                effects(graphics, font, word, x, y, a, config, palette);
                graphics.text(font, word, x, y, ARGB.color(a, color), false);
            }
            x += w + space;
        }
        graphics.pose().popMatrix();
    }

    /** Glow, shadow and outline behind a word, the way the sky draws them. */
    private static void effects(GuiGraphicsExtractor graphics, Font font, Component word, int x, int y, float a,
            SkyLyricsConfig config, Palette palette) {
        if (config.glow > 0.01f) {
            int glow = ARGB.color(Math.min(1f, a * 0.16f * config.glow), palette.nightGlow);
            for (int[] d : new int[][] {{-1, 0}, {1, 0}, {0, -1}, {0, 1}}) {
                graphics.text(font, word, x + d[0], y + d[1], glow, false);
            }
        }
        if (config.shadow > 0.01f) {
            graphics.text(font, word, x + 1, y + 1, ARGB.color(Math.min(1f, a * 0.35f * config.shadow), 0x0A0C18), false);
        }
        if (config.outline) {
            int ring = ARGB.color(Math.min(1f, a * 0.9f), config.outlineColor);
            int d = config.outlineWidth > 1.4f ? 2 : 1;
            for (int[] o : new int[][] {{-d, 0}, {d, 0}, {0, -d}, {0, d}, {-d, -d}, {d, d}, {-d, d}, {d, -d}}) {
                graphics.text(font, word, x + o[0], y + o[1], ring, false);
            }
        }
    }
}
