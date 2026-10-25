package de.henner.lyricsinthesky.render;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class HudLayoutTest {
    @Test
    void sitsAtTheTopOrAboveTheHotbar() {
        HudLayout.Position top = HudLayout.place("top", 0, 0, 480, 270, 200, 18);
        assertEquals(new HudLayout.Position(140, HudLayout.TOP), top);
        HudLayout.Position bottom = HudLayout.place("bottom", 0, 0, 480, 270, 200, 18);
        // Its lower edge clear of hotbar, health, the held item's name and action-bar messages.
        assertEquals(270 - HudLayout.BOTTOM, bottom.y() + 18);
    }

    @Test
    void staysOnScreenWhereverItWasMoved() {
        HudLayout.Position far = HudLayout.place("bottom", 900, -900, 480, 270, 200, 18);
        assertEquals(480 - 200 - 2, far.x());
        assertEquals(270 - 18 - 2, far.y());
        HudLayout.Position tooWide = HudLayout.place("top", 0, 0, 120, 270, 300, 18);
        assertEquals(2, tooWide.x());
    }

    @Test
    void draggingPicksTheNearerEdgeAndSnaps() {
        // Dropped in the upper half, 3 px right of the middle and 4 px below its usual height: snapped to both.
        HudLayout.Spot top = HudLayout.spotFor(143, HudLayout.TOP + 4, 480, 270, 200, 18);
        assertEquals(new HudLayout.Spot("top", 0, 0), top);
        // Dropped low and to the left: kept above the hotbar, 60 px left and 30 px nearer to the hotbar.
        int y = 270 - HudLayout.BOTTOM - 18 + 30;
        HudLayout.Spot bottom = HudLayout.spotFor(80, y, 480, 270, 200, 18);
        assertEquals(new HudLayout.Spot("bottom", -60, -30), bottom);
    }

    @Test
    void aDroppedBarComesBackExactlyWhereItWasDropped() {
        HudLayout.Spot spot = HudLayout.spotFor(37, 190, 480, 270, 160, 31);
        HudLayout.Position back = HudLayout.place(spot.anchor(), spot.offsetX(), spot.offsetY(), 480, 270, 160, 31);
        assertEquals(new HudLayout.Position(37, 190), back);
    }
}
