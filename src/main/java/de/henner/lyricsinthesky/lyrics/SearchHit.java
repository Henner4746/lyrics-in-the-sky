package de.henner.lyricsinthesky.lyrics;

import de.henner.lyricsinthesky.nowplaying.NowPlaying;
import java.util.Comparator;
import java.util.List;

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
}
