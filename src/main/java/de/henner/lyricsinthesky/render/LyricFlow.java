package de.henner.lyricsinthesky.render;

import de.henner.lyricsinthesky.LyricsController;
import de.henner.lyricsinthesky.lyrics.Lyrics;
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
 * The lyrics of one song as a gliding stack, like the lyrics view of a music app: the line being sung sits
 * bright in the middle, the next one waits faintly below, and on every new line the whole stack glides up on a
 * spring while the old line fades out above. The letters of each word light up one after another as it is sung.
 *
 * <p>Everything is laid out in font pixels (x right, y down) around the middle of the current line and then
 * mapped onto a plane in the sky, or close around the player, like {@link SkyLine}.
 */
final class LyricFlow {
    /** Text size compared with the classic style: the stack is the one thing to read, so it is bigger. */
    static final float SIZE = 1.35f;
    /** Widest row, as blocks at 100 blocks distance (about 57°); longer lines wrap. */
    static final float WRAP_AT_100 = 108f;
    private static final long PREROLL_MS = 150;
    private static final float WORD_GAP_PX = 4.5f;
    private static final float ROW_SPACING_PX = 3f;
    private static final float LINE_GAP_PX = 10f;
    /** Scrolling spring, rad/s: a new line glides in within about half a second. */
    private static final float SCROLL_OMEGA = 8f;
    private static final float UNSUNG_ALPHA = 0.55f;
    /** How strongly a freshly sung word tints towards the accent color before settling. */
    private static final float FLASH = 0.6f;
    private static final long LETTER_FADE_MS = 170;
    private static final float LETTER_RISE_PX = 0.9f;
    private static final long FADE_IN_MS = 600;
    static final long LEAVE_MS = 900;
    private static final float LEAVE_RISE_PX = 22f;
    /** Wobble on the beat at full strength: growth and tilt in degrees of the line in the middle. */
    private static final float KICK_SCALE = 0.05f;
    private static final float KICK_TILT_DEG = 1.4f;
    private static final long TITLE_WORD_STAGGER_MS = 90;
    private static final float TITLE_SCALE = 1.2f;
    /** A song title may be this much wider than a lyric row before it wraps, so it rarely breaks mid-title. */
    private static final float TITLE_ROOM = 1.4f;
    private static final float SUBTITLE_SCALE = 0.72f;
    private static final String DOT = "•";

    private static final class Word {
        final FormattedCharSequence text;
        final float width;
        final float scale;
        final int row;
        /** Song position (wall clock for the title) at which the word lights up, and how long its letters take. */
        final long litMs;
        final long sweepMs;
        float x;
        float y;

        Word(FormattedCharSequence text, float width, float scale, int row, long litMs, long sweepMs) {
            this.text = text;
            this.width = width;
            this.scale = scale;
            this.row = row;
            this.litMs = litMs;
            this.sweepMs = sweepMs;
        }
    }

    private static final class Entry {
        final FlowSchedule.Item item;
        final List<Word> words;
        final float width;
        final float height;
        /** Middle of the entry in stack coordinates. */
        float center;

        Entry(FlowSchedule.Item item, List<Word> words, float lineHeight) {
            this.item = item;
            this.words = words;
            float[] size = layout(words, lineHeight);
            this.width = size[0];
            this.height = size[1];
        }
    }

    final String key;
    private final SkyLine.Look look;
    private final Palette palette;
    private final long createdNanos;
    private final FlowSchedule.Spring scroll = new FlowSchedule.Spring(SCROLL_OMEGA);
    private final List<Entry> entries = new ArrayList<>();
    private List<FlowSchedule.Item> items = List.of();
    private final List<TextRenderable> glyphs = new ArrayList<>();
    private final Style regular;
    private final Style bold;
    private final boolean withTitle;
    /** Widest row in font pixels. */
    private final float maxRowPx;
    /** Waits this long before fading in, so a stack that replaces another one does not overlap with it. */
    private final long fadeDelayMs;
    private Lyrics builtFor;
    private String builtTitle;
    private boolean built;
    private float yaw;
    private float elevation;
    private long lastNanos;
    private long leaveStartNanos = -1;
    private long songPositionMs;

    /**
     * @param blocksPerPixelAt100 text size as blocks per font pixel at 100 blocks distance, before {@link #SIZE}
     * @param fadeDelayMs         how long to stay invisible first, e.g. while the previous stack glides away
     */
    LyricFlow(String key, SkyLine.Look look, Palette palette, Style regular, Style bold, boolean withTitle,
            float blocksPerPixelAt100, long fadeDelayMs) {
        this.key = key;
        this.look = look;
        this.palette = palette;
        this.regular = regular;
        this.bold = bold;
        this.withTitle = withTitle;
        this.maxRowPx = WRAP_AT_100 / (blocksPerPixelAt100 * look.size() * SIZE);
        this.fadeDelayMs = fadeDelayMs;
        this.createdNanos = SkyLyricsRenderer.animationNanos();
    }

    /** Takes this frame's song state; rebuilds the entries when lyrics arrive or the title card text changes. */
    void update(LyricsController.Timeline timeline, String subtitle, Font font) {
        if (leaveStartNanos >= 0) {
            return;
        }
        songPositionMs = timeline.positionMs();
        String title = timeline.title() + "\n" + subtitle;
        if (!built || timeline.lyrics() != builtFor || !title.equals(builtTitle)) {
            build(timeline, subtitle, font);
        }
    }

    /** Where the stack hangs; it keeps the last direction once it is leaving. */
    void aim(float yawDeg, float elevationDeg) {
        if (leaveStartNanos < 0) {
            yaw = yawDeg;
            elevation = elevationDeg;
        }
    }

    void leave(long nowNanos) {
        if (leaveStartNanos < 0) {
            leaveStartNanos = nowNanos;
        }
    }

    boolean isLeaving() {
        return leaveStartNanos >= 0;
    }

    boolean isFinished(long nowNanos) {
        return leaveStartNanos >= 0 && (nowNanos - leaveStartNanos) / 1_000_000L > LEAVE_MS;
    }

    /** True once the stack has been on show for {@code holdMs}. */
    boolean shownFor(long nowNanos, long holdMs) {
        return (nowNanos - createdNanos) / 1_000_000L > holdMs;
    }

    private void build(LyricsController.Timeline timeline, String subtitle, Font font) {
        built = true;
        builtFor = timeline.lyrics();
        builtTitle = timeline.title() + "\n" + subtitle;
        entries.clear();
        for (FlowSchedule.Item item : FlowSchedule.build(timeline.lyrics(), timeline.durationMs(), withTitle)) {
            List<Word> words = switch (item.kind()) {
                case TITLE -> titleWords(timeline.title(), subtitle, font);
                case DOTS -> dotWords(font);
                case LINE -> lineWords(timeline.lyrics().lines().get(item.lineIndex()), item, font);
            };
            if (!words.isEmpty()) {
                entries.add(new Entry(item, words, font.lineHeight));
            }
        }
        float y = 0f;
        for (int i = 0; i < entries.size(); i++) {
            Entry entry = entries.get(i);
            if (i > 0) {
                y += entries.get(i - 1).height / 2f + LINE_GAP_PX + entry.height / 2f;
            }
            entry.center = y;
        }
        items = entries.stream().map(entry -> entry.item).toList();
    }

    private List<Word> titleWords(String title, String subtitle, Font font) {
        List<Word> words = new ArrayList<>();
        long lit = 0;
        for (String part : ("♪ " + title).trim().split("\\s+")) {
            words.add(word(part, bold, font, TITLE_SCALE, lit, 260));
            lit += TITLE_WORD_STAGGER_MS;
        }
        for (String part : subtitle.trim().split("\\s+")) {
            if (!part.isEmpty()) {
                words.add(word(part, regular, font, SUBTITLE_SCALE, lit, 200));
                lit += TITLE_WORD_STAGGER_MS / 2;
            }
        }
        wrap(words, true);
        return words;
    }

    private List<Word> dotWords(Font font) {
        List<Word> words = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            words.add(word(DOT, bold, font, 1.3f, 0, 0));
        }
        wrap(words, false);
        return words;
    }

    private List<Word> lineWords(Lyrics.Line line, FlowSchedule.Item item, Font font) {
        String[] parts = line.words();
        long[] times = item.wordTimes();
        List<Word> words = new ArrayList<>(parts.length);
        for (int i = 0; i < parts.length; i++) {
            long lit = i < times.length ? times[i] : item.startMs();
            words.add(word(parts[i], bold, font, 1f, lit, i < times.length ? FlowSchedule.sweepMs(times, i, item.endMs()) : 300));
        }
        wrap(words, false);
        return words;
    }

    private static Word word(String text, Style style, Font font, float scale, long litMs, long sweepMs) {
        Component component = Component.literal(text).withStyle(style);
        return new Word(component.getVisualOrderText(), font.width(component), scale, 0, litMs, sweepMs);
    }

    /**
     * Breaks words into rows no wider than {@code maxRowPx}, as evenly as possible so a long line never leaves
     * a single word dangling on its own row; the title keeps its subtitle on a row of its own.
     */
    private void wrap(List<Word> words, boolean titleRows) {
        float total = 0f;
        for (Word word : words) {
            total += word.width * word.scale + WORD_GAP_PX;
        }
        int rows = Math.max(1, rowsNeeded(words, maxRowPx));
        // Aim for equal rows, but never wider than the limit.
        float target = Math.min(maxRowPx, total / rows + WORD_GAP_PX);
        int row = 0;
        float rowWidth = 0f;
        float lastScale = words.isEmpty() ? 1f : words.get(0).scale;
        for (int i = 0; i < words.size(); i++) {
            Word word = words.get(i);
            float advance = word.width * word.scale + WORD_GAP_PX;
            boolean scaleChange = titleRows && word.scale != lastScale;
            boolean full = titleRows
                    ? rowWidth + advance > maxRowPx * TITLE_ROOM
                    : rowWidth + advance / 2f > target || rowWidth + advance > maxRowPx;
            if (rowWidth > 0 && (scaleChange || full)) {
                row++;
                rowWidth = 0f;
            }
            rowWidth += advance;
            lastScale = word.scale;
            words.set(i, new Word(word.text, word.width, word.scale, row, word.litMs, word.sweepMs));
        }
    }

    private static int rowsNeeded(List<Word> words, float maxRow) {
        int rows = words.isEmpty() ? 0 : 1;
        float rowWidth = 0f;
        for (Word word : words) {
            float advance = word.width * word.scale + WORD_GAP_PX;
            if (rowWidth > 0 && rowWidth + advance > maxRow) {
                rows++;
                rowWidth = 0f;
            }
            rowWidth += advance;
        }
        return rows;
    }

    /** Centers every row and stacks the rows; returns width and height of the block. */
    private static float[] layout(List<Word> words, float lineHeight) {
        int rows = words.isEmpty() ? 0 : words.get(words.size() - 1).row + 1;
        float[] rowWidth = new float[rows];
        float[] rowHeight = new float[rows];
        for (Word word : words) {
            rowWidth[word.row] += word.width * word.scale + WORD_GAP_PX;
            rowHeight[word.row] = Math.max(rowHeight[word.row], lineHeight * word.scale);
        }
        float width = 0f;
        float height = 0f;
        for (int r = 0; r < rows; r++) {
            rowWidth[r] -= WORD_GAP_PX;
            width = Math.max(width, rowWidth[r]);
            height += rowHeight[r] + (r > 0 ? ROW_SPACING_PX : 0f);
        }
        float[] rowCenter = new float[rows];
        float top = -height / 2f;
        for (int r = 0; r < rows; r++) {
            rowCenter[r] = top + rowHeight[r] / 2f;
            top += rowHeight[r] + ROW_SPACING_PX;
        }
        float[] cursor = new float[rows];
        for (int r = 0; r < rows; r++) {
            cursor[r] = -rowWidth[r] / 2f;
        }
        for (Word word : words) {
            float scaled = word.width * word.scale;
            word.x = cursor[word.row] + scaled / 2f;
            word.y = rowCenter[word.row];
            cursor[word.row] += scaled + WORD_GAP_PX;
        }
        return new float[] {width, height};
    }

    void render(SkyLine.Frame frame, List<SkyGlyph> out) {
        if (entries.isEmpty()) {
            return;
        }
        long now = frame.nowNanos();
        float dt = lastNanos == 0 ? 0f : Math.min(0.1f, (now - lastNanos) / 1e9f);
        lastNanos = now;
        // Time on screen: everything that animates by itself (fade, title, dots) starts after the delay.
        float sinceCreated = (now - createdNanos) / 1e6f - fadeDelayMs;
        float leaveU = leaveStartNanos < 0 ? 0f : Easing.clamp01((now - leaveStartNanos) / 1e6f / LEAVE_MS);

        int target = FlowSchedule.currentIndex(items, songPositionMs + PREROLL_MS);
        if (leaveStartNanos < 0) {
            // A big jump (seeking) glides over the last line only instead of racing through the whole song.
            if (Math.abs(target - scroll.value()) > 4f) {
                scroll.snap(target - Math.signum(target - scroll.value()));
            }
            scroll.step(target, dt);
        }
        float position = scroll.value();

        float fade = Easing.outCubic(sinceCreated / FADE_IN_MS) * (1f - Easing.inOutSine(leaveU));
        float opacity = frame.opacity() * fade;
        if (opacity < 0.01f) {
            return;
        }
        float atDistance = look.distance() / 100f;
        float blocksPerPixel = frame.blocksPerPixelAt100() * look.size() * SIZE * atDistance;
        // A slow float of the whole stack, so it never looks pasted onto the sky.
        float bob = (float) Math.sin(sinceCreated * 0.0009) * 0.8f;
        Matrix4f base = SkyLine.bannerBasis(yaw, elevation, look.distance())
                .scale(blocksPerPixel)
                .translate(0f, bob - stackY(position) - LEAVE_RISE_PX * Easing.inOutSine(leaveU), 0f);

        int glowColor = ARGB.srgbLerp(frame.nightness(), GlyphEffects.DAY_HALO, palette.nightGlow);
        float glowAlpha = 0.21f - 0.08f * frame.nightness();
        float dayness = 1f - frame.nightness();
        float halfHeight = frame.font().lineHeight / 2f;
        // On the beat the line in the middle kicks; the others, and a stack gliding away, stay calm.
        Beat.Kick kick = leaveStartNanos < 0 ? frame.kick() : Beat.Kick.NONE;

        for (int e = Math.max(0, (int) Math.floor(position) - 2); e < Math.min(entries.size(), (int) position + 4); e++) {
            Entry entry = entries.get(e);
            float d = e - position;
            float lineAlpha = opacity * FlowSchedule.alpha(d);
            if (lineAlpha < 0.01f) {
                continue;
            }
            // Only a single very long word (or a very long title) can still be too wide after wrapping.
            float limit = entry.item.kind() == FlowSchedule.Kind.TITLE ? maxRowPx * TITLE_ROOM : maxRowPx;
            float fit = entry.width > limit ? limit / entry.width : 1f;
            float blur = FlowSchedule.blur(d) + 1.6f * leaveU;
            float near = Math.max(0f, 1f - Math.abs(d));
            Matrix4f entryPose = new Matrix4f(base).translate(0f, entry.center, 0f)
                    .rotateZ((float) Math.toRadians(KICK_TILT_DEG * kick.tilt() * near))
                    .scale(FlowSchedule.scale(d) * fit * (1f + KICK_SCALE * kick.strength() * near));
            renderEntry(entry, entryPose, frame, sinceCreated, lineAlpha, blur, glowColor, glowAlpha, dayness, halfHeight, out);
        }
    }

    private void renderEntry(Entry entry, Matrix4f entryPose, SkyLine.Frame frame, float sinceCreated, float lineAlpha,
            float blur, int glowColor, float glowAlpha, float dayness, float halfHeight, List<SkyGlyph> out) {
        FlowSchedule.Kind kind = entry.item.kind();
        int count = entry.words.size();
        for (int wi = 0; wi < count; wi++) {
            Word word = entry.words.get(wi);
            // How long ago the word started lighting up: song time for lyrics, time on screen for the title.
            float wordT = switch (kind) {
                case TITLE -> sinceCreated - 250f - word.litMs;
                case DOTS -> 0f;
                case LINE -> songPositionMs - word.litMs;
            };
            float along = count > 1 ? wi / (float) (count - 1) : 0f;
            float pulse = wordT >= 0f && kind == FlowSchedule.Kind.LINE
                    ? 1f + 0.045f * (float) Math.sin(Math.PI * Easing.clamp01(wordT / 380f)) : 1f;
            Matrix4f wordPose = new Matrix4f(entryPose)
                    .translate(word.x, word.y, 0f)
                    .scale(word.scale * pulse)
                    .translate(-word.width / 2f, -halfHeight, 0f);
            glyphs.clear();
            GlyphEffects.collect(frame.font(), word.text, glyphs);
            int n = glyphs.size();
            for (int gi = 0; gi < n; gi++) {
                float lit;
                if (kind == FlowSchedule.Kind.DOTS) {
                    // Three dots breathing one after another while the music plays on.
                    lit = 0.5f + 0.5f * (float) Math.sin(sinceCreated / 1000f * Math.PI * 2 / 1.6 - wi * 0.9);
                } else {
                    float letterT = wordT - word.sweepMs * (gi / (float) Math.max(1, n));
                    lit = Easing.outCubic(letterT / LETTER_FADE_MS);
                }
                float alpha = lineAlpha * (UNSUNG_ALPHA + (1f - UNSUNG_ALPHA) * lit);
                if (alpha < 0.01f) {
                    continue;
                }
                // Freshly sung words flash in the accent color and settle back to the text color.
                float flash = kind == FlowSchedule.Kind.TITLE ? 0.5f
                        : lit * (1f - Easing.smoothstep(250f, 1800f, wordT)) * FLASH;
                int color = ARGB.srgbLerp(flash, palette.text, palette.accent(along));
                float bloom = kind == FlowSchedule.Kind.LINE && wordT >= 0f
                        ? 1f + 0.9f * (1f - Easing.smoothstep(0f, 900f, wordT)) : 1f;
                TextRenderable glyph = glyphs.get(gi);
                Matrix4f pose = new Matrix4f(wordPose).translate(0f, (1f - lit) * LETTER_RISE_PX, 0f);
                GlyphEffects.emit(glyph, pose, color, alpha, glowColor, glowAlpha * (0.55f + 0.45f * lit) * bloom,
                        dayness, blur, look.overlay(), out);
            }
        }
    }

    /** Stack coordinate of the middle of the view for a fractional entry position. */
    private float stackY(float position) {
        if (position <= 0f) {
            return entries.get(0).center + position * (entries.size() > 1 ? entries.get(1).center : 20f);
        }
        int below = Math.min((int) Math.floor(position), entries.size() - 1);
        if (below >= entries.size() - 1) {
            return entries.get(entries.size() - 1).center;
        }
        float fraction = position - below;
        return entries.get(below).center + (entries.get(below + 1).center - entries.get(below).center) * fraction;
    }
}
