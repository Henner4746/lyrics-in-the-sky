# Store listing – Lyrics in the Sky

Copy-ready texts for a mod page (CurseForge, Modrinth or GitHub Releases). English first, as the
platforms require. The description below is exactly what the CurseForge page shows (Markdown).

## Name

Lyrics in the Sky

## Summary (one sentence, no formatting)

Synced lyrics of the song playing on your PC build up word by word in the Minecraft sky or float all around you.

## Categories

Cosmetic, Utility & QoL (client-side only; Fabric; Minecraft 26.2 and 26.3; requires Fabric API)

## Description

Play music on your PC – Spotify, Amazon Music, YouTube Music or any other player – and its synced lyrics appear in the world while you play: built up word by word in the sky, or as single words floating all around you.

**Client-side Fabric mod for Minecraft 26.2 and 26.3. Requires [Fabric API](https://www.curseforge.com/minecraft/mc-mods/fabric-api).**

## Two display modes

- **In the sky:** word groups of different sizes build up each line close above the horizon, on staggered, gently rising rows turned slightly away from you, so the line reads with depth. Every group fades in as it is sung, and the line fades out softly when the next one starts somewhere else. Like the sun, it stands behind everything: mountains, trees, houses and clouds are in front of it.
- **Around you:** every sung word floats on its own at a fixed spot near you – big, slanted, slightly turned – and stays where it is while you walk or fight, until it fades out a couple of seconds later. A "now playing" card hangs in front of you when a song starts.

## Features

- Lyrics synced word by word: exact word timing when the lyrics have it, otherwise at the pace the song is sung; skipping within a song shows up at once
- Colors of your choice: gradient text in rose, lavender or six more palettes, or a different one per song
- Four styles: *Elegant* (the two looks above, default), *Natürlich 1:1* (the same as if it really stood in the world: lines right above the hills, players and blocks in front of the words around you), *Stapel* (the current line in the middle, the next one faintly below, gliding up from line to line) and *Verspielt* (pop, zoom, swirl and sparkles)
- Fonts: modern (Poppins), handwriting (Kalam) or the Minecraft font
- A "now playing" pill at the top of the screen with the word being sung highlighted
- Presets: Standard, Himmel 1:1, Um dich 1:1, Party and Ruhig set the whole look with one click
- Optional: the lyrics turn along with your view instead of staying where they appeared
- Optional wobble on the beat: every sung word gives the lyrics a little kick
- In the sky behind mountains and clouds like the sun, or switched to always visible
- Settings screen on **Right Shift**, minimal in black and white, with live preview: mode, style, colors, font, size, height, opacity, timing and more
- Works without any login for music on your PC; an optional Spotify login also covers music on your phone, console or other Spotify Connect devices

## How to use

1. Install Fabric Loader (0.18.4 or newer for 26.2, 0.19.3 or newer for 26.3) and Fabric API, then put the mod into your `mods` folder.
2. Start some music on your PC and join a world.
3. Lyrics come from lrclib.net, which is on from the start; the first time, a note in chat says what is sent and offers to switch it off.
4. Press **Right Shift** for the settings, or run `/skylyrics demo` to see it without music.

Commands: `/skylyrics settings`, `status`, `demo`, `on` / `off`, `mode sky|around`, `style elegant|natural|flow|classic`, `palette <name>`, `font sans|script|minecraft`, `offset <ms>`, `opacity`, `scale`, `height`, `follow`, `title`, `hud`, `behind on|off`, `beat on|off`, `along on|off`, `preset <name>`, `lrclib on|off`, `spotify connect|disconnect`.

## Optional: Spotify login

Music playing on your PC needs no login. To show lyrics for Spotify on your phone, console or another Spotify Connect device, connect your account in the settings. Spotify only lets small apps serve a handful of accounts, so you need a free Spotify developer app of your own (takes two minutes):

1. Click "Spotify-App anlegen …" in the settings. It opens the [Spotify developer dashboard](https://developer.spotify.com/dashboard).
2. Create an app with the redirect URI `http://127.0.0.1:43217/callback` and tick "Web API".
3. Paste its Client ID into the settings, click "Mit Spotify verbinden", then "Agree" in the browser.

No client secret is needed (the login uses PKCE).

## Requirements

- Minecraft Java Edition 26.2 or 26.3, Fabric Loader 0.18.4+ (26.2) or 0.19.3+ (26.3), Fabric API
- Windows 10/11, or Linux with `gdbus` (part of GLib). macOS is not supported yet.
- In-game messages are in German for now.

## Network access and privacy

This mod connects to the internet. Everything it sends is listed here:

- **lrclib.net** (on from the start, with a note in chat the first time; switch it off in the settings or with `/skylyrics lrclib off`): the title, artist, album and length of the song that is playing, to look up its synced lyrics. Nothing else is sent; lyrics are kept in memory only and never saved.
- **Spotify Web API** (only if you connect your Spotify account): reads which song is playing (scopes `user-read-currently-playing` and `user-read-playback-state`). Login runs through Spotify's official consent page; the tokens stay in `config/lyricsinthesky/spotify.json` on your PC and you can disconnect at any time.
- **Your PC's media session:** on Windows a small PowerShell script bundled in the mod (`lyricsinthesky/smtc-helper.ps1`, readable inside the jar) reads the system media session – the same information Windows shows in its volume overlay. On Linux the mod asks MPRIS via `gdbus`. This stays on your PC.

No telemetry, no servers of our own, no cookies, no unofficial Spotify or Amazon interfaces. This mod does not ship or host any lyrics.

## Known limitations

- Shader packs (Iris) are untested.
- Livestreams and videos without a known length get no lyrics.
- Without login, Spotify reports its position only every few seconds; `/skylyrics offset` fixes small delays.

## Credits

- Lyrics: [LRCLIB](https://lrclib.net), a free community database of synced lyrics
- Fonts: [Poppins](https://fonts.google.com/specimen/Poppins) by The Poppins Project Authors and [Kalam](https://fonts.google.com/specimen/Kalam) by Indian Type Foundry, both SIL Open Font License 1.1
- This mod's code was written with the help of AI (Claude). All images on this page are in-game screenshots of the mod itself.

Not affiliated with Mojang, Microsoft, Spotify, Amazon or LRCLIB.

## Changelog 1.2.1 (for the file upload)

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

All images are in-game screenshots of the mod itself; none are AI-generated images.

## Icon

`icon-400.png` (400×400, drawn from the bundled Kalam font, no AI image generation)
