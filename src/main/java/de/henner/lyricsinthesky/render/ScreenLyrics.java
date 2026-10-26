package de.henner.lyricsinthesky.render;

import de.henner.lyricsinthesky.LyricsController;
import de.henner.lyricsinthesky.LyricsInTheSkyClient;
import de.henner.lyricsinthesky.SkyLyricsConfig;
import de.henner.lyricsinthesky.gui.GlassMenuScreen;
import de.henner.lyricsinthesky.lyrics.Lyrics;
import java.util.ArrayList;
import java.util.List;
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
 * The lyrics on the screen itself (display "Bildschirm"), in one of these looks: "subtitle" the line at the bottom like
 * a film's subtitles, the word being sung in the accent; "karaoke" the line filling up with color word by word, the
 * next one waiting below; "stage" big lines on the left as in Spotify's and Apple Music's lyrics, the line before and
 * the next ones dimmed, everything gliding up as the song goes on; "word" only the word being sung, big; "typewriter"
 * the line typed letter by letter as it is sung; "ticker" the song's words running by like a news ticker, the word
 * being sung always at one spot; "bounce" a ball hopping from word to word over the line; "wave" the sung words rolling
 * like a wave; "sidebar" the lines in a column at the right edge; "kinetic" each word popping up in its own size and
 * slant as it is sung; "chat" the lines as speech bubbles like in a messenger. The player's palette, own colors,
 * outline, capitals, opacity, animation speed and behaviour on pause apply as in the sky.
 */
public final class ScreenLyrics implements HudElement {
    /** A new line slides and fades in this long, at animation speed 1. */
    private static final float CHANGE_S = 0.32f;
    /** The song's title shows this long at its start while no line is sung yet. */
    private static final long TITLE_MS = 4_500;
    /** A word fills up over its own length, but at most this long, so a held note does not crawl. */
    private static final long MAX_FILL_MS = 1_200;
    private static final float BASE_SCALE = 1.45f;

    private float alpha;
    private long lastNanos;
    private String lineKey = "";
    private long lineChangedNanos;
    private String trackKey = "";
    private long trackStartNanos;
    /** The stage's lines glide up: how far, in lines, the current one still is below its place. */
    private float stageLift;
    private int stageIndex = -1;

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
        long started = Perf.start();
        draw(graphics);
        Perf.stop(Perf.BAR, started);
    }

    private void draw(GuiGraphicsExtractor graphics) {
        Minecraft minecraft = Minecraft.getInstance();
        LyricsController controller = LyricsInTheSkyClient.controller();
        if (controller == null) {
            return;
        }
        SkyLyricsConfig config = controller.config();
        long now = System.nanoTime();
        float dt = lastNanos == 0 ? 0f : Math.min(0.25f, (now - lastNanos) / 1e9f);
        lastNanos = now;
        boolean wanted = config.enabled && "screen".equals(config.mode) && !covered(minecraft.gui.screen());
        LyricsController.Timeline timeline = wanted ? controller.timeline(System.currentTimeMillis()) : null;
        float target = timeline == null ? 0f : timeline.playing() ? 1f : switch (config.pauseMode) {
            case "hide" -> 0f;
            case "keep" -> 1f;
            default -> 0.4f;
        };
        alpha += (target - alpha) * (1f - (float) Math.exp(-dt * config.animationSpeed / 0.22f));
        if (timeline == null || alpha < 0.02f) {
            return;
        }
        if (!timeline.trackKey().equals(trackKey)) {
            trackKey = timeline.trackKey();
            trackStartNanos = now;
            stageIndex = -1;
        }
        Font font = minecraft.font;
        Style style = SkyLyricsRenderer.regularStyle(config.font);
        Palette palette = Palette.pick(config, trackKey, false);
        float scale = BASE_SCALE * config.screenScale;
        Lyrics lyrics = timeline.lyrics();
        int index = lyrics == null || lyrics.isEmpty() ? -1 : lyrics.indexAt(timeline.positionMs());
        boolean sung = index >= 0 && !lyrics.lines().get(index).text().isBlank()
                && timeline.positionMs() < lyrics.endOf(index, timeline.durationMs());
        String key = trackKey + "#" + index + "#" + System.identityHashCode(lyrics) + "#" + sung;
        if (!key.equals(lineKey)) {
            lineKey = key;
            lineChangedNanos = now;
        }
        float fresh = Easing.outCubic((now - lineChangedNanos) / 1e9f * config.animationSpeed / CHANGE_S);
        Look look = new Look(font, style, palette, config, alpha * config.opacity, scale);
        switch (config.screenStyle) {
            case "stage" -> {
                drawStage(graphics, look, timeline, lyrics, index, dt, stageX(graphics), stageY(graphics, look), 0.8f);
                return;
            }
            case "sidebar" -> {
                Look narrow = look.scaled(0.8f);
                drawStage(graphics, narrow, timeline, lyrics, index, dt, Math.round(graphics.guiWidth() * 0.66f),
                        stageY(graphics, narrow), 0.3f);
                return;
            }
            case "ticker" -> {
                drawTicker(graphics, look, timeline, lyrics, index);
                return;
            }
            case "chat" -> {
                drawChat(graphics, look.scaled(0.8f), lyrics, index, sung, fresh);
                return;
            }
            default -> { }
        }
        if (!sung) {
            // Nothing sung right now: at the song's start its title, else the screen stays clear.
            if (config.showTitleCard && (now - trackStartNanos) / 1_000_000L < TITLE_MS) {
                String title = timeline.artist().isBlank() ? timeline.title() : timeline.title() + " · " + timeline.artist();
                drawCentered(graphics, look, title.split(" "), null, -1, 0f, anchorY(graphics, look, 1), fresh, 0.85f);
            }
            return;
        }
        Lyrics.Line line = lyrics.lines().get(index);
        String[] words = line.words();
        long[] times = lyrics.wordTimes(index, timeline.durationMs());
        int current = currentWord(times, timeline.positionMs());
        long lineEnd = lyrics.endOf(index, timeline.durationMs());
        switch (config.screenStyle) {
            case "word" -> {
                drawWord(graphics, look, words, times, current, timeline.positionMs());
                return;
            }
            case "typewriter" -> {
                drawTypewriter(graphics, look, words, times, current, timeline.positionMs(), lineEnd, fresh);
                return;
            }
            case "bounce" -> {
                drawBounce(graphics, look, words, times, current, timeline.positionMs(), lineEnd, fresh);
                return;
            }
            case "wave" -> {
                drawWave(graphics, look, words, current, fresh);
                return;
            }
            case "kinetic" -> {
                drawKinetic(graphics, look, words, times, current, timeline.positionMs(), index);
                return;
            }
            default -> { }
        }
        if ("karaoke".equals(config.screenStyle)) {
            float[] fill = new float[words.length];
            for (int i = 0; i < words.length; i++) {
                fill[i] = i < current ? 1f : i == current ? fill(times, i, timeline.positionMs(),
                        lyrics.endOf(index, timeline.durationMs())) : 0f;
            }
            String[] next = index + 1 < lyrics.lines().size() ? lyrics.lines().get(index + 1).words() : new String[0];
            int rows = rows(look, words) + (next.length > 0 ? 1 : 0);
            int y = anchorY(graphics, look, rows);
            int used = drawCentered(graphics, look, words, fill, current, 0f, y, fresh, 1f);
            if (next.length > 0) {
                Look small = look.scaled(0.72f);
                drawCentered(graphics, small, next, null, -1, 0f, y + used + 3, fresh, 0.45f);
            }
            return;
        }
        drawCentered(graphics, look, words, null, current, 1f, anchorY(graphics, look, rows(look, words)), fresh, 1f);
    }

    /** The menus cover the middle of the screen; the lyrics wait until they are closed, or the live view runs. */
    private static boolean covered(Screen screen) {
        return screen instanceof GlassMenuScreen menu && !menu.peeking();
    }

    /** What every line of a frame is drawn with. */
    private record Look(Font font, Style style, Palette palette, SkyLyricsConfig config, float alpha, float scale) {
        Look scaled(float factor) {
            return new Look(font, style, palette, config, alpha, scale * factor);
        }

        int width(String word) {
            return font.width(Component.literal(word).withStyle(style));
        }

        int lineHeight() {
            return Math.round(11 * scale);
        }
    }

    /** Index of the word being sung, or -1 before the first. */
    private static int currentWord(long[] times, long positionMs) {
        int current = -1;
        for (int i = 0; i < times.length; i++) {
            if (times[i] <= positionMs) {
                current = i;
            }
        }
        return current;
    }

    private static float fill(long[] times, int index, long positionMs, long lineEndMs) {
        long start = times[index];
        long end = index + 1 < times.length ? times[index + 1] : lineEndMs;
        long length = Math.max(80, Math.min(MAX_FILL_MS, end - start));
        return Easing.clamp01((positionMs - start) / (float) length);
    }

    /** The rows a line breaks into on the screen: as many words each as fit 86 % of its width. */
    private static List<int[]> wrap(Look look, String[] words, int maxWidth) {
        List<int[]> rows = new ArrayList<>();
        int space = look.width(" ");
        int start = 0;
        int width = 0;
        for (int i = 0; i < words.length; i++) {
            int w = look.width(words[i]);
            if (i > start && (width + space + w) * look.scale() > maxWidth) {
                rows.add(new int[] {start, i});
                start = i;
                width = w;
            } else {
                width += (i > start ? space : 0) + w;
            }
        }
        if (start < words.length) {
            rows.add(new int[] {start, words.length});
        }
        return rows;
    }

    private static int maxWidth() {
        return Math.round(Minecraft.getInstance().getWindow().getGuiScaledWidth() * 0.86f);
    }

    private static int rows(Look look, String[] words) {
        return Math.max(1, wrap(look, words, maxWidth()).size());
    }

    /** Where a block of {@code rows} rows starts, for the chosen position. */
    private static int anchorY(GuiGraphicsExtractor graphics, Look look, int rows) {
        int height = rows * look.lineHeight();
        return switch (look.config().screenPosition) {
            case "top" -> 38;
            case "center" -> (graphics.guiHeight() - height) / 2 + 12;
            default -> graphics.guiHeight() - 62 - height;
        };
    }

    /**
     * A line centred on the screen, wrapped onto rows; returns the height used.
     *
     * @param fill    per word how far it is filled with the accent (karaoke), or {@code null}
     * @param current the word being sung, in the accent ({@code accentCurrent} 1) or brighter; -1 for none
     * @param dim     how bright the whole line is, 1 for the line being sung
     */
    private static int drawCentered(GuiGraphicsExtractor graphics, Look look, String[] words, float[] fill, int current,
            float accentCurrent, int y, float fresh, float dim) {
        List<int[]> rows = wrap(look, words, maxWidth());
        int space = look.width(" ");
        int lineHeight = look.lineHeight();
        float alpha = look.alpha() * dim * fresh;
        int rise = Math.round((1f - fresh) * 6f);
        if (look.config().screenBackground) {
            int widest = 0;
            for (int[] row : rows) {
                widest = Math.max(widest, rowWidth(look, words, row, space));
            }
            int w = Math.round(widest * look.scale());
            int x = (graphics.guiWidth() - w) / 2;
            Glass.fill(graphics, x - 8, y + rise - 4, x + w + 8, y + rise + rows.size() * lineHeight + 2, 6,
                    ARGB.color(alpha * 0.55f, 0x000000));
        }
        for (int r = 0; r < rows.size(); r++) {
            int[] row = rows.get(r);
            int width = rowWidth(look, words, row, space);
            graphics.pose().pushMatrix();
            graphics.pose().translate((graphics.guiWidth() - width * look.scale()) / 2f, y + rise + r * lineHeight);
            graphics.pose().scale(look.scale(), look.scale());
            int cursor = 0;
            for (int i = row[0]; i < row[1]; i++) {
                int color;
                float a;
                if (fill != null) {
                    color = look.palette().text;
                    a = alpha * (fill[i] >= 1f ? 1f : 0.55f);
                } else {
                    boolean sung = current >= 0 && i <= current;
                    color = i == current && accentCurrent > 0f
                            ? look.palette().accent(words.length > 1 ? i / (float) (words.length - 1) : 0f) : look.palette().text;
                    a = alpha * (current < 0 || sung ? 1f : 0.68f);
                }
                word(graphics, look, words[i], cursor, color, a);
                if (fill != null && fill[i] > 0f && fill[i] < 1f) {
                    // The word being sung fills with the accent from the left as it is sung.
                    int w = look.width(words[i]);
                    graphics.enableScissor(cursor - 1, -2, cursor + Math.round(w * fill[i]), 12);
                    graphics.text(look.font(), Component.literal(words[i]).withStyle(look.style()), cursor, 0,
                            ARGB.color(alpha, look.palette().accent(i / (float) Math.max(1, words.length - 1))), false);
                    graphics.disableScissor();
                } else if (fill != null && fill[i] >= 1f) {
                    graphics.text(look.font(), Component.literal(words[i]).withStyle(look.style()), cursor, 0,
                            ARGB.color(alpha, look.palette().accent(i / (float) Math.max(1, words.length - 1))), false);
                }
                cursor += look.width(words[i]) + space;
            }
            graphics.pose().popMatrix();
        }
        return rows.size() * lineHeight;
    }

    private static int rowWidth(Look look, String[] words, int[] row, int space) {
        int width = 0;
        for (int i = row[0]; i < row[1]; i++) {
            width += look.width(words[i]) + (i > row[0] ? space : 0);
        }
        return width;
    }

    /** One word with the outline or a soft shadow behind it, as the sky draws them. */
    private static void word(GuiGraphicsExtractor graphics, Look look, String text, int x, int color, float alpha) {
        Component word = Component.literal(text).withStyle(look.style());
        SkyLyricsConfig config = look.config();
        if (config.outline) {
            int ring = ARGB.color(Math.min(1f, alpha * 0.9f), config.outlineColor);
            int d = config.outlineWidth > 1.4f ? 2 : 1;
            for (int[] o : new int[][] {{-d, 0}, {d, 0}, {0, -d}, {0, d}, {-d, -d}, {d, d}, {-d, d}, {d, -d}}) {
                graphics.text(look.font(), word, x + o[0], o[1], ring, false);
            }
        } else if (config.shadow > 0.01f) {
            // Stronger than in the sky: on the screen the words stand before a bright sky or white snow alike.
            graphics.text(look.font(), word, x + 1, 1, ARGB.color(Math.min(1f, alpha * 0.65f * config.shadow), 0x0A0C18),
                    false);
        }
        graphics.text(look.font(), word, x, 0, ARGB.color(alpha, color), false);
    }

    /**
     * The stage: the line being sung big on the left, the one before small and dim above it, the next two dim below;
     * when the next line starts everything glides up by one.
     */
    private void drawStage(GuiGraphicsExtractor graphics, Look look, LyricsController.Timeline timeline, Lyrics lyrics,
            int index, float dt, int x, int top, float widthShare) {
        int maxWidth = Math.round(graphics.guiWidth() * widthShare);
        if (lyrics == null || lyrics.isEmpty()) {
            String title = timeline.artist().isBlank() ? timeline.title() : timeline.title() + " · " + timeline.artist();
            drawLeft(graphics, look, title.split(" "), -1, x, top, 1f, maxWidth);
            return;
        }
        int shown = Math.max(0, index);
        if (stageIndex != shown) {
            stageLift = stageIndex < 0 ? 0f : Math.max(-2f, Math.min(2f, shown - stageIndex));
            stageIndex = shown;
        }
        stageLift *= (float) Math.exp(-dt * look.config().animationSpeed / 0.09f);
        float step = look.lineHeight() * 1.25f;
        float y = top + stageLift * step;
        long[] times = index >= 0 ? lyrics.wordTimes(index, timeline.durationMs()) : new long[0];
        boolean sung = index >= 0 && timeline.positionMs() < lyrics.endOf(index, timeline.durationMs());
        int current = sung ? currentWord(times, timeline.positionMs()) : -1;
        // The line before, then the line being sung, then the next two, each a little further away and dimmer.
        if (shown - 1 >= 0) {
            Look small = look.scaled(0.78f);
            String[] before = lyrics.lines().get(shown - 1).words();
            drawLeft(graphics, small, before, -1, x, Math.round(y - step * rowsLeft(small, before, maxWidth)), 0.3f,
                    maxWidth);
        }
        String[] words = lyrics.lines().get(shown).words();
        int used = drawLeft(graphics, look, words, index >= 0 ? current : -1, x, Math.round(y), index >= 0 ? 1f : 0.45f,
                maxWidth);
        float below = y + used + look.lineHeight() * 0.35f;
        for (int k = 1; k <= 2 && shown + k < lyrics.lines().size(); k++) {
            Look small = look.scaled(0.82f);
            String[] next = lyrics.lines().get(shown + k).words();
            below += drawLeft(graphics, small, next, -1, x, Math.round(below), k == 1 ? 0.42f : 0.22f, maxWidth)
                    + look.lineHeight() * 0.25f;
        }
    }

    private static int stageX(GuiGraphicsExtractor graphics) {
        return Math.round(graphics.guiWidth() * 0.08f);
    }

    private static int stageY(GuiGraphicsExtractor graphics, Look look) {
        return switch (look.config().screenPosition) {
            case "top" -> 54;
            case "bottom" -> graphics.guiHeight() - 92 - look.lineHeight();
            default -> graphics.guiHeight() / 2 - look.lineHeight();
        };
    }

    private static int rowsLeft(Look look, String[] words, int maxWidth) {
        return Math.max(1, wrap(look, words, maxWidth).size());
    }

    /** A line from {@code x}, wrapped onto rows; the sung words bright, the one being sung in the accent. */
    private static int drawLeft(GuiGraphicsExtractor graphics, Look look, String[] words, int current, int x, int y,
            float dim, int maxWidth) {
        List<int[]> rows = wrap(look, words, maxWidth);
        int space = look.width(" ");
        int lineHeight = look.lineHeight();
        float alpha = look.alpha() * dim;
        for (int r = 0; r < rows.size(); r++) {
            int[] row = rows.get(r);
            graphics.pose().pushMatrix();
            graphics.pose().translate(x, y + r * lineHeight);
            graphics.pose().scale(look.scale(), look.scale());
            int cursor = 0;
            for (int i = row[0]; i < row[1]; i++) {
                boolean sung = current >= 0 && i <= current;
                int color = i == current ? look.palette().accent(words.length > 1 ? i / (float) (words.length - 1) : 0f)
                        : look.palette().text;
                word(graphics, look, words[i], cursor, color, alpha * (current < 0 || sung ? 1f : 0.5f));
                cursor += look.width(words[i]) + space;
            }
            graphics.pose().popMatrix();
        }
        return rows.size() * lineHeight;
    }

    /** Where each word of a centred, wrapped line sits on the screen: its left edge, row top and width, scaled. */
    private static float[][] places(GuiGraphicsExtractor graphics, Look look, String[] words, int y) {
        List<int[]> rows = wrap(look, words, maxWidth());
        int space = look.width(" ");
        float[][] places = new float[words.length][];
        for (int r = 0; r < rows.size(); r++) {
            int[] row = rows.get(r);
            float x = (graphics.guiWidth() - rowWidth(look, words, row, space) * look.scale()) / 2f;
            for (int i = row[0]; i < row[1]; i++) {
                float w = look.width(words[i]) * look.scale();
                places[i] = new float[] {x, y + r * look.lineHeight(), w};
                x += w + space * look.scale();
            }
        }
        return places;
    }

    /** One word at a place on the screen, in its look's size. */
    private static void wordAt(GuiGraphicsExtractor graphics, Look look, String text, float x, float y, float scale,
            int color, float alpha) {
        graphics.pose().pushMatrix();
        graphics.pose().translate(x, y);
        graphics.pose().scale(scale, scale);
        word(graphics, look, text, 0, color, alpha);
        graphics.pose().popMatrix();
    }

    private static int accentOf(Look look, int index, int count) {
        return look.palette().accent(count > 1 ? index / (float) (count - 1) : 0f);
    }

    /** "Ein Wort": only the word being sung, big in the middle, popping in. */
    private static void drawWord(GuiGraphicsExtractor graphics, Look look, String[] words, long[] times, int current,
            long positionMs) {
        if (current < 0) {
            return;
        }
        String text = words[current];
        float pop = Easing.outBack(Easing.clamp01((positionMs - times[current]) / 1000f
                * look.config().animationSpeed / 0.22f));
        Look big = look.scaled(2.2f);
        float scale = big.scale() * (0.8f + 0.2f * pop);
        float width = big.width(text) * scale;
        float y = anchorY(graphics, big, 1) + (1f - pop) * 6f;
        wordAt(graphics, look, text, (graphics.guiWidth() - width) / 2f, y, scale, accentOf(look, current, words.length),
                look.alpha() * Easing.clamp01(pop * 1.4f));
    }

    /** "Schreibmaschine": the line typed letter by letter as it is sung, with a blinking cursor behind. */
    private static void drawTypewriter(GuiGraphicsExtractor graphics, Look look, String[] words, long[] times,
            int current, long positionMs, long lineEndMs, float fresh) {
        int y = anchorY(graphics, look, rows(look, words));
        float[][] places = places(graphics, look, words, y);
        if (look.config().screenBackground) {
            // The band behind the whole line from the start, so it does not grow letter by letter.
            float left = graphics.guiWidth();
            float right = 0;
            float bottom = y;
            for (float[] place : places) {
                left = Math.min(left, place[0]);
                right = Math.max(right, place[0] + place[2]);
                bottom = Math.max(bottom, place[1] + look.lineHeight());
            }
            Glass.fill(graphics, Math.round(left) - 8, y - 4, Math.round(right) + 8, Math.round(bottom) + 2, 6,
                    ARGB.color(look.alpha() * fresh * 0.55f, 0x000000));
        }
        float cursorX = -1;
        float cursorY = y;
        for (int i = 0; i < words.length && i <= current; i++) {
            float typed = i < current ? 1f : fill(times, i, positionMs, lineEndMs);
            int letters = Math.round(words[i].length() * typed);
            if (letters <= 0) {
                continue;
            }
            String shown = words[i].substring(0, letters);
            wordAt(graphics, look, shown, places[i][0], places[i][1], look.scale(), look.palette().text, look.alpha() * fresh);
            cursorX = places[i][0] + look.width(shown) * look.scale();
            cursorY = places[i][1];
        }
        boolean blink = (System.nanoTime() / 400_000_000L) % 2 == 0;
        if (cursorX >= 0 && blink) {
            wordAt(graphics, look, "_", cursorX + look.scale(), cursorY, look.scale(), accentOf(look, Math.max(0, current),
                    words.length), look.alpha() * fresh);
        }
    }

    /** "Springender Ball": the line as subtitles, with a ball hopping from word to word over it as they are sung. */
    private static void drawBounce(GuiGraphicsExtractor graphics, Look look, String[] words, long[] times, int current,
            long positionMs, long lineEndMs, float fresh) {
        int y = anchorY(graphics, look, rows(look, words)) + Math.round(6 * look.scale());
        drawCentered(graphics, look, words, null, current, 1f, y, fresh, 1f);
        if (current < 0) {
            return;
        }
        float[][] places = places(graphics, look, words, y);
        float[] from = places[current];
        float[] to = current + 1 < words.length ? places[current + 1] : from;
        long end = current + 1 < times.length ? times[current + 1] : lineEndMs;
        float t = Easing.clamp01((positionMs - times[current]) / (float) Math.max(1, end - times[current]));
        float x = from[0] + from[2] / 2f + (to[0] + to[2] / 2f - from[0] - from[2] / 2f) * t;
        float hop = (to == from ? 0.35f : 1f) * (float) Math.sin(Math.PI * t) * 7f * look.scale();
        float top = from[1] + (to[1] - from[1]) * t - 4f * look.scale() - hop;
        int radius = Math.max(2, Math.round(2.4f * look.scale()));
        int color = ARGB.color(look.alpha() * fresh, accentOf(look, current, words.length));
        Glass.fill(graphics, Math.round(x) - radius, Math.round(top) - radius, Math.round(x) + radius,
                Math.round(top) + radius, radius, color);
    }

    /** "Welle": the words sung so far roll like a wave, letter by letter; the ones still to come wait still and dim. */
    private static void drawWave(GuiGraphicsExtractor graphics, Look look, String[] words, int current, float fresh) {
        int y = anchorY(graphics, look, rows(look, words));
        float[][] places = places(graphics, look, words, y);
        float t = (System.nanoTime() / 1e9f) * 5f * look.config().animationSpeed;
        int letter = 0;
        for (int i = 0; i < words.length; i++) {
            boolean sung = current >= 0 && i <= current;
            int color = i == current ? accentOf(look, i, words.length) : look.palette().text;
            float x = places[i][0];
            for (int c = 0; c < words[i].length(); c++) {
                String ch = words[i].substring(c, c + 1);
                float lift = sung ? (float) Math.sin(t - letter * 0.55f) * (i == current ? 2.4f : 1.2f) * look.scale() : 0f;
                wordAt(graphics, look, ch, x, places[i][1] - lift, look.scale(), color,
                        look.alpha() * fresh * (sung ? 1f : 0.5f));
                x += look.width(ch) * look.scale();
                letter++;
            }
            letter += 2;
        }
    }

    /** "Kinetisch": each word pops up when it is sung, in its own size and slant, the newest in the accent. */
    private static void drawKinetic(GuiGraphicsExtractor graphics, Look look, String[] words, long[] times, int current,
            long positionMs, int lineIndex) {
        // Every word has its own size, so the rows are laid out by those sizes, with room for the slant.
        float[] sizes = new float[words.length];
        for (int i = 0; i < words.length; i++) {
            sizes[i] = look.scale() * (0.85f + 0.55f * Easing.hash(lineIndex * 31L + 7, i));
        }
        float gap = look.width(" ") * look.scale() * 1.6f;
        float maxWidth = maxWidth();
        List<int[]> rows = new ArrayList<>();
        int start = 0;
        float width = 0;
        for (int i = 0; i < words.length; i++) {
            float w = look.width(words[i]) * sizes[i];
            if (i > start && width + gap + w > maxWidth) {
                rows.add(new int[] {start, i});
                start = i;
                width = w;
            } else {
                width += (i > start ? gap : 0) + w;
            }
        }
        rows.add(new int[] {start, words.length});
        float rowHeight = look.lineHeight() * 1.5f;
        float y = anchorY(graphics, look, Math.round(rows.size() * 1.5f) + 1) + rowHeight / 2f;
        for (int[] row : rows) {
            float rowWidth = 0;
            for (int i = row[0]; i < row[1]; i++) {
                rowWidth += look.width(words[i]) * sizes[i] + (i > row[0] ? gap : 0);
            }
            float x = (graphics.guiWidth() - rowWidth) / 2f;
            for (int i = row[0]; i < row[1]; i++) {
                float full = look.width(words[i]) * sizes[i];
                if (i <= current) {
                    float slant = (Easing.hash(lineIndex * 17L + 3, i) - 0.5f) * 0.24f;
                    float pop = Easing.outBack(Easing.clamp01((positionMs - times[i]) / 1000f
                            * look.config().animationSpeed / 0.2f));
                    float scale = sizes[i] * (0.6f + 0.4f * pop);
                    graphics.pose().pushMatrix();
                    graphics.pose().translate(x + full / 2f, y);
                    graphics.pose().rotate(slant * pop);
                    graphics.pose().translate(-look.width(words[i]) * scale / 2f, -5.5f * scale);
                    graphics.pose().scale(scale, scale);
                    word(graphics, look, words[i], 0, i == current ? accentOf(look, i, words.length) : look.palette().text,
                            look.alpha() * Easing.clamp01(pop * 1.5f));
                    graphics.pose().popMatrix();
                }
                x += full + gap;
            }
            y += rowHeight;
        }
    }

    /** "Laufband": the words of this and the next lines run by like a ticker, the one being sung always at one spot. */
    private static void drawTicker(GuiGraphicsExtractor graphics, Look look, LyricsController.Timeline timeline,
            Lyrics lyrics, int index) {
        int y = anchorY(graphics, look, 1);
        if (look.config().screenBackground) {
            Glass.fill(graphics, 0, y - 4, graphics.guiWidth(), y + look.lineHeight() + 2, 0,
                    ARGB.color(look.alpha() * 0.55f, 0x000000));
        }
        if (lyrics == null || lyrics.isEmpty()) {
            String title = timeline.artist().isBlank() ? timeline.title() : timeline.title() + " · " + timeline.artist();
            drawCentered(graphics, look, title.split(" "), null, -1, 0f, y, 1f, 0.85f);
            return;
        }
        long position = timeline.positionMs();
        int first = Math.max(0, index - 1);
        int last = Math.min(lyrics.lines().size() - 1, Math.max(0, index) + 3);
        int space = look.width(" ");
        int gap = look.width(" · ");
        float focusAt = -1;
        float tape = 0;
        List<OnTape> laid = new ArrayList<>();
        for (int l = first; l <= last; l++) {
            String[] words = lyrics.lines().get(l).words();
            if (words.length == 0) {
                continue;
            }
            long[] times = lyrics.wordTimes(l, timeline.durationMs());
            long end = lyrics.endOf(l, timeline.durationMs());
            for (int i = 0; i < words.length; i++) {
                int w = look.width(words[i]);
                long next = i + 1 < times.length ? times[i + 1] : end;
                boolean sung = position >= times[i];
                boolean now = sung && position < next && l == index;
                if (now) {
                    focusAt = tape + w * fill(times, i, position, end);
                } else if (l == Math.max(0, index) && i == 0 && !sung && focusAt < 0) {
                    focusAt = tape;
                }
                laid.add(new OnTape(words[i], tape, sung, now, i / (float) Math.max(1, words.length - 1)));
                tape += w + space;
            }
            tape += gap - space;
        }
        if (focusAt < 0) {
            // Between lines: the end of the line just sung stays at the spot.
            focusAt = laid.isEmpty() ? 0 : laid.get(laid.size() - 1).x();
            for (OnTape w : laid) {
                if (w.sung()) {
                    focusAt = w.x() + look.width(w.text());
                }
            }
        }
        float spot = graphics.guiWidth() * 0.35f;
        for (OnTape w : laid) {
            float x = spot + (w.x() - focusAt) * look.scale();
            if (x > graphics.guiWidth() || x + look.width(w.text()) * look.scale() < 0) {
                continue;
            }
            int color = w.now() ? look.palette().accent(w.along()) : look.palette().text;
            wordAt(graphics, look, w.text(), x, y, look.scale(), color, look.alpha() * (w.sung() ? 1f : 0.5f));
        }
    }

    /** A word on the ticker's tape: where it starts, unscaled, whether it was sung, and how far along its line it is. */
    private record OnTape(String text, float x, boolean sung, boolean now, float along) {}

    /** "Chat": the lines sung so far as speech bubbles, newest at the bottom, taking turns left and right. */
    private static void drawChat(GuiGraphicsExtractor graphics, Look look, Lyrics lyrics, int index, boolean sung,
            float fresh) {
        if (lyrics == null || lyrics.isEmpty() || index < 0) {
            return;
        }
        int maxWidth = Math.round(graphics.guiWidth() * 0.45f);
        int pad = 5;
        int space = look.width(" ");
        float bottom = switch (look.config().screenPosition) {
            case "top" -> 40 + look.lineHeight() * 9f;
            case "center" -> graphics.guiHeight() / 2f + look.lineHeight() * 4f;
            default -> graphics.guiHeight() - 60f;
        };
        float rise = (1f - fresh) * (look.lineHeight() + 2 * pad);
        float y = bottom + rise;
        for (int l = index, shown = 0; l >= 0 && shown < 4; l--) {
            String[] words = lyrics.lines().get(l).words();
            if (words.length == 0) {
                continue;
            }
            List<int[]> rows = wrap(look, words, maxWidth);
            int widest = 0;
            for (int[] row : rows) {
                widest = Math.max(widest, rowWidth(look, words, row, space));
            }
            float w = widest * look.scale() + 2 * pad;
            float h = rows.size() * look.lineHeight() + 2 * pad - 2;
            y -= h;
            boolean right = l % 2 == 1;
            float x = right ? graphics.guiWidth() * 0.94f - w : graphics.guiWidth() * 0.06f;
            float age = l == index ? (sung ? 1f : 0.7f) : Math.max(0.25f, 0.75f - 0.17f * shown);
            float a = look.alpha() * age * (l == index ? fresh : 1f);
            int fillColor = l == index ? ARGB.color(a * 0.9f, look.palette().accent(right ? 1f : 0f))
                    : ARGB.color(a * 0.6f, 0x1C1C24);
            Glass.fill(graphics, Math.round(x), Math.round(y), Math.round(x + w), Math.round(y + h), 6, fillColor);
            for (int r = 0; r < rows.size(); r++) {
                int[] row = rows.get(r);
                float cursor = x + pad;
                for (int i = row[0]; i < row[1]; i++) {
                    graphics.pose().pushMatrix();
                    graphics.pose().translate(cursor, y + pad + r * look.lineHeight());
                    graphics.pose().scale(look.scale(), look.scale());
                    graphics.text(look.font(), Component.literal(words[i]).withStyle(look.style()), 0, 0,
                            ARGB.color(Math.min(1f, a * 1.1f), 0xFFFFFF), l == index);
                    graphics.pose().popMatrix();
                    cursor += (look.width(words[i]) + space) * look.scale();
                }
            }
            y -= 4;
            shown++;
        }
    }
}
