package com.dragonmeow.mctranslator;

import com.dragonmeow.mctranslator.style.ColorProfile;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ColorProfileTest {

    private static final int RED = 0xFF0000;
    private static final int BLUE = 0x0000FF;

    @Test
    void emptyHasNoColourOrStyle() {
        ColorProfile p = ColorProfile.empty();
        assertFalse(p.hasAnyColor());
        assertFalse(p.hasAnyStyle());
        assertEquals(ColorProfile.NO_COLOR, p.colorAt(0, 5));
    }

    @Test
    void detectsColourPresence() {
        ColorProfile noColor = new ColorProfile(
                new int[]{ColorProfile.NO_COLOR, ColorProfile.NO_COLOR}, false, false, false, false, false);
        assertFalse(noColor.hasAnyColor());

        ColorProfile withColor = new ColorProfile(new int[]{ColorProfile.NO_COLOR, RED}, false, false, false, false, false);
        assertTrue(withColor.hasAnyColor());
        assertTrue(withColor.hasAnyStyle());
    }

    @Test
    void styleWithoutColourStillCountsAsStyle() {
        ColorProfile bold = new ColorProfile(new int[]{ColorProfile.NO_COLOR}, true, false, false, false, false);
        assertFalse(bold.hasAnyColor());
        assertTrue(bold.hasAnyStyle());
    }

    @Test
    void colorAtMapsIndexProportionally() {
        ColorProfile p = new ColorProfile(new int[]{RED, BLUE}, false, false, false, false, false);
        // translatedLen 4: indices 0,1 -> first half (RED), 2,3 -> second half (BLUE)
        assertEquals(RED, p.colorAt(0, 4));
        assertEquals(RED, p.colorAt(1, 4));
        assertEquals(BLUE, p.colorAt(2, 4));
        assertEquals(BLUE, p.colorAt(3, 4));
    }

    @Test
    void colorAtClampsOutOfRange() {
        ColorProfile p = new ColorProfile(new int[]{RED, BLUE}, false, false, false, false, false);
        // Defensive: index beyond translatedLen must not throw.
        assertEquals(BLUE, p.colorAt(99, 4));
        assertEquals(ColorProfile.NO_COLOR, p.colorAt(0, 0));
    }
}
