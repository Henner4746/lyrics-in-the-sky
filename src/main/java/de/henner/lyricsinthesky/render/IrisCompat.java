package de.henner.lyricsinthesky.render;

import java.lang.reflect.Method;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Asks Iris whether a shader pack draws the world. A shader pack paints its own image over everything drawn during
 * the world passes, so the lyrics then wait until it is done. Iris is optional and only reached through its public
 * API by name; without it this always says no.
 */
final class IrisCompat {
    private static final Logger LOGGER = LoggerFactory.getLogger("lyricsinthesky");
    private static final Object API;
    private static final Method SHADER_PACK_IN_USE;

    static {
        Object api = null;
        Method inUse = null;
        if (FabricLoader.getInstance().isModLoaded("iris")) {
            try {
                Class<?> type = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
                api = type.getMethod("getInstance").invoke(null);
                inUse = type.getMethod("isShaderPackInUse");
            } catch (ReflectiveOperationException | LinkageError e) {
                LOGGER.warn("Iris is installed, but its API could not be reached; shader packs may hide the lyrics: {}",
                        e.toString());
                api = null;
                inUse = null;
            }
        }
        API = api;
        SHADER_PACK_IN_USE = inUse;
    }

    private IrisCompat() {}

    static boolean shadersInUse() {
        if (SHADER_PACK_IN_USE == null) {
            return false;
        }
        try {
            return (boolean) SHADER_PACK_IN_USE.invoke(API);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }
}
