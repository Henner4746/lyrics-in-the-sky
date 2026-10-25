package de.henner.lyricsinthesky.render;

import de.henner.lyricsinthesky.LyricsController;
import de.henner.lyricsinthesky.LyricsInTheSkyClient;
import de.henner.lyricsinthesky.SkyLyricsConfig;
import de.henner.lyricsinthesky.gui.HudEditScreen;
import de.henner.lyricsinthesky.gui.SettingsScreen;
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
 * {@link SkyLyricsConfig#HUD_STYLES}), at the top or at the bottom above the hotbar wherever the player moved it.
 * Everything eases in and out.
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
    private static final String[] PREVIEW_LINE = "So sieht deine Leiste aus, Wort für Wort".split(" ");
    private static final String[] PREVIEW_NEXT = "und hier kommt die nächste Zeile".split(" ");
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
    private boolean playing;
    private Palette palette = Palette.AURORA;
    private Style textStyle = Style.EMPTY;
    /** Highlight the words being sung as one group instead of only the current word. */
    private boolean groupHighlight;
    /** The line's times were spread over the song from plain text; the icon says so. */
    private boolean estimated;

    public static Bounds lastBounds() {
        return lastBounds;
    }

    public static Shown lastShown() {
        return lastShown;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
        Minecraft minecraft = Minecraft.getInstance();
        LyricsController controller = LyricsInTheSkyClient.controller();
        Screen screen = minecraft.gui.screen();
        if (controller == null || screen instanceof SettingsScreen) {
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
                targetWidth = PADDING + ICON + GAP + textWidth + GAP + EQ_WIDTH + PADDING;
            }
        }
        width = width < 0 ? targetWidth : width + (targetWidth - width) * (1f - (float) Math.exp(-dt / 0.12f));
        int w = Math.round(width);
        HudLayout.Position at = HudLayout.place(config.hudAnchor, config.hudOffsetX, config.hudOffsetY,
                graphics.guiWidth(), graphics.guiHeight(), w, height);
        lastBounds = new Bounds(at.x(), at.y(), w, height);
        float slide = Easing.outCubic((now - contentChangedNanos) / 1e6f / LINE_SLIDE_MS);
        lastShown = new Shown(String.join(" ", words), lyricLine, alpha * slide);
        switch (style) {
            case "karaoke" -> drawKaraoke(graphics, font, at.x(), at.y(), w, slide);
            case "plain" -> drawPlain(graphics, font, at.x(), at.y(), w, slide);
            default -> drawPill(graphics, font, at.x(), at.y(), w, slide, now);
        }
    }

    private void drawPill(GuiGraphicsExtractor graphics, Font font, int x0, int y0, int w, float slide, long now) {
        roundedRect(graphics, x0, y0, x0 + w, y0 + HEIGHT, HEIGHT / 2, ARGB.color(alpha * 0.62f, 0x0E0F1A));
        roundedRectOutline(graphics, x0, y0, x0 + w, y0 + HEIGHT, HEIGHT / 2, ARGB.color(alpha * 0.16f, 0xFFFFFF));

        int iconX = x0 + PADDING;
        int iconY = y0 + (HEIGHT - ICON) / 2;
        roundedRect(graphics, iconX, iconY, iconX + ICON, iconY + ICON, ICON / 2, ARGB.color(alpha, palette.accentEnd));
        graphics.centeredText(font, estimated ? "≈" : "♪", iconX + ICON / 2 + 1, iconY + 2, ARGB.color(alpha, 0xFFFFFF));

        int textX = iconX + ICON + GAP;
        int textRight = x0 + w - PADDING - EQ_WIDTH - GAP;
        int textY = y0 + 5 + Math.round((1f - slide) * 6f);
        graphics.enableScissor(textX - 2, y0 + 1, textRight + 2, y0 + HEIGHT - 1);
        drawWords(graphics, font, textX, textY, alpha * slide);
        graphics.disableScissor();

        drawEqualizer(graphics, textRight + GAP, y0, now);
    }

    /** Just the words with a shadow: sung ones white, the current one in the accent color, the rest grey. */
    private void drawPlain(GuiGraphicsExtractor graphics, Font font, int x0, int y0, int w, float slide) {
        float opacity = alpha * slide;
        int space = font.width(Component.literal(" ").withStyle(textStyle));
        int current = currentWord();
        int cursor = x0 + 2;
        int y = y0 + 1 + Math.round((1f - slide) * 4f);
        graphics.enableScissor(x0 - 2, y0 - 2, x0 + w + 2, y0 + PLAIN_HEIGHT + 2);
        for (int i = 0; i < words.length; i++) {
            Component word = Component.literal(words[i]).withStyle(textStyle);
            int color = !lyricLine || (current >= 0 && i < current) ? 0xFFFFFF
                    : i == current ? palette.accentStart : UNSUNG;
            graphics.text(font, word, cursor, y, ARGB.color(opacity, color), true);
            cursor += font.width(word) + space;
        }
        graphics.disableScissor();
    }

    /** Two lines in a box: the current one fills up word by word as it is sung, the next one waits faintly below. */
    private void drawKaraoke(GuiGraphicsExtractor graphics, Font font, int x0, int y0, int w, float slide) {
        roundedRect(graphics, x0, y0, x0 + w, y0 + KARAOKE_HEIGHT, 6, ARGB.color(alpha * 0.62f, 0x0E0F1A));
        roundedRectOutline(graphics, x0, y0, x0 + w, y0 + KARAOKE_HEIGHT, 6, ARGB.color(alpha * 0.16f, 0xFFFFFF));
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
        palette = Palette.pick(config.palette, timeline.trackKey(), "around".equals(config.mode));
        textStyle = barStyle(config);
        groupHighlight = "natural".equals(config.style);
        positionMs = timeline.positionMs();
        playing = timeline.playing();
        Lyrics lyrics = timeline.lyrics();
        String key;
        if (lyrics != null && !lyrics.isEmpty() && !"title".equals(config.hudStyle)) {
            int index = lyrics.indexAt(timeline.positionMs());
            if (index >= 0 && !lyrics.lines().get(index).text().isBlank()
                    && timeline.positionMs() < lyrics.endOf(index, timeline.durationMs())) {
                key = timeline.trackKey() + "#" + index;
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
        key = timeline.trackKey() + "#title#" + timeline.state();
        if (!key.equals(contentKey)) {
            String hint = SkyLyricsRenderer.hint(timeline.state());
            String song = timeline.artist().isBlank() || !hint.isEmpty() ? timeline.title()
                    : timeline.title() + " · " + timeline.artist();
            showTitle(key, (hint.isEmpty() ? "" : hint + " · ") + song, now);
        }
    }

    /** While the bar is being moved and nothing plays: a sample line, sung over and over. */
    private void preview(SkyLyricsConfig config, long now) {
        palette = Palette.pick(config.palette, "preview", "around".equals(config.mode));
        textStyle = barStyle(config);
        groupHighlight = "natural".equals(config.style);
        playing = true;
        positionMs = now / 1_000_000L % PREVIEW_CYCLE_MS;
        if ("title".equals(config.hudStyle)) {
            if (!contentKey.equals("preview#title")) {
                showTitle("preview#title", "Lyrics in the Sky · Vorschau", now);
            }
            return;
        }
        if (!contentKey.equals("preview")) {
            words = PREVIEW_LINE;
            nextWords = PREVIEW_NEXT;
            wordTimes = new long[words.length];
            for (int i = 0; i < words.length; i++) {
                wordTimes[i] = i * PREVIEW_STEP_MS;
            }
            lineEndMs = words.length * PREVIEW_STEP_MS + 600;
            lyricLine = true;
            changed("preview", now);
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

    /** The bar speaks in the same type as the lyrics, except handwriting, which is too busy at this size. */
    private static Style barStyle(SkyLyricsConfig config) {
        return "sans".equals(config.font) ? SkyLyricsRenderer.regularStyle("sans") : Style.EMPTY;
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
            roundedRect(graphics, left - 3, y - 3, right + 3, y + 10, 4, ARGB.color(opacity * 0.85f * fresh, palette.accentEnd));
        }
        int cursor = x;
        for (int i = 0; i < words.length; i++) {
            Component word = Component.literal(words[i]).withStyle(textStyle);
            int wordWidth = font.width(word);
            boolean sung = !lyricLine || i <= current;
            int color = sung ? 0xFFFFFF : UNSUNG;
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

    /** Pill-shaped fill built from horizontal strips; GUI rendering has no rounded rectangles of its own. */
    private static void roundedRect(GuiGraphicsExtractor graphics, int x0, int y0, int x1, int y1, int radius, int color) {
        if (ARGB.alpha(color) == 0) {
            return;
        }
        int height = y1 - y0;
        radius = Math.min(radius, Math.min((x1 - x0) / 2, height / 2));
        for (int row = 0; row < height; row++) {
            float dy = Math.max(0f, Math.max(radius - row - 0.5f, row + 0.5f - (height - radius)));
            int inset = radius == 0 ? 0 : Math.round(radius - (float) Math.sqrt(Math.max(0f, radius * radius - dy * dy)));
            graphics.fill(x0 + inset, y0 + row, x1 - inset, y0 + row + 1, color);
        }
    }

    private static void roundedRectOutline(GuiGraphicsExtractor graphics, int x0, int y0, int x1, int y1, int radius, int color) {
        int height = y1 - y0;
        radius = Math.min(radius, Math.min((x1 - x0) / 2, height / 2));
        for (int row = 0; row < height; row++) {
            float dy = Math.max(0f, Math.max(radius - row - 0.5f, row + 0.5f - (height - radius)));
            int inset = Math.round(radius - (float) Math.sqrt(Math.max(0f, radius * radius - dy * dy)));
            if (row == 0 || row == height - 1) {
                graphics.fill(x0 + inset, y0 + row, x1 - inset, y0 + row + 1, color);
            } else {
                graphics.fill(x0 + inset, y0 + row, x0 + inset + 1, y0 + row + 1, color);
                graphics.fill(x1 - inset - 1, y0 + row, x1 - inset, y0 + row + 1, color);
            }
        }
    }
}
