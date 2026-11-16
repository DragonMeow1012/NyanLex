package com.dragonmeow.nyanlex.translate;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure rules behind "a custom font borrows the default font's glyphs for characters it lacks". */
class GlyphBorrowPolicyTest {

    private static final float NONE = Float.NaN;

    /** Inline fake: tops by code point, NaN for everything not listed. */
    private static GlyphBorrowPolicy.TopLookup tops(Object... codePointAndTop) {
        java.util.Map<Integer, Float> map = new java.util.HashMap<>();
        for (int i = 0; i < codePointAndTop.length; i += 2) {
            Object key = codePointAndTop[i];
            int codePoint = key instanceof Character ? (Character) key : (Integer) key;
            map.put(codePoint, ((Number) codePointAndTop[i + 1]).floatValue());
        }
        return cp -> map.getOrDefault(cp, NONE);
    }

    @Test
    void vanillaFontsAreNeverBorrowedInto() {
        assertTrue(GlyphBorrowPolicy.isVanillaFont("minecraft", "default"));
        assertTrue(GlyphBorrowPolicy.isVanillaFont("minecraft", "uniform"));
        assertTrue(GlyphBorrowPolicy.isVanillaFont("minecraft", "alt"));
        assertTrue(GlyphBorrowPolicy.isVanillaFont("minecraft", "illageralt"));
        assertTrue(GlyphBorrowPolicy.isVanillaFont("minecraft", "missing"));
        assertTrue(GlyphBorrowPolicy.isVanillaFont("minecraft", "include/default"));
        assertTrue(GlyphBorrowPolicy.isVanillaFont("minecraft", "include/space"));
        assertTrue(GlyphBorrowPolicy.isVanillaFont("minecraft", "include/unifont"));
    }

    @Test
    void customFontsAreRecognisedByPathAndByNamespace() {
        assertFalse(GlyphBorrowPolicy.isVanillaFont("minecraft", "hud/dialogue/text/x/body_0"));
        assertFalse(GlyphBorrowPolicy.isVanillaFont("minecraft", "space"));
        assertFalse(GlyphBorrowPolicy.isVanillaFont("minecraft", "defaultx"));
        assertFalse(GlyphBorrowPolicy.isVanillaFont("minecraft", "includes/foo"));
        assertFalse(GlyphBorrowPolicy.isVanillaFont("other", "default"));
        assertFalse(GlyphBorrowPolicy.isVanillaFont("other", "include/default"));
    }

    @Test
    void unknownIdIsTreatedAsVanillaSoNothingIsTouched() {
        assertTrue(GlyphBorrowPolicy.isVanillaFont(null, "default"));
        assertTrue(GlyphBorrowPolicy.isVanillaFont("minecraft", null));
    }

    @Test
    void onlyTheDefaultFontIsTheDonor() {
        assertTrue(GlyphBorrowPolicy.isDefaultFont("minecraft", "default"));
        assertFalse(GlyphBorrowPolicy.isDefaultFont("minecraft", "uniform"));
        assertFalse(GlyphBorrowPolicy.isDefaultFont("other", "default"));
    }

    @Test
    void privateUseAndInvalidCodePointsAreNeverBorrowed() {
        assertTrue(GlyphBorrowPolicy.isBorrowable('A'));
        assertTrue(GlyphBorrowPolicy.isBorrowable(0x4E2D));   // CJK ideograph
        assertTrue(GlyphBorrowPolicy.isBorrowable(0x3042));   // hiragana
        assertTrue(GlyphBorrowPolicy.isBorrowable(0xFF01));   // fullwidth punctuation
        assertTrue(GlyphBorrowPolicy.isBorrowable(0x20000));  // CJK extension B
        assertFalse(GlyphBorrowPolicy.isBorrowable(0));
        assertFalse(GlyphBorrowPolicy.isBorrowable(-1));
        assertFalse(GlyphBorrowPolicy.isBorrowable(0xD800));
        assertFalse(GlyphBorrowPolicy.isBorrowable(0xE000));
        assertFalse(GlyphBorrowPolicy.isBorrowable(0xF8FF));
        assertFalse(GlyphBorrowPolicy.isBorrowable(0xF0000));
        assertFalse(GlyphBorrowPolicy.isBorrowable(0x10FFFF));
        assertFalse(GlyphBorrowPolicy.isBorrowable(0x110000));
    }

    @Test
    void shiftIsCustomTopMinusDefaultTopOfLetterA() {
        // A custom font whose letters sit 27 px higher than the default font's.
        float shift = GlyphBorrowPolicy.computeShift(tops('A', -27f), tops('A', 0f));
        assertEquals(-27.0F, shift);
        // A custom font that sits lower.
        assertEquals(14.0F, GlyphBorrowPolicy.computeShift(tops('A', 14f), tops('A', 0f)));
        // Default 'A' is not at zero: the difference is what matters.
        assertEquals(-12.0F, GlyphBorrowPolicy.computeShift(tops('A', -5f), tops('A', 7f)));
    }

    @Test
    void sameVerticalLayoutNeedsNoShift() {
        assertEquals(0.0F, GlyphBorrowPolicy.computeShift(tops('A', 0f), tops('A', 0f)));
    }

    @Test
    void fallsBackToLowercaseThenDigitWhenCapitalAIsMissing() {
        // No 'A' in the custom font: 'a' is the reference.
        assertEquals(-10.0F, GlyphBorrowPolicy.computeShift(tops('a', -8f, '0', 99f), tops('A', 0f, 'a', 2f, '0', 0f)));
        // Only digits: '0' is the reference.
        assertEquals(-3.0F, GlyphBorrowPolicy.computeShift(tops('0', -3f), tops('A', 0f, 'a', 2f, '0', 0f)));
        // The donor lacks 'A' but both have 'a'.
        assertEquals(5.0F, GlyphBorrowPolicy.computeShift(tops('A', 100f, 'a', 7f), tops('a', 2f)));
    }

    @Test
    void noReferenceGlyphMeansNoShift() {
        assertEquals(0.0F, GlyphBorrowPolicy.computeShift(tops(0x4E2D, 5f), tops('A', 0f)));
        assertEquals(0.0F, GlyphBorrowPolicy.computeShift(tops(), tops('A', 0f)));
        assertEquals(0.0F, GlyphBorrowPolicy.computeShift(tops('A', 3f), tops()));
        assertEquals(0.0F, GlyphBorrowPolicy.computeShift(null, tops('A', 0f)));
        assertEquals(0.0F, GlyphBorrowPolicy.computeShift(tops('A', 3f), null));
    }

    @Test
    void nonFiniteTopsAreIgnored() {
        assertEquals(0.0F, GlyphBorrowPolicy.computeShift(tops('A', Float.POSITIVE_INFINITY), tops('A', 0f)));
        assertEquals(-4.0F, GlyphBorrowPolicy.computeShift(tops('A', Float.NaN, 'a', -2f), tops('A', 0f, 'a', 2f)));
    }

    @Test
    void ownLookupIsConsultedBeforeTheDonorAndStopsAtTheFirstUsableReference() {
        List<String> calls = new ArrayList<>();
        GlyphBorrowPolicy.TopLookup own = cp -> {
            calls.add("own" + (char) cp);
            return cp == 'a' ? -1f : NONE;
        };
        GlyphBorrowPolicy.TopLookup donor = cp -> {
            calls.add("donor" + (char) cp);
            return 0f;
        };
        assertEquals(-1.0F, GlyphBorrowPolicy.computeShift(own, donor));
        // 'A' is missing in the custom font, so the donor is not asked about it; 'a' resolves and ends the search.
        assertEquals(List.of("ownA", "owna", "donora"), calls);
    }

    @Test
    void shiftIsMeasuredOncePerFontUntilCleared() {
        GlyphBorrowPolicy.ShiftCache cache = new GlyphBorrowPolicy.ShiftCache();
        int[] ownCalls = {0};
        GlyphBorrowPolicy.TopLookup own = cp -> {
            ownCalls[0]++;
            return -27f;
        };
        GlyphBorrowPolicy.TopLookup donor = tops('A', 0f);

        assertEquals(-27.0F, cache.shiftFor("minecraft:hud/a", own, donor));
        assertEquals(-27.0F, cache.shiftFor("minecraft:hud/a", own, donor));
        assertEquals(1, ownCalls[0], "second request must come from the cache");
        assertEquals(1, cache.size());

        // Another font id is measured separately.
        assertEquals(0.0F, cache.shiftFor("minecraft:hud/b", tops(), donor));
        assertEquals(2, cache.size());
    }

    @Test
    void clearingTheCacheForcesRemeasuring() {
        GlyphBorrowPolicy.ShiftCache cache = new GlyphBorrowPolicy.ShiftCache();
        GlyphBorrowPolicy.TopLookup donor = tops('A', 0f);

        assertEquals(-5.0F, cache.shiftFor("f:x", tops('A', -5f), donor));
        // After a resource reload the same id may lay out differently.
        cache.clear();
        assertEquals(0, cache.size());
        assertEquals(-9.0F, cache.shiftFor("f:x", tops('A', -9f), donor));
    }

    @Test
    void zeroShiftIsCachedToo() {
        GlyphBorrowPolicy.ShiftCache cache = new GlyphBorrowPolicy.ShiftCache();
        int[] ownCalls = {0};
        GlyphBorrowPolicy.TopLookup own = cp -> {
            ownCalls[0]++;
            return NONE;
        };
        assertEquals(0.0F, cache.shiftFor("f:none", own, tops('A', 0f)));
        int afterFirst = ownCalls[0];
        assertEquals(0.0F, cache.shiftFor("f:none", own, tops('A', 0f)));
        assertEquals(afterFirst, ownCalls[0]);
    }
}
