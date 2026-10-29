package de.henner.lyricsinthesky.render;

import de.henner.lyricsinthesky.LyricsController;
import de.henner.lyricsinthesky.LyricsInTheSkyClient;
import de.henner.lyricsinthesky.SkyLyricsConfig;
import de.henner.lyricsinthesky.lyrics.Lyrics;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;

/**
 * The sky painted behind the lyrics in place of the game's own, frame by frame: the panorama on a dome around the
 * player, and on top of it what moves with the music. Aurora curtains, beams, the equalizer and the vinyl's sheen
 * light up on every sung word; a new line sends up fireworks or a shooting star; strong words strike lightning. The
 * thing a sky is named after (moon, planet, record, setting sun) stands where the player looked when it was chosen.
 * Overworld only, and not with a shader pack, which paints its own sky.
 */
public final class CustomSky {
    public static final CustomSky INSTANCE = new CustomSky();
    /** Distance of the dome; the sky pass has a rotation-only view, so this only has to fit in front of the far plane. */
    private static final float R = 80f;
    private static final float[] DOME_RINGS = {-40, -20, -8, -2, 2, 6, 10, 15, 20, 26, 33, 41, 50, 60, 70, 80, 90};
    private static final int DOME_SEGMENTS = 64;
    /** Seconds a newly chosen sky takes to fade in over the last one. */
    private static final float CROSSFADE_S = 1.4f;

    private static final int ORDER_PREVIOUS = 0;
    private static final int ORDER_DOME = 1;
    private static final int ORDER_DOME_GLOW = 2;
    private static final int ORDER_STARS = 3;
    private static final int ORDER_BACK = 4;
    private static final int ORDER_HERO_BACK = 5;
    private static final int ORDER_HERO = 6;
    private static final int ORDER_HERO_FRONT = 7;
    private static final int ORDER_LIGHT = 8;
    private static final int ORDER_FRONT = 9;
    private static final int ORDER_FLASH = 10;

    final SkyTextures textures = new SkyTextures();
    private final SkyMesh mesh = new SkyMesh();
    private final Random random = new Random();

    private String shown = "off";
    private String previous;
    /** How far {@link #shown} has faded in over {@link #previous}. */
    private float crossfade = 1f;
    /** All of it, eased: 0 shows the game's sky. */
    private float visible;
    private long lastNanos;
    /** Seconds of sky time, faster or slower by the player's tempo. */
    private float time;
    private float heroYaw = Float.NaN;
    private String lastTrack = "";
    private int lastLine = -1;
    /** The kick of the word being sung, rising at once and dying away: what everything pulses with. */
    private float pulse;
    private float sinceStrike = 99f;
    private float sinceBurst = 99f;
    private float nextAmbient = 3f;
    private float accentA;
    private int colorA = 0x3CFFB0;
    private int colorB = 0xB070FF;

    private float[] starAz = new float[0];
    private float[] starEl;
    private float[] starSize;
    private float[] starPhase;
    private int[] starColor;
    private final List<Burst> bursts = new ArrayList<>();
    private final List<Strike> strikes = new ArrayList<>();
    private final List<Streak> streaks = new ArrayList<>();
    private final List<Ripple> ripples = new ArrayList<>();
    private float[] eqLevel = new float[64];
    private float[][] petals = new float[0][];
    private float[][] clouds = new float[0][];

    private record Burst(float az, float el, float born, int color, float[] dirs, float speed) {}

    private record Strike(float az, float born, float[] path, float[] branch) {}

    private record Streak(float az, float el, float born, float angle) {}

    private record Ripple(float born) {}

    private CustomSky() {}

    /** The sky chosen and drawn now, for the menu and the self test. */
    public String shown() {
        return visible > 0.01f ? shown : "off";
    }

    /** Vertices built in the last frame, for the self test. */
    public int lastVertexCount() {
        return mesh.vertexCount();
    }

    /** The menu's picture of a sky, or {@code null} while it is still being painted. */
    public Identifier postcard(String style) {
        return textures.postcard(style);
    }

    private static final java.util.Map<String, int[]> PREVIEW_COLORS = new java.util.HashMap<>();

    /** A sky's colors high up and at the horizon, for the menu while its picture is painted. */
    public static int[] previewColors(String style) {
        return PREVIEW_COLORS.computeIfAbsent(style, key -> {
            if ("off".equals(key)) {
                return new int[] {0xFF78A7FF, 0xFFC0D8FF};
            }
            SkyNoise noise = new SkyNoise(key.hashCode());
            float high = (float) Math.toRadians(55);
            float low = (float) Math.toRadians(3);
            return new int[] {SkyArt.skyColor(key, noise, 0f, (float) Math.sin(high), (float) Math.cos(high)),
                    SkyArt.skyColor(key, noise, 0f, (float) Math.sin(low), (float) Math.cos(low))};
        });
    }

    /** This frame's sky, or {@code null} for the game's own. Called in the sky pass, before the lyrics. */
    SkyMesh frame(SkyLyricsConfig config, float skyLuminance) {
        Minecraft minecraft = Minecraft.getInstance();
        long now = System.nanoTime();
        float dt = lastNanos == 0 ? 0f : Math.min(0.1f, (now - lastNanos) / 1e9f);
        lastNanos = now;
        if (minecraft.level == null || minecraft.level.dimension() != Level.OVERWORLD) {
            visible = 0f;
            return null;
        }
        float yaw = minecraft.gameRenderer.gameRenderState().levelRenderState.cameraRenderState.yRot;
        String wanted = config.sky;
        if (!wanted.equals(shown)) {
            if (!"off".equals(shown) && visible > 0.01f) {
                if (previous != null && !previous.equals(wanted)) {
                    textures.releasePanorama(previous);
                }
                previous = shown;
            }
            shown = wanted;
            crossfade = 0f;
            heroYaw = yaw;
            seed(shown);
        }
        if (Float.isNaN(heroYaw)) {
            heroYaw = yaw;
            seed(shown);
        }
        float target = "off".equals(shown) ? 0f : config.skyStrength;
        if (config.skyNightOnly && !SkyLyricsConfig.DAY_SKIES.contains(shown)) {
            target *= SkyArt.smooth(0.62f, 0.22f, skyLuminance);
        }
        if ("off".equals(shown) && previous != null) {
            // Switched off: the last sky fades out on its own.
            target = 0f;
        }
        visible += Math.signum(target - visible) * Math.min(Math.abs(target - visible), dt / 1.2f);
        if (visible < 0.004f && "off".equals(shown)) {
            if (previous != null) {
                textures.releasePanorama(previous);
                previous = null;
            }
            mesh.clear();
            return null;
        }
        time += dt * config.skySpeed;
        listen(config, dt);

        mesh.clear();
        float rain = minecraft.level.getRainLevel(1f);
        Identifier newDome = "off".equals(shown) ? null : textures.panorama(shown);
        if (newDome != null) {
            crossfade = Math.min(1f, crossfade + dt / CROSSFADE_S);
        }
        if (previous != null) {
            Identifier oldDome = textures.panorama(previous);
            if (oldDome != null && crossfade < 1f) {
                dome(oldDome, ORDER_PREVIOUS, visible, rain, previous);
            } else if (crossfade >= 1f) {
                textures.releasePanorama(previous);
                previous = null;
            }
        }
        if (newDome == null) {
            return mesh.drawn().isEmpty() ? null : mesh;
        }
        float alpha = visible * (previous == null ? 1f : crossfade);
        dome(newDome, ORDER_DOME, alpha, rain, shown);
        decorate(shown, alpha * (1f - 0.7f * rain * ("storm".equals(shown) ? 0f : 1f)), yaw, dt, config);
        return mesh;
    }

    /** Follows the song: the kick of each word, and each new line. */
    private void listen(SkyLyricsConfig config, float dt) {
        pulse = Math.max(0f, pulse - dt * 2.6f);
        boolean newLine = false;
        LyricsController controller = LyricsInTheSkyClient.controller();
        LyricsController.Timeline timeline = controller == null ? null : controller.timeline(System.currentTimeMillis());
        if (timeline != null && timeline.playing() && config.skyReactive) {
            pulse = Math.max(pulse, SkyLyricsRenderer.kick(timeline).strength());
            Lyrics lyrics = timeline.lyrics();
            int line = lyrics == null || lyrics.isEmpty() ? -1 : lyrics.indexAt(timeline.positionMs());
            if (!timeline.trackKey().equals(lastTrack)) {
                lastTrack = timeline.trackKey();
                lastLine = line;
            } else if (line != lastLine) {
                newLine = line >= 0;
                lastLine = line;
            }
        }
        if (config.skySongColors && timeline != null) {
            Palette palette = Palette.pick(config, timeline.trackKey(), false);
            colorA = palette.accentStart & 0xFFFFFF;
            colorB = palette.accentEnd & 0xFFFFFF;
        } else {
            colorA = switch (shown) {
                case "equalizer", "lightshow" -> 0x2EF2FF;
                case "vinyl" -> 0xFF5CB0;
                default -> 0x3CFFB0;
            };
            colorB = switch (shown) {
                case "equalizer", "lightshow" -> 0xFF45D8;
                case "vinyl" -> 0xFFB45C;
                default -> 0xB070FF;
            };
        }
        accentA = newLine ? 1f : Math.max(0f, accentA - dt * 1.5f);
        sinceStrike += dt;
        sinceBurst += dt;
        nextAmbient -= dt;
        boolean ambient = nextAmbient <= 0f;
        if (ambient) {
            nextAmbient = 3f + random.nextFloat() * 4f;
        }
        Minecraft minecraft = Minecraft.getInstance();
        float yaw = minecraft.gameRenderer.gameRenderState().levelRenderState.cameraRenderState.yRot;
        switch (shown) {
            case "fireworks" -> {
                if (newLine || pulse > 0.8f && sinceBurst > 0.45f || ambient && sinceBurst > 2.5f) {
                    burst(yaw);
                    if (newLine && random.nextBoolean()) {
                        burst(yaw);
                    }
                }
            }
            case "storm" -> {
                if (pulse > 0.75f && sinceStrike > 2.2f && random.nextFloat() < 0.6f || ambient && sinceStrike > 6f) {
                    strike(yaw);
                }
            }
            case "stars", "nebula", "ringworld", "blackhole" -> {
                if (newLine && random.nextFloat() < 0.7f || ambient && random.nextFloat() < 0.4f) {
                    streak(yaw);
                }
            }
            case "vinyl" -> {
                if (pulse > 0.85f && (ripples.isEmpty() || time - ripples.get(ripples.size() - 1).born() > 0.5f)) {
                    ripples.add(new Ripple(time));
                }
            }
            default -> { }
        }
        bursts.removeIf(burst -> time - burst.born() > 3.2f);
        strikes.removeIf(strike -> time - strike.born() > 0.6f);
        streaks.removeIf(streak -> time - streak.born() > 1.2f);
        ripples.removeIf(ripple -> time - ripple.born() > 2.4f);
    }

    // ----------------------------------------------------------------------------------------------------- the dome

    private void dome(Identifier texture, int order, float alpha, float rain, String style) {
        if (alpha <= 0.002f) {
            return;
        }
        SkyMesh.Batch batch = mesh.batch(texture, false, true, order);
        float dim = "storm".equals(style) ? 1f : 1f - 0.4f * rain;
        int color = argb(alpha, SkyArt.scale(0xFFFFFF, dim));
        float hero = (float) Math.toRadians(heroYaw);
        // Nebulae and the milky way turn slowly, as the real sky does.
        float turn = switch (style) {
            case "stars", "nebula", "ringworld", "blackhole" -> time * 0.006f;
            case "storm" -> time * 0.02f;
            case "pastel", "rainbow", "petals" -> time * 0.004f;
            default -> 0f;
        };
        for (int ring = 0; ring + 1 < DOME_RINGS.length; ring++) {
            float el0 = (float) Math.toRadians(DOME_RINGS[ring]);
            float el1 = (float) Math.toRadians(DOME_RINGS[ring + 1]);
            float v0 = 0.5f - DOME_RINGS[ring] / 180f;
            float v1 = 0.5f - DOME_RINGS[ring + 1] / 180f;
            for (int s = 0; s < DOME_SEGMENTS; s++) {
                float a0 = (float) (s * Math.PI * 2 / DOME_SEGMENTS);
                float a1 = (float) ((s + 1) * Math.PI * 2 / DOME_SEGMENTS);
                float u0 = (float) ((a0 - turn) / (Math.PI * 2));
                float u1 = (float) ((a1 - turn) / (Math.PI * 2));
                sphereVertex(batch, hero + a0, el0, R, u0, v0, color);
                sphereVertex(batch, hero + a1, el0, R, u1, v0, color);
                sphereVertex(batch, hero + a1, el1, R, u1, v1, color);
                sphereVertex(batch, hero + a0, el1, R, u0, v1, color);
            }
        }
        if ("nebula".equals(style) && pulse > 0.02f) {
            // The gas glows up with every word: the panorama once more, added on top.
            SkyMesh.Batch glow = mesh.batch(texture, true, true, ORDER_DOME_GLOW);
            int light = argb(alpha * 0.28f * pulse, 0xFFFFFF);
            for (int i = 0; i < batch.vertices; i++) {
                int at = i * 5;
                glow.vertex(batch.data[at], batch.data[at + 1], batch.data[at + 2], batch.data[at + 3], batch.data[at + 4], light);
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ what's on it

    private void decorate(String style, float alpha, float yaw, float dt, SkyLyricsConfig config) {
        if (alpha <= 0.002f) {
            return;
        }
        float hero = (float) Math.toRadians(heroYaw);
        stars(style, alpha);
        switch (style) {
            case "northern" -> aurora(hero, alpha);
            case "lightshow" -> beams(hero, alpha);
            case "fireworks" -> fireworks(alpha);
            case "storm" -> {
                clouds(alpha, 0x3A3F48, 0x9AA6C0, ORDER_FRONT, dt, 0.012f);
                lightning(alpha);
            }
            case "equalizer" -> equalizer(alpha, dt);
            case "retro" -> {
                sprite(SkyArt.Sprite.DOT, true, ORDER_HERO_BACK, hero, 6f, 34f, 34f, 0f,
                        argb(alpha * (0.4f + 0.25f * pulse), 0xFF5C9A));
                sprite(SkyArt.Sprite.RETRO_SUN, false, ORDER_HERO, hero, 6f, 13f, 13f, 0f, argb(alpha, 0xFFFFFF));
            }
            case "afterglow" -> {
                sprite(SkyArt.Sprite.DOT, true, ORDER_HERO_BACK, hero, 2f, 30f, 30f, 0f, argb(alpha * 0.45f, 0xFFB060));
                sprite(SkyArt.Sprite.DOT, true, ORDER_HERO, hero, 2f, 6f, 6f, 0f, argb(alpha, 0xFFF0C0));
                for (int i = 0; i < 12; i++) {
                    float angle = (float) (i * Math.PI * 2 / 12 + time * 0.03f);
                    rayFromSun(hero, 2f, angle, 32f + 10f * (float) Math.sin(i * 1.7f), alpha * (0.1f + 0.06f * pulse));
                }
                clouds(alpha, 0xC77A6A, 0xFFC890, ORDER_FRONT, dt, 0.008f);
            }
            case "petals" -> petals(alpha, dt);
            case "fullmoon" -> {
                sprite(SkyArt.Sprite.DOT, true, ORDER_HERO_BACK, hero, 30f, 24f, 24f, 0f,
                        argb(alpha * (0.32f + 0.12f * pulse), 0x7A9ACC));
                sprite(SkyArt.Sprite.MOON, false, ORDER_HERO, hero, 30f, 7f, 7f, -0.4f, argb(alpha, 0xFFFFFF));
                clouds(alpha * 0.6f, 0x5A6A88, 0xC8D4EC, ORDER_FRONT, dt, 0.006f);
            }
            case "ringworld" -> {
                sprite(SkyArt.Sprite.DOT, true, ORDER_BACK, hero, 20f, 34f, 34f, 0f, argb(alpha * 0.2f, 0xC2A07A));
                tilted(SkyArt.Sprite.RING, false, hero, 20f, 30f, 1.32f, 0f, alpha * (0.85f + 0.15f * pulse), 0xFFFFFF, true);
                sprite(SkyArt.Sprite.PLANET, false, ORDER_HERO, hero, 20f, 14f, 14f, 0.25f, argb(alpha, 0xFFFFFF));
                tilted(SkyArt.Sprite.RING, false, hero, 20f, 30f, 1.32f, 0f, alpha * (0.85f + 0.15f * pulse), 0xFFFFFF, false);
                float drift = time * 0.01f;
                sprite(SkyArt.Sprite.MOON, false, ORDER_HERO, hero + 0.75f + drift, 34f, 1.6f, 1.6f, 0f, argb(alpha, 0xD8C8B8));
                sprite(SkyArt.Sprite.MOON, false, ORDER_HERO, hero - 0.6f + drift * 0.7f, 12f, 1.1f, 1.1f, 0f,
                        argb(alpha, 0xB8C8E8));
            }
            case "blackhole" -> {
                float spin = time * 0.35f;
                sprite(SkyArt.Sprite.DOT, true, ORDER_BACK, hero, 24f, 30f, 30f, 0f,
                        argb(alpha * (0.22f + 0.12f * pulse), 0xFF7A3A));
                tilted(SkyArt.Sprite.DISK, true, hero, 24f, 20f, 1.36f, spin, alpha * (0.75f + 0.35f * pulse), 0xFFFFFF, true);
                // The far side of the disk, bent over the hole by its gravity.
                sprite(SkyArt.Sprite.DISK, true, ORDER_HERO_BACK, hero, 24f, 8f, 8f, spin, argb(alpha * 0.4f, 0xFFD0A0));
                sprite(SkyArt.Sprite.CORE, false, ORDER_HERO, hero, 24f, 4.6f, 4.6f, 0f, argb(alpha, 0xFFFFFF));
                sprite(SkyArt.Sprite.PHOTON_RING, true, ORDER_HERO_FRONT, hero, 24f, 6.2f, 6.2f, 0f,
                        argb(alpha * (0.8f + 0.4f * pulse), 0xFFE0B0));
                tilted(SkyArt.Sprite.DISK, true, hero, 24f, 20f, 1.36f, spin, alpha * (0.75f + 0.35f * pulse), 0xFFFFFF, false);
            }
            case "vinyl" -> {
                float spin = -time * 1.1f;
                sprite(SkyArt.Sprite.DOT, true, ORDER_BACK, hero, 26f, 32f, 32f, 0f, argb(alpha * 0.25f, colorA));
                sprite(SkyArt.Sprite.VINYL, false, ORDER_HERO, hero, 26f, 13f, 13f, spin, argb(alpha, 0xFFFFFF));
                sprite(SkyArt.Sprite.LABEL, false, ORDER_HERO_FRONT, hero, 26f, 4.1f, 4.1f, spin, argb(alpha, colorA));
                sprite(SkyArt.Sprite.SHEEN, true, ORDER_LIGHT, hero, 26f, 13f, 13f, 0f,
                        argb(alpha * (0.55f + 0.7f * pulse), 0xFFFFFF));
                for (Ripple ripple : ripples) {
                    float age = (time - ripple.born()) / 2.4f;
                    float size = 13f + 22f * age;
                    sprite(SkyArt.Sprite.PHOTON_RING, true, ORDER_BACK, hero, 26f, size, size, 0f,
                            argb(alpha * 0.5f * (1f - age) * (1f - age), colorB));
                }
            }
            case "pastel" -> {
                sprite(SkyArt.Sprite.DOT, true, ORDER_HERO_BACK, hero + 0.7f, 35f, 20f, 20f, 0f, argb(alpha * 0.45f, 0xFFF4D8));
                clouds(alpha, 0xE8C8D8, 0xFFFFFF, ORDER_FRONT, dt, 0.007f);
            }
            case "rainbow" -> {
                sprite(SkyArt.Sprite.RAINBOW, false, ORDER_HERO, hero, 18f, 44f, 22f, 0f,
                        argb(alpha * (0.75f + 0.25f * pulse), 0xFFFFFF));
                clouds(alpha, 0xD8E4F0, 0xFFFFFF, ORDER_FRONT, dt, 0.006f);
            }
            default -> { }
        }
        streaks(alpha);
    }

    private void stars(String style, float alpha) {
        Identifier dot = textures.sprite(SkyArt.Sprite.DOT);
        Identifier spike = textures.sprite(SkyArt.Sprite.SPIKE);
        if (dot == null || spike == null) {
            return;
        }
        float boost = 1f + 0.6f * pulse;
        for (int i = 0; i < starAz.length; i++) {
            float twinkle = 0.62f + 0.38f * (float) Math.sin(time * (1.3f + starPhase[i] * 2.2f) + starPhase[i] * 40f);
            float a = alpha * twinkle * Math.min(1f, boost * (0.45f + starSize[i]));
            float size = 0.18f + 0.32f * starSize[i] * starSize[i];
            boolean bright = starSize[i] > 0.93f;
            SkyArt.Sprite sprite = bright ? SkyArt.Sprite.SPIKE : SkyArt.Sprite.DOT;
            float half = bright ? size * 3.2f : size;
            sprite(sprite, true, ORDER_STARS, starAz[i], starEl[i], half, half, 0f, argb(a, starColor[i]));
        }
    }

    private void aurora(float hero, float alpha) {
        float[] centers = {-1.05f, -0.2f, 0.65f, 1.7f};
        float[] spans = {2.4f, 2.0f, 2.6f, 1.8f};
        float[] bases = {7f, 11f, 8f, 13f};
        float[] heights = {30f, 22f, 34f, 18f};
        for (int c = 0; c < centers.length; c++) {
            float strength = alpha * (0.55f + 0.45f * pulse) * (c % 2 == 0 ? 1f : 0.75f);
            curtain(hero + centers[c] + 0.08f * (float) Math.sin(time * 0.11f + c), spans[c], bases[c],
                    heights[c] * (1f + 0.22f * pulse), R - 2f - c, c * 1.7f, strength);
        }
    }

    private void curtain(float center, float span, float baseEl, float height, float radius, float seed, float strength) {
        Identifier texture = textures.sprite(SkyArt.Sprite.CURTAIN);
        if (texture == null) {
            return;
        }
        SkyMesh.Batch batch = mesh.batch(texture, true, true, ORDER_LIGHT);
        int segments = 40;
        for (int i = 0; i < segments; i++) {
            float s0 = i / (float) segments;
            float s1 = (i + 1) / (float) segments;
            addCurtainQuad(batch, center, span, baseEl, height, radius, seed, strength, s0, s1);
        }
    }

    private void addCurtainQuad(SkyMesh.Batch batch, float center, float span, float baseEl, float height, float radius,
            float seed, float strength, float s0, float s1) {
        float[] s = {s0, s1};
        float[] az = new float[2];
        float[] bottom = new float[2];
        float[] top = new float[2];
        int[] lowColor = new int[2];
        int[] highColor = new int[2];
        for (int k = 0; k < 2; k++) {
            az[k] = center + (s[k] - 0.5f) * span;
            float wave = 2.6f * (float) Math.sin(az[k] * 3f + time * 0.35f + seed)
                    + 1.4f * (float) Math.sin(az[k] * 7.3f - time * 0.62f + seed * 2f);
            float edge = (float) Math.sin(Math.PI * s[k]);
            float rise = height * (0.65f + 0.35f * (float) Math.sin(az[k] * 5.1f + time * 0.21f + seed));
            bottom[k] = (float) Math.toRadians(baseEl + wave);
            top[k] = (float) Math.toRadians(baseEl + wave + rise);
            lowColor[k] = argb(strength * edge, colorA);
            highColor[k] = argb(strength * edge * 0.7f, colorB);
        }
        float u0 = s0 * 4f + time * 0.015f;
        float u1 = s1 * 4f + time * 0.015f;
        sphereVertex(batch, az[0], bottom[0], radius, u0, 1f, lowColor[0]);
        sphereVertex(batch, az[1], bottom[1], radius, u1, 1f, lowColor[1]);
        sphereVertex(batch, az[1], top[1], radius, u1, 0f, highColor[1]);
        sphereVertex(batch, az[0], top[0], radius, u0, 0f, highColor[0]);
    }

    private void beams(float hero, float alpha) {
        Identifier texture = textures.sprite(SkyArt.Sprite.BEAM);
        if (texture == null) {
            return;
        }
        SkyMesh.Batch batch = mesh.batch(texture, true, false, ORDER_LIGHT);
        int count = 10;
        for (int k = 0; k < count; k++) {
            float base = hero + (float) (k * Math.PI * 2 / count);
            float sway = (float) Math.toRadians(32 * Math.sin(time * 0.5f + k * 1.3f));
            float tipEl = (float) Math.toRadians(52 + 16 * Math.sin(time * 0.37f + k * 0.7f));
            int color = k % 3 == 0 ? colorA : k % 3 == 1 ? colorB : 0xFFE36B;
            float flash = 0.3f + 0.7f * pulse + 0.5f * accentA;
            beam(batch, base, (float) Math.toRadians(-3), base + sway, tipEl, 0.5f, 7f, argb(Math.min(1f, alpha * flash), color));
        }
        // The light of the stage, low over the horizon.
        for (int k = 0; k < 6; k++) {
            float az = hero + (float) (k * Math.PI * 2 / 6) + time * 0.02f;
            sprite(SkyArt.Sprite.DOT, true, ORDER_BACK, az, 0f, 26f, 9f, 0f,
                    argb(alpha * (0.18f + 0.22f * pulse), k % 2 == 0 ? colorA : colorB));
        }
    }

    private void beam(SkyMesh.Batch batch, float az0, float el0, float az1, float el1, float width0, float width1, int color) {
        float[] b = direction(az0, el0, R);
        float[] t = direction(az1, el1, R);
        float[] mid = {(b[0] + t[0]) / 2, (b[1] + t[1]) / 2, (b[2] + t[2]) / 2};
        float[] along = {t[0] - b[0], t[1] - b[1], t[2] - b[2]};
        float[] side = normalize(cross(along, mid));
        batch.vertex(b[0] - side[0] * width0, b[1] - side[1] * width0, b[2] - side[2] * width0, 0f, 0f, color);
        batch.vertex(b[0] + side[0] * width0, b[1] + side[1] * width0, b[2] + side[2] * width0, 1f, 0f, color);
        batch.vertex(t[0] + side[0] * width1, t[1] + side[1] * width1, t[2] + side[2] * width1, 1f, 1f, color);
        batch.vertex(t[0] - side[0] * width1, t[1] - side[1] * width1, t[2] - side[2] * width1, 0f, 1f, color);
    }

    private void rayFromSun(float hero, float sunEl, float angle, float length, float alpha) {
        Identifier texture = textures.sprite(SkyArt.Sprite.BEAM);
        if (texture == null) {
            return;
        }
        SkyMesh.Batch batch = mesh.batch(texture, true, false, ORDER_HERO_BACK);
        float tipAz = hero + (float) Math.toRadians(length * Math.cos(angle));
        float tipEl = (float) Math.toRadians(sunEl + length * Math.sin(angle) * 0.8f);
        beam(batch, hero, (float) Math.toRadians(sunEl), tipAz, tipEl, 0.4f, 6f, argb(alpha, 0xFFD890));
    }

    private void fireworks(float alpha) {
        for (Burst burst : bursts) {
            float age = time - burst.born();
            float rise = 0.7f;
            if (age < rise) {
                // The rocket on its way up, a spark with a short tail.
                float t = age / rise;
                float el = (float) Math.toRadians(-2 + (burst.el() + 2) * (1 - (1 - t) * (1 - t)));
                sprite(SkyArt.Sprite.DOT, true, ORDER_LIGHT, burst.az(), (float) Math.toDegrees(el), 0.5f, 0.5f, 0f,
                        argb(alpha, 0xFFE8C0));
                continue;
            }
            float t = age - rise;
            float life = 2.2f;
            if (t > life) {
                continue;
            }
            float fade = (float) Math.pow(1f - t / life, 1.4f);
            if (t < 0.25f) {
                sprite(SkyArt.Sprite.DOT, true, ORDER_FLASH, burst.az(), burst.el(), 9f, 9f, 0f,
                        argb(alpha * 0.5f * (1f - t / 0.25f), burst.color()));
            }
            float[] dirs = burst.dirs();
            for (int i = 0; i < dirs.length; i += 2) {
                for (int ghost = 0; ghost < 3; ghost++) {
                    float tg = Math.max(0f, t - ghost * 0.07f);
                    float reach = burst.speed() * (1f - (float) Math.exp(-tg * 2.2f)) / 2.2f;
                    float el = burst.el() + dirs[i + 1] * reach - 2.2f * tg * tg;
                    float az = burst.az() + (float) Math.toRadians(dirs[i] * reach / Math.cos(Math.toRadians(burst.el())));
                    float sparkle = ghost == 0 && (i / 2 + (int) (time * 20)) % 7 == 0 ? 1.4f : 1f;
                    int color = ghost == 0 ? SkyArt.mix(0xFFFFFF, burst.color(), Math.min(1f, t * 3f)) : burst.color();
                    sprite(SkyArt.Sprite.DOT, true, ORDER_LIGHT, az, el, 0.42f - ghost * 0.08f, 0.42f - ghost * 0.08f, 0f,
                            argb(Math.min(1f, alpha * fade * sparkle * (1f - ghost * 0.3f)), color));
                }
            }
        }
    }

    private void burst(float yaw) {
        if (bursts.size() > 14) {
            return;
        }
        float az = (float) Math.toRadians(yaw + (random.nextFloat() - 0.5f) * 90f);
        float el = 22f + random.nextFloat() * 26f;
        int[] colors = {0xFF5C8A, 0xFFD36B, 0x6BE0FF, 0xB08CFF, 0x7CFFA0, colorA, colorB};
        int count = 54 + random.nextInt(24);
        float[] dirs = new float[count * 2];
        for (int i = 0; i < count; i++) {
            double angle = random.nextDouble() * Math.PI * 2;
            float speed = 0.7f + 0.3f * random.nextFloat();
            dirs[i * 2] = (float) Math.cos(angle) * speed;
            dirs[i * 2 + 1] = (float) Math.sin(angle) * speed;
        }
        bursts.add(new Burst(az, el, time, colors[random.nextInt(colors.length)], dirs, 16f + random.nextFloat() * 8f));
        sinceBurst = 0f;
    }

    private void lightning(float alpha) {
        Identifier texture = textures.sprite(SkyArt.Sprite.BEAM);
        if (texture == null) {
            return;
        }
        SkyMesh.Batch batch = mesh.batch(texture, true, false, ORDER_LIGHT);
        for (Strike strike : strikes) {
            float age = time - strike.born();
            // Bright, nearly out, bright again, gone: the flicker of a real strike.
            float light = age < 0.06f ? 1f : age < 0.14f ? 0.25f : age < 0.3f ? 0.9f * (1f - (age - 0.14f) / 0.16f) : 0f;
            light *= 1f - Math.max(0f, (age - 0.3f) / 0.3f);
            if (light <= 0f) {
                continue;
            }
            sprite(SkyArt.Sprite.DOT, true, ORDER_FLASH, strike.az(), 25f, 40f, 30f, 0f, argb(alpha * 0.35f * light, 0xB8C8FF));
            polyline(batch, strike.az(), strike.path(), 0.35f, argb(Math.min(1f, alpha * light * 1.2f), 0xEEF2FF));
            polyline(batch, strike.az(), strike.branch(), 0.22f, argb(alpha * light * 0.8f, 0xD8E0FF));
        }
    }

    private void strike(float yaw) {
        float az = (float) Math.toRadians(yaw + (random.nextFloat() - 0.5f) * 80f);
        int points = 15;
        float[] path = new float[points * 2];
        float x = 0f;
        for (int i = 0; i < points; i++) {
            path[i * 2] = x;
            path[i * 2 + 1] = 58f - i * (60f / (points - 1));
            x += (random.nextFloat() - 0.5f) * 3.2f;
        }
        int from = 3 + random.nextInt(5);
        float[] branch = new float[6 * 2];
        float bx = path[from * 2];
        float by = path[from * 2 + 1];
        float lean = random.nextBoolean() ? 1f : -1f;
        for (int i = 0; i < 6; i++) {
            branch[i * 2] = bx;
            branch[i * 2 + 1] = by;
            bx += lean * (1f + random.nextFloat() * 1.6f);
            by -= 2.2f + random.nextFloat() * 1.5f;
        }
        strikes.add(new Strike(az, time, path, branch));
        sinceStrike = 0f;
    }

    /** A line of thin beams through points given as (degrees to the side of {@code az}, elevation in degrees). */
    private void polyline(SkyMesh.Batch batch, float az, float[] points, float width, int color) {
        for (int i = 0; i + 3 < points.length; i += 2) {
            float a0 = az + (float) Math.toRadians(points[i]);
            float a1 = az + (float) Math.toRadians(points[i + 2]);
            beam(batch, a0, (float) Math.toRadians(points[i + 1]), a1, (float) Math.toRadians(points[i + 3]), width, width, color);
        }
    }

    private void streaks(float alpha) {
        for (Streak streak : streaks) {
            float age = (time - streak.born()) / 1.2f;
            float a = alpha * (float) Math.sin(Math.PI * Math.min(1f, age));
            float travel = age * 22f;
            float az = streak.az() + (float) Math.toRadians(Math.cos(streak.angle()) * travel);
            float el = streak.el() + (float) Math.sin(streak.angle()) * travel;
            sprite(SkyArt.Sprite.STREAK, true, ORDER_LIGHT, az, el, 5f, 0.5f, streak.angle(), argb(a, 0xF0F4FF));
        }
    }

    private void streak(float yaw) {
        float az = (float) Math.toRadians(yaw + (random.nextFloat() - 0.5f) * 70f);
        float el = 30f + random.nextFloat() * 30f;
        float angle = (float) Math.toRadians(random.nextBoolean() ? -25 - random.nextFloat() * 20 : -155 + random.nextFloat() * 20);
        streaks.add(new Streak(az, el, time, angle));
    }

    private void equalizer(float alpha, float dt) {
        Identifier bar = textures.sprite(SkyArt.Sprite.EQ_BAR);
        if (bar == null) {
            return;
        }
        SkyMesh.Batch batch = mesh.batch(bar, true, false, ORDER_LIGHT);
        float hero = (float) Math.toRadians(heroYaw);
        int count = eqLevel.length;
        float width = (float) (Math.PI * 2 / count) * 0.32f;
        for (int i = 0; i < count; i++) {
            float wobble = 0.5f + 0.5f * (float) Math.sin(i * 1.93f + time * 3.1f);
            float sway = 0.5f + 0.5f * (float) Math.sin(i * 0.61f - time * 1.7f);
            float target = 0.12f + 0.55f * pulse * (0.45f + 0.55f * wobble) + 0.22f * sway * (0.3f + pulse);
            eqLevel[i] += (target - eqLevel[i]) * Math.min(1f, dt * (target > eqLevel[i] ? 18f : 4f));
            float az = hero + (float) (i * Math.PI * 2 / count);
            float top = (float) Math.toRadians(-2 + 4 + 26 * eqLevel[i]);
            float bottom = (float) Math.toRadians(-2);
            int color = argb(alpha * 0.9f, SkyArt.mix(colorA, colorB, 0.5f + 0.5f * (float) Math.sin(i * Math.PI * 2 / count * 2)));
            sphereVertex(batch, az - width, bottom, R - 3f, 0f, 1f, color);
            sphereVertex(batch, az + width, bottom, R - 3f, 1f, 1f, color);
            sphereVertex(batch, az + width, top, R - 3f, 1f, 0f, color);
            sphereVertex(batch, az - width, top, R - 3f, 0f, 0f, color);
            sprite(SkyArt.Sprite.DOT, true, ORDER_FLASH, az, (float) Math.toDegrees(top) + 0.8f, 1.1f, 0.5f, 0f,
                    argb(alpha * 0.8f, 0xFFFFFF));
        }
    }

    private void petals(float alpha, float dt) {
        float gust = 0.02f + 0.12f * pulse;
        for (float[] petal : petals) {
            // az, el (degrees), roll, spin, fall speed, size, tint
            petal[0] += gust * dt * (0.6f + petal[5]);
            petal[1] -= petal[4] * dt;
            petal[2] += petal[3] * dt * (1f + 2f * pulse);
            if (petal[1] < -6f) {
                petal[1] = 55f + random.nextFloat() * 25f;
                petal[0] = (float) (random.nextFloat() * Math.PI * 2);
            }
            int tint = SkyArt.mix(0xFFB8D4, 0xFFFFFF, petal[6]);
            sprite(SkyArt.Sprite.PETAL, false, ORDER_FRONT, petal[0], petal[1], petal[5], petal[5] * 0.8f, petal[2],
                    argb(alpha * 0.95f, tint));
        }
    }

    private void clouds(float alpha, int shadow, int light, int order, float dt, float speed) {
        boolean flashing = !strikes.isEmpty() && time - strikes.get(strikes.size() - 1).born() < 0.35f;
        for (float[] cloud : clouds) {
            // az, el, size, drift factor, shade
            cloud[0] += speed * dt * cloud[3];
            int color = SkyArt.mix(shadow, light, flashing ? 1f : cloud[4]);
            float bob = 0.4f * pulse * (float) Math.sin(cloud[0] * 3f);
            sprite(SkyArt.Sprite.CLOUD, false, order, cloud[0], cloud[1] + bob, cloud[2], cloud[2] * 0.55f, 0f,
                    argb(alpha * 0.85f, color));
        }
    }

    /** Places the stars, petals and clouds of a style, the same every time for the same style. */
    private void seed(String style) {
        Random seeded = new Random(style.hashCode() * 977L + 3);
        int count = switch (style) {
            case "stars" -> 1500;
            case "ringworld" -> 900;
            case "blackhole" -> 700;
            case "nebula", "northern", "equalizer" -> 550;
            case "vinyl", "fireworks" -> 420;
            case "fullmoon" -> 280;
            case "retro", "lightshow" -> 220;
            case "petals" -> 120;
            case "afterglow" -> 40;
            default -> 0;
        };
        float minEl = "retro".equals(style) ? 28f : "afterglow".equals(style) ? 45f : 1f;
        starAz = new float[count];
        starEl = new float[count];
        starSize = new float[count];
        starPhase = new float[count];
        starColor = new int[count];
        for (int i = 0; i < count; i++) {
            starAz[i] = (float) (seeded.nextFloat() * Math.PI * 2);
            // Evenly over the dome above minEl, not crowded at the zenith.
            float lowSin = (float) Math.sin(Math.toRadians(minEl));
            starEl[i] = (float) Math.toDegrees(Math.asin(lowSin + (1f - lowSin) * seeded.nextFloat()));
            starSize[i] = seeded.nextFloat();
            starPhase[i] = seeded.nextFloat();
            float warmth = seeded.nextFloat();
            starColor[i] = warmth < 0.15f ? 0xFFD8B0 : warmth < 0.35f ? 0xC8D8FF : 0xFFFFFF;
        }
        boolean petalSky = "petals".equals(style);
        petals = new float[petalSky ? 170 : 0][];
        for (int i = 0; i < petals.length; i++) {
            petals[i] = new float[] {(float) (seeded.nextFloat() * Math.PI * 2), -6f + seeded.nextFloat() * 86f,
                    seeded.nextFloat() * 6f, (seeded.nextFloat() - 0.5f) * 2.4f, 2f + seeded.nextFloat() * 3.5f,
                    0.45f + seeded.nextFloat() * 0.5f, seeded.nextFloat()};
        }
        int cloudCount = switch (style) {
            case "storm" -> 70;
            case "pastel", "rainbow" -> 34;
            case "afterglow" -> 26;
            case "fullmoon" -> 14;
            default -> 0;
        };
        clouds = new float[cloudCount][];
        for (int i = 0; i < cloudCount; i++) {
            float low = "storm".equals(style) ? 6f : 4f;
            float high = "storm".equals(style) ? 45f : "fullmoon".equals(style) ? 38f : 30f;
            clouds[i] = new float[] {(float) (seeded.nextFloat() * Math.PI * 2), low + seeded.nextFloat() * (high - low),
                    "storm".equals(style) ? 9f + seeded.nextFloat() * 9f : 5f + seeded.nextFloat() * 7f,
                    0.6f + seeded.nextFloat() * 0.8f, seeded.nextFloat()};
        }
        bursts.clear();
        strikes.clear();
        streaks.clear();
        ripples.clear();
        eqLevel = new float[64];
    }

    // ---------------------------------------------------------------------------------------------------- geometry

    /**
     * A flat picture facing the player at a direction, like the sun: half its width and height in degrees, turned by
     * {@code roll} radians.
     */
    private void sprite(SkyArt.Sprite sprite, boolean glow, int order, float az, float elDeg, float halfWDeg, float halfHDeg,
            float roll, int color) {
        if ((color >>> 24) == 0) {
            return;
        }
        Identifier texture = textures.sprite(sprite);
        if (texture == null) {
            return;
        }
        SkyMesh.Batch batch = mesh.batch(texture, glow, false, order);
        float radius = sprite == SkyArt.Sprite.DOT || sprite == SkyArt.Sprite.SPIKE ? R - 6f : R - 4f;
        float[] f = direction(az, (float) Math.toRadians(elDeg), 1f);
        float[] right = normalize(new float[] {-f[2], 0f, f[0]});
        if (Float.isNaN(right[0])) {
            right = new float[] {1f, 0f, 0f};
        }
        float[] up = cross(right, f);
        float c = (float) Math.cos(roll);
        float s = (float) Math.sin(roll);
        float[] a = {right[0] * c + up[0] * s, right[1] * c + up[1] * s, right[2] * c + up[2] * s};
        float[] b = {up[0] * c - right[0] * s, up[1] * c - right[1] * s, up[2] * c - right[2] * s};
        float hw = radius * (float) Math.tan(Math.toRadians(Math.min(70f, halfWDeg)));
        float hh = radius * (float) Math.tan(Math.toRadians(Math.min(70f, halfHDeg)));
        float cx = f[0] * radius;
        float cy = f[1] * radius;
        float cz = f[2] * radius;
        batch.vertex(cx - a[0] * hw - b[0] * hh, cy - a[1] * hw - b[1] * hh, cz - a[2] * hw - b[2] * hh, 0f, 1f, color);
        batch.vertex(cx + a[0] * hw - b[0] * hh, cy + a[1] * hw - b[1] * hh, cz + a[2] * hw - b[2] * hh, 1f, 1f, color);
        batch.vertex(cx + a[0] * hw + b[0] * hh, cy + a[1] * hw + b[1] * hh, cz + a[2] * hw + b[2] * hh, 1f, 0f, color);
        batch.vertex(cx - a[0] * hw + b[0] * hh, cy - a[1] * hw + b[1] * hh, cz - a[2] * hw + b[2] * hh, 0f, 0f, color);
    }

    /**
     * A ring or disk around a body, seen nearly edge-on: half of it, the far half drawn before the body and the near
     * half after, so the body hides the one and the other passes in front of it.
     */
    private void tilted(SkyArt.Sprite sprite, boolean glow, float az, float elDeg, float halfDeg, float tilt, float spin,
            float alpha, int color, boolean far) {
        Identifier texture = textures.sprite(sprite);
        if (texture == null || alpha <= 0.002f) {
            return;
        }
        SkyMesh.Batch batch = mesh.batch(texture, glow, false, far ? ORDER_HERO_BACK : ORDER_HERO_FRONT);
        float radius = R - 4f;
        float[] f = direction(az, (float) Math.toRadians(elDeg), 1f);
        float[] right = normalize(new float[] {-f[2], 0f, f[0]});
        float[] up = cross(right, f);
        // The ring's plane: across the view, and tipped back away from the player by tilt.
        float[] depth = {up[0] * (float) Math.cos(tilt) + f[0] * (float) Math.sin(tilt),
                up[1] * (float) Math.cos(tilt) + f[1] * (float) Math.sin(tilt),
                up[2] * (float) Math.cos(tilt) + f[2] * (float) Math.sin(tilt)};
        float size = radius * (float) Math.tan(Math.toRadians(halfDeg));
        float c = (float) Math.cos(spin);
        float s = (float) Math.sin(spin);
        int argb = argb(alpha, color);
        // The far half is the side of +depth (tipped away from the player).
        float[][] corners = far
                ? new float[][] {{-1f, 0f}, {1f, 0f}, {1f, 1f}, {-1f, 1f}}
                : new float[][] {{-1f, -1f}, {1f, -1f}, {1f, 0f}, {-1f, 0f}};
        for (float[] corner : corners) {
            float x = corner[0];
            float y = corner[1];
            float px = f[0] * radius + (right[0] * x + depth[0] * y) * size;
            float py = f[1] * radius + (right[1] * x + depth[1] * y) * size;
            float pz = f[2] * radius + (right[2] * x + depth[2] * y) * size;
            // The texture turns within the ring's plane.
            float tu = 0.5f + 0.5f * (x * c - y * s);
            float tv = 0.5f + 0.5f * (x * s + y * c);
            batch.vertex(px, py, pz, tu, tv, argb);
        }
    }

    private static void sphereVertex(SkyMesh.Batch batch, float az, float el, float radius, float u, float v, int color) {
        float cosEl = (float) Math.cos(el);
        batch.vertex((float) -Math.sin(az) * cosEl * radius, (float) Math.sin(el) * radius,
                (float) Math.cos(az) * cosEl * radius, u, v, color);
    }

    private static float[] direction(float az, float el, float radius) {
        float cosEl = (float) Math.cos(el);
        return new float[] {(float) -Math.sin(az) * cosEl * radius, (float) Math.sin(el) * radius,
                (float) Math.cos(az) * cosEl * radius};
    }

    private static float[] cross(float[] a, float[] b) {
        return new float[] {a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
    }

    private static float[] normalize(float[] v) {
        float length = (float) Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
        return length < 1e-5f ? new float[] {Float.NaN, 0f, 0f} : new float[] {v[0] / length, v[1] / length, v[2] / length};
    }

    private static int argb(float alpha, int rgb) {
        int a = Math.round(Math.max(0f, Math.min(1f, alpha)) * 255);
        return a << 24 | (rgb & 0xFFFFFF);
    }
}
