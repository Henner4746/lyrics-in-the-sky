package de.henner.lyricsinthesky.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import de.henner.lyricsinthesky.SkyLyricsConfig;
import org.junit.jupiter.api.Test;

class PaletteTest {
    @Test
    void customColorsComeFromTheConfigAndTheirChangeMakesANewKey() {
        SkyLyricsConfig config = new SkyLyricsConfig();
        config.palette = "custom";
        config.customTop = 0x112233;
        config.customBottom = 0x445566;
        config.customAccent = 0x778899;
        config.customGlow = 0xAABBCC;
        Palette palette = Palette.pick(config, "song", false);
        assertEquals(0x112233, palette.text);
        assertEquals(0x445566, palette.textBottom);
        assertEquals(0x778899, palette.accentStart);
        assertEquals(0x778899, palette.accentEnd);
        assertEquals(0xAABBCC, palette.nightGlow);
        String key = Palette.key(config);
        config.customAccent = 0x000001;
        assertNotEquals(key, Palette.key(config));
    }

    @Test
    void namedPalettesStayAsTheyWere() {
        SkyLyricsConfig config = new SkyLyricsConfig();
        assertSame(Palette.ROSE, Palette.pick(config, "song", false));
        assertSame(Palette.LAVENDER, Palette.pick(config, "song", true));
        config.palette = "neon";
        assertSame(Palette.NEON, Palette.pick(config, "song", false));
        assertEquals("neon", Palette.key(config));
    }

    @Test
    void rainbowTurnsRoundTheColorWheelAndAnOwnAccentReplacesTheAccentOnly() {
        assertNotEquals(Palette.rainbow(0.0).accentStart, Palette.rainbow(0.5).accentStart);
        assertEquals(Palette.rainbow(0.25).text, Palette.rainbow(1.25).text);
        Palette own = Palette.GOLD.withAccent(0x123456);
        assertEquals(Palette.GOLD.text, own.text);
        assertEquals(0x123456, own.accentStart);
        assertEquals(0x123456, own.accent(0.7f));
    }
}
