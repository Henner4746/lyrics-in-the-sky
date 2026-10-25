package de.henner.lyricsinthesky.render;

import java.util.Locale;
import net.minecraft.util.ARGB;

/**
 * Text colors: a gradient from the top to the bottom of the letters, plus an accent that freshly sung words
 * flash in. ROSE and LAVENDER are the colors of the two looks the mod is modelled on.
 */
enum Palette {
    /** Dusty rose turning violet towards the bottom of the letters: the look in the sky. */
    ROSE(0xCC6A93, 0x8E5A9C, 0xE88FB5, 0xB07ACF, 0xF0A8D0),
    /** White turning lavender towards the bottom: the look around the player. */
    LAVENDER(0xFFFFFF, 0xB79CF2, 0xD9C8FF, 0xA88BF0, 0xC8B4FF),
    AURORA(0xEAF4FF, 0xB8D4FF, 0x7CF7E4, 0xB28CFF, 0x8FD8FF),
    SUNSET(0xFFF4EA, 0xFFC4A0, 0xFFB36B, 0xFF6FA8, 0xFF9A7A),
    GOLD(0xFFFFFF, 0xFFE0A0, 0xFFE27A, 0xFFB347, 0xFFD27A),
    SAKURA(0xFFF3F8, 0xFFC0DC, 0xFF9ED2, 0xFFC4A8, 0xFFB0D8),
    OCEAN(0xF0FBFF, 0xA8D8FF, 0x6FD3FF, 0x8C9CFF, 0x7FC8FF),
    WHITE(0xFFFFFF, 0xE6ECFF, 0xFFFFFF, 0xDDE8FF, 0xCFE0FF),
    /** Clear white turning icy blue, with a cyan-to-lilac accent: goes with the Liquid Glass theme. */
    GLASS(0xFFFFFF, 0xD3EAFF, 0x9EE9FF, 0xC7B5FF, 0xBFE6FF),
    /** Bright magenta and cyan, like neon tubes at night. */
    NEON(0xFFFFFF, 0xFFC2F4, 0x2EF2FF, 0xFF45D8, 0xFF5CE1);

    /** Color at the top and at the bottom of every letter. */
    final int text;
    final int textBottom;
    final int accentStart;
    final int accentEnd;
    /** Glow behind the text at night; by day the glow turns into a soft dark halo for contrast. */
    final int nightGlow;

    Palette(int text, int textBottom, int accentStart, int accentEnd, int nightGlow) {
        this.text = text;
        this.textBottom = textBottom;
        this.accentStart = accentStart;
        this.accentEnd = accentEnd;
        this.nightGlow = nightGlow;
    }

    /** Accent along the line, from start color on the first word to end color on the last. */
    int accent(float along) {
        return ARGB.srgbLerp(Easing.clamp01(along), accentStart, accentEnd);
    }

    /**
     * @param configured one of the config's palettes: "standard" is rose in the sky and lavender around the
     *                   player, "auto" a different palette per song
     */
    static Palette pick(String configured, String trackKey, boolean around) {
        if ("standard".equals(configured)) {
            return around ? LAVENDER : ROSE;
        }
        if (!"auto".equals(configured)) {
            try {
                return valueOf(configured.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                // fall through to auto
            }
        }
        // Same song, same palette; WHITE is left for explicit choice.
        Palette[] auto = {AURORA, SUNSET, GOLD, SAKURA, OCEAN};
        return auto[Math.floorMod(trackKey.hashCode(), auto.length)];
    }
}
