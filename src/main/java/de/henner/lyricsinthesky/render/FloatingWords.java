package de.henner.lyricsinthesky.render;

import de.henner.lyricsinthesky.LyricsController;
import de.henner.lyricsinthesky.lyrics.Lyrics;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.Set;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.font.TextRenderable;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import org.joml.Matrix4f;

/**
 * "Around you" in the look the mod is modelled on: every sung word appears on its own at a fixed spot in the
 * world around the player – near or farther, left or right, turned a little – fades in, stays for a couple of
 * seconds while the player walks or fights among the words, and fades out again. At the start of a song a
 * "now playing" card hangs in front of the player the same way.
 */
final class FloatingWords {
    /** World size of the text: blocks per font pixel – big, close words, about 8° tall at five blocks. */
    private static final float BLOCKS_PER_PIXEL = 0.08f;
    /** The "now playing" card only belongs to the start of a song, not to switching modes halfway through. */
    private static final long TITLE_ONLY_BEFORE_MS = 10_000;
    private static final long FADE_IN_MS = 240;
    private static final long HOLD_MS = 2_150;
    private static final long FADE_OUT_MS = 520;
    private static final long TITLE_HOLD_MS = 4_200;
    /** About as many as in the original: the words are big, more would crowd the view. */
    private static final int MAX_WORDS = 5;
    private static final long PREROLL_MS = 80;
    /** Words sung longer ago than this when first noticed (joining mid-song, seeking) are skipped. */
    private static final long LATE_LIMIT_MS = 900;
    private static final float RISE_BLOCKS = 0.16f;

    private record Row(FormattedCharSequence text, float width, float scale) {}

    private static final class Floater {
        final List<Row> rows;
        final double x;
        final double y;
        final double z;
        final float faceYaw;
        final float faceElevation;
        final float twist;
        final float roll;
        final float yawOffset;
        final long bornNanos;
        final long holdMs;
        final float width;
        final float height;
        long leaveNanos = -1;

        Floater(List<Row> rows, double x, double y, double z, float faceYaw, float faceElevation, float twist, float roll,
                float yawOffset, long bornNanos, long holdMs, float lineHeight) {
            this.rows = rows;
            this.x = x;
            this.y = y;
            this.z = z;
            this.faceYaw = faceYaw;
            this.faceElevation = faceElevation;
            this.twist = twist;
            this.roll = roll;
            this.yawOffset = yawOffset;
            this.bornNanos = bornNanos;
            this.holdMs = holdMs;
            float w = 0f;
            float h = 0f;
            for (Row row : rows) {
                w = Math.max(w, row.width() * row.scale());
                h += lineHeight * row.scale();
            }
            this.width = w;
            this.height = h;
        }

        void leave(long nowNanos) {
            if (leaveNanos < 0) {
                leaveNanos = nowNanos;
            }
        }
    }

    final String key;
    private final Palette palette;
    private final Style style;
    private final boolean withTitle;
    private final Random random;
    private final List<Floater> floaters = new ArrayList<>();
    private final Set<String> spawned = new HashSet<>();
    private final List<TextRenderable> glyphs = new ArrayList<>();
    private boolean titleShown;
    private boolean leaving;
    private long lastSongMs = Long.MIN_VALUE;

    FloatingWords(String key, Palette palette, Style style, boolean withTitle) {
        this.key = key;
        this.palette = palette;
        this.style = style;
        this.withTitle = withTitle;
        this.random = new Random(key.hashCode());
    }

    /**
     * Spawns the words whose time has come, placed around where the camera looks now.
     *
     * @param cameraYaw   Minecraft yaw of the view, degrees
     * @param cameraPitch degrees the camera looks up (positive) or down
     */
    void update(LyricsController.Timeline timeline, String subtitle, double camX, double camY, double camZ,
            float cameraYaw, float cameraPitch, long nowNanos, Font font) {
        if (leaving) {
            return;
        }
        long song = timeline.positionMs();
        if (song < lastSongMs - 1_500) {
            // Seeked back: the words may come again.
            spawned.clear();
        }
        lastSongMs = song;
        // The card belongs to the start of a song; switching to this mode halfway through goes straight to words.
        if (withTitle && !titleShown && song < TITLE_ONLY_BEFORE_MS) {
            titleShown = true;
            List<Row> rows = new ArrayList<>();
            rows.add(row("♪ NOW PLAYING", font, 0.42f));
            rows.add(row(timeline.title(), font, 1.15f));
            if (!subtitle.isBlank()) {
                rows.add(row(subtitle, font, 0.55f));
            }
            spawn(rows, new VideoLayout.Spot(0f, 9f, 0.9f, 0f, 0f), camX, camY, camZ, cameraYaw, nowNanos, TITLE_HOLD_MS,
                    font.lineHeight);
        }
        Lyrics lyrics = timeline.lyrics();
        if (lyrics == null || lyrics.isEmpty()) {
            return;
        }
        int current = lyrics.indexAt(song + PREROLL_MS);
        for (int index = Math.max(0, current - 1); index <= current; index++) {
            Lyrics.Line line = lyrics.lines().get(index);
            String[] words = line.words();
            long[] times = lyrics.wordTimes(index, timeline.durationMs());
            for (int w = 0; w < words.length && w < times.length; w++) {
                long due = times[w];
                if (due > song + PREROLL_MS) {
                    break;
                }
                String wordKey = index + "/" + w;
                if (!spawned.add(wordKey) || song - due > LATE_LIMIT_MS) {
                    continue;
                }
                float scale = VideoLayout.letters(words[w]) >= 6 ? 1.18f : VideoLayout.letters(words[w]) <= 2 ? 0.82f : 1f;
                Row word = row(words[w], font, scale);
                VideoLayout.Spot spot = VideoLayout.nextSpot(random, seenWords(camX, camY, camZ, cameraYaw, nowNanos),
                        cameraPitch, word.width() * scale * BLOCKS_PER_PIXEL, font.lineHeight * scale * BLOCKS_PER_PIXEL);
                spawn(List.of(word), spot, camX, camY, camZ, cameraYaw, nowNanos, HOLD_MS, font.lineHeight);
            }
        }
        // Never too many at once: the oldest word makes room.
        long alive = floaters.stream().filter(floater -> floater.leaveNanos < 0).count();
        for (Floater floater : floaters) {
            if (alive <= MAX_WORDS) {
                break;
            }
            if (floater.leaveNanos < 0) {
                floater.leave(nowNanos);
                alive--;
            }
        }
    }

    /**
     * The words still clearly visible, as the player sees them now – they stay put while the player turns and
     * walks. Words that are fading out count until they are mostly gone.
     */
    private List<VideoLayout.Seen> seenWords(double camX, double camY, double camZ, float cameraYaw, long nowNanos) {
        List<VideoLayout.Seen> seen = new ArrayList<>();
        for (Floater floater : floaters) {
            if (floater.leaveNanos >= 0 && (nowNanos - floater.leaveNanos) / 1e6f > FADE_OUT_MS * 0.6f) {
                continue;
            }
            double dx = floater.x - camX;
            double dy = floater.y - camY;
            double dz = floater.z - camZ;
            float worldYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
            float horizontal = (float) Math.sqrt(dx * dx + dz * dz);
            seen.add(VideoLayout.seen(Mth.wrapDegrees(worldYaw - cameraYaw), horizontal, (float) dy,
                    floater.width * BLOCKS_PER_PIXEL, floater.height * BLOCKS_PER_PIXEL));
        }
        return seen;
    }

    private Row row(String text, Font font, float scale) {
        Component component = Component.literal(text).withStyle(style);
        return new Row(component.getVisualOrderText(), font.width(component), scale);
    }

    private void spawn(List<Row> rows, VideoLayout.Spot spot, double camX, double camY, double camZ, float cameraYaw,
            long nowNanos, long holdMs, float lineHeight) {
        float yaw = cameraYaw + spot.yawOffset();
        double radians = Math.toRadians(yaw);
        // Minecraft yaw 0 looks south (+Z), 90 west (-X).
        double x = camX - Math.sin(radians) * spot.distance();
        double z = camZ + Math.cos(radians) * spot.distance();
        double y = camY + spot.height();
        float elevation = (float) Math.toDegrees(Math.atan2(spot.height(), spot.distance()));
        floaters.add(new Floater(rows, x, y, z, yaw, elevation, spot.twist(), spot.roll(), spot.yawOffset(), nowNanos,
                holdMs, lineHeight));
    }

    /** Lets every word fade out, e.g. when the song changes; nothing new appears after this. */
    void leave(long nowNanos) {
        leaving = true;
        for (Floater floater : floaters) {
            floater.leave(nowNanos);
        }
    }

    boolean isFinished() {
        return leaving && floaters.isEmpty();
    }

    void render(SkyLine.Frame frame, double camX, double camY, double camZ, List<SkyGlyph> out) {
        long now = frame.nowNanos();
        // A faint shadow by day keeps the light letters readable against a bright sky.
        float shadow = 0.45f * (1f - frame.nightness());
        for (Iterator<Floater> it = floaters.iterator(); it.hasNext(); ) {
            Floater floater = it.next();
            float age = (now - floater.bornNanos) / 1e6f;
            if (floater.leaveNanos < 0 && age > FADE_IN_MS + floater.holdMs) {
                floater.leave(now);
            }
            float out01 = floater.leaveNanos < 0 ? 0f : Easing.clamp01((now - floater.leaveNanos) / 1e6f / FADE_OUT_MS);
            if (out01 >= 1f) {
                it.remove();
                continue;
            }
            float in = Easing.outCubic(age / FADE_IN_MS);
            float fadeOut = Easing.inOutSine(out01);
            float alpha = frame.opacity() * in * (1f - fadeOut);
            if (alpha < 0.01f) {
                continue;
            }
            // Glides up into place, drifts up a little more as it fades, and floats gently in between.
            float lift = -(1f - Easing.outQuint(age / FADE_IN_MS)) * RISE_BLOCKS + fadeOut * RISE_BLOCKS
                    + (float) Math.sin(age * 0.0012f + floater.yawOffset) * 0.03f;
            float scale = BLOCKS_PER_PIXEL * (0.9f + 0.1f * in) * (1f + 0.05f * fadeOut);
            Matrix4f pose = SkyLine.bannerBasis(floater.faceYaw, floater.faceElevation, 0f)
                    .setTranslation((float) (floater.x - camX), (float) (floater.y + lift - camY), (float) (floater.z - camZ))
                    .rotateY((float) Math.toRadians(floater.twist))
                    .rotateZ((float) Math.toRadians(floater.roll))
                    .scale(scale);
            float blur = (1f - in) * 1.6f + fadeOut * 1.6f;
            float top = -floater.height / 2f;
            for (Row row : floater.rows) {
                Matrix4f rowPose = new Matrix4f(pose)
                        .translate(-row.width() * row.scale() / 2f, top, 0f)
                        .scale(row.scale());
                top += frame.font().lineHeight * row.scale();
                glyphs.clear();
                GlyphEffects.collect(frame.font(), row.text(), glyphs);
                for (TextRenderable glyph : glyphs) {
                    GlyphEffects.emitPlain(glyph, rowPose, palette.text, palette.textBottom, alpha, shadow, blur, true, out);
                }
            }
        }
    }
}
