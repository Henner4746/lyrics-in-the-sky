package de.henner.lyricsinthesky.render;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.font.TextRenderable;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.util.ARGB;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * One lyric line (or the title card) living in the sky: words laid out on a gentle arc, each popping in
 * when it is sung, glowing softly, and dissolving when the line is over.
 *
 * <p>Layout happens once in font pixels (x right, y down, centered on 0/0); glyphs are fetched from the
 * font every frame so a resource reload can never leave stale textures behind.
 */
final class SkyLine {
    /**
     * How a glyph arrives. {@code leadMs}: how long the animation takes until the glyph is readable (about 60 %
     * opacity), so it can start that much early and the word is readable right when it is sung.
     */
    enum Entry {
        FADE_UP(140), POP(125), ZOOM(175), SWIRL(140), DROP(105), TYPE(0);

        final long leadMs;

        Entry(long leadMs) {
            this.leadMs = leadMs;
        }
    }

    enum Exit { DISSOLVE, RISE, SCATTER, SHATTER }

    /**
     * Per-frame values shared by all lines.
     *
     * @param blocksPerPixelAt100 text size as blocks per font pixel if the text were 100 blocks away
     * @param maxWidthAt100       widest line, in blocks at 100 blocks distance
     * @param kick                wobble on the beat of the singing; {@link Beat.Kick#NONE} when switched off
     * @param alongYaw            "turn along": the view direction the lyrics follow closely, NaN when switched off
     */
    record Frame(
            long nowNanos,
            long songPositionMs,
            float blocksPerPixelAt100,
            float maxWidthAt100,
            float opacity,
            float nightness,
            Font font,
            Style sparkleStyle,
            Beat.Kick kick,
            float alongYaw) {

        /** How far a line placed around {@code placement}'s direction turns along with the view now, in degrees. */
        float alongShift(Placements.Placement placement) {
            return Float.isNaN(alongYaw) ? 0f : Mth.wrapDegrees(alongYaw - placement.anchor());
        }
    }

    /**
     * Where and how a line lives.
     *
     * @param distance  blocks from the camera; sets the perspective of the turned lines
     * @param overlay   in front of the world, drawn last; otherwise behind it, drawn right after the sky
     * @param size      size factor on top of the configured scale
     * @param orbitDegS slow drift around the player, degrees per second
     */
    record Look(float distance, boolean overlay, float size, float orbitDegS) {}

    /** In the sky: behind everything, like the sun; 48 blocks out for the perspective of turned lines. */
    static final Look SKY = new Look(48f, false, 1f, 0f);
    /** Around the player: close, always visible like a hologram, slowly circling. */
    static final Look AROUND = new Look(6f, true, 1.15f, 1.0f);
    static final long EXIT_MS = 1_100;
    private static final long ENTRY_MS = 520;
    private static final long GLYPH_STAGGER_MS = 16;
    private static final long TITLE_WORD_STAGGER_MS = 110;
    private static final float MAX_ROW_PX = 150f;
    private static final float ROW_GAP_PX = 15f;
    private static final float WORD_GAP_PX = 4.5f;
    private static final float EMPHASIS_SCALE = 1.38f;
    private static final long SPARKLE_LIFE_MS = 850;
    private static final String SPARKLE = "✦";
    /** Wobble on the beat at full strength: growth and tilt in degrees. */
    private static final float KICK_SCALE = 0.05f;
    private static final float KICK_TILT_DEG = 1.6f;

    private static final class Word {
        final FormattedCharSequence text;
        final float width;
        final float scale;
        final boolean emphasized;
        final int row;
        final long appearMs;
        final float phase;
        float x;
        float y;
        float rotation;
        boolean revealed;

        Word(FormattedCharSequence text, float width, float scale, boolean emphasized, int row, long appearMs, float phase) {
            this.text = text;
            this.width = width;
            this.scale = scale;
            this.emphasized = emphasized;
            this.row = row;
            this.appearMs = appearMs;
            this.phase = phase;
        }
    }

    private record Sparkle(float x, float y, float vx, float vy, float spin, float size, int color, long bornNanos,
            long lifeMs) {}

    /** Offsets for one glyph from the entry or exit animation. */
    private static final class Anim {
        float dx;
        float dy;
        float rotation;
        float scale = 1f;
        float alpha = 1f;
        float blur;

        Anim set(float dx, float dy, float rotation, float scale, float alpha, float blur) {
            this.dx = dx;
            this.dy = dy;
            this.rotation = rotation;
            this.scale = scale;
            this.alpha = alpha;
            this.blur = blur;
            return this;
        }

        Anim identity() {
            return set(0, 0, 0, 1, 1, 0);
        }
    }

    final String key;
    private final Placements.Placement placement;
    private final Look look;
    private final Palette palette;
    private final Entry entry;
    private final Exit exit;
    private final long seed;
    private final boolean wallClock;
    private final long createdNanos;
    private final List<Word> words;
    private final float width;
    private final List<Sparkle> sparkles = new ArrayList<>();
    private final Anim entryAnim = new Anim();
    private final Anim exitAnim = new Anim();
    private long exitStartNanos = -1;
    private long frozenSongPositionMs;
    private long nextShimmerNanos;

    private SkyLine(String key, Placements.Placement placement, Look look, Palette palette, long seed, boolean wallClock,
            List<Word> words, boolean staggerRows) {
        this.key = key;
        this.placement = placement;
        this.look = look;
        this.palette = palette;
        this.seed = seed;
        this.wallClock = wallClock;
        this.words = words;
        this.createdNanos = System.nanoTime();
        this.nextShimmerNanos = createdNanos + 600_000_000L;
        this.entry = pick(Entry.values(), new int[] {3, 3, 2, 2, 1, 1}, Easing.hash(seed, 11));
        this.exit = pick(Exit.values(), new int[] {3, 2, 2, 1}, Easing.hash(seed, 12));
        this.width = layout(words, seed, staggerRows);
    }

    static SkyLine lyric(String key, String text, long[] wordTimes, Placements.Placement placement, Look look,
            Palette palette, long seed, Font font, Style regular, Style bold) {
        String[] parts = text.trim().split("\\s+");
        int[] emphasized = chooseEmphasis(parts, seed);
        List<Word> words = new ArrayList<>(parts.length);
        float rowWidth = 0f;
        int row = 0;
        for (int i = 0; i < parts.length; i++) {
            boolean emphasis = i == emphasized[0] || i == emphasized[1];
            Component component = Component.literal(parts[i]).withStyle(emphasis ? bold : regular);
            float scale = emphasis ? EMPHASIS_SCALE : 1f;
            float wordWidth = font.width(component);
            float advance = wordWidth * scale + WORD_GAP_PX;
            if (rowWidth > 0 && rowWidth + advance > MAX_ROW_PX) {
                row++;
                rowWidth = 0f;
            }
            rowWidth += advance;
            long appear = i < wordTimes.length ? wordTimes[i] : wordTimes.length > 0 ? wordTimes[wordTimes.length - 1] : 0;
            words.add(new Word(component.getVisualOrderText(), wordWidth, scale, emphasis, row, appear,
                    Easing.hash(seed, 300 + i) * 6.28f));
        }
        return new SkyLine(key, placement, look, palette, seed, false, words, true);
    }

    static SkyLine title(String key, String title, String subtitle, Placements.Placement placement, Look look,
            Palette palette, long seed, Font font, Style regular, Style bold) {
        List<Word> words = new ArrayList<>();
        int index = 0;
        for (String part : ("♪ " + title).trim().split("\\s+")) {
            Component component = Component.literal(part).withStyle(bold);
            words.add(new Word(component.getVisualOrderText(), font.width(component), 1.25f, true, 0, 0,
                    Easing.hash(seed, 300 + index++) * 6.28f));
        }
        if (!subtitle.isBlank()) {
            for (String part : subtitle.trim().split("\\s+")) {
                Component component = Component.literal(part).withStyle(regular);
                words.add(new Word(component.getVisualOrderText(), font.width(component), 0.72f, false, 1, 0,
                        Easing.hash(seed, 300 + index++) * 6.28f));
            }
        }
        return new SkyLine(key, placement, look, palette, seed, true, words, false);
    }

    /** Index of up to two words to make bigger: usually the last word of the line, like a stressed note. */
    private static int[] chooseEmphasis(String[] parts, long seed) {
        int[] chosen = {-1, -1};
        if (parts.length == 1) {
            chosen[0] = Easing.hash(seed, 1) < 0.5f ? 0 : -1;
            return chosen;
        }
        if (Easing.hash(seed, 2) > 0.8f) {
            return chosen;
        }
        int last = parts.length - 1;
        int longest = 0;
        for (int i = 1; i < parts.length; i++) {
            if (letters(parts[i]) > letters(parts[longest])) {
                longest = i;
            }
        }
        chosen[0] = letters(parts[last]) >= 4 ? last : (letters(parts[longest]) >= 5 ? longest : -1);
        if (parts.length >= 7 && longest != chosen[0] && Easing.hash(seed, 3) < 0.4f) {
            chosen[1] = longest;
        }
        return chosen;
    }

    private static int letters(String word) {
        int count = 0;
        for (int i = 0; i < word.length(); i++) {
            if (Character.isLetterOrDigit(word.charAt(i))) {
                count++;
            }
        }
        return count;
    }

    /** Places words on slightly tilted, curved rows; returns the total width in font pixels. */
    private static float layout(List<Word> words, long seed, boolean staggerRows) {
        int rows = words.isEmpty() ? 0 : words.get(words.size() - 1).row + 1;
        float[] rowWidths = new float[rows];
        for (Word word : words) {
            rowWidths[word.row] += word.width * word.scale + WORD_GAP_PX;
        }
        float widest = 0f;
        for (int r = 0; r < rows; r++) {
            rowWidths[r] -= WORD_GAP_PX;
            widest = Math.max(widest, rowWidths[r]);
        }
        float minX = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE;
        float[] cursor = new float[rows];
        for (int r = 0; r < rows; r++) {
            // Two-row lines sit staggered like hand lettering: first row left, second row right.
            float shift = staggerRows && rows > 1 ? (r % 2 == 0 ? -1f : 1f) * Math.min(18f, widest * 0.12f) : 0f;
            cursor[r] = -rowWidths[r] / 2f + shift;
        }
        for (Word word : words) {
            int r = word.row;
            float tilt = (float) Math.toRadians((Easing.hash(seed, 100 + r) - 0.5f) * 14f);
            float curve = (Easing.hash(seed, 200 + r) - 0.5f) * 7f;
            float half = Math.max(1f, rowWidths[r] / 2f);
            float scaledWidth = word.width * word.scale;
            float centerX = cursor[r] + scaledWidth / 2f;
            float rowCenter = staggerRows && rows > 1 ? (r % 2 == 0 ? -1f : 1f) * Math.min(18f, widest * 0.12f) : 0f;
            float along = (centerX - rowCenter) / half;
            float rowY = (r - (rows - 1) / 2f) * ROW_GAP_PX;
            word.x = centerX;
            word.y = rowY + (float) Math.tan(tilt) * (centerX - rowCenter) - curve * (1f - along * along);
            float slope = (float) Math.tan(tilt) + curve * 2f * along / half;
            word.rotation = (float) Math.toDegrees(Math.atan(slope));
            cursor[r] += scaledWidth + WORD_GAP_PX;
            minX = Math.min(minX, centerX - scaledWidth / 2f);
            maxX = Math.max(maxX, centerX + scaledWidth / 2f);
        }
        // Re-center horizontally after staggering and emphasis.
        float middle = (minX + maxX) / 2f;
        for (Word word : words) {
            word.x -= middle;
        }
        return words.isEmpty() ? 0f : maxX - minX;
    }

    void startExit(long nowNanos, long songPositionMs) {
        if (exitStartNanos < 0) {
            exitStartNanos = nowNanos;
            frozenSongPositionMs = songPositionMs;
        }
    }

    boolean isExiting() {
        return exitStartNanos >= 0;
    }

    boolean isFinished(long nowNanos) {
        return exitStartNanos >= 0 && (nowNanos - exitStartNanos) / 1_000_000L > EXIT_MS;
    }

    void render(Frame frame, List<SkyGlyph> out) {
        float life = (frame.nowNanos() - createdNanos) / 1e6f;
        float exitU = exitStartNanos < 0 ? 0f : Easing.clamp01((frame.nowNanos() - exitStartNanos) / 1e6f / EXIT_MS);
        long songPosition = exitStartNanos >= 0 ? frozenSongPositionMs : frame.songPositionMs();

        // Sizes are angular: the same on screen whatever the distance.
        float atDistance = look.distance() / 100f;
        float blocksPerPixel = frame.blocksPerPixelAt100() * look.size() * atDistance;
        float maxWidth = frame.maxWidthAt100() * atDistance;
        if (width * blocksPerPixel > maxWidth) {
            blocksPerPixel = maxWidth / width;
        }
        float bob = (float) Math.sin(life * 0.0011 + seed % 97) * 0.5f;
        float drift = Math.min(life / 1000f * 0.12f, 1.2f);
        float orbit = (Easing.hash(seed, 13) < 0.5f ? -1f : 1f) * look.orbitDegS() * life / 1000f;
        // On the beat the line kicks around its middle; the title card and a line on its way out stay calm.
        Beat.Kick kick = wallClock || exitStartNanos >= 0 ? Beat.Kick.NONE : frame.kick();
        Matrix4f base = bannerBasis(placement.yaw() + frame.alongShift(placement) + orbit, placement.elevation(), look.distance())
                .rotateZ((float) Math.toRadians(placement.roll() + KICK_TILT_DEG * kick.tilt()))
                .translate(0f, (bob - drift) * atDistance, 0f)
                .scale(blocksPerPixel * (1f + KICK_SCALE * kick.strength()));
        if (exit == Exit.RISE && exitU > 0f) {
            base.translate(0f, -Easing.inCubic(exitU) * 26f, 0f).scale(1f + 0.12f * exitU);
        }

        int glowColor = ARGB.srgbLerp(frame.nightness(), GlyphEffects.DAY_HALO, palette.nightGlow);
        // A firmer dark halo by day keeps light text readable against a bright sky.
        float glowAlpha = 0.21f - 0.08f * frame.nightness();
        float halfHeight = frame.font().lineHeight / 2f;
        int glyphIndex = 0;
        List<TextRenderable> glyphs = new ArrayList<>();

        for (int wi = 0; wi < words.size(); wi++) {
            Word word = words.get(wi);
            glyphs.clear();
            GlyphEffects.collect(frame.font(), word.text, glyphs);
            // In time: the entry starts early by as long as it needs to become readable, and the letters, which
            // arrive one after another, are centred on the moment the word is sung.
            float wordT = wallClock ? life - wi * TITLE_WORD_STAGGER_MS
                    : songPosition + entry.leadMs + (glyphs.size() - 1) * GLYPH_STAGGER_MS / 2f - word.appearMs;
            if (wordT < 0f) {
                glyphIndex += glyphs.size();
                continue;
            }
            if (!word.revealed) {
                word.revealed = true;
                if ((word.emphasized || wallClock) && wordT < 600f && exitStartNanos < 0) {
                    burst(word, frame.nowNanos(), wi);
                }
            }

            float accentMix = wallClock ? 0.55f : 1f - Easing.smoothstep(200f, 1700f, wordT);
            int color = ARGB.srgbLerp(accentMix * 0.9f, palette.text,
                    palette.accent(words.size() > 1 ? wi / (float) (words.size() - 1) : 0f));
            float pulse = 1f + (word.emphasized ? 0.14f : 0.07f) * (float) Math.sin(Math.PI * Easing.clamp01(wordT / 420f));
            float floatY = (float) Math.sin(life * 0.0017 + word.phase) * 0.7f;
            Matrix4f wordPose = new Matrix4f(base)
                    .translate(word.x, word.y + floatY, 0f)
                    .rotateZ((float) Math.toRadians(word.rotation))
                    .scale(word.scale * pulse)
                    .translate(-word.width / 2f, -halfHeight, 0f);

            for (int gi = 0; gi < glyphs.size(); gi++, glyphIndex++) {
                TextRenderable glyph = glyphs.get(gi);
                float glyphT = wordT - gi * GLYPH_STAGGER_MS;
                if (glyphT < 0f) {
                    continue;
                }
                entryAnim(Easing.clamp01(glyphT / ENTRY_MS), glyphIndex);
                exitAnim(exitU, glyphIndex);
                float alpha = frame.opacity() * entryAnim.alpha * exitAnim.alpha;
                if (alpha < 0.015f) {
                    continue;
                }
                float cx = (glyph.left() + glyph.right()) / 2f;
                float cy = (glyph.top() + glyph.bottom()) / 2f;
                Matrix4f pose = new Matrix4f(wordPose)
                        .translate(cx + entryAnim.dx + exitAnim.dx, cy + entryAnim.dy + exitAnim.dy, 0f)
                        .rotateZ((float) Math.toRadians(entryAnim.rotation + exitAnim.rotation))
                        .scale(Math.max(0f, entryAnim.scale * exitAnim.scale))
                        .translate(-cx, -cy, 0f);
                GlyphEffects.emit(glyph, pose, color, alpha, glowColor, glowAlpha, 1f - frame.nightness(),
                        entryAnim.blur + exitAnim.blur, look.overlay(), out);
            }
        }
        renderSparkles(frame, base, exitU, out);
    }

    private void entryAnim(float u, int index) {
        switch (entry) {
            case FADE_UP -> {
                float e = Easing.outCubic(u);
                entryAnim.set(0f, (1f - e) * 7f, 0f, 0.94f + 0.06f * e, e, (1f - e) * 2.4f);
            }
            case POP -> entryAnim.set(0f, 0f, 0f, Math.max(0f, Easing.outBack(u)), Easing.clamp01(u * 2.5f), (1f - u) * 1.2f);
            case ZOOM -> {
                float e = Easing.outQuint(u);
                entryAnim.set(0f, 0f, 0f, 1f + (1f - e) * 1.6f, Easing.clamp01(u * 1.8f), (1f - e) * 3.5f);
            }
            case SWIRL -> {
                float e = Easing.outCubic(u);
                float direction = index % 2 == 0 ? -1f : 1f;
                entryAnim.set(0f, -(1f - e) * 4f, direction * (1f - e) * 70f,
                        0.5f + 0.5f * Math.max(0f, Easing.outBack(u)), e, (1f - e) * 1.5f);
            }
            case DROP -> entryAnim.set(0f, -(1f - Easing.outBounce(u)) * 16f, 0f, 1f, Easing.clamp01(u * 3f), 0f);
            case TYPE -> entryAnim.set(0f, 0f, 0f, 1f + 0.35f * (1f - Easing.outCubic(u)), u > 0f ? 1f : 0f, (1f - u) * 1.5f);
        }
    }

    private void exitAnim(float u, int index) {
        if (u <= 0f) {
            exitAnim.identity();
            return;
        }
        switch (exit) {
            case DISSOLVE -> {
                float delay = Easing.hash(seed, index) * 0.35f;
                float v = Easing.clamp01((u - delay) / 0.65f);
                exitAnim.set(0f, -v * 5f, 0f, 1f + 0.08f * v, 1f - Easing.outCubic(v), v * 4f);
            }
            case RISE -> {
                float v = Easing.inOutSine(u);
                exitAnim.set(0f, 0f, 0f, 1f, 1f - v, v * 2f);
            }
            case SCATTER -> {
                double angle = Easing.hash(seed, index) * Math.PI * 2;
                float distance = 20f * Easing.outCubic(u);
                exitAnim.set((float) Math.cos(angle) * distance, (float) Math.sin(angle) * distance,
                        (Easing.hash(seed, index + 999) - 0.5f) * 140f * u, 1f, 1f - u, u * 1.5f);
            }
            case SHATTER -> {
                float delay = Easing.hash(seed, index) * 0.3f;
                float v = Easing.clamp01((u - delay) / 0.7f);
                exitAnim.set((Easing.hash(seed, index + 7) - 0.5f) * 10f * v, v * v * 34f,
                        (Easing.hash(seed, index + 13) - 0.5f) * 220f * v, 1f - 0.4f * v, 1f - v, 0f);
            }
        }
    }

    private void burst(Word word, long nowNanos, int wordIndex) {
        int count = 7;
        // Lighter than the accent so the sparkles read as light, not as dark specks against the sky.
        int color = ARGB.srgbLerp(0.45f, palette.accent(Easing.hash(seed, 400 + wordIndex)), 0xFFFFFF);
        for (int i = 0; i < count; i++) {
            float angle = (float) (i * Math.PI * 2 / count + Easing.hash(seed, 500 + wordIndex * 10 + i) * 0.6f);
            float speed = 12f + Easing.hash(seed, 600 + wordIndex * 10 + i) * 12f;
            sparkles.add(new Sparkle(word.x, word.y, (float) Math.cos(angle) * speed, (float) Math.sin(angle) * speed * 0.7f,
                    (Easing.hash(seed, 700 + i) - 0.5f) * 360f, 0.45f + Easing.hash(seed, 800 + i) * 0.35f, color,
                    nowNanos, SPARKLE_LIFE_MS));
        }
    }

    private void shimmer(long nowNanos) {
        if (exitStartNanos >= 0 || nowNanos < nextShimmerNanos) {
            return;
        }
        List<Word> shown = words.stream().filter(word -> word.revealed).toList();
        if (shown.isEmpty()) {
            return;
        }
        int n = (int) (nowNanos / 1_000_000L);
        Word word = shown.get(Math.floorMod(n * 31, shown.size()));
        float spread = word.width * word.scale / 2f;
        sparkles.add(new Sparkle(word.x + (Easing.hash(seed, n) - 0.5f) * 2f * spread, word.y - 7f,
                (Easing.hash(seed, n + 1) - 0.5f) * 6f, -4f - Easing.hash(seed, n + 2) * 3f, 90f,
                0.3f + Easing.hash(seed, n + 3) * 0.2f,
                ARGB.srgbLerp(0.45f, palette.accent(Easing.hash(seed, n + 4)), 0xFFFFFF), nowNanos, 1_100));
        nextShimmerNanos = nowNanos + (long) ((650 + Easing.hash(seed, n + 5) * 700) * 1_000_000L);
    }

    private void renderSparkles(Frame frame, Matrix4f base, float exitU, List<SkyGlyph> out) {
        shimmer(frame.nowNanos());
        if (sparkles.isEmpty()) {
            return;
        }
        List<TextRenderable> star = new ArrayList<>(1);
        GlyphEffects.collect(frame.font(), Component.literal(SPARKLE).withStyle(frame.sparkleStyle()).getVisualOrderText(), star);
        if (star.isEmpty()) {
            return;
        }
        TextRenderable glyph = star.get(0);
        float cx = (glyph.left() + glyph.right()) / 2f;
        float cy = (glyph.top() + glyph.bottom()) / 2f;
        for (Iterator<Sparkle> it = sparkles.iterator(); it.hasNext(); ) {
            Sparkle sparkle = it.next();
            float u = (frame.nowNanos() - sparkle.bornNanos()) / 1e6f / sparkle.lifeMs();
            if (u >= 1f) {
                it.remove();
                continue;
            }
            float travel = Easing.outCubic(u);
            float alpha = frame.opacity() * (float) Math.pow(1f - u, 1.5) * (1f - exitU);
            if (alpha < 0.015f) {
                continue;
            }
            float size = sparkle.size() * (1f + 0.35f * (float) Math.sin(u * Math.PI));
            Matrix4f pose = new Matrix4f(base)
                    .translate(sparkle.x() + sparkle.vx() * travel, sparkle.y() + sparkle.vy() * travel, 0f)
                    .rotateZ((float) Math.toRadians(sparkle.spin() * u))
                    .scale(size)
                    .translate(-cx, -cy, 0f);
            out.add(new SkyGlyph(glyph, pose, ARGB.color(alpha, sparkle.color()), SkyGlyph.LAYER_TEXT, look.overlay()));
        }
    }

    /**
     * Maps text space (x right, y down) onto a plane facing the camera, centered on the direction given by
     * yaw and elevation. The sky pass has a rotation-only view matrix, so the plane sits at a fixed spot in
     * the sky like the sun.
     */
    static Matrix4f bannerBasis(float yawDeg, float elevationDeg, float distance) {
        double yaw = Math.toRadians(yawDeg);
        double elevation = Math.toRadians(elevationDeg);
        Vector3f forward = new Vector3f(
                (float) (-Math.sin(yaw) * Math.cos(elevation)),
                (float) Math.sin(elevation),
                (float) (Math.cos(yaw) * Math.cos(elevation)));
        Vector3f right = new Vector3f(forward).cross(0f, 1f, 0f).normalize();
        Vector3f up = new Vector3f(right).cross(forward).normalize();
        return new Matrix4f(
                right.x, right.y, right.z, 0f,
                -up.x, -up.y, -up.z, 0f,
                -forward.x, -forward.y, -forward.z, 0f,
                forward.x * distance, forward.y * distance, forward.z * distance, 1f);
    }

    private static <T> T pick(T[] values, int[] weights, float random) {
        int total = 0;
        for (int weight : weights) {
            total += weight;
        }
        float target = random * total;
        for (int i = 0; i < values.length; i++) {
            target -= weights[i];
            if (target < 0) {
                return values[i];
            }
        }
        return values[values.length - 1];
    }
}
