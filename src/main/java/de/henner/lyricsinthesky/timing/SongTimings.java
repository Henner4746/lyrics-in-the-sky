package de.henner.lyricsinthesky.timing;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.OptionalInt;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Timing corrections per song (by {@link de.henner.lyricsinthesky.lyrics.SongId}): the player's own, kept in
 * {@code config/lyricsinthesky/song-timing.json}, and the ones many players agreed on, from the timing tracker. The
 * player's own always wins.
 */
public final class SongTimings {
    private static final Logger LOGGER = LoggerFactory.getLogger("lyricsinthesky");
    private static final Gson GSON = new Gson();
    /** Same range as the global timing slider and the tracker accept. */
    public static final int MAX_OFFSET_MS = 5_000;

    /**
     * What the tracker knows about a song: how many players corrected it, their common correction if any, and how many
     * of them measured it with Hoergenau.
     */
    public record Shared(int players, Integer offsetMs, int measured) {
        public Shared(int players, Integer offsetMs) {
            this(players, offsetMs, 0);
        }
    }

    private final Path file;
    private final Map<String, Integer> own = new ConcurrentHashMap<>();
    private final Map<String, Shared> shared = new ConcurrentHashMap<>();

    public SongTimings(Path file) {
        this.file = file;
        load();
    }

    /** The correction to apply: the player's own, else the one many players agreed on, else none. */
    public int offsetFor(String songId) {
        Integer mine = own.get(songId);
        if (mine != null) {
            return mine;
        }
        Shared common = shared.get(songId);
        return common != null && common.offsetMs() != null ? common.offsetMs() : 0;
    }

    public OptionalInt own(String songId) {
        Integer mine = own.get(songId);
        return mine == null ? OptionalInt.empty() : OptionalInt.of(mine);
    }

    public Shared shared(String songId) {
        return shared.get(songId);
    }

    /** Sets the player's own correction for a song; {@code null} forgets it, so the shared one applies again. */
    public void setOwn(String songId, Integer offsetMs) {
        if (offsetMs == null) {
            own.remove(songId);
        } else {
            own.put(songId, Math.max(-MAX_OFFSET_MS, Math.min(MAX_OFFSET_MS, offsetMs)));
        }
        save();
    }

    public void putShared(String songId, Shared common) {
        shared.put(songId, common);
    }

    private void load() {
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            Map<String, Integer> read = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8),
                    new TypeToken<Map<String, Integer>>() {}.getType());
            if (read != null) {
                read.forEach((id, offset) -> {
                    if (id != null && offset != null) {
                        own.put(id, offset);
                    }
                });
            }
        } catch (IOException | JsonParseException e) {
            LOGGER.warn("Could not read {}: {}", file, e.getMessage());
        }
    }

    private synchronized void save() {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(new TreeMap<>(own)), StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOGGER.warn("Could not save {}: {}", file, e.getMessage());
        }
    }
}
