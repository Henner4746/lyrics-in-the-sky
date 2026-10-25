package de.henner.lyricsinthesky.render;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Random;
import net.minecraft.util.Mth;

/** Picks a different spot in the sky for every line, spread around where the player is looking. */
final class Placements {
    /**
     * @param anchor the view direction the line was placed around; with "turn along" the line keeps its offset from
     *               where the player looks now instead of from this direction
     */
    record Placement(float yaw, float elevation, float roll, float anchor) {}

    /** Offsets from the view direction: yaw degrees, elevation degrees. */
    private static final float[][] SLOTS = {
            {0, 0}, {-22, -6}, {20, 8}, {-12, 14}, {16, -8},
            {0, 17}, {-26, 5}, {25, -2}, {9, 12}, {-9, -9},
    };
    private static final int AVOID_RECENT = 3;
    /**
     * Lines right above the hills keep at least this many degrees left or right of the view direction, plus up to
     * {@link #HORIZON_SIDE_SPREAD}: two lines in a row are then 24° to 60° apart.
     */
    private static final float HORIZON_MIN_SIDE = 12f;
    private static final float HORIZON_SIDE_SPREAD = 18f;

    private final Random random = new Random();
    private final Deque<Integer> recent = new ArrayDeque<>();
    /** Side of the last line right above the hills: -1 left, 1 right. */
    private int horizonSide;

    void reset(long seed) {
        random.setSeed(seed);
        recent.clear();
        horizonSide = random.nextBoolean() ? 1 : -1;
    }

    Placement next(float anchorYaw, float baseElevation) {
        int slot;
        do {
            slot = random.nextInt(SLOTS.length);
        } while (recent.contains(slot));
        recent.addLast(slot);
        if (recent.size() > AVOID_RECENT) {
            recent.removeFirst();
        }
        float yaw = anchorYaw + SLOTS[slot][0] + jitter(4f);
        float elevation = clamp(baseElevation + SLOTS[slot][1] + jitter(3f), 12f, 74f);
        return new Placement(yaw, elevation, jitter(5f), anchorYaw);
    }

    /**
     * The video look: lines close above the horizon, spread a little wider to the sides and less in height
     * than the classic slots.
     */
    Placement nextLow(float anchorYaw, float baseElevation) {
        Placement sky = next(anchorYaw, baseElevation);
        float yawOffset = Mth.wrapDegrees(sky.yaw() - anchorYaw) * 1.15f;
        float elevation = clamp(baseElevation + (sky.elevation() - baseElevation) * 0.55f, 5f, 45f);
        return new Placement(anchorYaw + yawOffset, elevation, sky.roll() * 0.5f, anchorYaw);
    }

    /**
     * Right above the hills: a few degrees over the horizon with a little variety. All lines sit at about the same
     * height there, so they take turns left and right of the view, far enough apart that the new line does not
     * run into the one fading out. The line itself is lifted just enough that its lowest row clears the horizon.
     *
     * @param baseElevation degrees above the horizon around which the lines sit
     */
    Placement nextHorizon(float anchorYaw, float baseElevation) {
        horizonSide = horizonSide > 0 ? -1 : 1;
        float yawOffset = horizonSide * (HORIZON_MIN_SIDE + random.nextFloat() * HORIZON_SIDE_SPREAD);
        float elevation = clamp(baseElevation - 2f + random.nextFloat() * 6f, 1f, 40f);
        return new Placement(anchorYaw + yawOffset, elevation, jitter(2.5f), anchorYaw);
    }

    Placement title(float anchorYaw, float baseElevation) {
        return new Placement(anchorYaw, clamp(baseElevation + 10f, 12f, 74f), 0f, anchorYaw);
    }

    /**
     * "Around you": spread a bit wider than in the sky, around where the player is looking, including how far
     * up or down.
     *
     * @param viewElevation degrees above the horizon the player is looking at
     */
    Placement nextAround(float anchorYaw, float viewElevation) {
        Placement sky = next(anchorYaw, 30f);
        float yawOffset = Mth.wrapDegrees(sky.yaw() - anchorYaw) * 1.35f;
        float elevation = clamp(viewElevation * 0.8f + (sky.elevation() - 30f) * 0.5f, -45f, 60f);
        return new Placement(anchorYaw + yawOffset, elevation, sky.roll() * 0.6f, anchorYaw);
    }

    Placement titleAround(float anchorYaw, float viewElevation) {
        return new Placement(anchorYaw, clamp(viewElevation * 0.8f + 6f, -45f, 60f), 0f, anchorYaw);
    }

    private float jitter(float range) {
        return (random.nextFloat() * 2f - 1f) * range;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
