package de.henner.lyricsinthesky.render;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Random;
import net.minecraft.util.Mth;

/** Picks a different spot in the sky for every line, spread around where the player is looking. */
final class Placements {
    record Placement(float yaw, float elevation, float roll) {}

    /** Offsets from the view direction: yaw degrees, elevation degrees. */
    private static final float[][] SLOTS = {
            {0, 0}, {-22, -6}, {20, 8}, {-12, 14}, {16, -8},
            {0, 17}, {-26, 5}, {25, -2}, {9, 12}, {-9, -9},
    };
    private static final int AVOID_RECENT = 3;

    private final Random random = new Random();
    private final Deque<Integer> recent = new ArrayDeque<>();

    void reset(long seed) {
        random.setSeed(seed);
        recent.clear();
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
        return new Placement(yaw, elevation, jitter(5f));
    }

    Placement title(float anchorYaw, float baseElevation) {
        return new Placement(anchorYaw, clamp(baseElevation + 10f, 12f, 74f), 0f);
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
        return new Placement(anchorYaw + yawOffset, elevation, sky.roll() * 0.6f);
    }

    Placement titleAround(float anchorYaw, float viewElevation) {
        return new Placement(anchorYaw, clamp(viewElevation * 0.8f + 6f, -45f, 60f), 0f);
    }

    private float jitter(float range) {
        return (random.nextFloat() * 2f - 1f) * range;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
