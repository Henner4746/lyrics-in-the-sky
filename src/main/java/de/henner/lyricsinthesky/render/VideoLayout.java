package de.henner.lyricsinthesky.render;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;

/**
 * Layout decisions of the two video looks, free of Minecraft types so they can be tested:
 * how a line in the sky splits into word groups of different sizes, and where the next floating word around
 * the player goes.
 */
final class VideoLayout {
    /** Short words that lean on the next word instead of standing alone. */
    private static final Set<String> CLITICS = Set.of(
            "i", "a", "an", "the", "my", "me", "to", "of", "in", "on", "at", "it", "is", "be", "and", "or", "so",
            "ich", "du", "er", "es", "wir", "ihr", "sie", "der", "die", "das", "den", "dem", "ein", "eine", "im",
            "am", "zu", "um", "und", "mit", "wie", "wo", "ja", "nur");
    static final float SMALL = 0.74f;
    static final float NORMAL = 1f;
    static final float LARGE = 1.32f;

    /**
     * Words of one line grouped the way they appear: a short word sticks to the following one ("I smoked",
     * "a dream"), everything else stands alone.
     *
     * @param first index of the first word of the group
     * @param count number of words in it
     * @param scale text size of the group
     */
    record Group(int first, int count, float scale) {}

    private VideoLayout() {}

    static List<Group> groups(String[] words, long seed) {
        List<Group> groups = new ArrayList<>();
        int i = 0;
        int index = 0;
        while (i < words.length) {
            int count = 1;
            if (i + 1 < words.length && isClitic(words[i]) && !endsPhrase(words[i])) {
                count = 2;
            }
            groups.add(new Group(i, count, scale(words, i, count, seed, index++)));
            i += count;
        }
        return groups;
    }

    /**
     * Size of a group: long words are drawn large, groups of only small words small, the rest mostly normal
     * with some variety, so the line has the uneven, hand-set rhythm of the original.
     */
    private static float scale(String[] words, int first, int count, long seed, int index) {
        int longest = 0;
        for (int w = first; w < first + count; w++) {
            longest = Math.max(longest, letters(words[w]));
        }
        float roll = Easing.hash(seed, 900 + index);
        if (longest >= 6) {
            return roll < 0.75f ? LARGE : NORMAL;
        }
        if (longest <= 3) {
            return roll < 0.7f ? SMALL : NORMAL;
        }
        return roll < 0.2f ? LARGE : roll < 0.45f ? SMALL : NORMAL;
    }

    private static boolean isClitic(String word) {
        String bare = word.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}']", "");
        return CLITICS.contains(bare) || bare.length() == 1;
    }

    private static boolean endsPhrase(String word) {
        return word.endsWith(",") || word.endsWith(".") || word.endsWith("!") || word.endsWith("?");
    }

    static int letters(String word) {
        int count = 0;
        for (int i = 0; i < word.length(); i++) {
            if (Character.isLetterOrDigit(word.charAt(i))) {
                count++;
            }
        }
        return count;
    }

    /**
     * Where a floating word goes, relative to the player.
     *
     * @param yawOffset degrees left (negative) or right of the view direction
     * @param distance  blocks from the eyes
     * @param height    blocks above (positive) or below eye level
     * @param twist     extra turn of the word around its vertical axis, so it is seen a little from the side
     * @param roll      slight tilt in the reading direction
     */
    record Spot(float yawOffset, float distance, float height, float twist, float roll) {}

    /** Words land within the view, not beside it: a 70° field of view is about 100° wide on a 16:9 screen. */
    static final float MAX_YAW_OFFSET = 42f;
    /** Not closer than this, or a big word fills half the screen once the player walks towards it. */
    static final float MIN_DISTANCE = 4.6f;
    static final float MAX_DISTANCE = 9f;

    /**
     * A word already on show, as seen from the player now, in degrees.
     *
     * @param halfWidth  half its width on screen
     * @param halfHeight half its height on screen
     */
    record Seen(float yaw, float elevation, float halfWidth, float halfHeight) {}

    /** Gap on screen between two words: positive when they are apart sideways or in height, negative if they overlap. */
    static float gap(Seen a, Seen b) {
        float sideways = Math.abs(wrapDegrees(a.yaw() - b.yaw())) - a.halfWidth() - b.halfWidth();
        float upwards = Math.abs(a.elevation() - b.elevation()) - a.halfHeight() - b.halfHeight();
        return Math.max(sideways, upwards);
    }

    /**
     * Picks a spot for the next floating word that keeps clear of the words still on show: of several random
     * candidates, the one with the widest gap to its nearest neighbour on screen wins, taking the real size of
     * every word into account, so big words do not end up on top of each other.
     *
     * @param recent      the words still visible, newest last
     * @param viewPitch   degrees the player looks up (positive) or down
     * @param widthBlocks width of the new word in blocks
     * @param heightBlocks height of the new word in blocks
     */
    static Spot nextSpot(Random random, List<Seen> recent, float viewPitch, float widthBlocks, float heightBlocks) {
        Spot best = null;
        float bestScore = -Float.MAX_VALUE;
        float pitch = Math.max(-30f, Math.min(30f, viewPitch));
        for (int attempt = 0; attempt < 32; attempt++) {
            float yaw = (random.nextFloat() * 2f - 1f) * MAX_YAW_OFFSET;
            float distance = MIN_DISTANCE + (float) Math.sqrt(random.nextFloat()) * (MAX_DISTANCE - MIN_DISTANCE);
            float height = (random.nextFloat() * 2.2f - 0.8f) + distance * (float) Math.tan(Math.toRadians(pitch * 0.7f));
            Seen candidate = seen(yaw, distance, height, widthBlocks, heightBlocks);
            float score = 30f;
            for (int i = 0; i < recent.size(); i++) {
                // Newer words count a little more: the next word should keep well away from the last one.
                float weight = 1f + 0.3f * i / Math.max(1, recent.size() - 1);
                score = Math.min(score, gap(candidate, recent.get(i)) * weight);
            }
            // Prefer the middle of the view slightly, so most words are seen without turning.
            score -= Math.abs(yaw) * 0.05f;
            if (score > bestScore) {
                bestScore = score;
                float twist = (yaw < 0 ? 1f : -1f) * (8f + random.nextFloat() * 22f);
                float roll = (random.nextFloat() * 2f - 1f) * 7f;
                best = new Spot(yaw, distance, height, twist, roll);
            }
        }
        return best;
    }

    /** How a word of the given size at a spot appears from the eyes. */
    static Seen seen(float yaw, float distance, float height, float widthBlocks, float heightBlocks) {
        float elevation = (float) Math.toDegrees(Math.atan2(height, distance));
        float halfWidth = (float) Math.toDegrees(Math.atan2(widthBlocks / 2f, distance));
        float halfHeight = (float) Math.toDegrees(Math.atan2(heightBlocks / 2f, distance));
        return new Seen(yaw, elevation, halfWidth, halfHeight);
    }

    private static float wrapDegrees(float degrees) {
        float wrapped = degrees % 360f;
        if (wrapped >= 180f) {
            wrapped -= 360f;
        }
        if (wrapped < -180f) {
            wrapped += 360f;
        }
        return wrapped;
    }

    /** Elevation in degrees of a spot as seen from the eyes. */
    static float elevation(Spot spot) {
        return (float) Math.toDegrees(Math.atan2(spot.height(), spot.distance()));
    }
}
