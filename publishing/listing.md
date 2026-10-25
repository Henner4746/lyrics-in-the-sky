# Store listing – Lyrics in the Sky

Copy-ready texts for a mod page (CurseForge, Modrinth or GitHub Releases). English first, as the
platforms require.

## Name

Lyrics in the Sky

## Summary (one sentence, no formatting)

Synced lyrics of the song playing on your PC appear word by word in the Minecraft sky.

## Categories

Utility, Decoration (client-side only; Fabric; Minecraft 26.2 and 26.3; requires Fabric API)

## Description

Play music on your PC – Spotify, Amazon Music, YouTube Music or any other player – and its synced lyrics
appear in the Minecraft sky while you play. Each word pops in on the beat in soft handwriting, every line
shows up in a different spot, and when the line is over it dissolves like a cloud.

### Features

- Lyrics synced word by word, in a handwritten font with a soft glow at night and a shadow by day
- Six entry and four exit animations, sparkles, and a different spot in the sky for every line
- Two display modes: far away in the sky (nearby trees and houses cover it), or close around you at eye
  level, always visible like a hologram
- A title card in the sky when a song starts, and a "now playing" pill at the top of the screen with the
  word being sung highlighted
- Settings screen on Right Shift with live preview: colors, font, size, height, opacity, timing and more
- Works without any login for music on your PC; an optional Spotify login also covers music on your phone,
  console or other Spotify Connect devices

### How to use

1. Install Fabric Loader 0.20.1+ and Fabric API, then put the mod into your `mods` folder.
2. Start some music on your PC and join a world.
3. The first time, the mod asks in chat whether it may look up lyrics on lrclib.net – click "Erlauben"
   (allow).
4. Press Right Shift for the settings, or run `/skylyrics demo` to see it without music.

### Optional: Spotify login

Music playing on your PC needs no login. To show lyrics for Spotify on your phone, console or another
Spotify Connect device, connect your account in the settings. Spotify only lets small apps serve a handful of
accounts, so you need a free Spotify developer app of your own (takes two minutes):

1. Click "Spotify-App anlegen …" in the settings. It opens the
   [Spotify developer dashboard](https://developer.spotify.com/dashboard).
2. Create an app with the redirect URI `http://127.0.0.1:43217/callback` and tick "Web API".
3. Paste its Client ID into the settings and click "Mit Spotify verbinden", then "Agree" in the browser.

No client secret is needed (the login uses PKCE).

### Requirements

- Minecraft Java Edition 26.2 or 26.3, Fabric Loader 0.20.1 or newer, Fabric API
- Windows 10/11, or Linux with `gdbus` (part of GLib). macOS is not supported yet.
- In-game messages are in German for now.

### Network access and privacy

This mod connects to the internet. Everything it sends is listed here:

- **lrclib.net** (only after you allow it in chat or in the settings): the title, artist, album and length of
  the song that is playing, to look up its synced lyrics. Nothing else is sent; lyrics are kept in memory
  only and never saved.
- **Spotify Web API** (only if you connect your Spotify account in the settings): reads which song is
  playing (scopes `user-read-currently-playing` and `user-read-playback-state`). Login runs through Spotify's
  official consent page; the tokens stay in `config/lyricsinthesky/spotify.json` on your PC and you can
  disconnect at any time.
- **Your PC's media session:** on Windows a small PowerShell script bundled in the mod reads the system
  media session (the same information Windows shows in its volume overlay); on Linux the mod asks MPRIS via
  `gdbus`. This stays on your PC.

No telemetry, no servers of our own, no cookies, no unofficial Spotify or Amazon interfaces.

### Credits

- Lyrics: [LRCLIB](https://lrclib.net), a free community database of synced lyrics
- Font: Kalam by Indian Type Foundry, SIL Open Font License 1.1
- This mod's code was written with the help of AI (Claude).

Not affiliated with Mojang, Microsoft, Spotify, Amazon or LRCLIB.

## Gallery

| File | Caption |
|---|---|
| `gallery-1-demo.gif` | Sung words floating around the player at dusk, staying in place while the camera turns (built-in demo text) |
| `gallery-2-settings.png` | Settings on Right Shift |
| `gallery-3-around-you.png` | "Around you": every word floats on its own around the player |
| `gallery-4-sky.png` | "In the sky": a line building up at night, with the now-playing pill on top |

All images are in-game screenshots of the mod itself; none are AI-generated images.

## Icon

`icon-400.png` (400×400, drawn from the bundled Kalam font, no AI image generation)
