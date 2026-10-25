package de.henner.lyricsinthesky.render;

/**
 * Wobbling on the beat of the singing: every word that starts gives the lyrics a short kick – a quick swell that
 * dies away before the next word – stronger for long words than for little ones, tilting to alternate sides.
 * The kicks come from the same word times the words fade in with, so they are exactly in time with the singing.
 */
final class Beat {
    /** Time from the start of a word to the top of its kick. */
    static final long ATTACK_MS = 45;
    /** The kick dies away with this time constant, well before the next word in most songs. */
    static final long DECAY_MS = 170;
    /** Kicks start this much early, so the top lands right on the word. */
    static final long LEAD_MS = 30;
    private static final long SILENT_AFTER_MS = 6 * DECAY_MS;

    /**
     * @param strength 0 (still) to 1 (top of a kick from a long word)
     * @param tilt     {@code strength} with the side to tilt to: negative left, positive right
     */
    record Kick(float strength, float tilt) {
        static final Kick NONE = new Kick(0f, 0f);
    }

    private Beat() {}

    /**
     * The kick at {@code songMs}.
     *
     * @param words the words of the line being sung
     * @param times start time of every word, as from {@code Lyrics.wordTimes}
     */
    static Kick at(String[] words, long[] times, long songMs) {
        long now = songMs + LEAD_MS;
        int index = -1;
        for (int i = 0; i < Math.min(words.length, times.length); i++) {
            if (times[i] <= now) {
                index = i;
            }
        }
        if (index < 0 || now - times[index] > SILENT_AFTER_MS) {
            return Kick.NONE;
        }
        float strength = weight(words[index]) * envelope(now - times[index]);
        return new Kick(strength, index % 2 == 0 ? -strength : strength);
    }

    /** Shape of one kick over time: a fast rise, then an exponential fall. */
    static float envelope(long sinceStartMs) {
        if (sinceStartMs < 0) {
            return 0f;
        }
        float rise = Math.min(1f, sinceStartMs / (float) ATTACK_MS);
        float fall = (float) Math.exp(-Math.max(0, sinceStartMs - ATTACK_MS) / (double) DECAY_MS);
        return rise * fall;
    }

    /** Long words kick harder than "a" or "the". */
    static float weight(String word) {
        return 0.55f + 0.45f * Math.min(1f, VideoLayout.letters(word) / 6f);
    }
}
