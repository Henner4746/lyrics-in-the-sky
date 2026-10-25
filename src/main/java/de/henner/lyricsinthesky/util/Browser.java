package de.henner.lyricsinthesky.util;

import java.io.IOException;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Opens a web page in the default browser. Minecraft's own helper moved between 26.2 and 26.3, and AWT is not
 * safe inside the game, so this asks the operating system directly.
 */
public final class Browser {
    private static final Logger LOGGER = LoggerFactory.getLogger("lyricsinthesky");

    private Browser() {}

    public static boolean open(String url) {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        ProcessBuilder builder;
        if (os.startsWith("windows")) {
            // No shell involved, so '&' in the URL needs no escaping.
            builder = new ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", url);
        } else if (os.contains("mac")) {
            builder = new ProcessBuilder("open", url);
        } else {
            builder = new ProcessBuilder("xdg-open", url);
        }
        try {
            builder.redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            return true;
        } catch (IOException e) {
            LOGGER.warn("Could not open the browser: {}", e.getMessage());
            return false;
        }
    }
}
