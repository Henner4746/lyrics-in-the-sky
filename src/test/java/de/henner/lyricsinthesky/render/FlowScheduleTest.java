package de.henner.lyricsinthesky.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.henner.lyricsinthesky.lyrics.Lyrics;
import java.util.List;
import org.junit.jupiter.api.Test;

class FlowScheduleTest {
    private static final Lyrics LYRICS = Lyrics.parseLrc("""
            [00:01.00]First line here
            [00:04.00]Second line
            [00:07.00]
            [00:08.00]Third line after a short break
            [00:12.00]
            [00:30.00]Fourth after long break
            [01:10.00]Fifth after a gap without marker
            """);

    @Test
    void buildsTitleLinesAndDotsForLongBreaksOnly() {
        List<FlowSchedule.Item> items = FlowSchedule.build(LYRICS, 120_000, true);
        assertEquals(List.of(FlowSchedule.Kind.TITLE, FlowSchedule.Kind.LINE, FlowSchedule.Kind.LINE, FlowSchedule.Kind.LINE,
                FlowSchedule.Kind.DOTS, FlowSchedule.Kind.LINE, FlowSchedule.Kind.DOTS, FlowSchedule.Kind.LINE),
                items.stream().map(FlowSchedule.Item::kind).toList());
        // The one-second break after "Second line" is skipped, the 18-second one gets dots.
        assertEquals(3, items.get(3).lineIndex());
        assertEquals(12_000, items.get(4).startMs());
        // Without an empty line, the dots start once the line before has had its time.
        assertEquals(LYRICS.endOf(5, 120_000), items.get(6).startMs());
        assertEquals(3, items.get(1).wordTimes().length);
    }

    @Test
    void withoutTitleOrLyrics() {
        assertEquals(FlowSchedule.Kind.LINE, FlowSchedule.build(LYRICS, 120_000, false).get(0).kind());
        assertEquals(List.of(FlowSchedule.Kind.TITLE),
                FlowSchedule.build(null, 120_000, true).stream().map(FlowSchedule.Item::kind).toList());
        assertTrue(FlowSchedule.build(null, 120_000, false).isEmpty());
        assertEquals(-1, FlowSchedule.currentIndex(List.of(), 5_000));
    }

    @Test
    void currentEntryFollowsTheSong() {
        List<FlowSchedule.Item> items = FlowSchedule.build(LYRICS, 120_000, true);
        assertEquals(0, FlowSchedule.currentIndex(items, 500));
        assertEquals(1, FlowSchedule.currentIndex(items, 1_000));
        assertEquals(2, FlowSchedule.currentIndex(items, 7_500));
        assertEquals(3, FlowSchedule.currentIndex(items, 8_000));
        assertEquals(4, FlowSchedule.currentIndex(items, 12_500));
        assertEquals(6, FlowSchedule.currentIndex(items, 36_000));
        assertEquals(7, FlowSchedule.currentIndex(items, 71_000));
        // Before the first line without a title card, the first line waits as the current one.
        assertEquals(0, FlowSchedule.currentIndex(FlowSchedule.build(LYRICS, 120_000, false), 0));
    }

    @Test
    void linesFadeSmoothlyWithTheirDistance() {
        assertEquals(1f, FlowSchedule.alpha(0f), 1e-6);
        assertEquals(FlowSchedule.NEXT_ALPHA, FlowSchedule.alpha(1f), 1e-6);
        assertEquals(FlowSchedule.AFTER_NEXT_ALPHA, FlowSchedule.alpha(2f), 1e-6);
        assertEquals(0f, FlowSchedule.alpha(3f), 1e-6);
        assertEquals(0f, FlowSchedule.alpha(-1.1f), 1e-6);
        float previous = FlowSchedule.alpha(-1.5f);
        for (int i = 1; i <= 5000; i++) {
            float d = -1.5f + i * 0.001f;
            float alpha = FlowSchedule.alpha(d);
            // No jumps anywhere: the stack must glide, not flicker.
            assertTrue(Math.abs(alpha - previous) < 0.005f, "jump at " + d);
            // Brightest in the middle, fading towards both sides (checked on each side of the middle).
            boolean sameSide = (d - 0.001f < 0f) == (d < 0f);
            if (sameSide) {
                assertTrue(d < 0f ? alpha >= previous - 1e-6 : alpha <= previous + 1e-6, "not monotonic at " + d);
            }
            previous = alpha;
        }
        assertEquals(1f, FlowSchedule.scale(0f), 1e-6);
        assertEquals(FlowSchedule.SIDE_SCALE, FlowSchedule.scale(1f), 1e-6);
        assertEquals(FlowSchedule.SIDE_SCALE, FlowSchedule.scale(-2f), 1e-6);
        assertEquals(0f, FlowSchedule.blur(0f), 1e-6);
        assertEquals(FlowSchedule.SIDE_BLUR_PX, FlowSchedule.blur(1.5f), 1e-6);
    }

    @Test
    void springGlidesWithoutOvershoot() {
        FlowSchedule.Spring spring = new FlowSchedule.Spring(8f);
        spring.step(0f, 0f);
        float previous = 0f;
        float at750 = 0f;
        for (int frame = 1; frame <= 120; frame++) {
            float value = spring.step(1f, 1f / 60f);
            assertTrue(value >= previous, "moves backwards at frame " + frame);
            assertTrue(value <= 1f + 1e-6, "overshoots at frame " + frame);
            previous = value;
            if (frame == 45) {
                at750 = value;
            }
        }
        assertTrue(at750 > 0.98f, "still far away after 0.75 s: " + at750);
    }

    @Test
    void springIsIndependentOfFrameRate() {
        FlowSchedule.Spring slow = new FlowSchedule.Spring(8f);
        FlowSchedule.Spring fast = new FlowSchedule.Spring(8f);
        slow.step(0f, 0f);
        fast.step(0f, 0f);
        for (int i = 0; i < 30; i++) {
            slow.step(1f, 1f / 30f);
            for (int j = 0; j < 4; j++) {
                fast.step(1f, 1f / 120f);
            }
            assertEquals(slow.value(), fast.value(), 1e-4);
        }
    }

    @Test
    void springStaysSmoothWhenTheTargetMovesOn() {
        // A new line arrives while the stack is still gliding: the motion bends, it never jumps.
        FlowSchedule.Spring spring = new FlowSchedule.Spring(8f);
        spring.step(0f, 0f);
        float previous = 0f;
        float previousStep = 0f;
        for (int frame = 1; frame <= 90; frame++) {
            float target = frame < 15 ? 1f : 2f;
            float value = spring.step(target, 1f / 60f);
            float stepSize = value - previous;
            assertTrue(stepSize >= 0f && stepSize < 0.09f, "jump at frame " + frame);
            assertTrue(Math.abs(stepSize - previousStep) < 0.025f, "jerk at frame " + frame);
            previous = value;
            previousStep = stepSize;
        }
        assertEquals(2f, previous, 0.01f);
    }

    @Test
    void lettersSweepInBoundedTime() {
        long[] times = {1_000, 1_050, 3_000};
        assertEquals(120, FlowSchedule.sweepMs(times, 0, 5_000));
        assertEquals(700, FlowSchedule.sweepMs(times, 1, 5_000));
        assertEquals(500, FlowSchedule.sweepMs(times, 2, 3_500));
    }
}
