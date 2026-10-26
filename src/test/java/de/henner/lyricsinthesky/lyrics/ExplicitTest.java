package de.henner.lyricsinthesky.lyrics;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

class ExplicitTest {
    @Test
    void hidesExplicitWordsAsFirstLetterAndStars() {
        assertEquals("F*** this s***, m***********!", Explicit.ANY.hide("Fuck this shit, motherfucker!"));
        assertEquals("Mein S****** versinkt", Explicit.ANY.hide("Mein Schwanz versinkt"));
        assertEquals("A******** Lokomotive", Explicit.ANY.hide("Arschloch Lokomotive"));
        assertEquals("S******! Du H********", Explicit.ANY.hide("Scheiße! Du Hurensohn"));
        assertEquals("B**** ihn hart", Explicit.ANY.hide("Bumms ihn hart"));
        assertEquals("W****** Im Wald", Explicit.ANY.hide("Wichsen Im Wald"));
    }

    @Test
    void leavesHarmlessWordsThatOnlyLookAlike() {
        String clean = "Der Marsch der Barsche, ein dicker Cocktail, Spaß und Schein, Hurra!";
        assertSame(clean, Explicit.ANY.hide(clean));
        // "dick" is "thick" in German: hidden only in songs sung in English.
        assertEquals("Du bist so dick", Explicit.ANY.hide("Du bist so dick"));
        Lyrics english = Lyrics.parseLrc("[00:01.00]You know what I mean\n[00:03.00]Suck my dick\n");
        assertEquals("Suck my d***", Explicit.forLyrics(english).hide("Suck my dick"));
        Lyrics german = Lyrics.parseLrc("[00:01.00]Ich weiß nicht was du willst\n[00:03.00]Du bist so dick\n");
        assertEquals("Du bist so dick", Explicit.forLyrics(german).hide("Du bist so dick"));
    }

    @Test
    void hiddenLyricsKeepTheirWordsAndTimes() {
        Lyrics lyrics = Lyrics.parseLrc("[00:01.00]<00:01.00>Fuck <00:01.50>you, <00:02.00>Arschloch\n[00:04.00]Alles gut\n");
        Lyrics hidden = lyrics.mapText(Explicit.forLyrics(lyrics)::hide);
        assertEquals("F*** you, A********", hidden.lines().get(0).text());
        assertArrayEquals(lyrics.lines().get(0).wordStartsMs(), hidden.lines().get(0).wordStartsMs());
        assertEquals(lyrics.lines().get(0).words().length, hidden.lines().get(0).words().length);
        assertEquals("Alles gut", hidden.lines().get(1).text());
        assertEquals(lyrics.lrc(), hidden.lrc());
    }
}
