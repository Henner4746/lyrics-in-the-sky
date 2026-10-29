package de.henner.lyricsinthesky.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.henner.lyricsinthesky.SkyLyricsConfig;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SkyArtTest {
    private static final List<String> SKIES = SkyLyricsConfig.SKIES.subList(1, SkyLyricsConfig.SKIES.size());

    @Test
    void everySkyLooksDifferentAndIsOpaque() {
        Set<Integer> looks = new HashSet<>();
        for (String sky : SKIES) {
            SkyNoise noise = new SkyNoise(sky.hashCode());
            long r = 0;
            long g = 0;
            long b = 0;
            for (int i = 0; i < 64; i++) {
                double az = i * 0.7;
                double el = Math.toRadians(-5 + (i % 8) * 10);
                int color = SkyArt.skyColor(sky, noise, (float) (-Math.sin(az) * Math.cos(el)), (float) Math.sin(el),
                        (float) (Math.cos(az) * Math.cos(el)));
                assertEquals(0xFF, color >>> 24, sky + " is see-through");
                r += (color >> 16) & 0xFF;
                g += (color >> 8) & 0xFF;
                b += color & 0xFF;
            }
            // Average color: no two skies the same.
            looks.add((int) (r / 64) << 16 | (int) (g / 64) << 8 | (int) (b / 64));
        }
        assertEquals(SKIES.size(), looks.size());
    }

    @Test
    void thePanoramaHasNoSeamWhereItGoesRound() {
        SkyNoise noise = new SkyNoise(7);
        for (int y = 0; y < 8; y++) {
            float el = (float) Math.toRadians(-10 + y * 12);
            float cos = (float) Math.cos(el);
            int left = SkyArt.skyColor("nebula", noise, 0f, (float) Math.sin(el), cos);
            int right = SkyArt.skyColor("nebula", noise, (float) -Math.sin(Math.PI * 2) * cos, (float) Math.sin(el),
                    (float) Math.cos(Math.PI * 2) * cos);
            assertTrue(Math.abs(((left >> 8) & 0xFF) - ((right >> 8) & 0xFF)) <= 1, "seam at elevation " + y);
        }
    }

    @Test
    void roundShapesFadeOutBeforeTheirEdges() {
        for (SkyArt.Sprite sprite : List.of(SkyArt.Sprite.DOT, SkyArt.Sprite.SPIKE, SkyArt.Sprite.MOON, SkyArt.Sprite.PLANET,
                SkyArt.Sprite.CORE, SkyArt.Sprite.VINYL, SkyArt.Sprite.LABEL, SkyArt.Sprite.PHOTON_RING, SkyArt.Sprite.RING,
                SkyArt.Sprite.DISK, SkyArt.Sprite.RETRO_SUN, SkyArt.Sprite.CLOUD)) {
            int[] pixels = SkyArt.sprite(sprite);
            assertEquals(sprite.w * sprite.h, pixels.length);
            int[] corners = {0, sprite.w - 1, (sprite.h - 1) * sprite.w, sprite.h * sprite.w - 1};
            for (int corner : corners) {
                assertEquals(0, pixels[corner] >>> 24, sprite + " has a hard corner");
            }
            int center = pixels[(sprite.h / 2) * sprite.w + sprite.w / 2] >>> 24;
            if (sprite != SkyArt.Sprite.RING && sprite != SkyArt.Sprite.DISK && sprite != SkyArt.Sprite.PHOTON_RING
                    && sprite != SkyArt.Sprite.VINYL && sprite != SkyArt.Sprite.LABEL && sprite != SkyArt.Sprite.CLOUD
                    && sprite != SkyArt.Sprite.RETRO_SUN) {
                assertTrue(center > 200, sprite + " is faint in its middle: " + center);
            }
        }
    }

    @Test
    void everySkyHasAPostcardForTheMenu() {
        Map<SkyArt.Sprite, int[]> sprites = new EnumMap<>(SkyArt.Sprite.class);
        for (SkyArt.Sprite sprite : SkyArt.Sprite.values()) {
            sprites.put(sprite, SkyArt.sprite(sprite));
        }
        for (String sky : SKIES) {
            int[] card = SkyArt.postcard(sky, sprites);
            assertEquals(SkyArt.POSTCARD_W * SkyArt.POSTCARD_H, card.length);
            for (int pixel : card) {
                assertEquals(0xFF, pixel >>> 24, sky + "'s postcard is see-through");
            }
        }
    }

    @Test
    void namesAndHintsForEverySky() {
        for (String sky : SkyLyricsConfig.SKIES) {
            assertTrue(SkyLyricsConfig.SKY_NAMES.containsKey(sky), sky);
            assertTrue(!SkyLyricsConfig.skyHint(sky).isBlank(), sky);
        }
        assertTrue(SkyLyricsConfig.SKIES.containsAll(SkyLyricsConfig.DAY_SKIES));
    }
}
