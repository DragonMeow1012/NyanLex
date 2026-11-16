package com.dragonmeow.nyanlex.translate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class OverlaySpaceTest {

    @Test
    void advanceFollowsTheCodePointOffset() {
        assertEquals(0, OverlaySpace.advance(0xD0000));
        assertEquals(-92, OverlaySpace.advance(0xCFFA4));
        assertEquals(83, OverlaySpace.advance(0xD0053));
        assertEquals(-4096, OverlaySpace.advance(0xCF000));
        assertEquals(4096, OverlaySpace.advance(0xD1000));
        assertEquals(-16384, OverlaySpace.advance(0xC0001));
        assertEquals(16384, OverlaySpace.advance(0xDFFFF));
        assertEquals(OverlaySpace.UNDEFINED, OverlaySpace.advance(0xCEFFF));
        assertEquals(OverlaySpace.UNDEFINED, OverlaySpace.advance(0xD1001));
    }

    @Test
    void movesAreTheWholeTwoPlanes() {
        assertTrue(OverlaySpace.isMove(0xC0000));
        assertTrue(OverlaySpace.isMove(0xDFFFF));
        assertFalse(OverlaySpace.isMove(0xBFFFF));
        assertFalse(OverlaySpace.isMove(0xE000));
        assertFalse(OverlaySpace.isMove('A'));
    }

    @Test
    void spellsAnyDistanceAndRoundTrips() {
        assertEquals("", OverlaySpace.move(0));
        for (int px : new int[] {1, -1, 92, -184, 4096, -4096, 4097, -5000, 12345, -20000}) {
            String s = OverlaySpace.move(px);
            int sum = 0;
            for (int i = 0; i < s.length(); ) {
                int cp = s.codePointAt(i);
                i += Character.charCount(cp);
                assertTrue(OverlaySpace.advance(cp) != OverlaySpace.UNDEFINED, "px " + px);
                sum += OverlaySpace.advance(cp);
            }
            assertEquals(px, sum, "px " + px);
        }
        String small = OverlaySpace.move(-92);
        assertEquals(1, small.codePointCount(0, small.length()));
    }
}
