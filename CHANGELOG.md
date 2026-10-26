# Changelog

## Unreleased

- **Movable now-playing bar:** drag it with the mouse anywhere ("Leiste" button in the settings); it hangs from the
  top or sits at the bottom above the hotbar, and snaps to the middle and to its usual height.
- **Four looks for the bar:** *Pille* (as before), *Karaoke* (two lines in a box, every word fills up with color as it
  is sung), *Schlicht* (just the words with a shadow) and *Nur Titel* (only the song). In the settings under
  "Leiste", or `/skylyrics bar <pill|karaoke|plain|title>` and `/skylyrics barpos <top|bottom>`.

## Development builds (branch `lyricsplus`, not on CurseForge)

### 1.3.0-dev.7 – 2026-10-26

- **Hörgenau tested in the game with real playback:** a song with hand-made timings played through the speakers while
  the game ran; Hörgenau learned it in 55 s, all 22 line starts 60 ms off in the median and all within half a second.
- **Fixed:** over an instrumental Whisper made up one line thirteen times ("Die Kälte wird in der Kälte gebraten."),
  which would have shown for a song without known text. Lines whose words Whisper puts at nearly one moment are no
  longer taken.
- **Fixed:** a song played to its end as the last of a queue, where the player stops instead of going on, was thrown
  away as "paused"; it is now learned.
- Hörgenau hears everything the speakers play: for a song without known text, a video playing at the same time ends
  up in the lyrics (seen in a second test run). Noted in the README.

### 1.3.0-dev.6 – 2026-10-26

- **Hörgenau on real songs:** measured on three German songs with hand-made timings (rock, pop, rap), line starts are
  now 43–89 ms off in the median instead of 204–1662 ms, and 91–100 % land within a second.
  - The song's language is recognised from three cuts across the song instead of its first 30 s: a rap song with a
    long intro was taken for Khmer and misheard for two minutes.
  - Whisper's word times came about 140 ms late on every song; they are moved forward by 150 ms.
  - A line's first word that Whisper pins to the end of the pause before it is moved up to the next word.
  - Words heard as other words ("mir wird's" as "es ist") keep the times they were heard at.
  - Sounds Whisper marks as "*Musik*" or "[Applaus]" no longer count as sung words, and it is told to write sung
    words instead of such marks; it no longer reads on from its own text, which let it skip a whole verse.
  - Stretches of four or more words missed on the whole song are listened to once more on their own.
- **Fixed:** Hörgenau failed on songs where Whisper split a letter such as "ö" between two of its pieces; such songs
  were never learned.

### 1.3.0-dev.5 – 2026-10-26

- **Hörgenau (Windows, off by default):** for songs no service has timed, or whose lyrics nobody has, the mod listens
  while the song plays from its start (Windows audio loopback, in memory only) and afterwards lets whisper.cpp time the
  words: LRCLIB's text without times is placed on the recording, or what was heard becomes the lyrics. From the next
  play on the song runs word by word; real LRCLIB lines replace it once they exist. Switching it on downloads
  whisper.cpp and its model once (about 190 MB, checked against fixed checksums); each learned song then costs about
  1–2 minutes of up to four cores at low priority in a separate process. Page *Quellen*, `/skylyrics hoergenau on|off`.
- **Two more word time services:** SyncLRC and Better Lyrics (mostly Apple Music's timing), both only for songs
  someone asked for before; a song they have not kept yet is asked again later instead of counting as "nothing".
- **All word time services are asked at once** and still weighed in their measured order, so the answer comes as soon
  as the best fitting service has answered, not after all before it.

### 1.3.0-dev.4 – 2026-10-26

- **Word times for most songs:** "Wortgenau" now asks four unofficial services one after the other – Deezer
  (Musixmatch's timing, as on Spotify), LyricsPlus (Apple Music, QQ Music), NetEase and Kugou – each of which can be
  switched on its own (page *Quellen*, `/skylyrics source <name> on|off`); "Wortgenau" switches all of them, and all
  are off until you do. Of 48 test songs with LRCLIB lines, all 48 now get real word times (22 before).
- Word times are matched by each line's first words, so services that break lines differently still fit, and a
  steady shift of up to 8 s (a longer intro) is recognised. When LRCLIB's hand-made lines drift, two services that
  agree with each other are trusted instead; where its lines only scatter by a few hundred ms around the service's,
  in the same order, the service's times are taken too, unless another service fits closely. Differences below
  1 s are no longer shifted onto LRCLIB's lines.
- Where Deezer has word times only for another cut of the song (a radio edit, a remaster that fades out earlier),
  they are taken when they line up with LRCLIB's lines strictly and all the way through; after a shorter cut ends,
  LRCLIB's lines go on. A service with the song as it plays still comes first.
- **Liquid Glass** (new default theme): the now-playing bar in every look, its editor and the settings as frosted
  glass like iOS 26, with the world behind the settings softly blurred. *Klassisch* keeps the old look
  (`/skylyrics theme classic`).
- **Settings in three pages:** *Allgemein*, *Aussehen* and *Quellen*; hints show in up to two lines.
- **More looks:** size, background and roundness of the bar (`barsize`, `barbg`, `barround`), glow and shadow of the
  lyrics (`glow`), the palettes *Glas* and *Neon* and the preset *Glas*.

### 1.3.0-dev.3 – 2026-10-25

- The now-playing bar shows the song again during music without singing (intros, solos). Since the bar could be
  moved, it stayed an empty dark bar there.

### 1.3.0-dev.2 – 2026-10-25

- **More songs get lyrics:** when LRCLIB's exact lookup finds nothing, the title is tried without version notes
  ("- Remastered 2009", "(2015 Remaster)", "- Single Mix"), then a free search that only takes results naming the
  song and the artist. When LRCLIB has only the text without times, it is spread over the song by line length; the
  bar shows "≈" instead of "♪", and the mod offers "Wortgenau" once, which often has real times for such songs.
- **Timing per song:** a song whose lyrics run early or late can be corrected on its own, with the slider "Dieser
  Song" in the settings, the earlier/later keys while it plays, or `/skylyrics songoffset <ms|reset>`. Kept in
  `config/lyricsinthesky/song-timing.json`.
- **Shared corrections:** corrections are shared anonymously with a small tracker at `henrik.click/lyrics-sync`
  (source in `tracker/`). Once 10 players corrected a song and 70 % of them agree within 150 ms, that correction
  applies to everyone who has not set an own one. On by default with a note in chat the first time; switch it off
  with "Teilen" in the settings or `/skylyrics share off`.

### 1.3.0-dev – 2026-10-25

- **Wortgenau (optional, off by default):** exact word times from LyricsPlus (unofficial, from Apple Music and QQ
  Music), used only when they fit LRCLIB's lines; for songs LRCLIB does not know, LyricsPlus stands in.

## 1.2.2 – 2026-10-25

- **Works with shader packs (Iris):** with a shader pack on, the lyrics are drawn on top of the pack's finished
  image, still hidden by hills, trees and walls in front of them. Before, the shader pack painted over them and
  nothing was visible. Tested with Complementary Reimagined on Iris 1.11 for 26.2 and 26.3; the pack's own clouds
  do not hide the text.
- **Lyrics found once are kept:** they are saved in `config/lyricsinthesky/lyrics` (up to 1000 songs, the least
  recently played make room), so a song heard before shows its lyrics at once, even when LRCLIB is busy or offline.
- **The top bar says why no line shows:** lyrics switched off, none found, or lrclib.net not answering (the mod
  keeps trying), instead of the text simply being gone.
- Switching "Songtexte" off and on again no longer throws away lyrics already found.
- The gliding stack comes back when the lyrics arrive late, e.g. after LRCLIB was busy.

## 1.2.1 – 2026-10-25

- **Steady sync:** the position the lyrics follow is now a clock of its own that the player's reports pull
  gently into place. It never runs backwards while a song plays, so words no longer flicker when the media
  session and the Spotify account report a few dozen milliseconds apart; skipping still shows up at once.
- **Lyrics come back by themselves:** when LRCLIB is busy or the connection drops, the lookup is tried again
  after 5 s, 15 s, 45 s and so on, also while the same song keeps playing (before, a failure stuck for ten
  minutes or until the next song). When LRCLIB's exact lookup fails, the search is tried right away.
- **Spotify stays connected:** a single refusal from Spotify no longer logs you out. Only answers that say the
  account is not added to the app count, several in a row; an expired access is simply renewed.

## 1.2.0 – 2026-10-25

- **New look in both modes** (style *Elegant*, the new default):
  - **In the sky:** word groups of different sizes build up line by line close above the horizon, on
    staggered, gently rising rows turned slightly away from you, so each line reads with depth. Every group
    fades in as it is sung; the line fades out softly when the next one starts somewhere else.
  - **Around you:** every sung word floats on its own at a fixed spot near you, big, slanted and slightly
    turned. Words stay where they are while you walk or fight, and fade out after a couple of seconds. A
    "now playing" card hangs in front of you when a song starts.
- **The sky text now stands behind everything**, like the sun: mountains, trees, houses, clouds and other
  players all stand in front of it, at any distance. A line never dips below the horizon. The words around you
  stay in front of everything, clouds included.
- **Better sync:** words of lines without word timing follow the pace this song is sung at, measured from its
  own lyrics, instead of a fixed slow guess that let the last words of a line lag behind by up to seconds.
  Skipping within a song shows up at once, also with the Spotify login, and a line you skip back to starts over.
- **Gradient text colors**, changeable in the settings: *Standard* (rose in the sky, white to lavender
  around you), rose, lavender, per-song colors and the previous palettes.
- **New font *Modern*** (Poppins, SIL Open Font License) as default, also in the now-playing pill. Handwriting
  and the Minecraft font remain selectable.
- **Three more styles:** *Natürlich 1:1* (the elegant look as if it really stood in the world: lines right above the
  hills, players and blocks in front of the words around you, which prefer spots with nothing in the way, and
  the words being sung highlighted as a group in the now-playing pill), *Stapel* (the current line in the
  middle, the next one faintly below, gliding up from line to line) and *Verspielt* (the varied animations of
  1.0). `/skylyrics style <elegant|natural|flow|classic>`.
- **Wobble on the beat** (switch, off by default): every sung word gives the lyrics a short kick, long words
  a stronger one, tilting to alternate sides. `/skylyrics beat on`.
- **Presets:** Standard, Himmel 1:1, Um dich 1:1, Party and Ruhig set the whole look with one click in the
  settings or with `/skylyrics preset`; timing, lookups and the Spotify login stay as they are.
- **Turn along** (switch, off by default): the lyrics follow the view about a tenth of a second behind
  instead of staying where they appeared. `/skylyrics along on`.
- **Behind the hills or always visible:** a switch for the sky text, `/skylyrics behind off` puts it in front
  of everything.
- **Exact on the word:** every style fades a word in early by as long as its animation needs, so it is
  readable right when it is sung (the playful style lagged by up to a third of a second). Lyrics with their
  own word timestamps on LRCLIB win over line-timed ones.
- **Fixed:** a lyrics search failed when one of LRCLIB's results had no length.
- **LRCLIB on from the start**, with a one-time note in chat on what is sent and a button to switch it off.
- **New settings screen:** minimal, black and white, every choice visible at once, the hovered option
  explained in one line; fits small windows.
- **Runs on older Fabric Loaders:** 0.18.4 or newer for 26.2 and 0.19.3 or newer for 26.3, the same as Fabric API
  itself needs (was 0.20.1).

## 1.1.0 – 2026-10-25

- **Settings screen** on Right Shift (or `/skylyrics settings`): every option with live preview, the world
  stays visible behind the panel.
- **"Around you" mode:** lines float close around the player at eye level, slowly circling, always visible like
  a hologram. Switch with the settings screen or `/skylyrics mode around`.
- **Optional Spotify login** for music that does not play on this PC (phone, console, Spotify Connect): click
  "Connect with Spotify", log in and agree in the browser. Uses a Spotify app's client ID with PKCE, no secret;
  builds ship with the client ID of the "Lyrics in the Sky" app, which can be replaced in the settings. Expired
  logins (Spotify refresh tokens last 180 days) ask to reconnect, and accounts the app does not serve (Spotify
  development mode) are told to use their own app. Without login everything keeps working through the media
  session as before.
- More tests: PKCE, the local login callback, Spotify responses, source priority; the in-game self test now
  opens the settings screen and switches modes.

## 1.0.0 – 2026-10-25

First release, for Minecraft 26.2 and 26.3 (Fabric).

- Synced lyrics of the song playing on your PC appear word by word in the sky.
- Now playing from the Windows media session (Spotify, Amazon Music, browser players) and from MPRIS on Linux.
- Lyrics from [LRCLIB](https://lrclib.net) after you allow it in chat; enhanced LRC word timing is used when available.
- Handwritten font (Kalam), soft glow, day shadow, six entry and four exit animations, sparkles.
- Every line pops up at a different spot in the sky; long lines wrap into two staggered rows.
- Title card in the sky when a song starts, and a now-playing pill at the top of the screen with the sung word highlighted.
- `/skylyrics` commands, three key bindings, settings in `config/lyricsinthesky.json`.
