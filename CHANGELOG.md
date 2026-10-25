# Changelog

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
