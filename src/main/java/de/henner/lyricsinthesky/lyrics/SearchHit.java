package de.henner.lyricsinthesky.lyrics;

import de.henner.lyricsinthesky.nowplaying.NowPlaying;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/**
 * One song a word time service found in its search, and the check whether it is the song playing. Services that are
 * searched by words return covers, live takes and other songs of the same name, so only hits with the same title,
 * one shared artist and nearly the same length are asked for lyrics, the closest in length first.
 *
 * @param durationMs 0 when the service does not say
 * @param key        whatever else the service needs to fetch the lyrics, e.g. an access key
 */
record SearchHit(String id, String title, String artist, long durationMs, String key) {
    static final long MAX_DURATION_DIFF_MS = 3_000;
    /** Other performances or arrangements, never just another cut of the same recording. */
    private static final Pattern OTHER_TAKE = Pattern.compile(
            "\\b(live|remix|re-mix|club|dub|extended|instrumental|karaoke|acoustic|unplugged|demo|a ?cappella|cover|"
                    + "sped|slowed|reverb|lullaby|session|orchestral|piano)\\b", Pattern.CASE_INSENSITIVE);

    /** What to type into a service's search: the first artist and the title without version notes. */
    static String searchText(NowPlaying track) {
        return TrackNames.primaryArtist(track.artist()) + " " + coreTitle(track.title());
    }

    static String coreTitle(String title) {
        List<String> variants = TrackNames.titleVariants(title);
        return variants.get(variants.size() - 1);
    }

    /** The hits that are the song playing, best first, at most {@code max}. */
    static List<SearchHit> matching(NowPlaying track, List<SearchHit> hits, int max) {
        String title = coreTitle(track.title());
        List<String> titleWords = TrackNames.words(title);
        return hits.stream()
                .filter(hit -> TrackNames.sameSong(title, track.artist(), hit.title(), hit.artist()))
                .filter(hit -> track.durationMs() <= 0 || hit.durationMs() <= 0
                        || Math.abs(hit.durationMs() - track.durationMs()) <= MAX_DURATION_DIFF_MS)
                .sorted(Comparator
                        .comparing((SearchHit hit) -> !TrackNames.words(coreTitle(hit.title())).equals(titleWords))
                        .thenComparingLong(hit -> hit.durationMs() <= 0 || track.durationMs() <= 0 ? MAX_DURATION_DIFF_MS
                                : Math.abs(hit.durationMs() - track.durationMs())))
                .limit(max)
                .toList();
    }

    /**
     * Hits of the same song with another length, which may be another cut of the same recording (a radio edit, an
     * earlier fade-out), closest in length first, at most {@code max}. Live takes, remixes and the like are left out;
     * whether a cut fits is decided by {@link WordTimeTransfer} against LRCLIB's lines.
     */
    static List<SearchHit> otherCuts(NowPlaying track, List<SearchHit> hits, int max) {
        if (track.durationMs() <= 0) {
            return List.of();
        }
        List<String> titleWords = TrackNames.words(coreTitle(track.title()));
        return hits.stream()
                .filter(hit -> hit.durationMs() > 0 && Math.abs(hit.durationMs() - track.durationMs()) > MAX_DURATION_DIFF_MS)
                .filter(hit -> TrackNames.sameSong(coreTitle(track.title()), track.artist(), hit.title(), hit.artist()))
                .filter(hit -> TrackNames.words(coreTitle(hit.title())).equals(titleWords))
                .filter(hit -> !OTHER_TAKE.matcher(hit.title()).find())
                .sorted(Comparator.comparingLong(hit -> Math.abs(hit.durationMs() - track.durationMs())))
                .limit(max)
                .toList();
    }
}
