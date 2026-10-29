package de.henner.lyricsinthesky.render;

import com.mojang.blaze3d.platform.NativeImage;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The custom skies' textures: painted by {@link SkyArt} on a background thread at low priority, so the game never
 * waits, and handed to the graphics card on the render thread the next time they are asked for. Until then the
 * asking code gets {@code null} and draws without them.
 */
final class SkyTextures {
    private static final Logger LOGGER = LoggerFactory.getLogger("lyricsinthesky");
    private static final ExecutorService PAINTER = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "LyricsInTheSky-SkyPainter");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        return thread;
    });

    /** Painted and waiting for the render thread: pixels with their size. */
    private record Painted(int[] pixels, int w, int h) {}

    private final Map<String, Painted> painted = new ConcurrentHashMap<>();
    private final Set<String> asked = ConcurrentHashMap.newKeySet();
    private final Map<String, Identifier> uploaded = new HashMap<>();
    private final Map<SkyArt.Sprite, int[]> spritePixels = new EnumMap<>(SkyArt.Sprite.class);

    /** A shape to draw on top of the panorama, or {@code null} while it is still being painted. */
    Identifier sprite(SkyArt.Sprite sprite) {
        paintSprites();
        return get("sprite/" + sprite.name().toLowerCase(Locale.ROOT));
    }

    /** The sky all around for a style, or {@code null} while it is still being painted. */
    Identifier panorama(String style) {
        String key = "panorama/" + style;
        if (asked.add(key)) {
            PAINTER.submit(() -> paint(key, () -> new Painted(SkyArt.panorama(style), SkyArt.PANORAMA_W, SkyArt.PANORAMA_H)));
        }
        return get(key);
    }

    /** The menu's small picture of a style, or {@code null} while the pictures are still being painted. */
    Identifier postcard(String style) {
        paintSprites();
        String key = "postcard/" + style;
        if (asked.add(key)) {
            PAINTER.submit(() -> paint(key, () -> {
                Map<SkyArt.Sprite, int[]> sprites;
                synchronized (spritePixels) {
                    sprites = new EnumMap<>(spritePixels);
                }
                return new Painted(SkyArt.postcard(style, sprites), SkyArt.POSTCARD_W, SkyArt.POSTCARD_H);
            }));
        }
        return get(key);
    }

    /** Frees a style's panorama once it is no longer shown; it is painted again when needed. */
    void releasePanorama(String style) {
        String key = "panorama/" + style;
        Identifier id = uploaded.remove(key);
        if (id != null) {
            Minecraft.getInstance().getTextureManager().release(id);
        }
        painted.remove(key);
        asked.remove(key);
    }

    private void paintSprites() {
        if (!asked.add("sprites")) {
            return;
        }
        for (SkyArt.Sprite sprite : SkyArt.Sprite.values()) {
            String key = "sprite/" + sprite.name().toLowerCase(Locale.ROOT);
            PAINTER.submit(() -> paint(key, () -> {
                int[] pixels = SkyArt.sprite(sprite);
                synchronized (spritePixels) {
                    spritePixels.put(sprite, pixels);
                }
                return new Painted(pixels, sprite.w, sprite.h);
            }));
        }
    }

    private void paint(String key, Supplier<Painted> painter) {
        try {
            long started = System.nanoTime();
            painted.put(key, painter.get());
            LOGGER.debug("Painted sky texture {} in {} ms", key, (System.nanoTime() - started) / 1_000_000);
        } catch (RuntimeException e) {
            LOGGER.warn("Could not paint sky texture {}: {}", key, e.toString());
        }
    }

    /** The texture once it is on the graphics card; uploads it when it was just painted. Render thread only. */
    private Identifier get(String key) {
        Identifier id = uploaded.get(key);
        if (id != null) {
            return id;
        }
        Painted ready = painted.remove(key);
        if (ready == null) {
            return null;
        }
        NativeImage image = new NativeImage(ready.w(), ready.h(), false);
        for (int y = 0; y < ready.h(); y++) {
            for (int x = 0; x < ready.w(); x++) {
                image.setPixel(x, y, ready.pixels()[y * ready.w() + x]);
            }
        }
        id = Identifier.fromNamespaceAndPath("lyricsinthesky", "sky/" + key);
        Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(() -> "Lyrics in the Sky " + key, image));
        uploaded.put(key, id);
        return id;
    }
}
