package de.henner.lyricsinthesky.render;

import de.henner.lyricsinthesky.LyricsController;
import de.henner.lyricsinthesky.LyricsInTheSkyClient;
import de.henner.lyricsinthesky.SkyLyricsConfig;
import de.henner.lyricsinthesky.lyrics.Lyrics;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.util.ARGB;

/**
 * The "now playing" pill at the top of the screen: song or current lyric line, the word being sung
 * highlighted, and a small equalizer. Everything eases in and out.
 */
public final class NowPlayingHud implements HudElement {
    private static final int HEIGHT = 18;
    private static final int TOP = 5;
    private static final int PADDING = 7;
    private static final int ICON = 12;
    private static final int EQ_WIDTH = 13;
    private static final int GAP = 6;
    private static final int MAX_TEXT = 300;
    private static final long LINE_SLIDE_MS = 260;

    private float alpha;
    private float width = -1f;
    private long lastNanos;
    private String contentKey = "";
    private long contentChangedNanos;
    private String[] words = new String[0];
    private long[] wordTimes = new long[0];
    private long lineEndMs;
    private boolean lyricLine;
    private long positionMs;
    private boolean playing;
    private Palette palette = Palette.AURORA;

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
        Minecraft minecraft = Minecraft.getInstance();
        LyricsController controller = LyricsInTheSkyClient.controller();
        if (controller == null) {
            return;
        }
        long now = System.nanoTime();
        float dt = lastNanos == 0 ? 0f : Math.min(0.25f, (now - lastNanos) / 1e9f);
        lastNanos = now;
        SkyLyricsConfig config = controller.config();
        LyricsController.Timeline timeline = config.showHud ? controller.timeline(System.currentTimeMillis()) : null;
        if (timeline != null) {
            update(timeline, config, now);
        }
        alpha += ((timeline != null ? 1f : 0f) - alpha) * (1f - (float) Math.exp(-dt / 0.18f));
        if (alpha < 0.02f || words.length == 0) {
            return;
        }

        Font font = minecraft.font;
        int textWidth = Math.min(MAX_TEXT, Math.min(graphics.guiWidth() - 90, textWidth(font)));
        float targetWidth = PADDING + ICON + GAP + textWidth + GAP + EQ_WIDTH + PADDING;
        width = width < 0 ? targetWidth : width + (targetWidth - width) * (1f - (float) Math.exp(-dt / 0.12f));
        int w = Math.round(width);
        int x0 = (graphics.guiWidth() - w) / 2;
        int y0 = TOP;

        roundedRect(graphics, x0, y0, x0 + w, y0 + HEIGHT, HEIGHT / 2, ARGB.color(alpha * 0.62f, 0x0E0F1A));
        roundedRectOutline(graphics, x0, y0, x0 + w, y0 + HEIGHT, HEIGHT / 2, ARGB.color(alpha * 0.16f, 0xFFFFFF));

        int iconX = x0 + PADDING;
        int iconY = y0 + (HEIGHT - ICON) / 2;
        roundedRect(graphics, iconX, iconY, iconX + ICON, iconY + ICON, ICON / 2, ARGB.color(alpha, palette.accentEnd));
        graphics.centeredText(font, "♪", iconX + ICON / 2 + 1, iconY + 2, ARGB.color(alpha, 0xFFFFFF));

        int textX = iconX + ICON + GAP;
        int textRight = x0 + w - PADDING - EQ_WIDTH - GAP;
        float slide = Easing.outCubic((now - contentChangedNanos) / 1e6f / LINE_SLIDE_MS);
        int textY = y0 + 5 + Math.round((1f - slide) * 6f);
        graphics.enableScissor(textX - 2, y0 + 1, textRight + 2, y0 + HEIGHT - 1);
        drawWords(graphics, font, textX, textY, alpha * slide);
        graphics.disableScissor();

        drawEqualizer(graphics, textRight + GAP, y0, now);
    }

    private void update(LyricsController.Timeline timeline, SkyLyricsConfig config, long now) {
        palette = Palette.pick(config.palette, timeline.trackKey());
        positionMs = timeline.positionMs();
        playing = timeline.playing();
        Lyrics lyrics = timeline.lyrics();
        String key;
        if (lyrics != null && !lyrics.isEmpty()) {
            int index = lyrics.indexAt(timeline.positionMs());
            if (index >= 0 && !lyrics.lines().get(index).text().isBlank()
                    && timeline.positionMs() < lyrics.endOf(index, timeline.durationMs())) {
                key = timeline.trackKey() + "#" + index;
                if (!key.equals(contentKey)) {
                    words = lyrics.lines().get(index).words();
                    wordTimes = lyrics.wordTimes(index, timeline.durationMs());
                    lineEndMs = lyrics.endOf(index, timeline.durationMs());
                    lyricLine = true;
                    changed(key, now);
                }
                return;
            }
        }
        key = timeline.trackKey() + "#title";
        if (!key.equals(contentKey)) {
            String artist = timeline.artist().isBlank() ? "" : " · " + timeline.artist();
            words = (timeline.title() + artist).trim().split("\\s+");
            wordTimes = new long[0];
            lyricLine = false;
            changed(key, now);
        }
    }

    private void changed(String key, long now) {
        contentKey = key;
        contentChangedNanos = now;
    }

    private int textWidth(Font font) {
        return font.width(String.join(" ", words));
    }

    private void drawWords(GuiGraphicsExtractor graphics, Font font, int x, int y, float opacity) {
        int space = font.width(" ");
        int current = currentWord();
        int cursor = x;
        for (int i = 0; i < words.length; i++) {
            int wordWidth = font.width(words[i]);
            boolean sung = !lyricLine || i <= current;
            if (lyricLine && i == current) {
                // Highlight pill behind the word being sung, fading in as the word starts.
                float fresh = Easing.outCubic((positionMs - wordTimes[i]) / 180f);
                roundedRect(graphics, cursor - 3, y - 3, cursor + wordWidth + 3, y + 10, 4,
                        ARGB.color(opacity * 0.85f * fresh, palette.accentEnd));
            }
            int color = sung ? 0xFFFFFF : 0x9AA0B4;
            graphics.text(font, words[i], cursor, y, ARGB.color(opacity * (sung ? 1f : 0.85f), color), false);
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
