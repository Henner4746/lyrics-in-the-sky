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
    void lookValuesAreKeptInRange(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("lyricsinthesky.json");
        Files.writeString(file, "{\"theme\": \"chrome\", \"hudScale\": 9, \"hudOpacity\": -1, \"hudRoundness\": 0.4, "
                + "\"glow\": 5}", StandardCharsets.UTF_8);
        SkyLyricsConfig config = SkyLyricsConfig.load(file);
        assertEquals("glass", config.theme);
        assertEquals(1.5f, config.hudScale);
        assertEquals(0f, config.hudOpacity);
        assertEquals(0.4f, config.hudRoundness);
        assertEquals(2f, config.glow);
    }

    @Test
    void unknownValuesFallBackToTheDefaults(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("lyricsinthesky.json");
        Files.writeString(file, "{\"style\": \"neon\", \"palette\": \"plaid\", \"mode\": \"moon\", \"pauseMode\": \"sleep\", "
                + "\"hoergenauModel\": \"huge\", \"hoergenauPower\": \"max\", \"menuStyle\": \"wood\", \"hudHighlight\": \"glow\", "
                + "\"hudFont\": \"comic\"}", StandardCharsets.UTF_8);
        SkyLyricsConfig config = SkyLyricsConfig.load(file);
        assertEquals("elegant", config.style);
        assertEquals("standard", config.palette);
        assertEquals("sky", config.mode);
        assertEquals("dim", config.pauseMode);
        assertEquals("small", config.hoergenauModel);
        assertEquals("normal", config.hoergenauPower);
        assertEquals("minimal", config.menuStyle);
        assertEquals("box", config.hudHighlight);
        assertEquals("auto", config.hudFont);
    }

    @Test
    void newLookOptionsAreKeptInRange(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("lyricsinthesky.json");
        Files.writeString(file, "{\"palette\": \"rainbow\", \"outlineWidth\": 7, \"shadow\": -2, \"animationSpeed\": 0.1, "
                + "\"beatStrength\": 9, \"sideOffsetDeg\": -400, \"customTop\": -1, \"menuAccent\": -15584170}",
                StandardCharsets.UTF_8);
        SkyLyricsConfig config = SkyLyricsConfig.load(file);
        assertEquals("rainbow", config.palette);
        assertEquals(2f, config.outlineWidth);
        assertEquals(0f, config.shadow);
        assertEquals(0.5f, config.animationSpeed);
        assertEquals(2f, config.beatStrength);
        assertEquals(-60f, config.sideOffsetDeg);
        // Colors are plain RGB: whatever alpha came along is dropped.
        assertEquals(0xFFFFFF, config.customTop);
        assertEquals(0x123456, config.menuAccent);
    }

    @Test
    void menusKeptByDefaultBecomeTheOldLookWithEveryOptionButAChoiceAfterwardsStays(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("lyricsinthesky.json");
        // The lively menu (default up to dev.10) and the glass one (dev.11, configVersion 2) were kept by default.
        Files.writeString(file, "{\"menuStyle\": \"fancy\"}", StandardCharsets.UTF_8);
        assertEquals("minimal", SkyLyricsConfig.load(file).menuStyle);
        Files.writeString(file, "{\"menuStyle\": \"glass\", \"configVersion\": 2}", StandardCharsets.UTF_8);
        SkyLyricsConfig config = SkyLyricsConfig.load(file);
        assertEquals("minimal", config.menuStyle);
        // Chosen afterwards, it stays.
        config.menuStyle = "glass";
        config.save();
        assertEquals("glass", SkyLyricsConfig.load(file).menuStyle);
        config.menuStyle = "fancy";
        config.save();
        assertEquals("fancy", SkyLyricsConfig.load(file).menuStyle);
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
    void everyPresetIsALookOfItsOwnAndHasANameAndALine() {
        for (String preset : SkyLyricsConfig.PRESETS) {
            SkyLyricsConfig config = new SkyLyricsConfig();
            config.outline = true;
            config.screenScale = 1.9f;
            config.applyPreset(preset);
            assertEquals(preset, config.currentPreset(), preset);
            assertEquals(true, SkyLyricsConfig.PRESET_NAMES.containsKey(preset), preset);
            assertEquals(false, SkyLyricsConfig.presetHint(preset).startsWith("Eine Vorlage"), preset);
        }
        SkyLyricsConfig config = new SkyLyricsConfig();
        config.applyPreset("karaoke");
        assertEquals("screen", config.mode);
        assertEquals("karaoke", config.screenStyle);
        config.applyPreset("standard");
        // A preset puts back what the one before changed.
        assertEquals("sky", config.mode);
        assertEquals(false, config.outline);
        assertEquals("subtitle", config.screenStyle);
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
