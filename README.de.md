<div align="center">

<img src="src/main/resources/assets/lyricsinthesky/icon.png" width="112" alt="Lyrics in the Sky Icon">

# Lyrics in the Sky

**Der Song, den du gerade hörst, Wort für Wort am Minecraft-Himmel.**

![Minecraft](https://img.shields.io/badge/Minecraft-26.2%20%7C%2026.3-5b8731)
![Fabric](https://img.shields.io/badge/Fabric-Loader%200.20.1%2B-dbb98a)
![Java](https://img.shields.io/badge/Java-25-e76f00)
![Systeme](https://img.shields.io/badge/Windows%20%7C%20Linux-unterst%C3%BCtzt-4c6ef5)
![Lizenz](https://img.shields.io/badge/Lizenz-MIT-blue)

[English version](README.md)

<img src="preview/preview.gif" width="720" alt="Songtext erscheint Wort für Wort am Minecraft-Himmel in der Dämmerung">

</div>

Lass auf deinem PC Musik laufen, egal ob Spotify, Amazon Music, YouTube Music oder ein anderer Player. Die
synchronisierten Songtexte erscheinen dann beim Spielen am Himmel: Jedes Wort ploppt im Takt in weicher
Handschrift auf, jede Zeile an einer anderen Stelle, und wenn sie vorbei ist, verweht sie wie eine Wolke.

## Funktionen

- **Wort für Wort synchron.** Genaue Wortzeiten, wenn die Lyrics sie haben; sonst verteilt die Mod die
  Wörter nach Länge über die Zeile.
- **Wirklich am Himmel.** Zeilen verhalten sich wie die Sonne: Sie wandern mit, stecken nie in Wänden,
  verschwinden hinter nahen Bäumen und Häusern und stehen vor den Wolken.
- **Viel Bewegung.** Sechs Einblendungen (aufploppen, hochschweben, Zoom, Wirbel, Fallen, Schreibmaschine),
  vier Ausblendungen (verschwimmen, aufsteigen, zerstreuen, zersplittern), Funken, weicher Glow bei Nacht
  und Schatten am Tag.
- **Jede Zeile woanders,** verteilt um deine Blickrichtung; lange Zeilen brechen versetzt auf zwei Reihen
  um, ein betontes Wort wird größer.
- **Titelkarte** am Himmel, wenn ein neuer Song beginnt.
- **Now-Playing-Pille** oben mit aktueller Zeile, markiertem Wort und kleinem Equalizer.
- **Sechs Farbpaletten,** Handschrift oder Minecraft-Schrift, einstellbare Größe, Höhe, Deckkraft und
  Verzögerung.

## Voraussetzungen

| | |
|---|---|
| Minecraft | Java Edition 26.2 oder 26.3 |
| Mod-Loader | [Fabric Loader](https://fabricmc.net) 0.20.1+ und [Fabric API](https://modrinth.com/mod/fabric-api) |
| System | Windows 10/11 oder Linux mit `gdbus` (Teil von GLib, auf praktisch jedem Desktop installiert) |
| Java | 25 (bringt der Minecraft-Launcher mit) |

## Installation

1. `lyrics-in-the-sky-1.0.0+mc26.3.jar` (oder `…+mc26.2.jar`) und Fabric API in den `mods`-Ordner legen.
2. Spiel starten, Musik starten, Welt betreten.
3. Beim ersten Mal fragt die Mod im Chat, ob sie lrclib.net nach Songtexten fragen darf. **[Erlauben]**
   klicken.
4. Ohne Musik ausprobieren: `/skylyrics demo`

## Befehle

| Befehl | Wirkung |
|---|---|
| `/skylyrics status` | Song, Player, Position, Lyrics-Status und Offset |
| `/skylyrics demo` | 37 Sekunden Demo mit eigenem Text |
| `/skylyrics on` · `off` | Lyrics ein- oder ausschalten |
| `/skylyrics offset <ms>` | Verzögerung ausgleichen; positiv = Lyrics früher |
| `/skylyrics opacity <0.1–1>` | Deckkraft (Standard 0.82) |
| `/skylyrics scale <0.3–3>` | Textgröße |
| `/skylyrics height <10–70>` | Höhe über dem Horizont in Grad |
| `/skylyrics palette <name>` | `auto`, `aurora`, `sunset`, `gold`, `sakura`, `ocean`, `white` |
| `/skylyrics font <script\|minecraft>` | Handschrift oder Minecraft-Schrift |
| `/skylyrics follow <on\|off>` | neue Zeilen erscheinen dort, wo du hinschaust |
| `/skylyrics title <on\|off>` | Songtitel am Himmel beim Songwechsel |
| `/skylyrics hud <on\|off>` | Now-Playing-Pille oben |
| `/skylyrics lrclib <on\|off>` | Abfrage bei lrclib.net erlauben oder verbieten |

**Tasten** (standardmäßig unbelegt, unter *Steuerung → Lyrics in the Sky*): Lyrics ein/aus, Lyrics 100 ms
früher, Lyrics 100 ms später. Alle Einstellungen stehen auch in `config/lyricsinthesky.json`.

## So funktioniert es

```mermaid
flowchart LR
    A["Musik-Player<br/>Spotify · Amazon Music · Browser"] --> B["Mediensitzung des Systems<br/>Windows SMTC · Linux MPRIS"]
    B --> C["Lyrics in the Sky<br/>Titel, Künstler, Position"]
    C <-->|"Titel, Künstler, Album, Länge"| D[("LRCLIB<br/>synchrone Lyrics")]
    C --> E["Himmels-Renderer<br/>Wortzeiten, Animationen"]
    C --> F["Now-Playing-Pille"]
```

- **Was läuft gerade?** Unter Windows liest ein kleines PowerShell-Skript aus der Mod die
  Windows-Mediensitzung, unter Linux fragt die Mod MPRIS über `gdbus` ab. Zwischen den Meldungen des Players
  rechnet die Mod die Position selbst weiter, damit die Wörter im Takt bleiben.
- **Songtexte** kommen von [LRCLIB](https://lrclib.net), einer freien Datenbank für synchrone Lyrics.
  Gesendet werden nur Titel, Künstler, Album und Länge; gespeichert wird nichts.
- **Darstellung:** Der Text wird 48 Blöcke entfernt, aber relativ zur Kamera gezeichnet, nach der Welt und
  vor der Hand, mit eigenen transparenten Text-Pipelines.

## Datenschutz und faire Nutzung

- Kein Spotify- oder Amazon-Login, keine Cookies, keine inoffiziellen oder internen Schnittstellen.
- Abfragen erst nach deiner Erlaubnis; abschalten mit `/skylyrics lrclib off`.
- Lyrics werden beim Abspielen geholt und nur im Speicher gehalten. Das Projekt liefert oder hostet keine
  Songtexte.
- Nicht verbunden mit Mojang, Microsoft, Spotify, Amazon oder LRCLIB.

## Bekannte Grenzen

- macOS wird noch nicht unterstützt. Der Linux-Weg ist mit Beispieldaten getestet, aber noch nicht auf
  einem echten Desktop.
- Spotify meldet seine Position nur alle paar Sekunden; `/skylyrics offset` gleicht kleine Verzögerungen
  aus. Der Spotify-Client unter Linux meldet gar keine Position, nach dem Vorspulen ist die Zeit deshalb bis
  zum nächsten Song ungenau.
- Livestreams und Videos ohne bekannte Länge bekommen keine Lyrics.
- Mit Shaderpacks (Iris) ungetestet.

## Selbst bauen

```bash
./gradlew collectJars             # Jars für beide Versionen in build/libs
./gradlew :mc26.3:runSelfTest     # Demo im Dev-Client abspielen, Screenshots speichern, beenden
./gradlew :mc26.3:runRecordPreview  # dasselbe in 1280×720, ein Bild pro Tick für ein Vorschauvideo
```

| Pfad | Inhalt |
|---|---|
| `src/` | gemeinsamer Code: Mediensitzungen, LRCLIB, Wortzeiten, Animationen, HUD, Befehle |
| `versions/26.2`, `versions/26.3` | Grafik-Anbindung pro Minecraft-Version (Mojang hat die Render-API in 26.3 umgebaut) |
| `reports/`, `research_notes/` | Recherche zu bestehenden Mods, APIs und Rechtsfragen |
| `preview/` | Vorschauvideo und Screenshots |

Regeln für Beiträge: [CONTRIBUTING.md](CONTRIBUTING.md). Versionen: [CHANGELOG.md](CHANGELOG.md).

## Lizenz

Code: [MIT](LICENSE). Schrift [Kalam](https://fonts.google.com/specimen/Kalam) © Indian Type Foundry,
[SIL Open Font License 1.1](src/main/resources/assets/lyricsinthesky/font/ofl.txt).
