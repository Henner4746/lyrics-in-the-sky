package de.henner.lyricsinthesky.hoergenau;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.henner.lyricsinthesky.SkyLyricsConfig;
import de.henner.lyricsinthesky.hoergenau.WhisperEngine.Heard;
import de.henner.lyricsinthesky.lyrics.Lyrics;
import de.henner.lyricsinthesky.lyrics.LyricsCache;
import de.henner.lyricsinthesky.nowplaying.NowPlaying;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Hoergenau's parts that need neither Windows nor whisper.cpp, with made-up lyrics. */
class HoergenauTest {
    private static final List<String> LINES = List.of("Light the lanterns on the river", "Paper boats are drifting slowly",
            "Light the lanterns on the river");

    @Test
    void knownLyricsArePlacedOnWhatWasHeard() {
        // Heard as Whisper writes sung words: one misheard ("lanters"), one missing ("are"), one extra ("oh").
        List<Heard> heard = List.of(new Heard("Light", 1_000), new Heard("the", 1_300), new Heard("lanters", 1_600),
                new Heard("on", 2_100), new Heard("the", 2_300), new Heard("river", 2_500), new Heard("oh", 3_000),
                new Heard("Paper", 5_000), new Heard("boats", 5_400), new Heard("drifting", 6_000),
                new Heard("slowly", 6_600), new Heard("Light", 9_000), new Heard("the", 9_300),
                new Heard("lanterns", 9_600), new Heard("on", 10_100), new Heard("the", 10_300), new Heard("river", 10_500));
        HeardLyrics.Aligned aligned = HeardLyrics.align(LINES, heard, 20_000).orElseThrow();
        assertTrue(aligned.matched() > 0.9);
        Lyrics lyrics = Lyrics.parseLrc(aligned.lrc());
        assertTrue(lyrics.hasWordTiming());
        assertEquals(3, lyrics.lines().size());
        // Times are song positions: the recording started 20 s into the song.
        assertEquals(21_000, lyrics.lines().get(0).startMs());
        assertEquals(21_600, lyrics.lines().get(0).wordStartsMs()[2]);
        // "are" was not heard: it gets a time between "boats" and "drifting".
        long[] second = lyrics.lines().get(1).wordStartsMs();
        assertTrue(second[2] > 25_400 && second[2] < 26_000, "unheard word at " + second[2]);
        assertEquals(29_000, lyrics.lines().get(2).startMs());
        assertTrue(Hoergenau.learned(lyrics));
    }

    @Test
    void aLineMadeUpOverMusicIsDropped() {
        // As heard in the game over an instrumental: all words of the line at one moment; the sung line before stays.
        String json = """
                {"transcription":[
                 {"tokens":[{"text":" Wenn","offsets":{"from":0,"to":0},"t_dtw":14650},
                  {"text":" jedes","offsets":{"from":0,"to":0},"t_dtw":14720},
                  {"text":" Wunder","offsets":{"from":0,"to":0},"t_dtw":14790}]},
                 {"tokens":[{"text":" Die","offsets":{"from":0,"to":0},"t_dtw":17460},
                  {"text":" Kälte","offsets":{"from":0,"to":0},"t_dtw":17460},
                  {"text":" wird","offsets":{"from":0,"to":0},"t_dtw":17460},
                  {"text":" gebraten.","offsets":{"from":0,"to":0},"t_dtw":17461}]}]}""";
        List<List<Heard>> segments = WhisperEngine.parse(json.getBytes(StandardCharsets.UTF_8));
        assertEquals(1, segments.size());
        assertEquals("Wenn", segments.get(0).get(0).text());
    }

    @Test
    void soundsMarkedByWhisperAreNoWords() {
        List<Heard> heard = List.of(new Heard("*Musik*", 0), new Heard("ich", 500), new Heard("mach", 700),
                new Heard("[Musik", 900), new Heard("spielt]", 1_100), new Heard("Musik", 1_300), new Heard("(Lachen)", 1_600));
        assertEquals(List.of(new Heard("ich", 500), new Heard("mach", 700), new Heard("Musik", 1_300)),
                WhisperEngine.withoutTags(heard));
    }

    @Test
    void wordsHeardAsOtherWordsKeepTheirTimes() {
        // "mir wird's" heard as "es ist": the second line starts where that was heard, not halfway after "verdammt".
        List<Heard> heard = List.of(new Heard("dazu", 0), new Heard("bist", 300), new Heard("du", 500),
                new Heard("verdammt", 700), new Heard("Es", 3_000), new Heard("ist", 3_200), new Heard("ja", 3_400),
                new Heard("auch", 3_600), new Heard("zu", 3_800), new Heard("viel", 4_000));
        Lyrics lyrics = Lyrics.parseLrc(HeardLyrics.align(List.of("dazu bist du verdammt", "mir wird's ja auch zuviel"),
                heard, 0).orElseThrow().lrc());
        assertEquals(3_000, lyrics.lines().get(1).startMs());
        assertEquals(3_200, lyrics.lines().get(1).wordStartsMs()[1]);
    }

    @Test
    void aStretchTheWholeSongMissedIsHeardAgainOnItsOwn() {
        // The first line was not heard at all; heard again before "wenn" (plus a second after), it is found.
        List<String> lines = List.of("man o man am ende des tages", "wenn andere ihre kinder betten");
        List<Heard> heard = List.of(new Heard("Wenn", 9_820), new Heard("andere", 10_130), new Heard("ihre", 10_850),
                new Heard("Kinder", 11_730), new Heard("betten", 12_470));
        List<long[]> asked = new ArrayList<>();
        HeardLyrics.Listener again = (from, to) -> {
            asked.add(new long[] {from, to});
            return List.of(new Heard("Man", 3_530), new Heard("o", 3_700), new Heard("Mann", 3_790), new Heard("am", 4_570),
                    new Heard("Ende", 4_770), new Heard("des", 5_030), new Heard("Tages", 5_370), new Heard("Wenn", 9_820));
        };
        Lyrics lyrics = Lyrics.parseLrc(HeardLyrics.align(lines, heard, 0, again).orElseThrow().lrc());
        assertEquals(1, asked.size());
        assertEquals(0, asked.get(0)[0]);
        assertEquals(10_820, asked.get(0)[1]);
        assertEquals(3_530, lyrics.lines().get(0).startMs());
        assertEquals(9_820, lyrics.lines().get(1).startMs());
        // Without listening again the line is only guessed, 350 ms a word before "wenn".
        assertEquals(9_820 - 7 * 350, Lyrics.parseLrc(HeardLyrics.align(lines, heard, 0).orElseThrow().lrc())
                .lines().get(0).startMs());
    }

    @Test
    void lyricsThatAreNotInTheRecordingAreRefused() {
        List<Heard> heard = List.of(new Heard("completely", 1_000), new Heard("different", 1_400),
                new Heard("words", 1_800), new Heard("here", 2_000));
        assertTrue(HeardLyrics.align(LINES, heard, 0).isEmpty());
    }

    @Test
    void withoutLyricsTheHeardWordsBecomeTheLyrics() {
        String lrc = HeardLyrics.transcript(List.of(List.of(new Heard("Hello", 500), new Heard("there", 900)),
                List.of(new Heard("again", 3_000))), 1_000);
        Lyrics lyrics = Lyrics.parseLrc(lrc);
        assertEquals(2, lyrics.lines().size());
        assertEquals(1_500, lyrics.lines().get(0).startMs());
        assertEquals(1_900, lyrics.lines().get(0).wordStartsMs()[1]);
        assertTrue(lrc.startsWith(HeardLyrics.MARK));
    }

    @Test
    void readsWhisperCppTokensWithDtwTimes() {
        String json = """
                {"transcription":[{"tokens":[
                  {"text":"[_BEG_]","offsets":{"from":0,"to":0},"t_dtw":-1},
                  {"text":" Light","offsets":{"from":0,"to":320},"t_dtw":105},
                  {"text":" lan","offsets":{"from":320,"to":640},"t_dtw":150},
                  {"text":"terns","offsets":{"from":640,"to":960},"t_dtw":170},
                  {"text":" now","offsets":{"from":960,"to":1200},"t_dtw":-1}]}]}""";
        List<List<Heard>> segments = WhisperEngine.parse(json.getBytes(StandardCharsets.UTF_8));
        // DTW times come 150 ms early (sung words measured late); without one, Whisper's own estimate stays.
        assertEquals(List.of(new Heard("Light", 900), new Heard("lanterns", 1_350), new Heard("now", 960)),
                segments.get(0));
    }

    @Test
    void aFirstWordPinnedToThePauseBeforeMovesUpToTheNext() {
        // "Es" at the end of the pause, 2.6 s before "ist": it is sung just before "ist" (2 letters ≈ 180 ms).
        String json = """
                {"transcription":[{"tokens":[
                  {"text":" Es","offsets":{"from":0,"to":500},"t_dtw":1265},
                  {"text":" ist","offsets":{"from":500,"to":900},"t_dtw":1525},
                  {"text":" noch","offsets":{"from":900,"to":1300},"t_dtw":1570}]}]}""";
        List<Heard> words = WhisperEngine.parse(json.getBytes(StandardCharsets.UTF_8)).get(0);
        assertEquals(new Heard("Es", 15_100 - 180), words.get(0));
        assertEquals(15_100, words.get(1).startMs());
    }

    @Test
    void joinsALetterSplitOverTwoTokens() {
        // "Größe" as whisper.cpp writes it: "ö" (C3 B6) cut between two tokens, each alone no valid UTF-8.
        String bytes = """
                {"transcription":[{"tokens":[
                  {"text":" GrÃ","offsets":{"from":0,"to":200},"t_dtw":42},
                  {"text":"¶Ã\u009Fe","offsets":{"from":200,"to":400},"t_dtw":50},
                  {"text":" TÃ¼r","offsets":{"from":400,"to":600},"t_dtw":75}]}]}""";
        List<List<Heard>> segments = WhisperEngine.parse(bytes.getBytes(StandardCharsets.ISO_8859_1));
        assertEquals(List.of(new Heard("Größe", 270), new Heard("Tür", 600)), segments.get(0));
    }

    @Test
    void writesA16KhzMonoWav(@TempDir Path dir) throws Exception {
        short[] samples = new short[1600];
        samples[0] = 1234;
        Path wav = dir.resolve("a.wav");
        WhisperEngine.writeWav(wav, samples, 0, samples.length);
        byte[] bytes = Files.readAllBytes(wav);
        assertEquals(44 + 3200, bytes.length);
        assertEquals("RIFF", new String(bytes, 0, 4));
        assertEquals(16_000, (bytes[24] & 0xFF) | (bytes[25] & 0xFF) << 8 | (bytes[26] & 0xFF) << 16);
        assertEquals(1234, (short) ((bytes[44] & 0xFF) | bytes[45] << 8));
    }

    @Test
    void gapsAreFilledInOrder() {
        long[] times = {-1, 1_000, -1, -1, 1_600, -1};
        HeardLyrics.fillGaps(times);
        assertEquals(new ArrayList<>(List.of(650L, 1_000L, 1_200L, 1_400L, 1_600L, 1_950L)),
                new ArrayList<>(java.util.Arrays.stream(times).boxed().toList()));
    }

    @Test
    void linesWhisperMakesUpFromSubtitlesAreDropped() {
        // Heard by "Genau" over a whole quiet recording of a German rap song, at one moment as made up text is.
        String json = """
                {"transcription":[
                 {"tokens":[{"text":" WDR","offsets":{"from":0,"to":0},"t_dtw":200},
                  {"text":" mediagroup","offsets":{"from":0,"to":0},"t_dtw":290},
                  {"text":" GmbH","offsets":{"from":0,"to":0},"t_dtw":400},
                  {"text":" im","offsets":{"from":0,"to":0},"t_dtw":520},
                  {"text":" Auftrag","offsets":{"from":0,"to":0},"t_dtw":610},
                  {"text":" des","offsets":{"from":0,"to":0},"t_dtw":700},
                  {"text":" WDR","offsets":{"from":0,"to":0},"t_dtw":800}]},
                 {"tokens":[{"text":" Wo","offsets":{"from":0,"to":0},"t_dtw":1200},
                  {"text":" war","offsets":{"from":0,"to":0},"t_dtw":1240},
                  {"text":" ich","offsets":{"from":0,"to":0},"t_dtw":1290},
                  {"text":" in","offsets":{"from":0,"to":0},"t_dtw":1330},
                  {"text":" der","offsets":{"from":0,"to":0},"t_dtw":1370},
                  {"text":" Nacht?","offsets":{"from":0,"to":0},"t_dtw":1420}]}]}""";
        List<List<Heard>> segments = WhisperEngine.parse(json.getBytes(StandardCharsets.UTF_8));
        assertEquals(1, segments.size());
        assertEquals("Wo", segments.get(0).get(0).text());
        assertTrue(WhisperEngine.madeUp(heard("Untertitel im Auftrag des ZDF für funk, 2017")));
        assertTrue(WhisperEngine.madeUp(heard("Untertitelung aufgrund der Amara.org-Community")));
        assertTrue(WhisperEngine.madeUp(heard("Vielen Dank fürs Zuschauen!")));
        assertTrue(WhisperEngine.madeUp(heard("Thank you for watching.")));
        assertTrue(!WhisperEngine.madeUp(heard("Ich danke dir fürs Zuhören, mein Freund")));
        assertTrue(!WhisperEngine.madeUp(heard("Der Auftrag kam aus dem Funkhaus")));
    }

    private static List<Heard> heard(String line) {
        List<Heard> words = new ArrayList<>();
        for (String word : line.split(" ")) {
            words.add(new Heard(word, words.size() * 400L));
        }
        return words;
    }

    @Test
    void quietRecordingsAreMadeLouderForWhisper() {
        // Only Spotify's own sound, as recorded on Windows: peaks near 2,400 of 32,767.
        short[] quiet = new short[16_000];
        short[] loud = new short[16_000];
        for (int i = 0; i < quiet.length; i++) {
            double wave = Math.sin(i * 0.05);
            quiet[i] = (short) (2_400 * wave);
            loud[i] = (short) (30_000 * wave);
        }
        double gain = WhisperEngine.gain(quiet, 0, quiet.length);
        assertTrue(gain > 11 && gain < 13, "gain " + gain);
        assertEquals(1, WhisperEngine.gain(loud, 0, loud.length));
        // Silence and a faint hiss are not blown up beyond the limit.
        assertEquals(1, WhisperEngine.gain(new short[16_000], 0, 16_000));
        short[] hiss = new short[16_000];
        java.util.Arrays.fill(hiss, (short) 30);
        assertEquals(WhisperEngine.MAX_GAIN, WhisperEngine.gain(hiss, 0, hiss.length));
    }

    @Test
    void aSongFirstSeenPausedIsCheckedOnceItPlaysAlone(@TempDir Path dir) throws Exception {
        // As found in the game: Spotify paused mid-song when the world opened, then played on.
        CountingRecorder recorder = new CountingRecorder();
        Hoergenau hoergenau = checking(dir, recorder, new LyricsCache(dir.resolve("lyrics")));
        Lyrics timed = Lyrics.parseLrc("[00:12.00]Light the lanterns on the river\n[00:18.00]Paper boats are drifting slowly");
        hoergenau.observe(track(false), "song", Hoergenau.Shown.TIMED, List.of(), null, timed);
        assertEquals(0, recorder.starts);
        hoergenau.observe(track(true), "song", Hoergenau.Shown.TIMED, List.of(), "a video in the browser", timed);
        assertEquals(0, recorder.starts, "another player sounds along");
        hoergenau.observe(track(true), "song", Hoergenau.Shown.TIMED, List.of(), null, timed);
        assertEquals(1, recorder.starts);
        assertTrue(hoergenau.listeningTo("song"));
        // Only the player's own sound is asked for.
        assertEquals("SpotifyAB.SpotifyMusic_zpdnekdrzrea0!Spotify", recorder.player);
    }

    @Test
    void theLoopbackHelperTellsWhatItRecordsAndSince() {
        LoopbackRecorder.Header only = LoopbackRecorder.Header.of("LOOPBACK 16000 48000 2 16 int 3004 only Spotify.exe#5120");
        assertEquals(3004, only.sinceMs());
        assertEquals("only Spotify.exe#5120", only.what());
        LoopbackRecorder.Header all = LoopbackRecorder.Header.of("LOOPBACK 16000 48000 2 32 float 2 all no-process");
        assertEquals(2, all.sinceMs());
        assertEquals("all the PC plays (no-process)", all.what());
        // The helper of older versions: everything, from the header on.
        assertEquals(0, LoopbackRecorder.Header.of("LOOPBACK 16000 48000 2 32 float").sinceMs());
        assertEquals(null, LoopbackRecorder.Header.of("Exception calling Run"));
    }

    @Test
    void aSongCheckedBeforeIsNotHeardAgain(@TempDir Path dir) throws Exception {
        LyricsCache saved = new LyricsCache(dir.resolve("lyrics"));
        saved.write("song\u0000synccheck", "fits 40");
        CountingRecorder recorder = new CountingRecorder();
        Hoergenau hoergenau = checking(dir, recorder, saved);
        Lyrics timed = Lyrics.parseLrc("[00:12.00]Light the lanterns on the river");
        hoergenau.observe(track(true), "song", Hoergenau.Shown.TIMED, List.of(), null, timed);
        hoergenau.observe(track(true), "song", Hoergenau.Shown.TIMED, List.of(), null, timed);
        assertEquals(0, recorder.starts);
    }

    /** Hoergenau switched on, with whisper.cpp and the model in place (as empty files: nothing gets transcribed). */
    private static Hoergenau checking(Path dir, Recorder recorder, LyricsCache saved) throws Exception {
        Path engineDir = dir.resolve("hoergenau");
        Files.createDirectories(engineDir.resolve("bin"));
        Files.createFile(engineDir.resolve("bin").resolve("whisper-cli.exe"));
        Files.createFile(engineDir.resolve(WhisperEngine.Model.SMALL.file()));
        SkyLyricsConfig config = new SkyLyricsConfig();
        config.hoergenau = true;
        Hoergenau hoergenau = new Hoergenau(config, saved, new Hoergenau.Notifier() {
            @Override
            public void toast(String title, String detail) {}

            @Override
            public void chat(String message) {}
        }, key -> {}, new WhisperEngine(engineDir, () -> "small", () -> "normal"), recorder, () -> true);
        hoergenau.fixSyncWith((track, lyrics, offsetMs) -> {});
        hoergenau.recordFrom(() -> "SpotifyAB.SpotifyMusic_zpdnekdrzrea0!Spotify");
        return hoergenau;
    }

    private static NowPlaying track(boolean playing) {
        return new NowPlaying("test", "Lanterns", "The Made Up Band", "", playing, 82_000, 209_000,
                System.currentTimeMillis());
    }

    private static final class CountingRecorder implements Recorder {
        int starts;
        String player;

        @Override
        public void start(String player) {
            starts++;
            this.player = player;
        }

        @Override
        public long startNanos() {
            return 0;
        }

        @Override
        public int count() {
            return 0;
        }

        @Override
        public String failure() {
            return null;
        }

        @Override
        public short[] stop() {
            return new short[0];
        }
    }
}
