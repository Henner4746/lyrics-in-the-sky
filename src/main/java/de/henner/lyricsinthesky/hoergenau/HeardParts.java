package de.henner.lyricsinthesky.hoergenau;

import de.henner.lyricsinthesky.hoergenau.WhisperEngine.Heard;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * What Hoergenau heard of a song it has not learned yet, part by part: the stretches of the song it heard, and the
 * words in them with their times in the song. A song heard only partly is completed when it plays again, wherever it
 * was not heard yet; once nearly all of it was heard, it is learned from all parts at once. Kept beside the lyrics as
 * plain text; no audio.
 */
final class HeardParts {
    private static final String HEADER = "hoergenau-parts 1";

    /** The language Whisper heard in the first part; the later parts are heard in it too. {@code null} for none. */
    private final String language;
    /** The stretches heard, as [from, to) in ms of the song: sorted, apart from each other. */
    private final List<long[]> covered;
    /** The words heard, in the lines Whisper heard them in, with times in ms of the song; sorted by their start. */
    private final List<List<Heard>> segments;

    private HeardParts(String language, List<long[]> covered, List<List<Heard>> segments) {
        this.language = language;
        this.covered = covered;
        this.segments = segments;
    }

    static HeardParts none() {
        return new HeardParts(null, List.of(), List.of());
    }

    boolean isEmpty() {
        return covered.isEmpty();
    }

    String language() {
        return language;
    }

    List<List<Heard>> segments() {
        return segments;
    }

    /** Every word heard, in the order sung. */
    List<Heard> words() {
        return segments.stream().flatMap(List::stream).sorted(Comparator.comparingLong(Heard::startMs)).toList();
    }

    /** How much of the song was heard, in ms. */
    long heardMs() {
        return covered.stream().mapToLong(stretch -> stretch[1] - stretch[0]).sum();
    }

    /** The share of a song of this length that was heard. */
    double share(long durationMs) {
        return durationMs <= 0 ? 0 : Math.min(1, heardMs() / (double) durationMs);
    }

    /** Whether this moment of the song was heard already. */
    boolean covers(long positionMs) {
        for (long[] stretch : covered) {
            if (positionMs >= stretch[0] && positionMs < stretch[1]) {
                return true;
            }
        }
        return false;
    }

    /**
     * With one more part: the stretch from {@code fromMs} to {@code toMs} of the song and the words heard in it (times
     * in ms of the song). Where it was heard before, the new part's words take the place of the old ones.
     */
    HeardParts with(long fromMs, long toMs, List<List<Heard>> part, String partLanguage) {
        List<long[]> stretches = new ArrayList<>(covered);
        stretches.add(new long[] {fromMs, toMs});
        stretches.sort(Comparator.comparingLong(stretch -> stretch[0]));
        List<long[]> merged = new ArrayList<>();
        for (long[] stretch : stretches) {
            long[] last = merged.isEmpty() ? null : merged.get(merged.size() - 1);
            if (last != null && stretch[0] <= last[1]) {
                last[1] = Math.max(last[1], stretch[1]);
            } else {
                merged.add(new long[] {stretch[0], stretch[1]});
            }
        }
        List<List<Heard>> lines = new ArrayList<>();
        for (List<Heard> segment : segments) {
            List<Heard> kept = segment.stream().filter(word -> word.startMs() < fromMs || word.startMs() >= toMs).toList();
            if (!kept.isEmpty()) {
                lines.add(kept);
            }
        }
        for (List<Heard> segment : part) {
            if (!segment.isEmpty()) {
                lines.add(List.copyOf(segment));
            }
        }
        lines.sort(Comparator.comparingLong(segment -> segment.get(0).startMs()));
        return new HeardParts(language != null ? language : partLanguage, merged, lines);
    }

    /** As kept beside the lyrics. */
    String format() {
        StringBuilder text = new StringBuilder(HEADER).append('\n');
        text.append("language ").append(language == null ? "-" : language).append('\n');
        text.append("covered");
        for (long[] stretch : covered) {
            text.append(' ').append(stretch[0]).append('-').append(stretch[1]);
        }
        text.append('\n');
        for (List<Heard> segment : segments) {
            text.append("segment\n");
            for (Heard word : segment) {
                text.append(word.startMs()).append('\t').append(word.text()).append('\n');
            }
        }
        return text.toString();
    }

    /** What {@link #format()} wrote; nothing heard for anything else. */
    static HeardParts parse(String text) {
        String[] lines = text.split("\n");
        if (lines.length < 3 || !HEADER.equals(lines[0].strip()) || !lines[1].startsWith("language ")
                || !lines[2].startsWith("covered")) {
            return none();
        }
        try {
            String language = lines[1].substring("language ".length()).strip();
            List<long[]> covered = new ArrayList<>();
            for (String stretch : lines[2].substring("covered".length()).strip().split(" ")) {
                if (!stretch.isEmpty()) {
                    String[] ends = stretch.split("-");
                    covered.add(new long[] {Long.parseLong(ends[0]), Long.parseLong(ends[1])});
                }
            }
            List<List<Heard>> segments = new ArrayList<>();
            List<Heard> segment = null;
            for (int i = 3; i < lines.length; i++) {
                String line = lines[i];
                if ("segment".equals(line.strip())) {
                    segment = new ArrayList<>();
                    segments.add(segment);
                } else if (segment != null && line.contains("\t")) {
                    String[] fields = line.split("\t", 2);
                    segment.add(new Heard(fields[1], Long.parseLong(fields[0])));
                }
            }
            segments.removeIf(List::isEmpty);
            return new HeardParts("-".equals(language) ? null : language, covered,
                    segments.stream().map(List::copyOf).toList());
        } catch (RuntimeException e) {
            return none();
        }
    }
}
