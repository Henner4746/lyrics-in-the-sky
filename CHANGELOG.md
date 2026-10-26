# Changelog

## Unreleased

- **Movable now-playing bar:** drag it with the mouse anywhere ("Leiste" button in the settings); it hangs from the
  top or sits at the bottom above the hotbar, and snaps to the middle and to its usual height.
- **Four looks for the bar:** *Pille* (as before), *Karaoke* (two lines in a box, every word fills up with color as it
  is sung), *Schlicht* (just the words with a shadow) and *Nur Titel* (only the song). In the settings under
  "Leiste", or `/skylyrics bar <pill|karaoke|plain|title>` and `/skylyrics barpos <top|bottom>`.

## Development builds (branch `lyricsplus`, not on CurseForge)

### 1.3.0-dev.15 – 2026-10-26

- **Only word times are shared, never lyrics**: what Hörgenau learned on lrclib.net's text goes to the tracker on
  henrik.click as the times of its words and a fingerprint of the text, without a single word. Other players put the
  times on their own text from lrclib.net, and only when it is exactly the same – every line with as many words as it
  has times. The tracker checks the fingerprint against lrclib.net before the times count, and keeps no lyrics: the
  ones it kept before became times, the text was deleted and the database rewritten. Songs written down as heard,
  whose text nobody has, are no longer shared at all. Older versions still sending lyrics only have their times taken.
- **The project description tells everything**: what goes where – lrclib.net, the timing tracker, each optional
  unofficial word-time service, Hörgenau's downloads and its shared word times, Spotify – and that the mod's code was
  written with the help of AI.

### 1.3.0-dev.14 – 2026-10-26

- **Hörgenau hears only the music player**: instead of everything the PC plays, it records only the sound of the app
  playing the song (Windows' process loopback, Windows 10 2004 and newer) – voice chat, videos in other apps and
  Minecraft's own sounds stay out of what Whisper hears, and out of lyrics it writes down. For its first three seconds
  it also hears everything; when the player's own sound stays silent while the PC plays something, or the player
  cannot be found, it records everything as before. The log says which (`Hoergenau records only Spotify.exe#…`).
  Tested on Windows 11 with Spotify from the Store: a test tone from another program was 8354 loud in the recording
  of everything and 12 in Spotify's; a Windows voice talking over the song was written down by Whisper only from the
  recording of everything.
- **Quiet recordings are made louder for Whisper**: the player's own sound comes without what Windows raises
  afterwards and was 17 dB quieter. In it *Genau* heard nothing but a made-up line; made louder, all 93 words.
- **Made-up lines are dropped**: lines Whisper makes up from the subtitles it learned from ("WDR mediagroup GmbH im
  Auftrag des WDR", "Untertitel im Auftrag des ZDF", "Thanks for watching") never become lyrics.
- **Hörgenau explained in the menu**: the page *Hörgenau (KI)* now says what the AI is, and step by step how it learns
  songs, checks their sync, what it hears, *Schnell* or *Genau* with their real sizes and times, what *Leistung* means
  and how to tell what it is doing.
- Fixed: without a known player the recording helper did not start at all.

### 1.3.0-dev.13 – 2026-10-26

Fixes found in a real game on Windows, with Spotify, real loopback listening and the "Genau" model:

- **Hörgenau checks a song that was paused when it came up**: a song with word times seen paused, sounding along
  with another player or while the song before was still being worked on was never checked. Now the check waits until
  the song plays alone.
- **The sync check measures what is shown**: word times usually arrive a few seconds after LRCLIB's lines, while the
  check already listens. It compared with LRCLIB's lines and put its correction on them; now it checks, and corrects,
  the word-timed lyrics on screen. (A song whose LRCLIB lines were another version got "unsure" for it.)
- **Deezer no longer fails every few minutes**: its anonymous session lives six minutes, but was kept for ten, so
  between minute six and ten every Deezer request failed and Deezer then rested for five minutes. The session is now
  renewed shortly before its own lifetime ends.

### 1.3.0-dev.12 – 2026-10-26

- **The menu as it always looked, with every option** (new default, "Minimal"): all pages and options of the new menu
  drawn in the old style – the plain half see-through panel (a pane of Liquid Glass in that theme), grey labels and
  white values, the small switch with "An"/"Aus", thin sliders, choices in a row with the chosen one underlined,
  compact rows, the hovered option explained in the line at the bottom, nothing moving. The glass menu and the lively
  one stay a click away (page *Menü*, or `/skylyrics menu glass|fancy`).
- **Lyrics on the screen** (display "Bildschirm"): *Untertitel* (the line at the bottom like a film's subtitles, the
  word being sung in the accent), *Karaoke* (the line filling up with color word by word, the next one below) and
  *Bühne* (big lines on the left as in Spotify's and Apple Music's lyrics, the line before and the next ones dimmed,
  gliding up as the song goes on); at the bottom, in the middle or at the top, in any size, with a dark band behind if
  wanted. Palette, own colors, outline, capitals, opacity, animation speed and pause apply as in the sky.
- **14 presets instead of 6**, in every menu: Untertitel, Karaoke, Bühne, Neon-Nacht, Regenbogen, Kino, Retro and
  Schlicht join Standard, Himmel 1:1, Um dich 1:1, Party, Ruhig and Glas. A preset now also resets outline, capitals,
  shadow, animation speed, beat strength and the screen looks, so every preset looks the same each time.

### 1.3.0-dev.11 – 2026-10-26

- **The glass menu** (new default, Right Shift): a black glass window over the blurred world. It opens on a card for
  every part of the mod – Anzeige, Schrift & Farben, Animation, Leiste, Timing, Quellen, Hörgenau (KI), Spotify,
  Streamer, Menü – with switches right on the cards and the song playing on top. A card opens its page: a live preview
  (a small synthwave night sky singing a sample line in your look), sections, and rows of a title, a grey line on
  what it does and a switch, slider, choice or color on the right; a color picker with a saturation field, a hue strip,
  swatches and typed hex codes; "Auf Standard zurücksetzen" per page; a live view that steps the menu aside while the
  demo sings in the sky; Esc goes back, then closes. Everything glides, glows and springs open, or keeps still.
- **27 new options**, each with its effect in the game: own colors (top, bottom, sung word, glow) and a rainbow palette;
  outline with color and width; shadow by day on its own; capitals; animation speed; beat strength; what happens on
  pause; lyrics further left or right; the bar's own accent color, progress line, artist, equalizer, highlight (box,
  color, underline) and font; the menu's accent color, blur and animations; Hörgenau's model, power and sync check.
- **Hörgenau, better:** the model *Genau* (Whisper large-v3-turbo, 574 MB) hears 87–94 % of the sung words where
  *Schnell* hears 77–92 %, calibrated on the JamendoLyrics songs; *Leistung* chooses how many cores it may use.
- **Sync check:** Hörgenau hears 50 s of a song that has word times and moves it when nearly all words agree it runs
  early or late. Measured live against the sung audio of twelve songs played through Spotify: the words came 38–185 ms
  from the singing in the median, except two songs whose source had timed another version (In the End 1.1 s late from
  NetEase, Roller 0.8 s late from Deezer) – exactly what the check finds and corrects.
- The menu's text in Poppins Regular and Medium.

### 1.3.0-dev.10 – 2026-10-26

- **Umlauts back in word-timed lyrics:** QQ Music (read through LyricsPlus) loses every umlaut together with the letter
  after it and writes German nouns small, so Rammstein's "Sonne" counted "vier ff sechs" and sang "Fchtet euch". Where
  a service's text has lost such letters, its words now take LRCLIB's spelling of the same song ("fünf", "Fürchtet",
  "zählt", "Händen", "heiß", "das Licht"), keeping the service's word times. Lyrics already kept on disk are mended
  when they are loaded.
- **No stall when the first line appears:** the mod's fonts are drawn large for sharp text, and the game draws each
  letter into the font texture the first time it shows; on a slow PC the first line of a song stalled its frame by up
  to 55 ms. The letters of a song's lyrics are now drawn ahead, two per tick, as soon as the lyrics are found, and the
  usual letters after joining a world.
- Tested live on the mini-PC with a real Spotify account playing eight songs: all found word by word (Hörgenau,
  Deezer, LyricsPlus with Apple and QQ Music), the lines following Spotify's position, streamer mode hiding words and
  titles.

### 1.3.0-dev.9 – 2026-10-26

- **Streamer mode** (settings "Streamer", `/skylyrics streamer on|off`): explicit words in lyrics, titles and artists
  show as their first letter and stars ("F***"), in the sky, around you, in the bar and in chat. Hidden words keep
  their length, so word times and the karaoke fill still fit. German and English; words that are harmless in the other
  language ("dick" is "thick" in German) count only in songs sung in their language.
- **A lively menu**, the new default: a slow aurora behind the panel and notes rising through it, the panel springing
  open with light running around its rim, the title in drifting colors, rows sliding in one after the other, gliding
  knobs and capsules, sparks when something is switched, buttons that glow, shine and give when pressed. The quiet
  look stays one click away (page *Aussehen*, "Menü: Minimal", or `/skylyrics menu minimal`).
- **What Hörgenau learns counts for everyone** (on by default, "Teilen" on the page *Quellen*,
  `/skylyrics sharelearned on|off`): a learned song is shared through the tracker on henrik.click, and before a song
  nobody has timed is only estimated, the mod asks whether another player learned it; then it runs word by word
  right away, without listening. The tracker checks text placed on lrclib.net's lyrics against lrclib.net itself;
  lyrics written down as heard count once players on two networks heard alike, so no single person can put words
  under a song for all. Sent are the song (artist, title, album, length), the learned lyrics and the random install ID.
- **Hörgenau, smoother:** a song without word times offers it once per session with an "[Einschalten]" button in chat;
  what it does shows as a small note in the corner (downloading with percent, listening, learning, learned, or why it
  stopped listening) instead of lines in chat; while it records the playing song the bar's icon is a pulsing red
  recording light, while it learns an amber one.
- **Faster:** glass shapes are drawn with a handful of fills instead of one per pixel row. Measured in the game, the
  mod takes about 0.3 ms a frame in all (laying out, drawing, the bar).

### 1.3.0-dev.8 – 2026-10-26

- **Hörgenau only learns a song that plays alone:** when another player sounds at the same time (a video in the
  browser, also in another tab) it does not start listening, and a recording under way is thrown away. A song the
  Spotify account plays on another device is not listened to either, since the PC sounds something else then. In a
  test a YouTube video playing beside the song had ended up in the lyrics.

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
