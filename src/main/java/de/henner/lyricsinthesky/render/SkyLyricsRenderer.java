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
    /** How quickly the gliding stack follows the view, rad/s of its spring: it trails turns by about a second. */
    private static final float FLOW_AIM_OMEGA = 4f;
    /** A new stack starts fading in this long after the old one began to leave. */
    private static final long FLOW_HANDOVER_MS = 550;
    private static final FontDescription SCRIPT = new FontDescription.Resource(
            Identifier.fromNamespaceAndPath(LyricsInTheSkyClient.MOD_ID, "script"));
    private static final FontDescription SCRIPT_BOLD = new FontDescription.Resource(
            Identifier.fromNamespaceAndPath(LyricsInTheSkyClient.MOD_ID, "script_bold"));
    private static final FontDescription SANS = new FontDescription.Resource(
            Identifier.fromNamespaceAndPath(LyricsInTheSkyClient.MOD_ID, "sans"));
    private static final FontDescription SANS_ITALIC = new FontDescription.Resource(
            Identifier.fromNamespaceAndPath(LyricsInTheSkyClient.MOD_ID, "sans_italic"));

    private final Map<String, SkyLine> lines = new LinkedHashMap<>();
    /** The video look: lines in the sky ... */
    private final Map<String, ArcLine> arcs = new LinkedHashMap<>();
    /** ... and single words around the player, plus older sets of words still fading out. */
    private FloatingWords floating;
    private final List<FloatingWords> leavingFloating = new ArrayList<>();
    private final Placements placements = new Placements();
    /** The gliding stack of the current song, and older stacks still fading out. */
    private LyricFlow flow;
    private final List<LyricFlow> leavingFlows = new ArrayList<>();
    /** A song without lyrics whose title card has been shown; not shown again until the song changes. */
    private String flowDoneKey;
    private final FlowSchedule.Spring flowYaw = new FlowSchedule.Spring(FLOW_AIM_OMEGA);
    private final FlowSchedule.Spring flowElevation = new FlowSchedule.Spring(FLOW_AIM_OMEGA);
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
        boolean around = "around".equals(config.mode);
        boolean video = "video".equals(config.style);
        boolean flowStyle = "flow".equals(config.style);
        aimFlow(camera.yRot, -camera.xRot, config, dt);

        LyricsController.Timeline timeline = controller.timeline(System.currentTimeMillis());
        Set<String> wanted = new HashSet<>();
        Set<String> wantedArcs = new HashSet<>();
        float pauseTarget = 1f;
        if (timeline != null) {
            if (video && around) {
                updateFloating(timeline, config, minecraft, camera.pos.x, camera.pos.y, camera.pos.z, camera.yRot,
                        -camera.xRot, now);
            } else if (video) {
                updateArcs(timeline, config, minecraft, now, wantedArcs);
            } else if (flowStyle) {
                updateFlow(timeline, config, minecraft, now);
            } else {
                updateWanted(timeline, config, minecraft, now, wanted);
            }
            lastSongPositionMs = timeline.positionMs();
            pauseTarget = timeline.playing() ? 1f : 0.4f;
        }
        if (timeline == null || !flowStyle) {
            leaveFlow(now);
        }
        if (timeline == null || !(video && around)) {
            leaveFloating(now);
        }
        for (SkyLine line : lines.values()) {
            if (!wanted.contains(line.key)) {
                line.startExit(now, lastSongPositionMs);
            }
        }
        for (ArcLine arc : arcs.values()) {
            if (!wantedArcs.contains(arc.key)) {
                arc.startExit(now, lastSongPositionMs);
            }
        }
        lines.values().removeIf(line -> line.isFinished(now));
        arcs.values().removeIf(arc -> arc.isFinished(now));
        leavingFlows.removeIf(leaving -> leaving.isFinished(now));
        leavingFloating.removeIf(FloatingWords::isFinished);
        if (lines.isEmpty() && arcs.isEmpty() && flow == null && leavingFlows.isEmpty() && floating == null
                && leavingFloating.isEmpty()) {
            return;
        }
        pauseDim += (pauseTarget - pauseDim) * (1f - (float) Math.exp(-dt / 0.35f));

        float nightness = 1f - Easing.smoothstep(0.12f, 0.45f, skyLuminance);
        SkyLine.Frame frame = new SkyLine.Frame(now, lastSongPositionMs, BLOCKS_PER_PIXEL_AT_100 * config.scale,
                MAX_WIDTH_AT_100 * config.scale, config.opacity * pauseDim, nightness, minecraft.font, Style.EMPTY);
        List<SkyGlyph> glyphs = new ArrayList<>(2048);
        for (LyricFlow leaving : leavingFlows) {
            leaving.render(frame, glyphs);
        }
        if (flow != null) {
            flow.render(frame, glyphs);
        }
        for (SkyLine line : lines.values()) {
            line.render(frame, glyphs);
        }
        for (ArcLine arc : arcs.values()) {
            arc.render(frame, glyphs);
        }
        for (FloatingWords words : leavingFloating) {
            words.render(frame, camera.pos.x, camera.pos.y, camera.pos.z, glyphs);
        }
        if (floating != null) {
            floating.render(frame, camera.pos.x, camera.pos.y, camera.pos.z, glyphs);
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
        boolean around = "around".equals(config.mode);
        Palette palette = Palette.pick(config.palette, trackKey, around);
        SkyLine.Look look = around ? SkyLine.AROUND : SkyLine.SKY;
        Style regular = regularStyle(config.font);
        Style bold = boldStyle(config.font, false);

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

    /** The gliding stack: one per song, mode and look; a change lets the old stack glide away. */
    private void updateFlow(LyricsController.Timeline timeline, SkyLyricsConfig config, Minecraft minecraft, long now) {
        String key = timeline.trackKey() + "#" + config.mode + "#" + config.font + "#" + config.palette + "#"
                + config.showTitleCard;
        if (flow != null && !flow.key.equals(key)) {
            leaveFlow(now);
        }
        if (key.equals(flowDoneKey)) {
            return;
        }
        if (flow == null) {
            boolean around = "around".equals(config.mode);
            // While an old stack is still gliding away, the new one waits a moment so the two never overlap.
            flow = new LyricFlow(key, around ? SkyLine.AROUND : SkyLine.SKY,
                    Palette.pick(config.palette, timeline.trackKey(), around), regularStyle(config.font),
                    boldStyle(config.font, false), config.showTitleCard, BLOCKS_PER_PIXEL_AT_100,
                    leavingFlows.isEmpty() ? 0 : FLOW_HANDOVER_MS);
        }
        flow.update(timeline, subtitle(timeline), minecraft.font);
        flow.aim(flowYaw.value(), flowElevation.value());
        boolean settled = timeline.state() != LyricsController.LookupState.SEARCHING
                && timeline.state() != LyricsController.LookupState.IDLE;
        if (timeline.lyrics() == null && settled && flow.shownFor(now, TITLE_MS + TITLE_DELAY_MS)) {
            // No lyrics for this song: the title card has had its moment.
            flowDoneKey = key;
            leaveFlow(now);
        }
    }

    /**
     * The video look in the sky: the line being sung at its own spot near the horizon; when the next line
     * starts, the old one fades out and the new one builds up somewhere else.
     */
    private void updateArcs(LyricsController.Timeline timeline, SkyLyricsConfig config, Minecraft minecraft, long now,
            Set<String> wanted) {
        if (!timeline.trackKey().equals(trackKey)) {
            trackKey = timeline.trackKey();
            trackStartNanos = now;
            placements.reset(trackKey.hashCode());
        }
        Lyrics lyrics = timeline.lyrics();
        if (lyrics == null || lyrics.isEmpty()) {
            return;
        }
        int index = lyrics.indexAt(timeline.positionMs() + PREROLL_MS);
        if (index < 0) {
            return;
        }
        Lyrics.Line line = lyrics.lines().get(index);
        if (line.text().isBlank() || timeline.positionMs() >= lyrics.endOf(index, timeline.durationMs())) {
            return;
        }
        String key = trackKey + "#" + index + "#arc#" + config.font + "#" + config.palette;
        wanted.add(key);
        if (!arcs.containsKey(key)) {
            arcs.put(key, ArcLine.create(key, line.text(), lyrics.wordTimes(index, timeline.durationMs()),
                    placements.nextLow(anchorYaw, config.elevationDeg), SkyLine.SKY,
                    Palette.pick(config.palette, trackKey, false), nextSeed(), minecraft.font, boldStyle(config.font, false)));
        }
    }

    /** The video look around the player: one set of floating words per song. */
    private void updateFloating(LyricsController.Timeline timeline, SkyLyricsConfig config, Minecraft minecraft,
            double camX, double camY, double camZ, float cameraYaw, float cameraPitch, long now) {
        String key = timeline.trackKey() + "#float#" + config.font + "#" + config.palette + "#" + config.showTitleCard;
        if (floating != null && !floating.key.equals(key)) {
            leaveFloating(now);
        }
        if (floating == null) {
            floating = new FloatingWords(key, Palette.pick(config.palette, timeline.trackKey(), true),
                    boldStyle(config.font, true), config.showTitleCard);
        }
        floating.update(timeline, subtitle(timeline), camX, camY, camZ, cameraYaw, cameraPitch, now, minecraft.font);
    }

    private void leaveFloating(long now) {
        if (floating != null) {
            floating.leave(now);
            leavingFloating.add(floating);
            floating = null;
        }
    }

    static Style regularStyle(String font) {
        return switch (font) {
            case "script" -> Style.EMPTY.withFont(SCRIPT);
            case "sans" -> Style.EMPTY.withFont(SANS);
            default -> Style.EMPTY;
        };
    }

    /** @param italic the slanted variant, as used around the player */
    private static Style boldStyle(String font, boolean italic) {
        return switch (font) {
            case "script" -> Style.EMPTY.withFont(SCRIPT_BOLD);
            case "sans" -> Style.EMPTY.withFont(italic ? SANS_ITALIC : SANS);
            default -> Style.EMPTY.withBold(true).withItalic(italic);
        };
    }

    private void leaveFlow(long now) {
        if (flow != null) {
            flow.leave(now);
            leavingFlows.add(flow);
            flow = null;
        }
    }

    /**
     * Where the gliding stack hangs: in front of the player, trailing their turns on a spring so it glides
     * after the view instead of sticking to it. In the sky at the configured height; around the player at
     * roughly the height they look at.
     */
    private void aimFlow(float cameraYaw, float cameraElevation, SkyLyricsConfig config, float dt) {
        boolean around = "around".equals(config.mode);
        float yaw = config.followView || Float.isNaN(fixedYaw) ? cameraYaw : fixedYaw;
        float elevation = around ? (config.followView ? Mth.clamp(cameraElevation * 0.85f, -35f, 55f) : 0f)
                : config.elevationDeg;
        flowYaw.step(flowYaw.value() + Mth.wrapDegrees(yaw - flowYaw.value()), dt);
        flowElevation.step(elevation, dt);
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
        arcs.clear();
        floating = null;
        leavingFloating.clear();
        flow = null;
        leavingFlows.clear();
        flowDoneKey = null;
        flowYaw.reset();
        flowElevation.reset();
        trackKey = null;
        anchorYaw = Float.NaN;
        fixedYaw = Float.NaN;
        lastFrameNanos = 0;
    }
}
