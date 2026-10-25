package de.henner.lyricsinthesky;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SkyLyricsConfigTest {
    @Test
    void settingsFromTestBuildsOfTheElegantStyleCarryOver(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("lyricsinthesky.json");
        Files.writeString(file, "{\"style\": \"video\", \"palette\": \"video\", \"font\": \"sans\"}", StandardCharsets.UTF_8);
        SkyLyricsConfig config = SkyLyricsConfig.load(file);
        assertEquals("elegant", config.style);
        assertEquals("standard", config.palette);
        assertEquals("sans", config.font);
    }

    @Test
    void lyricsPlusSwitchedOnBeforeCarriesOverAndUnknownServicesAreDropped(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("lyricsinthesky.json");
        Files.writeString(file, "{\"lyricsPlus\": true, \"wordSources\": [\"kugou\", \"spotify\"]}", StandardCharsets.UTF_8);
        SkyLyricsConfig config = SkyLyricsConfig.load(file);
        // Kept in the order the services are asked.
        assertEquals(java.util.List.of("lyricsplus", "kugou"), config.wordSources);
        assertEquals(false, config.lyricsPlus);
        config.setWordTimes(true);
        assertEquals(SkyLyricsConfig.WORD_SOURCES, config.wordSources);
        config.setWordSource("deezer", false);
        assertEquals(false, config.wordSource("deezer"));
        config.setWordTimes(false);
        assertEquals(false, config.wordTimes());
    }

    @Test
    void unknownValuesFallBackToTheDefaults(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("lyricsinthesky.json");
        Files.writeString(file, "{\"style\": \"neon\", \"palette\": \"rainbow\", \"mode\": \"moon\"}", StandardCharsets.UTF_8);
        SkyLyricsConfig config = SkyLyricsConfig.load(file);
        assertEquals("elegant", config.style);
        assertEquals("standard", config.palette);
        assertEquals("sky", config.mode);
    }

    @Test
    void presetsSetTheLookAndAreRecognised() {
        SkyLyricsConfig config = new SkyLyricsConfig();
        assertEquals("standard", config.currentPreset());
        config.offsetMs = 300;
        config.lrclibAllowed = false;
        config.enabled = false;
        config.applyPreset("party");
        assertEquals("around", config.mode);
        assertEquals("classic", config.style);
        assertEquals(true, config.beatBounce);
        assertEquals(true, config.followAlong);
        assertEquals("party", config.currentPreset());
        // Timing, lookups and on/off are personal and stay as they were.
        assertEquals(300, config.offsetMs);
        assertEquals(Boolean.FALSE, config.lrclibAllowed);
        assertEquals(false, config.enabled);
        config.scale = 2f;
        assertEquals(null, config.currentPreset());
        config.applyPreset("sky");
        assertEquals("natural", config.style);
        assertEquals("sky", config.mode);
        assertEquals("sky", config.currentPreset());
    }

    @Test
    void aConfigWithOnlyAFewSettingsKeepsTheDefaultsForTheRest(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("lyricsinthesky.json");
        Files.writeString(file, "{\"style\": \"natural\", \"lrclibAllowed\": true}", StandardCharsets.UTF_8);
        SkyLyricsConfig config = SkyLyricsConfig.load(file);
        assertEquals("natural", config.style);
        assertEquals(Boolean.TRUE, config.lrclibAllowed);
        assertEquals("standard", config.palette);
        assertEquals(SkyLyricsConfig.DEFAULT_ELEVATION, config.elevationDeg, 1e-6);
        assertEquals(true, config.skyBehindTerrain);
        assertEquals(false, config.beatBounce);
    }
}
