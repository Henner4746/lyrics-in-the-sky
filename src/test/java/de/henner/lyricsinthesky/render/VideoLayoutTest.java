package de.henner.lyricsinthesky.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class VideoLayoutTest {
    @Test
    void shortWordsLeanOnTheNextOne() {
        String[] words = "I smoked away my brain, I think I'm going dumb".split(" ");
        List<String> groups = text(words, VideoLayout.groups(words, 42));
        assertEquals(List.of("I smoked", "away", "my brain,", "I think", "I'm", "going", "dumb"), groups);
    }

    @Test
    void aPhraseEndStaysWithItsWord() {
        // "a," ends a phrase and must not swallow the next word.
        String[] words = "give me a, dream".split(" ");
        List<String> groups = text(words, VideoLayout.groups(words, 1));
        assertEquals(List.of("give", "me a,", "dream"), groups);
    }

    @Test
    void everyWordIsInExactlyOneGroup() {
        String[] words = "und wenn der Song endet verblassen sie wie Sterne am Morgen".split(" ");
        for (long seed = 0; seed < 50; seed++) {
            int next = 0;
            for (VideoLayout.Group group : VideoLayout.groups(words, seed)) {
                assertEquals(next, group.first());
                assertTrue(group.count() == 1 || group.count() == 2);
                next += group.count();
            }
            assertEquals(words.length, next);
        }
    }

    @Test
    void sizesVaryButLongWordsTendToBeBig() {
        String[] words = "microphone fiend give me the beat and let me lean tap the vein let it stream".split(" ");
        int large = 0;
        int small = 0;
        int longWordsLarge = 0;
        int longWords = 0;
        for (long seed = 0; seed < 200; seed++) {
            for (VideoLayout.Group group : VideoLayout.groups(words, seed)) {
                float scale = group.scale();
                assertTrue(scale == VideoLayout.SMALL || scale == VideoLayout.NORMAL || scale == VideoLayout.LARGE);
                large += scale == VideoLayout.LARGE ? 1 : 0;
                small += scale == VideoLayout.SMALL ? 1 : 0;
                if (words[group.first()].length() >= 6 && group.count() == 1) {
                    longWords++;
                    longWordsLarge += scale == VideoLayout.LARGE ? 1 : 0;
                }
            }
        }
        assertTrue(large > 0 && small > 0, "no variety in sizes");
        assertTrue(longWordsLarge > longWords / 2, "long words should mostly be large");
    }

    @Test
    void floatingWordsStayInRangeAndDoNotOverlap() {
        Random random = new Random(7);
        Random words = new Random(11);
        List<VideoLayout.Seen> recent = new ArrayList<>();
        int overlapsWithLast = 0;
        int overlapsWithAny = 0;
        int samples = 0;
        for (int i = 0; i < 3000; i++) {
            // Typical words: 2 to 9 letters at 0.08 blocks per pixel, one line high.
            float width = (12 + words.nextInt(42)) * 0.08f;
            float height = 11 * 0.08f;
            VideoLayout.Spot spot = VideoLayout.nextSpot(random, recent, 5f, width, height);
            assertTrue(Math.abs(spot.yawOffset()) <= VideoLayout.MAX_YAW_OFFSET, "too far to the side");
            assertTrue(spot.distance() >= VideoLayout.MIN_DISTANCE && spot.distance() <= VideoLayout.MAX_DISTANCE);
            assertTrue(spot.height() > -3f && spot.height() < 5f, "height " + spot.height());
            assertTrue(Math.abs(spot.twist()) <= 30f && Math.abs(spot.roll()) <= 7f);
            VideoLayout.Seen seen = VideoLayout.seen(spot.yawOffset(), spot.distance(), spot.height(), width, height);
            if (!recent.isEmpty()) {
                samples++;
                if (VideoLayout.gap(seen, recent.get(recent.size() - 1)) < 0f) {
                    overlapsWithLast++;
                }
                for (VideoLayout.Seen other : recent) {
                    if (VideoLayout.gap(seen, other) < 0f) {
                        overlapsWithAny++;
                        break;
                    }
                }
            }
            recent.add(seen);
            // Five words on show at most, as in the renderer.
            if (recent.size() > 5) {
                recent.remove(0);
            }
        }
        // Big words, a narrow view: a rare touch is acceptable, landing on the last word is not.
        assertTrue(overlapsWithLast <= samples / 100, "overlaps with the last word: " + overlapsWithLast);
        assertTrue(overlapsWithAny <= samples / 25, "overlaps with any word: " + overlapsWithAny + " of " + samples);
    }

    @Test
    void gapMeasuresScreenDistanceBetweenWords() {
        VideoLayout.Seen a = new VideoLayout.Seen(0f, 0f, 5f, 2f);
        assertTrue(VideoLayout.gap(a, new VideoLayout.Seen(9f, 0f, 5f, 2f)) < 0f, "side by side, touching");
        assertEquals(2f, VideoLayout.gap(a, new VideoLayout.Seen(12f, 0f, 5f, 2f)), 1e-5);
        assertEquals(1f, VideoLayout.gap(a, new VideoLayout.Seen(3f, 5f, 5f, 2f)), 1e-5);
        // Across the back of the view, -179° and 179° are neighbours.
        assertTrue(VideoLayout.gap(new VideoLayout.Seen(-179f, 0f, 5f, 2f), new VideoLayout.Seen(179f, 0f, 5f, 2f)) < 0f);
    }

    @Test
    void lookingUpLiftsTheWords() {
        double low = 0;
        double high = 0;
        Random a = new Random(3);
        Random b = new Random(3);
        for (int i = 0; i < 200; i++) {
            low += VideoLayout.nextSpot(a, List.of(), -20f, 1.5f, 0.9f).height();
            high += VideoLayout.nextSpot(b, List.of(), 25f, 1.5f, 0.9f).height();
        }
        assertTrue(high > low + 200, "words should follow the view up");
    }

    private static List<String> text(String[] words, List<VideoLayout.Group> groups) {
        List<String> out = new ArrayList<>();
        for (VideoLayout.Group group : groups) {
            out.add(String.join(" ", java.util.Arrays.copyOfRange(words, group.first(), group.first() + group.count())));
        }
        return out;
    }
}
