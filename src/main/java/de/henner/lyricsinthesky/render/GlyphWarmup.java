package de.henner.lyricsinthesky.render;

import de.henner.lyricsinthesky.lyrics.Lyrics;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.Style;

/**
 * Draws letters into the textures of the mod's own fonts ahead of time, two per tick. The game draws a letter of a
 * TrueType font into its texture the first time the letter is measured or shown, and the mod's fonts are drawn large
 * for sharp text in the sky: on a slow PC the first line of a song stalled its frame by up to 55 ms while its letters
 * were drawn. So the letters of lyrics are drawn as soon as the lyrics are there, usually seconds before their first
 * line is sung, and the usual letters after joining a world. Runs on the client thread, which owns the font textures.
 */
public final class GlyphWarmup {
    /** Letters of most lyrics in Latin script, drawn after joining a world. */
    static final String USUAL = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789.,!?'\"’‘“”„-–—:;()&…"
            + "*äöüßÄÖÜàáâãåæçèéêëìíîïñòóôõøùúûýÿœÀÁÂÃÅÆÇÈÉÊËÌÍÎÏÑÒÓÔÕØÙÚÛÝŒ¿¡«»·";
    /** One letter in one font takes up to about 2 ms on a slow PC; two a tick stay well inside a frame. */
    static final int PER_TICK = 2;

    private static final Queue<Work> waiting = new ArrayDeque<>();
    private static final Set<Work> known = new HashSet<>();
    private static Lyrics warmedFor;

    private record Work(FontDescription font, int codepoint) {}

    private GlyphWarmup() {}

    /**
     * After joining a world: the usual letters in the fonts {@code font} ("sans", "script") draws with. Starts over, as
     * reloaded resources start the font textures over; letters already drawn are only looked up again, which is quick.
     */
    public static void usualLetters(String font) {
        waiting.clear();
        known.clear();
        warmedFor = null;
        add(USUAL, font);
    }

    /**
     * The letters of {@code lyrics}, ahead of the usual ones; nothing when they were asked for already. Stars streamer
     * mode shows instead of letters are among the usual ones.
     */
    public static void lyrics(Lyrics lyrics, String font) {
        if (lyrics == null || lyrics == warmedFor) {
            return;
        }
        warmedFor = lyrics;
        StringBuilder text = new StringBuilder();
        lyrics.lines().forEach(line -> text.append(line.text()));
        Queue<Work> later = new ArrayDeque<>(waiting);
        waiting.clear();
        add(text.toString(), font);
        waiting.addAll(later);
    }

    public static void tick(Minecraft minecraft) {
        for (int i = 0; i < PER_TICK && !waiting.isEmpty(); i++) {
            Work work = waiting.poll();
            // Measuring is enough: the game draws the letter into the texture to know its width.
            minecraft.font.width(FormattedText.of(Character.toString(work.codepoint()), Style.EMPTY.withFont(work.font())));
        }
    }

    private static void add(String text, String font) {
        List<FontDescription> fonts = SkyLyricsRenderer.fontsOf(font);
        text.codePoints().filter(codepoint -> !Character.isWhitespace(codepoint)).forEach(codepoint -> {
            for (FontDescription description : fonts) {
                Work work = new Work(description, codepoint);
                if (known.add(work)) {
                    waiting.add(work);
                }
            }
        });
    }
}
