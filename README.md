# Lyrics in the Sky

Fabric-Mod für Minecraft **26.2** und **26.3**: Die Songtexte des Liedes, das gerade auf deinem PC läuft
(Spotify, Amazon Music, YouTube Music oder andere Player im Browser), erscheinen synchron **Wort für Wort am
Himmel**: in weicher Handschrift, halbtransparent, mit Glow, Funken und wechselnden Animationen. Jede
Zeile ploppt an einer anderen Stelle auf und verblasst danach wieder. Oben im Bild zeigt eine kleine
„Now Playing“-Pille Titel, aktuelle Zeile und das gerade gesungene Wort.

Nicht verbunden mit Mojang, Microsoft, Spotify oder Amazon.

## Voraussetzungen

- Windows 10/11 (Windows-Mediensteuerung) oder Linux mit `gdbus` (MPRIS; auf Arch/CachyOS Teil von `glib2`)
- Minecraft Java Edition 26.2 oder 26.3, Fabric Loader 0.20.1 oder neuer, Fabric API
- Java 25 (bringt der Minecraft-Launcher mit)

## Installation

1. Die passende Datei `lyrics-in-the-sky-1.0.0+mc26.3.jar` oder `…+mc26.2.jar` in den `mods`-Ordner legen.
2. Fabric API dazulegen, falls noch nicht vorhanden.
3. Musik starten, Welt betreten. Beim ersten Mal fragt die Mod im Chat, ob sie lrclib.net nach Songtexten
   fragen darf. **[Erlauben]** klicken.
4. Ausprobieren ohne Musik: `/skylyrics demo`

## Befehle

| Befehl | Wirkung |
|---|---|
| `/skylyrics status` | zeigt Song, Player, Position, Lyrics-Status und Offset |
| `/skylyrics demo` | 37 Sekunden Demo mit eigenem Text |
| `/skylyrics on` / `off` | Lyrics ein- oder ausschalten |
| `/skylyrics offset <ms>` | Verzögerung ausgleichen; positiv = Lyrics früher |
| `/skylyrics opacity <0.1–1>` | Deckkraft (Standard 0.82) |
| `/skylyrics scale <0.3–3>` | Textgröße |
| `/skylyrics height <10–70>` | Höhe über dem Horizont in Grad |
| `/skylyrics palette <auto\|aurora\|sunset\|gold\|sakura\|ocean\|white>` | Farben; `auto` wählt pro Song |
| `/skylyrics font <script\|minecraft>` | Handschrift oder Minecraft-Pixelschrift |
| `/skylyrics follow <on\|off>` | neue Zeilen erscheinen dort, wo du hinschaust |
| `/skylyrics title <on\|off>` | Songtitel am Himmel beim Songwechsel |
| `/skylyrics hud <on\|off>` | „Now Playing“-Pille oben |
| `/skylyrics lrclib <on\|off>` | Abfrage bei lrclib.net erlauben oder verbieten |

## Tasten

Unter *Optionen → Steuerung → Tastenbelegung → Lyrics in the Sky* (standardmäßig nicht belegt):
Lyrics ein/aus, Lyrics 100 ms früher, Lyrics 100 ms später.

## Einstellungen

Alles landet in `config/lyricsinthesky.json` und kann dort auch von Hand geändert werden.

## So funktioniert es

- **Was läuft gerade?** Unter Windows liest ein kleines PowerShell-Skript (in der Mod enthalten, ohne
  Fremdprogramme) die Windows-Mediensitzung (SMTC), unter Linux fragt die Mod MPRIS über `gdbus` ab. Beides
  liefert Titel, Künstler, Album, Länge und Position. Zwischen den Meldungen der Player rechnet die Mod die
  Position selbst weiter, damit die Wörter im Takt bleiben. Meldet ein Player dauerhaft Position 0 (bekannter
  Fehler des Spotify-Linux-Clients), zählt die Mod ab Songbeginn selbst mit.
- **Songtexte** kommen von [LRCLIB](https://lrclib.net), einer freien Datenbank für synchronisierte
  Lyrics. Gesendet werden nur Titel, Künstler, Album und Länge. Nichts wird gespeichert oder weitergegeben.
  Kennt LRCLIB nur Zeilenzeiten, verteilt die Mod die Wörter nach Silbenlänge; erweiterte LRC-Zeiten pro
  Wort werden genau übernommen.
- **Darstellung**: Die Zeilen werden 48 Blöcke entfernt, aber relativ zur Kamera gezeichnet, also wie die
  Sonne: Sie kommen nie näher und stecken nie in Wänden. Bäume, Häuser und Hügel in der Nähe verdecken sie,
  Wolken und Himmel nicht (außer du fliegst selbst bis an die Wolken heran).

## Grenzen

- macOS wird noch nicht unterstützt. Der Linux-Weg ist mit Beispieldaten getestet, aber noch nicht auf
  einem echten Linux-Desktop.
- Spotify meldet seine Position nur alle paar Sekunden; kleine Abweichungen gleicht `/skylyrics offset` aus.
  Unter Linux mit dem Spotify-Client ist die Zeit nach einem Vorspulen ungenau, weil Spotify dort keine
  Position meldet.
- Livestreams und Videos ohne Länge bekommen keine Lyrics.
- Mit Shaderpacks (Iris) ist die Darstellung ungetestet.
- Kein Zugriff auf Spotify-Konten, keine Cookies, keine inoffiziellen Spotify- oder Musixmatch-Schnittstellen.

## Selbst bauen

```bash
gradlew.bat collectJars
```

Die Jars für beide Versionen liegen danach in `build/libs`. Der automatische Test im Spiel:

```bash
gradlew.bat :mc26.3:runSelfTest
```

Er lädt `versions/26.3/run/saves/SkyTest`, spielt die Demo ab, macht Screenshots nach
`versions/26.3/run/screenshots` und beendet das Spiel.

## Projektaufbau

- `src/main` – gemeinsamer Code (Mediensitzung, LRCLIB, Animationen, HUD, Befehle)
- `versions/26.3`, `versions/26.2` – je ein kleiner Grafik-Adapter und das Mixin, weil Mojang die
  Render-Schnittstellen zwischen 26.2 und 26.3 umgebaut hat
- `reports/` und `research_notes/` – die Vorab-Recherche zu bestehenden Mods, APIs und Rechtslage

## Lizenz

Code: MIT. Schrift „Kalam“ © Indian Type Foundry, SIL Open Font License 1.1 (`assets/lyricsinthesky/font/ofl.txt`).
