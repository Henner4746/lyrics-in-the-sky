# Changelog

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
- **Two more styles:** *Stapel* (the current line in the middle, the next one faintly below, gliding up from
  line to line) and *Verspielt* (the varied animations of 1.0). `/skylyrics style <elegant|flow|classic>`.
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
