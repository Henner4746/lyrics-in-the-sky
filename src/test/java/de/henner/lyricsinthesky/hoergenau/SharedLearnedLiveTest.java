package de.henner.lyricsinthesky.hoergenau;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.henner.lyricsinthesky.lyrics.LrclibClient;
import de.henner.lyricsinthesky.lyrics.Lyrics;
import de.henner.lyricsinthesky.nowplaying.NowPlaying;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * The exchange through the real tracker on henrik.click, with the mod's own code. {@code SHARED_LIVE} says which step:
 * <ul>
 *   <li>{@code aligned}: a song a player's Hoergenau learned on lrclib.net's text is asked for, and its word times are
 *   put on the text this PC gets from lrclib.net, every word with its own time;</li>
 *   <li>{@code heard-share}: a made-up song lrclib.net has no text of is shared as one player heard it; it must not
 *   count yet. What a second player heard is written to {@code SHARED_LIVE_OUT}, to be sent from another network;</li>
 *   <li>{@code heard-fetch}: after that, the song comes back word by word, as the first player heard it.</li>
 * </ul>
 */
@EnabledIfEnvironmentVariable(named = "SHARED_LIVE", matches = ".+")
class SharedLearnedLiveTest {
    private static final String VERSION = "1.3.0-live-test";
    /** Made up, so lrclib.net has no text of it; deleted from the tracker after the test. */
    private static final NowPlaying TEST_SONG = new NowPlaying("test", "Zwei Netze Probelauf", "Lyrics in the Sky Test",
            "Zwei Netze Probelauf", true, 0, 123_000, 0);
    private static final String FIRST_PLAYER = "7e570000-0000-4000-8000-000000000001";
    private static final String SECOND_PLAYER = "7e570000-0000-4000-8000-000000000002";

    @Test
    void sharedLearning() throws Exception {
        switch (System.getenv("SHARED_LIVE")) {
            case "aligned" -> aligned();
            case "heard-share" -> heardShare();
            case "heard-fetch" -> heardFetch();
            default -> throw new IllegalArgumentException("SHARED_LIVE: aligned, heard-share or heard-fetch");
        }
    }

    private static void aligned() throws Exception {
        NowPlaying track = new NowPlaying("test", "Staubsaugerliebe", "Peter Petersen", "Staubsaugerliebe", true, 0,
                210_841, 0);
        String plain = new LrclibClient(VERSION).lookup(track).plain().orElseThrow(
                () -> new AssertionError("lrclib.net has no text without times for the test song"));
        Lyrics text = Lyrics.estimated(plain, track.durationMs());
        String lrc = new SharedLearned(() -> true, FIRST_PLAYER, VERSION)
                .fetch(key(track), text).orElseThrow(() -> new AssertionError("the tracker gave no word times that fit"));
        Lyrics learned = Lyrics.parseLrc(lrc);
        assertTrue(Hoergenau.learned(learned), "learned lyrics");
        assertEquals(text.lines().size(), learned.lines().size(), "every line of lrclib.net's text");
        int words = 0;
        for (int i = 0; i < learned.lines().size(); i++) {
            assertEquals(text.lines().get(i).text(), learned.lines().get(i).text(), "line " + i);
            words += learned.lines().get(i).wordStartsMs().length;
        }
        System.out.printf("  aligned: %d lines, %d words, first line at %d ms, last at %d ms%n",
                learned.lines().size(), words, learned.lines().get(0).startMs(),
                learned.lines().get(learned.lines().size() - 1).startMs());
    }

    private static void heardShare() throws Exception {
        SharedLearned first = new SharedLearned(() -> true, FIRST_PLAYER, VERSION);
        assertTrue(first.shareHeard(key(TEST_SONG), TEST_SONG, heard(false)), "shared");
        // Sent in the background; one player alone does not count.
        Thread.sleep(8_000);
        assertTrue(new SharedLearned(() -> true, FIRST_PLAYER, VERSION).fetch(key(TEST_SONG), null).isEmpty(),
                "one player on one network counts already");
        String body = SharedLearned.heardBody(key(TEST_SONG), TEST_SONG, heard(true), SECOND_PLAYER, VERSION).toString();
        Files.writeString(Path.of(System.getenv("SHARED_LIVE_OUT")), body, StandardCharsets.UTF_8);
        System.out.println("  heard-share: first player sent, not counted yet; second player's body written");
    }

    private static void heardFetch() {
        Optional<String> lrc = new SharedLearned(() -> true, FIRST_PLAYER, VERSION).fetch(key(TEST_SONG), null);
        assertTrue(lrc.isPresent(), "the tracker gave the song after two networks heard it alike");
        // The one with more words heard counts.
        assertEquals(heard(false), lrc.get());
        Lyrics lyrics = Lyrics.parseLrc(lrc.get());
        assertTrue(lyrics.hasWordTiming(), "word by word");
        System.out.printf("  heard-fetch: %d lines word by word from the tracker%n", lyrics.lines().size());
    }

    /** What a player's Hoergenau wrote down; the second one heard a word less and one differently. */
    private static String heard(boolean second) {
        List<List<WhisperEngine.Heard>> segments = new ArrayList<>();
        segments.add(words(12_400, second ? "Wir testen heute zwei Netz" : "Wir testen heute zwei Netze"));
        segments.add(words(16_050, "und hoffen dass der Server teilt"));
        segments.add(words(21_300, second ? "was einer hörte" : "was einer hörte hört der andre"));
        return HeardLyrics.transcript(segments, 0);
    }

    private static List<WhisperEngine.Heard> words(long startMs, String line) {
        List<WhisperEngine.Heard> heard = new ArrayList<>();
        long at = startMs;
        for (String word : line.split(" ")) {
            heard.add(new WhisperEngine.Heard(word, at));
            at += 340 + word.length() * 30L;
        }
        return heard;
    }

    private static String key(NowPlaying track) {
        return track.artist() + "\u0000" + track.title() + "\u0000" + track.album();
    }
}
