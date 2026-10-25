package de.henner.lyricsinthesky.lyrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.henner.lyricsinthesky.nowplaying.NowPlaying;
import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class WordTimeFinderTest {
    private static final NowPlaying TRACK = new NowPlaying("test", "Lanterns", "The Made Up Band", "", true, 0, 120_000, 0);

    @Test
    void takesTheFirstServiceThatLinesUpWithLrclib() throws Exception {
        Lyrics base = Lyrics.parseLrc(lrc(0, 0));
        WordTimeFinder finder = new WordTimeFinder(List.of(fixed("One", null), fixed("Two", timed("Two", 100)),
                fixed("Three", timed("Three", 0))));
        WordTimeFinder.Outcome outcome = finder.find(TRACK, base, Set.of("one", "two", "three"));
        assertEquals("Two", outcome.taken().orElseThrow().source());
        assertEquals("LRCLIB", outcome.taken().get().how());
        assertTrue(outcome.taken().get().lyrics().hasWordTiming());
        assertEquals(Optional.of("two"), WordTimeFinder.serviceOf(outcome.taken().get().lyrics().lrc()));
    }

    @Test
    void wordTimesAreNamedAfterTheServiceAsked() throws Exception {
        WordTimeFinder finder = new WordTimeFinder(List.of(fixed("Proxy", timed("Upstream Music", 0))));
        WordTimeFinder.Taken taken = finder.find(TRACK, Lyrics.parseLrc(lrc(0, 0)), Set.of("proxy")).taken().orElseThrow();
        assertEquals("Proxy (Upstream Music)", taken.source());
        assertEquals(Optional.of("proxy"), WordTimeFinder.serviceOf(taken.lyrics().lrc()));
        assertEquals(Optional.of("lyricsplus"), WordTimeFinder.serviceOf(WordTimeTransfer.MARK + "\n[00:01.00]<00:01.00>old"));
    }

    @Test
    void servicesSwitchedOffAreNotAsked() throws Exception {
        WordTimeFinder finder = new WordTimeFinder(List.of(fixed("One", timed("One", 0))));
        assertTrue(finder.find(TRACK, Lyrics.parseLrc(lrc(0, 0)), Set.of()).taken().isEmpty());
    }

    @Test
    void twoServicesAgreeingBeatDriftingLrclib() throws Exception {
        // LRCLIB drifts by up to 1.8 s over the song; two services timed it alike on their own.
        Lyrics drifting = Lyrics.parseLrc(lrc(0, 120));
        WordTimeFinder finder = new WordTimeFinder(List.of(fixed("One", timed("One", 0)), fixed("Two", timed("Two", 80))));
        WordTimeFinder.Taken taken = finder.find(TRACK, drifting, Set.of("one", "two")).taken().orElseThrow();
        assertEquals("One", taken.source());
        assertEquals("confirmed by Two", taken.how());
    }

    @Test
    void oneServiceRepeatingAnothersDataConfirmsNothing() throws Exception {
        Lyrics drifting = Lyrics.parseLrc(lrc(0, 120));
        WordTimeFinder finder = new WordTimeFinder(List.of(fixed("One", timed("One", 0)), fixed("Copy", timed("Copy", 0))));
        assertTrue(finder.find(TRACK, drifting, Set.of("one", "copy")).taken().isEmpty());
    }

    @Test
    void standsInWithoutLrclib() throws Exception {
        WordTimeFinder finder = new WordTimeFinder(List.of(fixed("One", timed("One", 0))));
        assertEquals("stand-in", finder.find(TRACK, null, Set.of("one")).taken().orElseThrow().how());
    }

    @Test
    void aFailingServiceRestsAndNothingIsKeptAsNone() throws Exception {
        int[] calls = {0};
        WordTimeSource broken = new WordTimeSource() {
            @Override
            public String name() {
                return "Broken";
            }

            @Override
            public Optional<TimedWords> find(NowPlaying track) throws IOException {
                calls[0]++;
                throw new IOException("down");
            }
        };
        WordTimeFinder finder = new WordTimeFinder(List.of(broken, fixed("One", timed("One", 0))));
        Lyrics base = Lyrics.parseLrc(lrc(0, 0));
        assertEquals("One", finder.find(TRACK, base, Set.of("broken", "one")).taken().orElseThrow().source());
        WordTimeFinder.Outcome second = new WordTimeFinder(List.of(broken)).find(TRACK, base, Set.of("broken"));
        assertFalse(second.complete());
        finder.find(TRACK, base, Set.of("broken", "one"));
        // The first finder let it rest after its failure and did not ask it again.
        assertEquals(2, calls[0]);
    }

    @Test
    void nothingFoundHoldsOnlyForTheServicesAsked() {
        LocalDate day = LocalDate.of(2026, 10, 25);
        String none = WordTimeFinder.noneMarker(day, Set.of("deezer", "netease"));
        assertTrue(WordTimeFinder.recentlyNone(none, day.plusDays(3), Set.of("deezer")));
        assertFalse(WordTimeFinder.recentlyNone(none, day.plusDays(3), Set.of("deezer", "kugou")));
        assertFalse(WordTimeFinder.recentlyNone(none, day.plusDays(15), Set.of("deezer")));
    }

    @Test
    void findsLinesThatAServiceSplitsDifferently() {
        // LRCLIB has one long line where the service breaks it at the comma.
        StringBuilder base = new StringBuilder();
        List<TimedWords.Line> lines = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            long t = i * 6000L;
            base.append(String.format(Locale.ROOT, "[%02d:%02d.00]Lantern %d glows, river %d flows%n", t / 60000, t / 1000 % 60, i, i));
            lines.add(line(t + 150, "Lantern", Integer.toString(i), "glows,"));
            lines.add(line(t + 2650, "river", Integer.toString(i), "flows"));
        }
        assertTrue(WordTimeTransfer.transfer(Lyrics.parseLrc(base.toString()), new TimedWords("X", lines)).isPresent());
    }

    /** Sixteen lines, every 6 s, each line {@code driftMsPerLine} later than the last. */
    private static String lrc(long shift, long driftMsPerLine) {
        StringBuilder lrc = new StringBuilder();
        for (int i = 0; i < 16; i++) {
            long t = i * 6000L + shift + i * driftMsPerLine;
            lrc.append(String.format(Locale.ROOT, "[%02d:%02d.%03d]light the lantern number %d%n", t / 60000, t / 1000 % 60, t % 1000, i));
        }
        return lrc.toString();
    }

    /** The same lines with word times, as a service timed them {@code shift} ms later. */
    private static TimedWords timed(String source, long shift) {
        List<TimedWords.Line> lines = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            long t = i * 6000L + shift;
            lines.add(line(t, "light", "the", "lantern", "number", Integer.toString(i)));
        }
        return new TimedWords(source, lines);
    }

    private static TimedWords.Line line(long start, String... words) {
        List<TimedWords.Word> timed = new ArrayList<>();
        for (int w = 0; w < words.length; w++) {
            timed.add(new TimedWords.Word(start + w * 400L, words[w]));
        }
        return new TimedWords.Line(start, timed);
    }

    private static WordTimeSource fixed(String name, TimedWords words) {
        return new WordTimeSource() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public Optional<TimedWords> find(NowPlaying track) {
                return Optional.ofNullable(words);
            }
        };
    }
}
