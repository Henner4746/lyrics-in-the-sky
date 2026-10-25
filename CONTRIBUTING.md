# Contributing

Bug reports and pull requests are welcome. A few ground rules keep the mod safe to use and to host:

- **No private endpoints.** The mod only reads the operating system's media session and the public
  [LRCLIB API](https://lrclib.net/docs). Pull requests that use Spotify's internal lyrics endpoint, `sp_dc`
  cookies, Musixmatch app tokens, Amazon internal APIs or any other undocumented or login-based source will
  not be merged.
- **No bundled lyrics.** Lyrics are fetched at runtime and only kept in memory. Do not add lyric files,
  caches on disk or a mirror of the LRCLIB database.
- **No servers of our own.** Everything runs on the player's PC. No telemetry.
- **Keep both versions building.** Shared code lives in `src/`; only rendering glue that differs between
  Minecraft versions belongs in `versions/<mc>/`. Run `gradlew build` before opening a pull request.

## Building and testing

```bash
./gradlew build                 # both Minecraft versions, plus unit tests
./gradlew :mc26.3:runSelfTest   # opens the game, plays the demo, saves screenshots, quits
```

The self test expects a world called `SkyTest` in `versions/<mc>/run/saves/`.
