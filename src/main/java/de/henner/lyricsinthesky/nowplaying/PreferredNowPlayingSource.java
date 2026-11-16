package de.henner.lyricsinthesky.nowplaying;

import java.util.Locale;

/**
 * Combines the Spotify account with the operating system's media session: whichever is playing wins, and
 * when both are, the Spotify account (exact position, works across devices). When both play the same song, the
 * position comes from whichever reported last, so a skip within the song shows up as soon as either source
 * notices it.
 */
public final class PreferredNowPlayingSource implements NowPlayingSource {
    private final NowPlayingSource preferred;
    private final NowPlayingSource fallback;

    public PreferredNowPlayingSource(NowPlayingSource preferred, NowPlayingSource fallback) {
        this.preferred = preferred;
        this.fallback = fallback;
    }

    @Override
    public void start() {
        preferred.start();
        fallback.start();
    }

    @Override
    public void stop() {
        preferred.stop();
        fallback.stop();
    }

    @Override
    public NowPlaying current() {
        NowPlaying first = preferred.current();
        NowPlaying second = fallback.current();
        if (spotifyHereDiffers(first, second)) {
            return second;
        }
        if (first != null && first.playing()) {
            if (second != null && second.playing() && second.sameSong(first) && second.updatedAtMs() > first.updatedAtMs()) {
                // Keep the preferred source's names and length so the song never looks like a new one.
                return new NowPlaying(first.app(), first.title(), first.artist(), first.album(), true,
                        second.positionMs(), first.durationMs(), second.updatedAtMs());
            }
            return first;
        }
        if (second != null && second.playing()) {
            return second;
        }
        return first != null ? first : second;
    }

    /**
     * Whether the Spotify app on this PC plays another song than the account says. An account plays on one device at
     * a time, so the account's answer is old (Spotify kept refusing to answer) or the mod is connected to another
     * account: what this PC plays is what is heard.
     */
    static boolean spotifyHereDiffers(NowPlaying account, NowPlaying here) {
        return account != null && account.playing() && here != null && here.playing() && !here.sameSong(account)
                && here.app() != null && here.app().toLowerCase(Locale.ROOT).contains("spotify");
    }

    /** The media session's app when it plays the song here; the account's song may play on a phone or a console. */
    @Override
    public String localPlayer() {
        NowPlaying first = preferred.current();
        NowPlaying second = fallback.current();
        if (playsElsewhere(first, second)) {
            return null;
        }
        return fallback.localPlayer();
    }

    /**
     * When the account's song is not what this PC plays (it plays on a phone or a console), whatever the PC sounds is
     * something else; otherwise the media session's own report.
     */
    @Override
    public String otherPlaying() {
        NowPlaying first = preferred.current();
        NowPlaying second = fallback.current();
        if (playsElsewhere(first, second)) {
            return second != null && second.playing() ? second.app() : "the song plays on another device";
        }
        return fallback.otherPlaying();
    }

    /** Whether the account's song is what is shown and plays on another device than this PC. */
    private static boolean playsElsewhere(NowPlaying account, NowPlaying here) {
        return account != null && account.playing() && !spotifyHereDiffers(account, here)
                && (here == null || !here.playing() || !here.sameSong(account));
    }
}
