package de.henner.lyricsinthesky.render;

import de.henner.lyricsinthesky.Lang;
import de.henner.lyricsinthesky.LyricsController;
import de.henner.lyricsinthesky.LyricsInTheSkyClient;
import de.henner.lyricsinthesky.SkyLyricsConfig;
import de.henner.lyricsinthesky.gui.GlassMenuScreen;
import de.henner.lyricsinthesky.gui.HudEditScreen;
import de.henner.lyricsinthesky.hoergenau.Hoergenau;
import de.henner.lyricsinthesky.lyrics.Lyrics;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.util.ARGB;

/**
 * The "now playing" bar: song or current lyric line with the word being sung, in one of four looks (see
 * {@link SkyLyricsConfig#HUD_STYLES}), at the top or at the bottom above the hotbar wherever the player moved it,
 * classic dark or as Liquid Glass ({@link Glass}), in the size, roundness and density the player chose. Everything
 * eases in and out.
 */
public final class NowPlayingHud implements HudElement {
    private static final int HEIGHT = 18;
    private static final int KARAOKE_HEIGHT = 31;
    private static final int PLAIN_HEIGHT = 11;
    private static final int PADDING = 7;
    private static final int ICON = 12;
    private static final int EQ_WIDTH = 13;
    private static final int GAP = 6;
    private static final int MAX_TEXT = 300;
    private static final long LINE_SLIDE_MS = 260;
    /** Natural style: words started within this time before the current one are highlighted with it. */
    private static final long GROUP_WINDOW_MS = 650;
    /** Karaoke: a word fills up over its own length, but at most this long, so a held note does not crawl. */
    private static final long MAX_FILL_MS = 1_200;
    private static final int UNSUNG = 0x9AA0B4;
    /** Shown while the bar is being moved and nothing plays. */
    private static final String PREVIEW_LINE = "So sieht deine Leiste aus, Wort für Wort";
    private static final String PREVIEW_NEXT = "und hier kommt die nächste Zeile";
    private static final long PREVIEW_STEP_MS = 420;
    private static final long PREVIEW_CYCLE_MS = 4_600;

    /** Where the bar was drawn in the last frame, for dragging it; null while it is hidden. */
    private static volatile Bounds lastBounds;
    /** What the bar showed in the last frame and how visible its text was, for the self test. */
    private static volatile Shown lastShown;

    public record Bounds(int x, int y, int width, int height) {
        public boolean contains(double px, double py) {
            return px >= x && px < x + width && py >= y && py < y + height;
        }
    }

    /** @param opacity of the text, 0 to 1; stays near 0 when the text keeps starting over */
    public record Shown(String text, boolean lyricLine, float opacity) {}

    private float alpha;
    private float width = -1f;
    private long lastNanos;
    private String contentKey = "";
    private long contentChangedNanos;
    private String[] words = new String[0];
    private String[] nextWords = new String[0];
    private long[] wordTimes = new long[0];
    private long lineEndMs;
    private boolean lyricLine;
    private long positionMs;
    private long durationMs;
    private boolean playing;
    /** The player's choices: a progress line along the bottom, the equalizer at the pill's end. */
    private boolean progress;
    private boolean equalizer = true;
    /** How the word being sung is marked; see {@link SkyLyricsConfig#HUD_HIGHLIGHTS}. */
    private String highlight = "box";
    private Palette palette = Palette.AURORA;
    private Style textStyle = Style.EMPTY;
    /** Highlight the words being sung as one group instead of only the current word. */
    private boolean groupHighlight;
    /** The line's times were spread over the song from plain text; the icon says so. */
    private boolean estimated;
    /** Hoergenau records this song now (a red dot), or learns a song in the background (an amber one). */
    private boolean recording;
    private boolean learning;
    private boolean glass;
    /** The player's density of the bar's background, 0 to 1. */
    private float density = 1f;
    /** The player's roundness, 0 (square) to 1 (capsule). */
    private float roundness = 1f;

    public static Bounds lastBounds() {
        return lastBounds;
    }

    public static Shown lastShown() {
        return lastShown;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
        long started = Perf.start();
        extractBar(graphics);
        Perf.stop(Perf.BAR, started);
    }

    private void extractBar(GuiGraphicsExtractor graphics) {
        Minecraft minecraft = Minecraft.getInstance();
        LyricsController controller = LyricsInTheSkyClient.controller();
        Screen screen = minecraft.gui.screen();
        if (controller == null || screen instanceof GlassMenuScreen menu && !menu.peeking()) {
            // The settings panel covers the top of the screen; the bar would shine through it.
            lastBounds = null;
            return;
        }
        boolean editing = screen instanceof HudEditScreen;
        long now = System.nanoTime();
        float dt = lastNanos == 0 ? 0f : Math.min(0.25f, (now - lastNanos) / 1e9f);
        lastNanos = now;
        SkyLyricsConfig config = controller.config();
        LyricsController.Timeline timeline = config.showHud || editing ? controller.timeline(System.currentTimeMillis()) : null;
        if (timeline != null) {
            update(timeline, config, now);
        } else if (editing) {
            preview(config, now);
        }
        alpha += ((timeline != null || editing ? 1f : 0f) - alpha) * (1f - (float) Math.exp(-dt / 0.18f));
        if (alpha < 0.02f || words.length == 0) {
            lastBounds = null;
            lastShown = null;
            return;
        }

        Font font = minecraft.font;
        String style = config.hudStyle;
        equalizer = config.hudEqualizer;
        highlight = config.hudHighlight;
        int maxText = Math.min(MAX_TEXT, graphics.guiWidth() - 90);
        int textWidth = Math.min(maxText, textWidth(font, words));
        int height;
        float targetWidth;
        switch (style) {
            case "karaoke" -> {
                height = KARAOKE_HEIGHT;
                targetWidth = 2 * PADDING + Math.max(textWidth, Math.min(maxText, textWidth(font, nextWords)));
            }
            case "plain" -> {
                height = PLAIN_HEIGHT;
                targetWidth = textWidth + 4;
            }
            default -> {
                height = HEIGHT;
                targetWidth = PADDING + ICON + GAP + textWidth + (equalizer ? GAP + EQ_WIDTH : 0) + PADDING;
            }
        }
        width = width < 0 ? targetWidth : width + (targetWidth - width) * (1f - (float) Math.exp(-dt / 0.12f));
        int w = Math.round(width);
        float scale = config.hudScale;
        progress = config.hudProgress;
        glass = "glass".equals(config.theme);
        density = config.hudOpacity;
        roundness = config.hudRoundness;
        int shownWidth = Math.round(w * scale);
        int shownHeight = Math.round(height * scale);
        HudLayout.Position at = HudLayout.place(config.hudAnchor, config.hudOffsetX, config.hudOffsetY,
                graphics.guiWidth(), graphics.guiHeight(), shownWidth, shownHeight);
        lastBounds = new Bounds(at.x(), at.y(), shownWidth, shownHeight);
        float slide = Easing.outCubic((now - contentChangedNanos) / 1e6f / LINE_SLIDE_MS);
        lastShown = new Shown(String.join(" ", words), lyricLine, alpha * slide);
        // Drawn at 0/0 in its own size and scaled into place; scissors follow the scale.
        graphics.pose().pushMatrix();
        graphics.pose().translate(at.x(), at.y());
        graphics.pose().scale(scale, scale);
        switch (style) {
            case "karaoke" -> drawKaraoke(graphics, font, 0, 0, w, slide);
            case "plain" -> drawPlain(graphics, font, 0, 0, w, slide);
            default -> drawPill(graphics, font, 0, 0, w, slide, now);
        }
        graphics.pose().popMatrix();
    }

    /** The bar's background: dark with a faint frame, or a pane of glass. */
    private void background(GuiGraphicsExtractor graphics, int x0, int y0, int x1, int y1, int maxRadius) {
        int radius = Math.round(maxRadius * roundness);
        if (glass) {
            Glass.pane(graphics, x0, y0, x1, y1, radius, alpha, density);
        } else {
            Glass.fill(graphics, x0, y0, x1, y1, radius, ARGB.color(alpha * 0.62f * density, 0x0E0F1A));
            Glass.outline(graphics, x0, y0, x1, y1, radius, ARGB.color(alpha * 0.16f * Math.max(0.4f, density), 0xFFFFFF));
        }
    }

    private void drawPill(GuiGraphicsExtractor graphics, Font font, int x0, int y0, int w, float slide, long now) {
        background(graphics, x0, y0, x0 + w, y0 + HEIGHT, HEIGHT / 2);

        int iconX = x0 + PADDING;
        int iconY = y0 + (HEIGHT - ICON) / 2;
        // While Hoergenau records the song the icon is a pulsing red recording light, while it learns an amber one.
        int iconColor = recording ? 0xE53935 : learning ? 0xFFB300 : palette.accentEnd;
        float iconAlpha = recording || learning ? alpha * (0.65f + 0.35f * pulse(now, recording ? 4.0 : 2.5)) : alpha;
        Glass.fill(graphics, iconX, iconY, iconX + ICON, iconY + ICON, Math.round(ICON / 2f * Math.max(0.35f, roundness)),
                ARGB.color(iconAlpha, iconColor));
        if (glass) {
            Glass.rim(graphics, iconX, iconY, iconX + ICON, iconY + ICON, Math.round(ICON / 2f * Math.max(0.35f, roundness)),
                    alpha * 0.8f);
        }
        String icon = recording ? "●" : learning ? "…" : estimated ? "≈" : "♪";
        graphics.centeredText(font, icon, iconX + ICON / 2 + 1, iconY + 2, ARGB.color(alpha, 0xFFFFFF));

        int textX = iconX + ICON + GAP;
        int textRight = x0 + w - PADDING - (equalizer ? EQ_WIDTH + GAP : 0);
        int textY = y0 + 5 + Math.round((1f - slide) * 6f);
        graphics.enableScissor(textX - 2, y0 + 1, textRight + 2, y0 + HEIGHT - 1);
        drawWords(graphics, font, textX, textY, alpha * slide);
        graphics.disableScissor();

        if (equalizer) {
            drawEqualizer(graphics, textRight + GAP, y0, now);
        }
        drawProgress(graphics, x0 + Math.round(HEIGHT / 2f * roundness), x0 + w - Math.round(HEIGHT / 2f * roundness),
                y0 + HEIGHT - 2);
    }

    /** A thin line along the bottom of the bar: how far the song has played, when the player wants it. */
    private void drawProgress(GuiGraphicsExtractor graphics, int left, int right, int y) {
        if (!progress || durationMs <= 0 || right - left < 8) {
            return;
        }
        float played = Easing.clamp01(positionMs / (float) durationMs);
        graphics.fill(left, y, right, y + 1, ARGB.color(alpha * 0.22f, 0xFFFFFF));
        graphics.fill(left, y, left + Math.round((right - left) * played), y + 1, ARGB.color(alpha * 0.95f, palette.accentEnd));
    }

    /** Just the words with a shadow: sung ones white, the current one in the accent color, the rest grey. */
    private void drawPlain(GuiGraphicsExtractor graphics, Font font, int x0, int y0, int w, float slide) {
        float opacity = alpha * slide;
        int space = font.width(Component.literal(" ").withStyle(textStyle));
        int current = currentWord();
        int cursor = x0 + 2;
        int y = y0 + 1 + Math.round((1f - slide) * 4f);
        if (glass) {
            // Glass keeps "plain" plain: only a thin frosted ribbon behind the words.
            float keep = density;
            density *= 0.5f;
            background(graphics, x0 - 4, y0 - 2, x0 + w + 4, y0 + PLAIN_HEIGHT + 1, (PLAIN_HEIGHT + 3) / 2);
            density = keep;
        }
        graphics.enableScissor(x0 - 2, y0 - 2, x0 + w + 2, y0 + PLAIN_HEIGHT + 2);
        for (int i = 0; i < words.length; i++) {
            Component word = Component.literal(words[i]).withStyle(textStyle);
            int color = !lyricLine || (current >= 0 && i < current) ? 0xFFFFFF
                    : i == current ? palette.accentStart : UNSUNG;
            graphics.text(font, word, cursor, y, ARGB.color(opacity, color), true);
            cursor += font.width(word) + space;
        }
        graphics.disableScissor();
        drawProgress(graphics, x0 + 2, x0 + w - 2, y0 + PLAIN_HEIGHT);
        drawHoergenauDot(graphics, x0 + w + 9, y0 - 2, System.nanoTime());
    }

    /** Two lines in a box: the current one fills up word by word as it is sung, the next one waits faintly below. */
    private void drawKaraoke(GuiGraphicsExtractor graphics, Font font, int x0, int y0, int w, float slide) {
        background(graphics, x0, y0, x0 + w, y0 + KARAOKE_HEIGHT, 12);
        int rise = Math.round((1f - slide) * 6f);
        graphics.enableScissor(x0 + 2, y0 + 1, x0 + w - 2, y0 + KARAOKE_HEIGHT - 1);
        drawFilling(graphics, font, x0 + (w - textWidth(font, words)) / 2, y0 + 5 + rise, alpha * slide);
        if (nextWords.length > 0) {
            int space = font.width(Component.literal(" ").withStyle(textStyle));
            int cursor = x0 + (w - textWidth(font, nextWords)) / 2;
            for (String next : nextWords) {
                Component word = Component.literal(next).withStyle(textStyle);
                graphics.text(font, word, cursor, y0 + 18 + rise, ARGB.color(alpha * slide * 0.6f, UNSUNG), false);
                cursor += font.width(word) + space;
            }
        }
        graphics.disableScissor();
        drawProgress(graphics, x0 + 8, x0 + w - 8, y0 + KARAOKE_HEIGHT - 3);
        drawHoergenauDot(graphics, x0 + w - 2, y0 + 1, System.nanoTime());
    }

    /** Each word dim first; as it is sung, the accent color wipes over it from left to right. */
    private void drawFilling(GuiGraphicsExtractor graphics, Font font, int x, int y, float opacity) {
        int space = font.width(Component.literal(" ").withStyle(textStyle));
        int current = currentWord();
        int cursor = x;
        for (int i = 0; i < words.length; i++) {
            Component word = Component.literal(words[i]).withStyle(textStyle);
            int wordWidth = font.width(word);
            float filled = !lyricLine ? 1f : current < 0 ? (positionMs > lineEndMs ? 1f : 0f)
                    : i < current ? 1f : i == current ? fill(i) : 0f;
            if (filled < 1f) {
                graphics.text(font, word, cursor, y, ARGB.color(opacity * 0.75f, 0xFFFFFF), false);
            }
            if (filled > 0f) {
                int edge = cursor + Math.round(wordWidth * filled);
                graphics.enableScissor(cursor - 1, y - 2, edge, y + 10);
                graphics.text(font, word, cursor, y, ARGB.color(opacity, lyricLine ? palette.accentStart : 0xFFFFFF), false);
                graphics.disableScissor();
            }
            cursor += wordWidth + space;
        }
    }

    /** How far the word being sung has been sung, 0 to 1. */
    private float fill(int index) {
        long start = wordTimes[index];
        long end = index + 1 < wordTimes.length ? wordTimes[index + 1] : lineEndMs;
        long length = Math.max(80, Math.min(MAX_FILL_MS, end - start));
        return Easing.clamp01((positionMs - start) / (float) length);
    }

    private void update(LyricsController.Timeline timeline, SkyLyricsConfig config, long now) {
        palette = barPalette(config, timeline.trackKey());
        textStyle = barStyle(config);
        groupHighlight = "natural".equals(config.style);
        positionMs = timeline.positionMs();
        durationMs = timeline.durationMs();
        playing = timeline.playing();
        Hoergenau listener = LyricsInTheSkyClient.controller() == null ? null : LyricsInTheSkyClient.controller().hoergenau();
        recording = listener != null && config.hoergenau && listener.listeningTo(timeline.trackKey());
        learning = listener != null && config.hoergenau && listener.learning();
        Lyrics lyrics = timeline.lyrics();
        String key;
        if (lyrics != null && !lyrics.isEmpty() && !"title".equals(config.hudStyle)) {
            int index = lyrics.indexAt(timeline.positionMs());
            if (index >= 0 && !lyrics.lines().get(index).text().isBlank()
                    && timeline.positionMs() < lyrics.endOf(index, timeline.durationMs())) {
                // The lyrics' identity too: word times arriving, or streamer mode switched, change the words at once.
                key = timeline.trackKey() + "#" + index + "#" + System.identityHashCode(lyrics);
                if (!key.equals(contentKey)) {
                    words = lyrics.lines().get(index).words();
                    nextWords = index + 1 < lyrics.lines().size() ? lyrics.lines().get(index + 1).words() : new String[0];
                    wordTimes = lyrics.wordTimes(index, timeline.durationMs());
                    lineEndMs = lyrics.endOf(index, timeline.durationMs());
                    lyricLine = true;
                    estimated = lyrics.isEstimated();
                    changed(key, now);
                }
                return;
            }
        }
        // The song; when lyrics are off, not found or LRCLIB does not answer, first why no line shows, so the bar's
        // width limit cuts the title rather than the reason.
        key = timeline.trackKey() + "#title#" + timeline.state() + "#" + config.hudArtist + "#" + Lang.english();
        if (!key.equals(contentKey)) {
            String hint = SkyLyricsRenderer.hint(timeline.state());
            String song = timeline.artist().isBlank() || !hint.isEmpty() || !config.hudArtist ? timeline.title()
                    : timeline.title() + " · " + timeline.artist();
            showTitle(key, (hint.isEmpty() ? "" : hint + " · ") + song, now);
        }
    }

    /** While the bar is being moved and nothing plays: a sample line, sung over and over. */
    private void preview(SkyLyricsConfig config, long now) {
        palette = barPalette(config, "preview");
        textStyle = barStyle(config);
        groupHighlight = "natural".equals(config.style);
        playing = true;
        positionMs = now / 1_000_000L % PREVIEW_CYCLE_MS;
        durationMs = PREVIEW_CYCLE_MS;
        if ("title".equals(config.hudStyle)) {
            String titleKey = "preview#title#" + Lang.english();
            if (!contentKey.equals(titleKey)) {
                showTitle(titleKey, Lang.t("Lyrics in the Sky · Vorschau"), now);
            }
            return;
        }
        String lineKey = "preview#" + Lang.english();
        if (!contentKey.equals(lineKey)) {
            words = Lang.t(PREVIEW_LINE).split(" ");
            nextWords = Lang.t(PREVIEW_NEXT).split(" ");
            wordTimes = new long[words.length];
            for (int i = 0; i < words.length; i++) {
                wordTimes[i] = i * PREVIEW_STEP_MS;
            }
            lineEndMs = words.length * PREVIEW_STEP_MS + 600;
            lyricLine = true;
            changed(lineKey, now);
        }
    }

    /**
     * Shows the song instead of a line. {@code key} must be the one the caller compares against next frame: with any
     * other the title would count as new every frame and never fade in, leaving an empty bar.
     */
    private void showTitle(String key, String text, long now) {
        words = text.trim().split("\\s+");
        nextWords = new String[0];
        wordTimes = new long[0];
        lyricLine = false;
        estimated = false;
        changed(key, now);
    }

    /** The lyrics' palette, with the bar's own accent color when the player chose one. */
    private static Palette barPalette(SkyLyricsConfig config, String trackKey) {
        Palette palette = Palette.pick(config, trackKey, "around".equals(config.mode));
        return config.hudOwnAccent ? palette.withAccent(config.hudAccentColor) : palette;
    }

    /**
     * The bar speaks in the type the player chose for it; "auto" the lyrics' own, except handwriting, which is too busy
     * at this size and becomes the modern one.
     */
    private static Style barStyle(SkyLyricsConfig config) {
        String font = "auto".equals(config.hudFont) ? ("minecraft".equals(config.font) ? "minecraft" : "sans") : config.hudFont;
        return "sans".equals(font) ? SkyLyricsRenderer.regularStyle("sans") : Style.EMPTY;
    }

    private void changed(String key, long now) {
        contentKey = key;
        contentChangedNanos = now;
    }

    /** Measured word by word, exactly as the words are placed, so the last word is never cut off. */
    private int textWidth(Font font, String[] line) {
        int space = font.width(Component.literal(" ").withStyle(textStyle));
        int total = 0;
        for (int i = 0; i < line.length; i++) {
            total += font.width(Component.literal(line[i]).withStyle(textStyle)) + (i > 0 ? space : 0);
        }
        return total;
    }

    private void drawWords(GuiGraphicsExtractor graphics, Font font, int x, int y, float opacity) {
        int space = font.width(Component.literal(" ").withStyle(textStyle));
        int current = currentWord();
        if (lyricLine && current >= 0) {
            // Highlight behind the word being sung (or, in the natural style, the words being sung), fading in
            // as the first of them starts. Drawn before any text so it never covers a word.
            int first = groupHighlight ? groupStart(current) : current;
            int cursor = x;
            int left = x;
            int right = x;
            for (int i = 0; i <= current; i++) {
                int wordWidth = font.width(Component.literal(words[i]).withStyle(textStyle));
                left = i == first ? cursor : left;
                right = cursor + wordWidth;
                cursor += wordWidth + space;
            }
            float fresh = Easing.outCubic((positionMs - wordTimes[first]) / 180f);
            if ("underline".equals(highlight)) {
                // A line growing under the words being sung, from their left edge.
                int grown = left + Math.round((right - left) * fresh);
                Glass.fill(graphics, left, y + 10, grown, y + 12, 1, ARGB.color(opacity, palette.accentStart));
            } else if ("box".equals(highlight)) {
                int radius = Math.round(6.5f * roundness);
                Glass.fill(graphics, left - 3, y - 3, right + 3, y + 10, radius,
                        ARGB.color(opacity * (glass ? 0.62f : 0.85f) * fresh, palette.accentEnd));
                if (glass) {
                    // A drop of tinted glass sliding under the word.
                    Glass.rim(graphics, left - 3, y - 3, right + 3, y + 10, radius, opacity * fresh * 0.9f);
                }
            }
        }
        int sungFrom = lyricLine && current >= 0 && "color".equals(highlight)
                ? (groupHighlight ? groupStart(current) : current) : Integer.MAX_VALUE;
        int cursor = x;
        for (int i = 0; i < words.length; i++) {
            Component word = Component.literal(words[i]).withStyle(textStyle);
            int wordWidth = font.width(word);
            boolean sung = !lyricLine || i <= current;
            int color = i >= sungFrom && i <= current ? palette.accentStart : sung ? 0xFFFFFF : UNSUNG;
            graphics.text(font, word, cursor, y, ARGB.color(opacity * (sung ? 1f : 0.85f), color), false);
            cursor += wordWidth + space;
        }
    }

    /** Index of the word being sung, or -1 before the first word and after the line has been sung. */
    private int currentWord() {
        if (!lyricLine || wordTimes.length == 0 || positionMs < wordTimes[0]) {
            return -1;
        }
        int index = 0;
        while (index + 1 < wordTimes.length && positionMs >= wordTimes[index + 1]) {
            index++;
        }
        return positionMs > lineEndMs ? -1 : index;
    }

    /** First word of the group being sung: the words just before the current one that started a moment ago. */
    private int groupStart(int current) {
        int first = current;
        while (first > 0 && positionMs - wordTimes[first - 1] < GROUP_WINDOW_MS) {
            first--;
        }
        return first;
    }

    private static float pulse(long now, double perSecond) {
        return 0.5f + 0.5f * (float) Math.sin(now / 1e9 * perSecond * Math.PI);
    }

    /** In the looks without an icon: a small dot in the top right corner while Hoergenau records or learns. */
    private void drawHoergenauDot(GuiGraphicsExtractor graphics, int right, int top, long now) {
        if (!recording && !learning) {
            return;
        }
        int color = recording ? 0xE53935 : 0xFFB300;
        Glass.fill(graphics, right - 7, top + 3, right - 3, top + 7, 2,
                ARGB.color(alpha * (0.6f + 0.4f * pulse(now, recording ? 4.0 : 2.5)), color));
    }

    private void drawEqualizer(GuiGraphicsExtractor graphics, int x, int y0, long now) {
        double t = now / 1e9;
        for (int bar = 0; bar < 4; bar++) {
            float level = playing
                    ? 0.35f + 0.65f * (float) Math.abs(Math.sin(t * (5.1 + bar * 1.7) + bar * 1.3))
                    : 0.25f;
            int barHeight = Math.max(2, Math.round(level * 9f));
            int bx = x + bar * 3 + 1;
            int bottom = y0 + HEIGHT / 2 + 5;
            graphics.fill(bx, bottom - barHeight, bx + 2, bottom, ARGB.color(alpha, palette.accent(bar / 3f)));
        }
    }
}
