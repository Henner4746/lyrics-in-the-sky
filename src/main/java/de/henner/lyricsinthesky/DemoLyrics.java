package de.henner.lyricsinthesky;

import de.henner.lyricsinthesky.lyrics.Lyrics;

/** Our own demo text, so placement and animations can be checked without any song playing. */
final class DemoLyrics {
    static final long DURATION_MS = 37_000;

    static final Lyrics LYRICS = Lyrics.parseLrc("""
            [00:03.50]Lyrics in the Sky
            [00:07.00]Jede Zeile taucht woanders auf
            [00:11.20]Wort für Wort im Takt der Musik
            [00:15.40]hoch über den Bergen und Bäumen
            [00:19.60]dreh dich um, die nächste Zeile findet dich
            [00:24.20]<00:24.20>und <00:24.70>wenn <00:25.10>der <00:25.50>Song <00:26.00>endet
            [00:27.80]verblassen sie wie Sterne am Morgen
            [00:32.00]Viel Spaß beim Zuhören!
            """);

    private DemoLyrics() {}
}
