package de.henner.lyricsinthesky.render;

/**
 * Where the now-playing bar sits. It is kept as an edge (the top, or the bottom above the hotbar) and a distance from
 * it, so it stays in the same place relative to the hotbar at any window size and GUI scale.
 */
public final class HudLayout {
    /** Gap above the bar at the top. */
    static final int TOP = 5;
    /**
     * Bottom edge of the bar above the bottom of the screen: clear of the hotbar, health and armor, the held item's
     * name and action-bar messages such as "Now playing" from a jukebox.
     */
    static final int BOTTOM = 76;
    /** Dragged this close to the middle or to its usual height, the bar snaps there. */
    static final int SNAP = 6;
    private static final int MARGIN = 2;

    /** Top-left corner on screen, in GUI pixels. */
    public record Position(int x, int y) {}

    /** What the config keeps: the edge and the distance from the usual place. */
    public record Spot(String anchor, int offsetX, int offsetY) {}

    private HudLayout() {}

    /** Top-left corner of a bar of {@code width}×{@code height}; always fully on screen. */
    public static Position place(String anchor, int offsetX, int offsetY, int guiWidth, int guiHeight, int width,
            int height) {
        int x = guiWidth / 2 + offsetX - width / 2;
        int y = "bottom".equals(anchor) ? guiHeight - BOTTOM - offsetY - height : TOP + offsetY;
        return new Position(clamp(x, MARGIN, guiWidth - width - MARGIN), clamp(y, MARGIN, guiHeight - height - MARGIN));
    }

    /**
     * The spot for a bar dragged to ({@code x}, {@code y}): the nearer edge and the distance from it, snapped to the
     * middle and to the usual height when close.
     */
    public static Spot spotFor(int x, int y, int guiWidth, int guiHeight, int width, int height) {
        int offsetX = x + width / 2 - guiWidth / 2;
        boolean bottom = y + height / 2 > guiHeight / 2;
        int offsetY = bottom ? guiHeight - BOTTOM - (y + height) : y - TOP;
        return new Spot(bottom ? "bottom" : "top", Math.abs(offsetX) <= SNAP ? 0 : offsetX,
                Math.abs(offsetY) <= SNAP ? 0 : offsetY);
    }

    private static int clamp(int value, int min, int max) {
        return max < min ? min : Math.max(min, Math.min(max, value));
    }
}
