package com.dragonmeow.nyanlex;

import com.dragonmeow.nyanlex.style.ColorProfile;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ColorProfileTest {

    private static final int RED = 0xFF0000;
    private static final int BLUE = 0x0000FF;
    private static final int N = ColorProfile.NO_COLOR;

    @Test
    void emptyHasNoColourOrStyle() {
        ColorProfile p = ColorProfile.empty();
        assertFalse(p.hasAnyColor());
        assertFalse(p.hasAnyStyle());
        assertEquals(ColorProfile.NO_COLOR, p.dominantColor());
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
    void dominantColourIsTheMostCommonExplicitColour() {
        ColorProfile p = new ColorProfile(new int[]{RED, RED, BLUE, N}, false, false, false, false, false);
        assertEquals(RED, p.dominantColor());
    }

    @Test
    void distinctColorCountCountsExplicitColoursOnly() {
        assertEquals(0, new ColorProfile(new int[]{N, N}, false, false, false, false, false).distinctColorCount());
        assertEquals(1, new ColorProfile(new int[]{RED, RED, N}, false, false, false, false, false).distinctColorCount());
        assertEquals(2, new ColorProfile(new int[]{RED, BLUE, N}, false, false, false, false, false).distinctColorCount());
    }
}
