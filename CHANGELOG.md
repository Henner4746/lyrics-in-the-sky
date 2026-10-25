# Changelog

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
