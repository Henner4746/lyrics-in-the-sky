package de.henner.lyricsinthesky.gui;

import de.henner.lyricsinthesky.render.Glass;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.util.ARGB;

/**
 * The "Prachtvoll" look of the menus: a slow aurora behind the panel, light running around its rim, the title in
 * moving colors, rows sliding in one after the other, sparks when something is switched and notes rising behind the
 * options. Everything runs on the clock, so it looks the same at any frame rate; the minimal look draws none of it.
 */
final class Fancy {
    /** The accent colors everything cycles through: ice blue, lilac, rose, gold. */
    private static final int[] ACCENTS = {0x4FC3F7, 0xB388FF, 0xFF80AB, 0xFFD54F};
    private static final long START = System.nanoTime();
    private static final String[] NOTES = {"♪", "♫", "♩"};

    private Fancy() {}

    /** Seconds since the game started using the menus; the clock of every animation. */
    static float seconds() {
        return (System.nanoTime() - START) / 1e9f;
    }

    /** An accent color; one full cycle through all of them per 1.0 of {@code phase}. */
    static int accent(float phase) {
        float p = (phase - (float) Math.floor(phase)) * ACCENTS.length;
        int i = Math.min(ACCENTS.length - 1, (int) p);
        return mix(ACCENTS[i], ACCENTS[(i + 1) % ACCENTS.length], p - i);
    }

    /** {@code a} at 0 to {@code b} at 1, channel by channel; the alpha of the result is {@code a}'s. */
    static int mix(int a, int b, float t) {
        t = clamp01(t);
        int r = Math.round(ARGB.red(a) + (ARGB.red(b) - ARGB.red(a)) * t);
        int g = Math.round(ARGB.green(a) + (ARGB.green(b) - ARGB.green(a)) * t);
        int bl = Math.round(ARGB.blue(a) + (ARGB.blue(b) - ARGB.blue(a)) * t);
        return ARGB.color(ARGB.alpha(a), r, g, bl);
    }

    static float clamp01(float t) {
        return t < 0f ? 0f : t > 1f ? 1f : t;
    }

    static float easeOut(float t) {
        float u = 1f - clamp01(t);
        return 1f - u * u * u;
    }

    /** Overshoots a little before settling: things spring into place. */
    static float springOut(float t) {
        t = clamp01(t);
        float c = 1.70158f;
        float u = t - 1f;
        return 1f + (c + 1f) * u * u * u + c * u * u;
    }

    /** Moves {@code current} towards {@code target}, the same way at any frame rate; {@code rate} per second. */
    static float approach(float current, float target, float dt, float rate) {
        return current + (target - current) * (1f - (float) Math.exp(-rate * dt));
    }

    /** {@code color} with its alpha multiplied by {@code fade}. */
    static int fade(int color, float fade) {
        return fade >= 1f ? color : ARGB.color(ARGB.alpha(color) / 255f * clamp01(fade), color & 0xFFFFFF);
    }

    /** What moves in one open menu: when it opened, when the page changed, the sparks and notes in flight. */
    static final class Scene {
        private final long opened = System.nanoTime();
        private long pageShown = opened;
        private long lastFrame = opened;
        private float dt;
        private float noteTimer;
        private final List<Spark> behind = new ArrayList<>();
        private final List<Spark> front = new ArrayList<>();
        private final Random random = new Random();

        private static final class Spark {
            float x;
            float y;
            float vx;
            float vy;
            float life;
            final float maxLife;
            final int color;
            final String note;

            Spark(float x, float y, float vx, float vy, float life, int color, String note) {
                this.x = x;
                this.y = y;
                this.vx = vx;
                this.vy = vy;
                this.life = life;
                this.maxLife = life;
                this.color = color;
                this.note = note;
            }
        }

        /** The rows come in again, one after the other: a new page, or the same one built anew. */
        void pageShown() {
            pageShown = System.nanoTime();
        }

        /** Once per frame, before anything of the menu is drawn. */
        void frame() {
            long now = System.nanoTime();
            dt = Math.min(0.1f, (now - lastFrame) / 1e9f);
            lastFrame = now;
        }

        float dt() {
            return dt;
        }

        /** The panel springing open, 0 to a little over 1 and back to 1, over a third of a second. */
        float opening() {
            return springOut((System.nanoTime() - opened) / 1e9f / 0.38f);
        }

        /** How far row {@code index} has slid in, 0 to 1; rows follow each other 35 ms apart. */
        float appear(int index) {
            return easeOut(((System.nanoTime() - pageShown) / 1e9f - index * 0.035f) / 0.34f);
        }

        /**
         * Scales what is drawn next around the panel's middle while it opens; undo with {@code popMatrix()}. Returns
         * whether it pushed a matrix.
         */
        boolean openTransform(GuiGraphicsExtractor graphics, float cx, float cy) {
            float open = opening();
            if (open >= 0.999f && open <= 1.001f && (System.nanoTime() - opened) > 400_000_000L) {
                return false;
            }
            float scale = 0.9f + 0.1f * open;
            graphics.pose().pushMatrix();
            graphics.pose().translate(cx, cy + (1f - Math.min(1f, open)) * 10f);
            graphics.pose().scale(scale, scale);
            graphics.pose().translate(-cx, -cy);
            return true;
        }

        /**
         * Three ribbons of aurora drifting across the screen behind the panel, in the accent colors, and a soft
         * darkening towards the bottom so the panel stands out.
         */
        void backdrop(GuiGraphicsExtractor graphics, int width, int height) {
            float t = seconds();
            float shown = clamp01((System.nanoTime() - opened) / 1e9f / 0.5f);
            for (int ribbon = 0; ribbon < 3; ribbon++) {
                float base = height * (0.2f + 0.24f * ribbon);
                float amplitude = height * 0.07f;
                int half = 16 + 7 * ribbon;
                int color = accent(t * 0.04f + ribbon * 0.3f);
                for (int x = 0; x < width; x += 4) {
                    float wave = (float) (Math.sin(x * 0.011 + t * (0.45 + 0.12 * ribbon) + ribbon * 2.1)
                            + 0.35 * Math.sin(x * 0.027 - t * 0.8 + ribbon));
                    int center = Math.round(base + wave * amplitude);
                    float glow = shown * (0.13f + 0.07f * (float) Math.sin(x * 0.02 + t * 1.3 + ribbon));
                    graphics.fillGradient(x, center - half, x + 4, center, ARGB.color(0f, color), ARGB.color(glow, color));
                    graphics.fillGradient(x, center, x + 4, center + half, ARGB.color(glow, color), ARGB.color(0f, color));
                }
            }
            graphics.fillGradient(0, height / 2, width, height, 0x00000000, ARGB.color(0.35f * shown, 0x000000));
        }

        /** Two lights running around the panel's edge in the accent colors, over a faint glowing rim. */
        void rim(GuiGraphicsExtractor graphics, int x0, int y0, int x1, int y1, int radius) {
            float t = seconds();
            int w = x1 - x0 - 2 * radius;
            int h = y1 - y0 - 2 * radius;
            int perimeter = 2 * (w + h);
            if (w <= 0 || h <= 0) {
                return;
            }
            float head = (t * 0.22f) % 1f * perimeter;
            for (int s = 0; s < perimeter; s += 3) {
                float d = Math.abs(s - head);
                d = Math.min(d, perimeter - d);
                float d2 = Math.abs(d - perimeter / 2f);
                float light = (float) (Math.exp(-(d / 46f) * (d / 46f)) + Math.exp(-(d2 / 46f) * (d2 / 46f)));
                int color = ARGB.color(Math.min(0.95f, 0.10f + 0.85f * light), accent(s / (float) perimeter + t * 0.05f));
                int len = Math.min(3, perimeter - s);
                if (s < w) {
                    graphics.fill(x0 + radius + s, y0, x0 + radius + s + len, y0 + 1, color);
                } else if (s < w + h) {
                    int y = y0 + radius + (s - w);
                    graphics.fill(x1 - 1, y, x1, y + len, color);
                } else if (s < 2 * w + h) {
                    int x = x1 - radius - (s - w - h);
                    graphics.fill(x - len, y1 - 1, x, y1, color);
                } else {
                    int y = y1 - radius - (s - 2 * w - h);
                    graphics.fill(x0, y - len, x0 + 1, y, color);
                }
            }
        }

        /** The title in colors drifting through the accents, each letter rising and falling a little after the last. */
        void title(GuiGraphicsExtractor graphics, Font font, Style style, String text, int x, int y) {
            float t = seconds();
            float bob = (float) Math.sin(t * 4.0) * 1.2f;
            graphics.pose().pushMatrix();
            graphics.pose().translate(0f, bob);
            graphics.text(font, Component.literal("♫").withStyle(style), x, y, mix(0xFFFFFFFF, accent(t * 0.1f), 0.8f), false);
            graphics.pose().popMatrix();
            x += font.width(Component.literal("♫ ").withStyle(style));
            for (int i = 0; i < text.length(); ) {
                int cp = text.codePointAt(i);
                Component letter = Component.literal(new String(Character.toChars(cp))).withStyle(style);
                float lift = (float) Math.sin(t * 3.0 - i * 0.45) * 1.1f;
                int color = mix(0xFFFFFFFF, accent(t * 0.12f + i * 0.035f), 0.55f);
                graphics.pose().pushMatrix();
                graphics.pose().translate(0f, lift);
                graphics.text(font, letter, x, y, color, false);
                graphics.pose().popMatrix();
                x += font.width(letter);
                i += Character.charCount(cp);
            }
        }

        /** Sparks flying out from where something was switched; drawn above the options. */
        void burst(float x, float y) {
            for (int i = 0; i < 16; i++) {
                double angle = random.nextDouble() * Math.PI * 2;
                float speed = 28f + random.nextFloat() * 46f;
                int color = random.nextInt(4) == 0 ? 0xFFFFFF : accent(random.nextFloat());
                front.add(new Spark(x, y, (float) Math.cos(angle) * speed, (float) Math.sin(angle) * speed - 12f,
                        0.45f + random.nextFloat() * 0.35f, color, null));
            }
        }

        /**
         * Notes rising slowly from the bottom of the screen through the aurora; call before the panel, which then
         * covers them, so they never run through text.
         */
        void notes(GuiGraphicsExtractor graphics, Font font, Style style, int width, int height) {
            noteTimer -= dt;
            if (noteTimer <= 0f && width > 40) {
                noteTimer = 0.22f + random.nextFloat() * 0.25f;
                behind.add(new Spark(8 + random.nextFloat() * (width - 16), height - 2, 0f,
                        -(14f + random.nextFloat() * 12f), 6f + random.nextFloat() * 3f, accent(random.nextFloat()),
                        NOTES[random.nextInt(NOTES.length)]));
            }
            draw(graphics, font, style, behind, 0.55f, 0);
        }

        /** The sparks of switching; call last, above everything. */
        void sparks(GuiGraphicsExtractor graphics, Font font, Style style) {
            draw(graphics, font, style, front, 1f, Integer.MIN_VALUE);
        }

        private void draw(GuiGraphicsExtractor graphics, Font font, Style style, List<Spark> sparks, float strength, int top) {
            float t = seconds();
            for (Iterator<Spark> it = sparks.iterator(); it.hasNext(); ) {
                Spark spark = it.next();
                spark.life -= dt;
                spark.x += spark.vx * dt;
                spark.y += spark.vy * dt;
                if (spark.note == null) {
                    spark.vy += 60f * dt;
                    spark.vx *= (float) Math.exp(-2.5 * dt);
                }
                if (spark.life <= 0f || spark.y < top) {
                    it.remove();
                    continue;
                }
                float left = spark.life / spark.maxLife;
                float alpha = strength * Math.min(1f, left * 2.2f) * Math.min(1f, (1f - left) * 8f + 0.2f);
                if (spark.note != null) {
                    float sway = (float) Math.sin(t * 1.7 + spark.maxLife * 9) * 4f;
                    graphics.pose().pushMatrix();
                    graphics.pose().translate(spark.x + sway, spark.y);
                    graphics.text(font, Component.literal(spark.note).withStyle(style), 0, 0, ARGB.color(alpha, spark.color),
                            false);
                    graphics.pose().popMatrix();
                } else {
                    int x = Math.round(spark.x);
                    int y = Math.round(spark.y);
                    int color = ARGB.color(alpha, spark.color);
                    graphics.fill(x - 1, y, x + 2, y + 1, color);
                    graphics.fill(x, y - 1, x + 1, y + 2, color);
                }
            }
        }
    }

    /** A soft glow of {@code color} around a rounded rectangle, {@code spread} pixels wide. */
    static void glow(GuiGraphicsExtractor graphics, int x0, int y0, int x1, int y1, int radius, int spread, float alpha,
            int color) {
        for (int s = spread; s >= 1; s--) {
            Glass.fill(graphics, x0 - s, y0 - s, x1 + s, y1 + s, radius + s, ARGB.color(alpha * 0.32f / s, color));
        }
    }
}
