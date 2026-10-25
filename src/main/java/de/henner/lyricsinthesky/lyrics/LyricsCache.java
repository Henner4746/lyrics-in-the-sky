package de.henner.lyricsinthesky.lyrics;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Lyrics found before, kept on disk: a song that was found once still has its lyrics when LRCLIB is busy or the
 * connection is gone. One small LRC file per song, the least recently played ones make room after
 * {@link #MAX_ENTRIES}.
 */
public final class LyricsCache {
    private static final Logger LOGGER = LoggerFactory.getLogger("lyricsinthesky");
    static final int MAX_ENTRIES = 1000;
    private static final String INSTRUMENTAL = "[instrumental]";

    private final Path dir;
    private final int maxEntries;

    public LyricsCache(Path dir) {
        this(dir, MAX_ENTRIES);
    }

    LyricsCache(Path dir, int maxEntries) {
        this.dir = dir;
        this.maxEntries = maxEntries;
    }

    /** Empty when the song was never found or its file cannot be read. */
    public Optional<Lyrics> get(String key) {
        Path file = dir.resolve(fileName(key));
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            // Played again: it is the last to make room.
            Files.setLastModifiedTime(file, FileTime.fromMillis(System.currentTimeMillis()));
            if (text.strip().equals(INSTRUMENTAL)) {
                return Optional.of(Lyrics.instrumental());
            }
            Lyrics lyrics = Lyrics.parseLrc(text);
            return lyrics.isEmpty() ? Optional.empty() : Optional.of(lyrics);
        } catch (IOException | RuntimeException e) {
            LOGGER.debug("Cached lyrics unreadable: {}", e.toString());
            return Optional.empty();
        }
    }

    public void put(String key, Lyrics lyrics) {
        if (!lyrics.isInstrumental() && lyrics.lrc().isBlank()) {
            return;
        }
        try {
            Files.createDirectories(dir);
            Path file = dir.resolve(fileName(key));
            // Written next to it first, so a crash never leaves half a file behind.
            Path partial = dir.resolve(fileName(key) + ".part");
            Files.writeString(partial, lyrics.isInstrumental() ? INSTRUMENTAL : lyrics.lrc(), StandardCharsets.UTF_8);
            Files.move(partial, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            prune();
        } catch (IOException e) {
            LOGGER.debug("Could not keep lyrics on disk: {}", e.toString());
        }
    }

    private void prune() throws IOException {
        List<Path> files;
        try (Stream<Path> listing = Files.list(dir)) {
            files = listing.filter(path -> path.getFileName().toString().endsWith(".lrc")).toList();
        }
        if (files.size() <= maxEntries) {
            return;
        }
        List<Path> oldestFirst = files.stream().sorted(Comparator.comparing(LyricsCache::lastModified)).toList();
        for (Path old : oldestFirst.subList(0, files.size() - maxEntries)) {
            Files.deleteIfExists(old);
        }
    }

    private static FileTime lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path);
        } catch (IOException e) {
            return FileTime.fromMillis(0);
        }
    }

    /** A hash of the song, so titles with any characters make valid file names. */
    static String fileName(String key) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, 16) + ".lrc";
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
