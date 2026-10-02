package com.dragonmeow.nyanslate.style;

import org.junit.jupiter.api.Test;

import java.util.List;

import static com.dragonmeow.nyanslate.style.ColorShapeMatcher.Category.DIFFERENT_SEMANTIC;
import static com.dragonmeow.nyanslate.style.ColorShapeMatcher.Category.GRADIENT_REMAP;
import static com.dragonmeow.nyanslate.style.ColorShapeMatcher.Category.SAME_STRUCTURE;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 2026-10-02 colour-format-only sharing: {@link ColorShapeMatcher#classify} decides
 * whether two colourings of the SAME plain text can reuse one cached translation. Real
 * examples this guards: a Hypixel rarity line ("MYTHIC DUNGEON BOW") sometimes a solid
 * colour, sometimes a per-letter rainbow at a different animation frame (rule b); a
 * Seller/Buyer/Bidder rank badge changing colour between auctions (handled upstream by
 * slotting the badge out of the key entirely — not this class's concern, but the
 * "same cut points, different RGB" half of rule a is the same mechanism); and a line
 * where only ONE word out of several is recoloured, which must NOT be guessed (rule c).
 */
class ColorShapeMatcherTest {

    private static ColorShapeMatcher.ColorRun solid(int start, int end, int color) {
        int[] colors = new int[end - start];
        java.util.Arrays.fill(colors, color);
        return new ColorShapeMatcher.ColorRun(start, end, colors);
    }

    private static ColorShapeMatcher.ColorRun gradient(int start, int end, int firstColor, int step) {
        int[] colors = new int[end - start];
        for (int i = 0; i < colors.length; i++) colors[i] = firstColor + i * step;
        return new ColorShapeMatcher.ColorRun(start, end, colors);
    }

    // ---- (a) SAME_STRUCTURE: identical cut points, only RGB values differ ----

    @Test
    void samePositiveSingleSolidRunDifferentColourIsSameStructure() {
        // "MYTHIC DUNGEON BOW" rendered gold once, then white: one flat run either way.
        List<ColorShapeMatcher.ColorRun> previous = List.of(solid(0, 19, 0xFFAA00));
        List<ColorShapeMatcher.ColorRun> current = List.of(solid(0, 19, 0xFFFFFF));
        assertEquals(SAME_STRUCTURE, ColorShapeMatcher.classify(previous, current, 19));
    }

    @Test
    void samePositiveMultiRunMatchingCutPointsDifferentColoursIsSameStructure() {
        // "Seller: " (CS0) + "Alice" (CS1) rendered with two different colour PAIRS.
        List<ColorShapeMatcher.ColorRun> previous = List.of(
                solid(0, 8, 0xFFFFFF), solid(8, 13, 0x55FFFF));
        List<ColorShapeMatcher.ColorRun> current = List.of(
                solid(0, 8, 0xAAAAAA), solid(8, 13, 0xFFFF55));
        assertEquals(SAME_STRUCTURE, ColorShapeMatcher.classify(previous, current, 13));
    }

    @Test
    void sameNegativeIdenticalCutPointsButOneSideGradientIsNotSameStructure() {
        // Same single cut (the whole text, 0 internal boundary) but one side is a rainbow:
        // must NOT be (a) even though "boundaries" trivially agree.
        List<ColorShapeMatcher.ColorRun> previous = List.of(solid(0, 6, 0xFFAA00));
        List<ColorShapeMatcher.ColorRun> current = List.of(gradient(0, 6, 0xFF0000, 0x001133));
        assertEquals(GRADIENT_REMAP, ColorShapeMatcher.classify(previous, current, 6),
                "a gradient side must never be classified as a plain RGB swap");
    }

    @Test
    void sameNegativeDifferentCutPointsIsNotSameStructure() {
        List<ColorShapeMatcher.ColorRun> previous = List.of(solid(0, 19, 0xFFAA00));
        List<ColorShapeMatcher.ColorRun> current = List.of(
                solid(0, 6, 0xFFAA00), solid(6, 19, 0xFFFFFF));
        assertEquals(DIFFERENT_SEMANTIC, ColorShapeMatcher.classify(previous, current, 19));
    }

    // ---- (b) GRADIENT_REMAP: solid vs gradient (or gradient vs different gradient) ----

    @Test
    void gradientPositiveSolidVersusRainbowWholePhraseIsGradientRemap() {
        // The exact harness scenario: item A's "MYTHIC DUNGEON BOW" is one solid colour;
        // item B's is a per-character rainbow. Same 19-character plain text throughout.
        List<ColorShapeMatcher.ColorRun> solidWhole = List.of(solid(0, 19, 0xFFAA00));
        int[] rainbow = new int[19];
        for (int i = 0; i < rainbow.length; i++) rainbow[i] = 0xFF0000 + i * 0x001133;
        List<ColorShapeMatcher.ColorRun> rainbowWhole =
                List.of(new ColorShapeMatcher.ColorRun(0, 19, rainbow));
        assertEquals(GRADIENT_REMAP, ColorShapeMatcher.classify(solidWhole, rainbowWhole, 19));
        assertEquals(GRADIENT_REMAP, ColorShapeMatcher.classify(rainbowWhole, solidWhole, 19),
                "the rule is symmetric regardless of which render came first");
    }

    @Test
    void gradientPositiveTwoDifferentRainbowSequencesAreGradientRemap() {
        List<ColorShapeMatcher.ColorRun> previous = List.of(gradient(0, 6, 0xFF0000, 0x001133));
        List<ColorShapeMatcher.ColorRun> current = List.of(gradient(0, 6, 0x0000FF, 0x002211));
        assertEquals(GRADIENT_REMAP, ColorShapeMatcher.classify(previous, current, 6));
    }

    @Test
    void gradientNegativeTwoSolidWholeSpanRunsAreSameStructureNotGradient() {
        List<ColorShapeMatcher.ColorRun> previous = List.of(solid(0, 19, 0xFFAA00));
        List<ColorShapeMatcher.ColorRun> current = List.of(solid(0, 19, 0xFFFFFF));
        assertEquals(SAME_STRUCTURE, ColorShapeMatcher.classify(previous, current, 19),
                "two solid whole-span runs must stay (a), never be miscounted as (b)");
    }

    @Test
    void gradientNegativeGradientOnOneSideButDifferentCutPointsIsDifferentSemantic() {
        // The gradient covers only PART of the text on one side: not the "whole phrase is
        // one continuous treatment" shape rule (b) requires, so this is NOT safely reusable.
        List<ColorShapeMatcher.ColorRun> previous = List.of(solid(0, 19, 0xFFAA00));
        List<ColorShapeMatcher.ColorRun> current = List.of(
                gradient(0, 6, 0xFF0000, 0x001133), solid(6, 19, 0xFFAA00));
        assertEquals(DIFFERENT_SEMANTIC, ColorShapeMatcher.classify(previous, current, 19));
    }

    // ---- (c) DIFFERENT_SEMANTIC: cut points differ some other way ----

    @Test
    void differentPositiveOnlyOneWordRecolouredIsDifferentSemantic() {
        // "EPIC DUNGEON GLOVES" all one colour, then "EPIC" alone changes colour while the
        // rest stays together: a genuinely different (not gradient) cut appeared.
        List<ColorShapeMatcher.ColorRun> previous = List.of(solid(0, 20, 0xFFFFFF));
        List<ColorShapeMatcher.ColorRun> current = List.of(
                solid(0, 4, 0xFF5555), solid(4, 20, 0xFFFFFF));
        assertEquals(DIFFERENT_SEMANTIC, ColorShapeMatcher.classify(previous, current, 20));
    }

    @Test
    void differentPositiveTwoDiscreteColourBlocksNeverMatchingIsDifferentSemantic() {
        // Mirrors RarityLineComposerTest#coreSplitAcrossTwoColourRunsIsRejected: "EPIC" in
        // one colour, " DUNGEON GLOVES" in another, compared against a DIFFERENT 2-block
        // split (word boundary moved) — a genuinely different cut, not a gradient.
        List<ColorShapeMatcher.ColorRun> previous = List.of(
                solid(0, 4, 0xFF5555), solid(4, 20, 0x5555FF));
        List<ColorShapeMatcher.ColorRun> current = List.of(
                solid(0, 11, 0xFF5555), solid(11, 20, 0x5555FF));
        assertEquals(DIFFERENT_SEMANTIC, ColorShapeMatcher.classify(previous, current, 20));
    }

    @Test
    void differentNegativeSameTwoBlockCutWithNewColoursIsSameStructureNotDifferent() {
        List<ColorShapeMatcher.ColorRun> previous = List.of(
                solid(0, 4, 0xFF5555), solid(4, 20, 0x5555FF));
        List<ColorShapeMatcher.ColorRun> current = List.of(
                solid(0, 4, 0x55FF55), solid(4, 20, 0xFFFF55));
        assertEquals(SAME_STRUCTURE, ColorShapeMatcher.classify(previous, current, 20),
                "an identical cut must never be reported as different-semantic");
    }

    // ---- isGradient ----

    @Test
    void isGradientDistinguishesSolidFromVarying() {
        assertEquals(false, ColorShapeMatcher.isGradient(solid(0, 5, 0xFFFFFF)));
        assertEquals(true, ColorShapeMatcher.isGradient(gradient(0, 5, 0xFF0000, 0x001133)));
    }

    // ---- redistribute / sourceIndexFor ----

    @Test
    void redistributeStretchesAShortGradientOverLongerTranslatedText() {
        // 3-colour source stretched onto 6 translated characters: each source colour
        // should cover a proportional share, in order, with no colour dropped or guessed.
        int[] source = {0xFF0000, 0x00FF00, 0x0000FF};
        int[] out = ColorShapeMatcher.redistribute(source, 6);
        assertEquals(6, out.length);
        assertEquals(0xFF0000, out[0]);
        assertEquals(0x0000FF, out[5]);
        // Monotonic: the mapped source index never goes backwards as the output advances.
        int lastIdx = -1;
        for (int i = 0; i < out.length; i++) {
            int idx = ColorShapeMatcher.sourceIndexFor(6, source.length, i);
            assertEquals(true, idx >= lastIdx, "source index must be non-decreasing");
            assertEquals(source[idx], out[i]);
            lastIdx = idx;
        }
    }

    @Test
    void redistributeCompressesALongGradientOntoShorterTranslatedText() {
        int[] source = new int[19];
        for (int i = 0; i < source.length; i++) source[i] = 0xFF0000 + i * 0x001133;
        int[] out = ColorShapeMatcher.redistribute(source, 4);
        assertEquals(4, out.length);
        assertEquals(source[0], out[0]);
        assertEquals(source[source.length - 1], out[3]);
    }

    @Test
    void redistributeOfEmptySourceFillsNoColourRatherThanThrowing() {
        int[] out = ColorShapeMatcher.redistribute(new int[0], 3);
        assertEquals(3, out.length);
        for (int c : out) assertEquals(ColorProfile.NO_COLOR, c);
    }

    @Test
    void redistributeOfZeroTargetLengthIsEmpty() {
        assertArrayEquals(new int[0], ColorShapeMatcher.redistribute(new int[] {1, 2, 3}, 0));
    }

    // ---- validation ----

    @Test
    void classifyRejectsRunsThatDoNotCoverTheWholeText() {
        List<ColorShapeMatcher.ColorRun> tooShort = List.of(solid(0, 5, 0xFFFFFF));
        assertThrows(IllegalArgumentException.class,
                () -> ColorShapeMatcher.classify(tooShort, tooShort, 10));
    }

    @Test
    void classifyRejectsNonContiguousRuns() {
        List<ColorShapeMatcher.ColorRun> gappy = List.of(solid(0, 3, 0xFFFFFF), solid(5, 10, 0x000000));
        assertThrows(IllegalArgumentException.class,
                () -> ColorShapeMatcher.classify(gappy, gappy, 10));
    }

    @Test
    void colorRunRejectsMismatchedArrayLength() {
        assertThrows(IllegalArgumentException.class,
                () -> new ColorShapeMatcher.ColorRun(0, 5, new int[] {1, 2, 3}));
    }
}
