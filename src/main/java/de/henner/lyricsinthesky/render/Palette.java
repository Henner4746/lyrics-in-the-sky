package de.henner.lyricsinthesky.render;

import de.henner.lyricsinthesky.SkyLyricsConfig;
import java.util.Locale;
import java.util.Map;
import net.minecraft.util.ARGB;
import net.minecraft.util.Mth;

/**
 * Text colors: a gradient from the top to the bottom of the letters, plus an accent that freshly sung words
 * flash in. ROSE and LAVENDER are the colors of the two looks the mod is modelled on; "custom" takes the player's own
 * four colors, "rainbow" a new hue for every line.
 */
final class Palette {
    /** Dusty rose turning violet towards the bottom of the letters: the look in the sky. */
    static final Palette ROSE = new Palette(0xCC6A93, 0x8E5A9C, 0xE88FB5, 0xB07ACF, 0xF0A8D0);
    /** White turning lavender towards the bottom: the look around the player. */
    static final Palette LAVENDER = new Palette(0xFFFFFF, 0xB79CF2, 0xD9C8FF, 0xA88BF0, 0xC8B4FF);
    static final Palette AURORA = new Palette(0xEAF4FF, 0xB8D4FF, 0x7CF7E4, 0xB28CFF, 0x8FD8FF);
    static final Palette SUNSET = new Palette(0xFFF4EA, 0xFFC4A0, 0xFFB36B, 0xFF6FA8, 0xFF9A7A);
    static final Palette GOLD = new Palette(0xFFFFFF, 0xFFE0A0, 0xFFE27A, 0xFFB347, 0xFFD27A);
    static final Palette SAKURA = new Palette(0xFFF3F8, 0xFFC0DC, 0xFF9ED2, 0xFFC4A8, 0xFFB0D8);
    static final Palette OCEAN = new Palette(0xF0FBFF, 0xA8D8FF, 0x6FD3FF, 0x8C9CFF, 0x7FC8FF);
    static final Palette WHITE = new Palette(0xFFFFFF, 0xE6ECFF, 0xFFFFFF, 0xDDE8FF, 0xCFE0FF);
    /** Clear white turning icy blue, with a cyan-to-lilac accent: goes with the Liquid Glass theme. */
    static final Palette GLASS = new Palette(0xFFFFFF, 0xD3EAFF, 0x9EE9FF, 0xC7B5FF, 0xBFE6FF);
    /** Bright magenta and cyan, like neon tubes at night. */
    static final Palette NEON = new Palette(0xFFFFFF, 0xFFC2F4, 0x2EF2FF, 0xFF45D8, 0xFF5CE1);
    private static final Map<String, Palette> NAMED = Map.of("rose", ROSE, "lavender", LAVENDER, "aurora", AURORA,
            "sunset", SUNSET, "gold", GOLD, "sakura", SAKURA, "ocean", OCEAN, "white", WHITE, "glass", GLASS, "neon", NEON);
    /** A rainbow line keeps one hue; the next line made this much later is a good step further round the wheel. */
    private static final long RAINBOW_STEP_MS = 2_300;

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

    /** The same colors with one accent for the words being sung, e.g. the bar's own accent color. */
    Palette withAccent(int accent) {
        return new Palette(text, textBottom, accent, accent, nightGlow);
    }

    /**
     * The palette the player chose: "standard" is rose in the sky and lavender around the player, "auto" a different
     * palette per song, "custom" the player's own colors, "rainbow" a new hue for each line.
     */
    static Palette pick(SkyLyricsConfig config, String trackKey, boolean around) {
        return switch (config.palette) {
            case "custom" -> new Palette(config.customTop, config.customBottom, config.customAccent, config.customAccent,
                    config.customGlow);
            case "rainbow" -> rainbow(System.currentTimeMillis() / (double) RAINBOW_STEP_MS * 0.17);
            default -> pick(config.palette, trackKey, around);
        };
    }

    /** One of the fixed palettes by name; "standard" and "auto" as in {@link #pick(SkyLyricsConfig, String, boolean)}. */
    static Palette pick(String configured, String trackKey, boolean around) {
        if ("standard".equals(configured)) {
            return around ? LAVENDER : ROSE;
        }
        Palette named = NAMED.get(configured.toLowerCase(Locale.ROOT));
        if (named != null) {
            return named;
        }
        // Same song, same palette; WHITE is left for explicit choice.
        Palette[] auto = {AURORA, SUNSET, GOLD, SAKURA, OCEAN};
        return auto[Math.floorMod(trackKey.hashCode(), auto.length)];
    }

    /**
     * What makes the palette differ, for the keys of lines and stacks: changing a custom color or the palette makes
     * them come anew in the new colors.
     */
    static String key(SkyLyricsConfig config) {
        return "custom".equals(config.palette)
                ? "custom" + config.customTop + "/" + config.customBottom + "/" + config.customAccent + "/" + config.customGlow
                : config.palette;
    }

    /** Bright letters fading into a deeper shade of the same hue, the accent a neighbouring hue; {@code turns} 0 to 1. */
    static Palette rainbow(double turns) {
        float hue = (float) (turns - Math.floor(turns));
        return new Palette(Mth.hsvToRgb(hue, 0.18f, 1f), Mth.hsvToRgb(hue, 0.5f, 1f),
                Mth.hsvToRgb((hue + 0.06f) % 1f, 0.62f, 1f), Mth.hsvToRgb((hue + 0.14f) % 1f, 0.7f, 1f),
                Mth.hsvToRgb(hue, 0.55f, 1f));
    }
}
