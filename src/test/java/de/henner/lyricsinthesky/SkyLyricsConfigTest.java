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
    void unknownValuesFallBackToTheDefaults(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("lyricsinthesky.json");
        Files.writeString(file, "{\"style\": \"neon\", \"palette\": \"rainbow\", \"mode\": \"moon\"}", StandardCharsets.UTF_8);
        SkyLyricsConfig config = SkyLyricsConfig.load(file);
        assertEquals("elegant", config.style);
        assertEquals("standard", config.palette);
        assertEquals("sky", config.mode);
    }
}
