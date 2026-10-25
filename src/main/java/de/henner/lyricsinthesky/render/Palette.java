package de.henner.lyricsinthesky.render;

import java.util.Locale;
import net.minecraft.util.ARGB;

/** Text colors: words flash in an accent color when sung and settle to the text color. */
enum Palette {
    AURORA(0xEAF4FF, 0x7CF7E4, 0xB28CFF, 0x8FD8FF),
    SUNSET(0xFFF4EA, 0xFFB36B, 0xFF6FA8, 0xFF9A7A),
    GOLD(0xFFFFFF, 0xFFE27A, 0xFFB347, 0xFFD27A),
    SAKURA(0xFFF3F8, 0xFF9ED2, 0xFFC4A8, 0xFFB0D8),
    OCEAN(0xF0FBFF, 0x6FD3FF, 0x8C9CFF, 0x7FC8FF),
    WHITE(0xFFFFFF, 0xFFFFFF, 0xDDE8FF, 0xCFE0FF);

    final int text;
    final int accentStart;
    final int accentEnd;
    /** Glow behind the text at night; by day the glow turns into a soft dark halo for contrast. */
    final int nightGlow;

    Palette(int text, int accentStart, int accentEnd, int nightGlow) {
        this.text = text;
        this.accentStart = accentStart;
        this.accentEnd = accentEnd;
        this.nightGlow = nightGlow;
    }

    /** Accent along the line, from start color on the first word to end color on the last. */
    int accent(float along) {
        return ARGB.srgbLerp(Easing.clamp01(along), accentStart, accentEnd);
    }

    static Palette pick(String configured, String trackKey) {
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
