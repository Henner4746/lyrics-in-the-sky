# Store listing – Lyrics in the Sky

Copy-ready texts for a mod page (CurseForge, Modrinth or GitHub Releases). English first, as the
platforms require. The description below is exactly what the CurseForge page shows (Markdown). It describes
version 1.3.0; it goes onto the page together with the 1.3.0 upload.

## Name

Lyrics in the Sky

## Summary (one sentence, no formatting)

Synced lyrics of the song playing on your PC appear word by word in the Minecraft sky, all around you or on your screen.

## Categories

Cosmetic, Utility & QoL (client-side only; Fabric; Minecraft 26.2 and 26.3; requires Fabric API)

## Description

Play music on your PC – Spotify, Amazon Music, YouTube Music or any other player – and its synced lyrics appear while you play: built up word by word in the sky, as single words floating all around you, or on your screen like subtitles, karaoke or a stage.

**Client-side Fabric mod for Minecraft 26.2 and 26.3. Requires [Fabric API](https://www.curseforge.com/minecraft/mc-mods/fabric-api).**

## Three ways to show lyrics

- **In the sky:** word groups of different sizes build up each line close above the horizon, on staggered, gently rising rows, so the line reads with depth. Every group fades in as it is sung. Like the sun, it stands behind everything: mountains, trees, houses and clouds are in front of it.
- **Around you:** every sung word floats on its own at a fixed spot near you – big, slanted, slightly turned – and stays where it is while you walk or fight, until it fades out a couple of seconds later.
- **On the screen** (*Bildschirm*), in eleven looks: *Untertitel* (subtitles, the word being sung in color), *Karaoke* (the line fills with color word by word), *Bühne* (stage: big lines on the left as in music apps), *Ein Wort* (only the word being sung, big), *Schreibmaschine* (typed letter by letter), *Laufband* (running by like a news ticker), *Springender Ball* (a ball hopping from word to word), *Welle* (the sung words rolling like a wave), *Seitenleiste* (a column at the right edge), *Kinetisch* (each word popping up in its own size and slant) or *Chat* (speech bubbles like in a messenger) – at the bottom, in the middle or at the top, in any size.

## Features

- Lyrics synced word by word: exact word timing when the lyrics have it, otherwise at the pace the song is sung; skipping within a song shows up at once
- A settings menu on **Right Shift** with every option on its own page and a live preview, in three looks with the same options: *Minimal* (the plain panel, default), *Glas* (a black glass window over the blurred world) or *Prachtvoll* (the glass window with an aurora); options that do not apply right now are not shown
- 22 presets that set the whole look with one click: Standard, Himmel 1:1, Um dich 1:1, Party, Ruhig, Glas, Untertitel, Karaoke, Bühne, Neon-Nacht, Regenbogen, Kino, Retro, Schlicht, Ein Wort, Schreibmaschine, Laufband, Karaoke-Ball, Welle, Seitenleiste, Kinetisch and Chat
- Make it yours: gradient palettes, a rainbow or your own colors with a color picker; outline, shadow, glow, capitals; Modern (Poppins), handwriting (Kalam) or the Minecraft font; size, height, opacity, animation speed, a kick on the beat, what happens on pause, lyrics further left or right
- A "now playing" bar you can drag anywhere, in four looks (pill, karaoke, plain, title only), with an optional progress line, artist, equalizer and the sung word highlighted
- Timing per song: a song that runs early or late is fixed with one slider; what Hörgenau measured applies for everyone at once, corrections by hand once many players agree (see below)
- More songs word by word with *Wortgenau*: optional word-time services, off until you switch them on (see below)
- *Hörgenau* (Windows, optional): a speech AI listens on your PC and times songs no service has timed – also when you hear them in parts on different days – and checks that word-timed songs are in sync with what you hear (see below); a short introduction explains it once
- Streamer mode: explicit words in lyrics, titles and artists shown as "F***"
- Works without any login for music on your PC; an optional Spotify login also covers your phone, console or other Spotify Connect devices
- Lyrics found once are kept on your PC, so a song heard before shows them at once, even when LRCLIB is busy or you are offline
- Works with shader packs (Iris)

## How to use

1. Install Fabric Loader (0.18.4 or newer for 26.2, 0.19.3 or newer for 26.3) and Fabric API, then put the mod into your `mods` folder.
2. Start some music on your PC and join a world.
3. Lyrics come from lrclib.net, which is on from the start; the first time, a note in chat says what is sent and offers to switch it off.
4. Press **Right Shift** for the settings, or run `/skylyrics demo` to see it without music.

Commands: `/skylyrics settings`, `status`, `demo`, `on` / `off`, `mode sky|around|screen`, `preset <name>`, `palette <name>`, `font sans|script|minecraft`, `offset <ms>`, `songoffset <ms>`, `menu minimal|glass|fancy`, `source <name> on|off`, `hoergenau on|off`, `streamer on|off`, `share on|off`, `sharelearned on|off`, `lrclib on|off`, `spotify connect|disconnect`.

## Hörgenau: the AI that listens (Windows, off until you switch it on)

Some songs nobody has timed word by word. With *Hörgenau* on, the mod listens while such a song plays – if you hear only part of it, it keeps what it heard and hears the rest the next time – and lets [whisper.cpp](https://github.com/ggml-org/whisper.cpp) – OpenAI's Whisper speech recognition, running on your own PC – find when each word is sung. From the next play on, the song runs word by word. For songs that already have word times, it listens once for 50 seconds and moves the lyrics when they come too early or too late.

- **Only your music app is heard:** it records only the sound of the app playing the song (Windows' process loopback, Windows 10 2004 and newer) – not voice chat, videos in other apps or the game. Where Windows cannot tell the app apart, it records what the PC plays.
- **Nothing leaves your PC:** the recording stays in memory and is never uploaded.
- **Two models:** *Schnell* (fast: 190 MB, about 15 s for 50 s of music) and *Genau* (accurate: 574 MB, hears more words, about a minute), downloaded once when you switch it on; running in the background at low priority.
- The page *Hörgenau (KI)* in the settings explains step by step what it does.

## Optional: Spotify login

Music playing on your PC needs no login. To show lyrics for Spotify on your phone, console or another Spotify Connect device, connect your account in the settings. Spotify lets an app in development mode serve only its owner and five added people, so you need a free Spotify developer app of your own (takes two minutes):

1. Click "Spotify-App anlegen …" in the settings. It opens the [Spotify developer dashboard](https://developer.spotify.com/dashboard).
2. Create an app with the redirect URI `http://127.0.0.1:43217/callback` and tick "Web API".
3. Paste its Client ID into the settings, click "Mit Spotify verbinden", then "Agree" in the browser.

No client secret is needed (the login uses PKCE).

## Requirements

- Minecraft Java Edition 26.2 or 26.3, Fabric Loader 0.18.4+ (26.2) or 0.19.3+ (26.3), Fabric API
- Windows 10/11, or Linux with `gdbus` (part of GLib). macOS is not supported yet. Hörgenau is Windows only.
- In-game messages and the settings are in German for now.

## Network access and privacy

This mod connects to the internet. Everything it sends, and to whom, is listed here.

**On from the start** (each with a note in chat the first time, and a switch to turn it off):

- **lrclib.net:** the title, artist, album and length of the song that is playing, to look up its lyrics. Lyrics that were found are kept on your PC in `config/lyricsinthesky/lyrics` (up to 1000 songs); delete the folder at any time. Off: `/skylyrics lrclib off`.
- **Timing tracker on henrik.click** (run by the mod's author): for every song with lyrics the mod asks for its correction (the song ID, a hash of artist, title and the first lines). When you correct a song's timing, or Hörgenau measures it, it sends the song ID, the correction in milliseconds (for a measurement also how many words were paired and how close) and a random installation ID. A Hörgenau measurement applies to everyone at once, corrections by hand once many players agree. The tracker keeps keyed hashes of the installation ID and network, no IP addresses and no access log. Sending off: "Teilen" in the settings or `/skylyrics share off`.

**Only when you switch them on:**

- **Wortgenau – word-time services. These are unofficial interfaces** of other apps and services, free to reach without account, cookie or key, but not meant for other apps; each is off until you switch it on, asked at most once a second, with the mod's name as user agent:
  - Deezer (Musixmatch lyrics through Deezer's web player, with an anonymous session kept in memory): artist and title to `api.deezer.com`, then the Deezer song ID to `pipe.deezer.com`
  - LyricsPlus (the server of the YouLy+ extension, which reads Apple Music and QQ Music): title, artist, album, length to `lyricsplus.prjktla.my.id`
  - NetEase Cloud Music: artist and title to `music.163.com`
  - Kugou: artist, title, length to `lyrics.kugou.com`
  - SyncLRC: title, artist, album, length to `api.synclrc.dev`
  - Better Lyrics: title, artist, album, length to `api.betterlyrics.org`
- **Hörgenau:** downloads whisper.cpp once from GitHub and the model from Hugging Face, both checked against a fixed SHA-256. The recording never leaves your PC. With "Gelerntes teilen" (on with Hörgenau) it sends the **word times** of a song it learned on lrclib.net's text to the tracker on henrik.click – the song's names and length, the times of the words, a fingerprint of the text and the random installation ID, **not the lyrics** – and asks it for word times others learned, which the mod puts on lrclib.net's text on your own PC. For a song **nobody has the text of**, it sends what it wrote down as heard, **with its text**, so others get the song word by word; that counts once players on two networks heard it alike, and only while lrclib.net has no text for it. Such a song is heard only when Deezer's or Apple's catalog know it (artist and title to `api.deezer.com`, else `itunes.apple.com`), so no livestream, video or podcast is written down. Sending off: `/skylyrics sharelearned off`; what others learned is fetched either way.
- **Spotify Web API** (only if you connect your account): reads which song is playing (scopes `user-read-currently-playing` and `user-read-playback-state`). The tokens stay in `config/lyricsinthesky/spotify.json` on your PC; disconnect at any time.

**On your PC only:** on Windows a small PowerShell script bundled in the mod (`lyricsinthesky/smtc-helper.ps1`, readable inside the jar) reads the system media session – the same information Windows shows in its volume overlay. On Linux the mod asks MPRIS via `gdbus`. Hörgenau's recording helper (`lyricsinthesky/loopback-helper.ps1`) runs only while it listens.

No telemetry, no ads, no cookies. The mod ships no lyrics; the tracker keeps lyrics only of songs nobody has the text of, as players' Hörgenau wrote them down.

## Known limitations

- With a shader pack (Iris) the text is drawn on top of the pack's finished image: hills, trees and walls still hide it, the pack's own clouds do not. Tested with Complementary Reimagined.
- Livestreams and videos without a known length get no lyrics.
- Without login, Spotify reports its position only every few seconds; `/skylyrics offset` fixes small delays.
- The unofficial word-time services can change or disappear any day; the mod then simply estimates the words again.

## Credits

- Lyrics: [LRCLIB](https://lrclib.net), a free community database of synced lyrics
- Speech recognition: [whisper.cpp](https://github.com/ggml-org/whisper.cpp) (MIT) with OpenAI's Whisper models (MIT)
- Fonts: [Poppins](https://fonts.google.com/specimen/Poppins) by The Poppins Project Authors and [Kalam](https://fonts.google.com/specimen/Kalam) by Indian Type Foundry, both SIL Open Font License 1.1
- This mod's code was written with the help of AI (Claude). All images on this page are in-game screenshots of the mod itself.

Not affiliated with Mojang, Microsoft, Spotify, Amazon, Deezer, Apple, NetEase, Kugou, Tencent, OpenAI or LRCLIB.

## Changelog 1.3.0 (for the file upload)

- Three ways to show lyrics: in the sky, around you, and new on the screen in eleven looks, from subtitles and karaoke to a news ticker, a hopping ball and speech bubbles
- New settings menu with every option on its own page and a live preview, in three looks with the same options (Minimal by default, Glas, Prachtvoll); options that do not apply are hidden
- 22 presets and many new options: own colors and a color picker, rainbow, outline, shadow, capitals, animation speed, beat kick, pause behavior and more
- The now-playing bar can be dragged anywhere and comes in four looks, with progress line, artist and equalizer
- Timing per song with one slider; corrections many players agreed on apply for everyone (optional)
- Wortgenau: optional word-time services for many more songs word by word (off until you switch them on)
- Hörgenau (Windows, optional): a speech AI on your PC times songs nobody has timed, also heard in parts on different days, and checks the sync of word-timed songs; it hears only your music app, shares word times – and the text only of songs nobody has the text of – and introduces itself once
- Streamer mode hides explicit words
- More songs get lyrics: titles are also tried without version notes
- Works with shader packs, lyrics kept on your PC, and many fixes

## Changelog 1.2.2

- Works with shader packs (Iris): the lyrics are drawn on top of the pack's image, still hidden by hills and walls (before, they were invisible)
- Lyrics found once are kept on your PC, so a song heard before shows them at once even when LRCLIB is busy or offline
- The top bar says why no line shows: lyrics off, none found, or lrclib.net not answering (the mod keeps trying)
- Switching lyrics off and on again no longer throws away lyrics already found

## Changelog 1.2.1

- Steady sync: the lyrics follow a clock of their own that never runs backwards, so words no longer flicker; skipping still shows up at once
- Lyrics come back by themselves when LRCLIB was busy: retried after 5 s, 15 s, 45 s …, also while the song keeps playing
- Spotify stays connected: a single refusal no longer logs you out, an expired access is renewed

## Changelog 1.2.0

- New look in both modes: in the sky, word groups of different sizes build up each line near the horizon with depth; around you, every sung word floats on its own at a fixed spot and fades out after a few seconds
- The sky text stands behind everything, like the sun: mountains, trees, houses and clouds are in front of it
- Better sync: words follow the pace the song is sung at and are readable exactly when sung, word-timed lyrics win, and skipping within a song shows up at once
- Presets for a whole look at once, optional turning along with your view
- Optional wobble on the beat, a switch for the sky text to stand behind the hills or in front of everything
- Lyrics from lrclib.net on from the start, with a note in chat
- New minimal black-and-white settings screen
- Runs on Fabric Loader 0.18.4+ (26.2) and 0.19.3+ (26.3)
- Gradient text colors, changeable in the settings (rose, lavender, per-song and more)
- New default font Poppins, also in the now-playing pill
- Three more styles: a natural look (lines right above the hills, players and blocks in front of the words around you), a gliding stack and the playful look of 1.0 (`/skylyrics style`)

## Gallery

| File | Caption |
|---|---|
| `gallery-1-demo.gif` | Sung words floating around the player at dusk, staying in place while the camera turns (built-in demo text) |
| `gallery-2-settings.png` | Settings on Right Shift |
| `gallery-3-around-you.png` | "Around you": every word floats on its own around the player |
| `gallery-4-sky.png` | "In the sky": a line at dusk, behind the clouds like the sun, which sets behind a tree |

All images are in-game screenshots of the mod itself; none are AI-generated images. Before the 1.3.0 upload, new
screenshots of the menu, the screen looks and the Hörgenau page should replace `gallery-2-settings.png`.

## Icon

`icon-400.png` (400×400, drawn from the bundled Kalam font, no AI image generation)
