package de.henner.lyricsinthesky.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
import de.henner.lyricsinthesky.LyricsController;
import de.henner.lyricsinthesky.LyricsInTheSkyClient;
import de.henner.lyricsinthesky.SkyLyricsConfig;
import de.henner.lyricsinthesky.lyrics.Lyrics;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;

/**
 * Decides which lines are in the sky and draws them right after the vanilla sky.
 *
 * <p>The sky pass runs with a rotation-only model-view matrix, so everything drawn here is camera-relative
 * and behaves like the sun: it never comes closer, never clips into walls, and terrain drawn later covers it.
 */
public final class SkyLyricsRenderer {
    public static final SkyLyricsRenderer INSTANCE = new SkyLyricsRenderer();

    /** Lines start appearing slightly before their timestamp so the first word is readable on the beat. */
    private static final long PREROLL_MS = 150;
    private static final long TITLE_MS = 5_000;
    /** The title card waits this long for the lyrics lookup, so it can say whether lyrics were found. */
    private static final long TITLE_DELAY_MS = 900;
    /** Text size and maximum line width, as blocks at 100 blocks distance (about 4.5° tall, 44° wide). */
    private static final float BLOCKS_PER_PIXEL_AT_100 = 0.74f;
    private static final float MAX_WIDTH_AT_100 = 80f;
    private static final float FOLLOW_TIME_CONSTANT_S = 0.25f;
    private static final FontDescription SCRIPT = new FontDescription.Resource(
            Identifier.fromNamespaceAndPath(LyricsInTheSkyClient.MOD_ID, "script"));
    private static final FontDescription SCRIPT_BOLD = new FontDescription.Resource(
            Identifier.fromNamespaceAndPath(LyricsInTheSkyClient.MOD_ID, "script_bold"));

    private final Map<String, SkyLine> lines = new LinkedHashMap<>();
    private final Placements placements = new Placements();
    private String trackKey;
    private long trackStartNanos;
    private long lineCounter;
    private float anchorYaw = Float.NaN;
    private float viewElevation;
    private float fixedYaw = Float.NaN;
    private long lastFrameNanos;
    private float pauseDim = 1f;
    private long lastSongPositionMs;

    private SkyLyricsRenderer() {}

    /** @param skyLuminance brightness of the sky color this frame, 0 (night) to 1 (bright day) */
    public void render(RenderTarget target, float skyLuminance) {
        LyricsController controller = LyricsInTheSkyClient.controller();
        Minecraft minecraft = Minecraft.getInstance();
        if (controller == null || minecraft.level == null) {
            return;
        }
        long now = System.nanoTime();
        float dt = lastFrameNanos == 0 ? 0f : Math.min(0.25f, (now - lastFrameNanos) / 1e9f);
        lastFrameNanos = now;
        SkyLyricsConfig config = controller.config();
        var camera = minecraft.gameRenderer.gameRenderState().levelRenderState.cameraRenderState;
        updateAnchor(camera.yRot, dt, config.followView);
        // xRot is negative when looking up.
        viewElevation = config.followView ? -camera.xRot : 0f;

        LyricsController.Timeline timeline = controller.timeline(System.currentTimeMillis());
        Set<String> wanted = new HashSet<>();
        float pauseTarget = 1f;
        if (timeline != null) {
            updateWanted(timeline, config, minecraft, now, wanted);
            lastSongPositionMs = timeline.positionMs();
            pauseTarget = timeline.playing() ? 1f : 0.4f;
        }
        for (SkyLine line : lines.values()) {
            if (!wanted.contains(line.key)) {
                line.startExit(now, lastSongPositionMs);
            }
        }
        lines.values().removeIf(line -> line.isFinished(now));
        if (lines.isEmpty()) {
            return;
        }
        pauseDim += (pauseTarget - pauseDim) * (1f - (float) Math.exp(-dt / 0.35f));

        float nightness = 1f - Easing.smoothstep(0.12f, 0.45f, skyLuminance);
        SkyLine.Frame frame = new SkyLine.Frame(now, lastSongPositionMs, BLOCKS_PER_PIXEL_AT_100 * config.scale,
                MAX_WIDTH_AT_100 * config.scale, config.opacity * pauseDim, nightness, minecraft.font, Style.EMPTY);
        List<SkyGlyph> glyphs = new ArrayList<>(2048);
        for (SkyLine line : lines.values()) {
            line.render(frame, glyphs);
        }
        if (!glyphs.isEmpty()) {
            GlyphDrawer.draw(target, glyphs);
        }
    }

    private void updateWanted(LyricsController.Timeline timeline, SkyLyricsConfig config, Minecraft minecraft,
            long now, Set<String> wanted) {
        if (!timeline.trackKey().equals(trackKey)) {
            trackKey = timeline.trackKey();
            trackStartNanos = now;
            placements.reset(trackKey.hashCode());
        }
        Palette palette = Palette.pick(config.palette, trackKey);
        boolean around = "around".equals(config.mode);
        SkyLine.Look look = around ? SkyLine.AROUND : SkyLine.SKY;
        boolean script = "script".equals(config.font);
        Style regular = script ? Style.EMPTY.withFont(SCRIPT) : Style.EMPTY;
        Style bold = script ? Style.EMPTY.withFont(SCRIPT_BOLD) : Style.EMPTY.withBold(true);

        boolean lyricShown = false;
        Lyrics lyrics = timeline.lyrics();
        if (lyrics != null && !lyrics.isEmpty()) {
            int index = lyrics.indexAt(timeline.positionMs() + PREROLL_MS);
            if (index >= 0) {
                Lyrics.Line line = lyrics.lines().get(index);
                if (!line.text().isBlank() && timeline.positionMs() < lyrics.endOf(index, timeline.durationMs())) {
                    // The mode is part of the key, so switching modes lets the old line leave and a new one arrive.
                    String key = trackKey + "#" + index + config.mode;
                    wanted.add(key);
                    lyricShown = true;
                    if (!lines.containsKey(key)) {
                        Placements.Placement placement = around
                                ? placements.nextAround(anchorYaw, viewElevation)
                                : placements.next(anchorYaw, config.elevationDeg);
                        lines.put(key, SkyLine.lyric(key, line.text(), lyrics.wordTimes(index, timeline.durationMs()),
                                placement, look, palette, nextSeed(), minecraft.font, regular, bold));
                    }
                }
            }
        }

        long sinceTrackStart = (now - trackStartNanos) / 1_000_000L;
        boolean lookupSettled = timeline.state() != LyricsController.LookupState.SEARCHING
                && timeline.state() != LyricsController.LookupState.IDLE;
        String titleKey = trackKey + "#title" + config.mode;
        boolean titleExists = lines.containsKey(titleKey);
        if (config.showTitleCard && !lyricShown && sinceTrackStart < TITLE_MS + TITLE_DELAY_MS
                && (titleExists || lookupSettled || sinceTrackStart > TITLE_DELAY_MS)) {
            wanted.add(titleKey);
            if (!titleExists) {
                Placements.Placement placement = around
                        ? placements.titleAround(anchorYaw, viewElevation) : placements.title(anchorYaw, config.elevationDeg);
                lines.put(titleKey, SkyLine.title(titleKey, timeline.title(), subtitle(timeline), placement, look,
                        palette, nextSeed(), minecraft.font, regular, bold));
            }
        }
    }

    private static String subtitle(LyricsController.Timeline timeline) {
        String hint = switch (timeline.state()) {
            case NOT_ALLOWED -> " · Lyrics: /skylyrics lrclib on";
            case NOT_FOUND, INSTRUMENTAL -> " · keine Lyrics gefunden";
            case ERROR -> " · Lyrics-Dienst nicht erreichbar";
            default -> "";
        };
        return timeline.artist() + hint;
    }

    private long nextSeed() {
        return (trackKey == null ? 0 : trackKey.hashCode()) * 31L + ++lineCounter * 0x9E3779B9L;
    }

    /**
     * Where new lines are placed. Lines stay where they appeared; with "follow view" each new one pops up
     * around the current view direction, otherwise around the direction the player faced when it started.
     */
    private void updateAnchor(float cameraYaw, float dt, boolean followView) {
        if (Float.isNaN(anchorYaw) || !followView && Float.isNaN(fixedYaw)) {
            fixedYaw = cameraYaw;
        }
        if (followView) {
            // Light smoothing so a line placed during a quick turn does not land at a random edge.
            float diff = Mth.wrapDegrees(cameraYaw - (Float.isNaN(anchorYaw) ? cameraYaw : anchorYaw));
            anchorYaw = Float.isNaN(anchorYaw) ? cameraYaw : anchorYaw + diff * (1f - (float) Math.exp(-dt / FOLLOW_TIME_CONSTANT_S));
        } else {
            anchorYaw = fixedYaw;
        }
    }

    /** Clears everything, e.g. when leaving a world. */
    public void reset() {
        lines.clear();
        trackKey = null;
        anchorYaw = Float.NaN;
        fixedYaw = Float.NaN;
        lastFrameNanos = 0;
    }
}
