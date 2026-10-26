package de.henner.lyricsinthesky.render;

import de.henner.lyricsinthesky.LyricsController;
import de.henner.lyricsinthesky.LyricsInTheSkyClient;
import de.henner.lyricsinthesky.SkyLyricsConfig;
import de.henner.lyricsinthesky.gui.GlassMenuScreen;
import de.henner.lyricsinthesky.gui.SettingsScreen;
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
 * The lyrics on the screen itself (display "Bildschirm"), in one of three looks: "subtitle" the line at the bottom like a
 * film's subtitles, the word being sung in the accent; "karaoke" the line filling up with color word by word, the next
 * one waiting below; "stage" big lines on the left as in Spotify's and Apple Music's lyrics, the line before and the next
 * ones dimmed, everything gliding up as the song goes on. The player's palette, own colors, outline, capitals, opacity,
 * animation speed and behaviour on pause apply as in the sky.
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
        if ("stage".equals(config.screenStyle)) {
            drawStage(graphics, look, timeline, lyrics, index, dt);
            return;
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
        return screen instanceof SettingsScreen || screen instanceof GlassMenuScreen menu && !menu.peeking();
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
            graphics.text(look.font(), word, x + 1, 1, ARGB.color(Math.min(1f, alpha * 0.45f * config.shadow), 0x0A0C18),
                    false);
        }
        graphics.text(look.font(), word, x, 0, ARGB.color(alpha, color), false);
    }

    /**
     * The stage: the line being sung big on the left, the one before small and dim above it, the next two dim below;
     * when the next line starts everything glides up by one.
     */
    private void drawStage(GuiGraphicsExtractor graphics, Look look, LyricsController.Timeline timeline, Lyrics lyrics,
            int index, float dt) {
        if (lyrics == null || lyrics.isEmpty()) {
            String title = timeline.artist().isBlank() ? timeline.title() : timeline.title() + " · " + timeline.artist();
            drawLeft(graphics, look, title.split(" "), -1, stageX(graphics), stageY(graphics, look), 1f);
            return;
        }
        int shown = Math.max(0, index);
        if (stageIndex != shown) {
            stageLift = stageIndex < 0 ? 0f : Math.max(-2f, Math.min(2f, shown - stageIndex));
            stageIndex = shown;
        }
        stageLift *= (float) Math.exp(-dt * look.config().animationSpeed / 0.09f);
        int x = stageX(graphics);
        float step = look.lineHeight() * 1.25f;
        float y = stageY(graphics, look) + stageLift * step;
        long[] times = index >= 0 ? lyrics.wordTimes(index, timeline.durationMs()) : new long[0];
        boolean sung = index >= 0 && timeline.positionMs() < lyrics.endOf(index, timeline.durationMs());
        int current = sung ? currentWord(times, timeline.positionMs()) : -1;
        // The line before, then the line being sung, then the next two, each a little further away and dimmer.
        if (shown - 1 >= 0) {
            Look small = look.scaled(0.78f);
            String[] before = lyrics.lines().get(shown - 1).words();
            drawLeft(graphics, small, before, -1, x, Math.round(y - step * rowsLeft(small, before)), 0.3f);
        }
        String[] words = lyrics.lines().get(shown).words();
        int used = drawLeft(graphics, look, words, index >= 0 ? current : -1, x, Math.round(y), index >= 0 ? 1f : 0.45f);
        float below = y + used + look.lineHeight() * 0.35f;
        for (int k = 1; k <= 2 && shown + k < lyrics.lines().size(); k++) {
            Look small = look.scaled(0.82f);
            String[] next = lyrics.lines().get(shown + k).words();
            below += drawLeft(graphics, small, next, -1, x, Math.round(below), k == 1 ? 0.42f : 0.22f)
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

    private static int rowsLeft(Look look, String[] words) {
        return Math.max(1, wrap(look, words, Math.round(Minecraft.getInstance().getWindow().getGuiScaledWidth() * 0.8f)).size());
    }

    /** A line from the left edge, wrapped onto rows; the sung words bright, the one being sung in the accent. */
    private static int drawLeft(GuiGraphicsExtractor graphics, Look look, String[] words, int current, int x, int y,
            float dim) {
        List<int[]> rows = wrap(look, words, Math.round(graphics.guiWidth() * 0.8f));
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
}
