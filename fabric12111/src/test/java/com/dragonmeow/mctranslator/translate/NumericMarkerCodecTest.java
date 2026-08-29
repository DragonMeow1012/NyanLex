package com.dragonmeow.mctranslator.translate;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class NumericMarkerCodecTest {

    @Test
    void restoresAdjacentKnownMarkersButNeverMarkerSubstrings() {
        Map<String, String> slots = new LinkedHashMap<>();
        slots.put("30001", "⟦MT0⟧");
        slots.put("30002", "⟦MT1⟧");

        assertEquals("A⟦MT0⟧⟦MT1⟧B",
                NumericMarkerCodec.restoreExactlyOnce("A3000130002B", slots));
        assertNull(NumericMarkerCodec.restoreExactlyOnce("A130001 30002B", slots));
        assertNull(NumericMarkerCodec.restoreExactlyOnce("A300010 30002B", slots));
        assertNull(NumericMarkerCodec.restoreExactlyOnce("A30001 30001 30002B", slots));
    }

    @Test
    void extractsAnchorsAdjacentToProtectedSlotsWithoutSubstringAcceptance() {
        String adjacent = "7000170005alpha7000270003beta7000670004";
        assertEquals(List.of("70005alpha", "beta70006"),
                NumericMarkerCodec.extractAnchored(adjacent, 2, 70001, 6));

        assertNull(NumericMarkerCodec.extractAnchored(
                "170001alpha7000270003beta70004", 2, 70001, 4));
        assertNull(NumericMarkerCodec.extractAnchored(
                "700010alpha7000270003beta70004", 2, 70001, 4));
        assertNull(NumericMarkerCodec.extractAnchored(
                "70001alpha70003beta7000270004", 2, 70001, 4));
    }
}
