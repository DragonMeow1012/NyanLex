package com.dragonmeow.nyanslate;

import com.dragonmeow.nyanslate.translate.ParagraphModel;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParagraphModelTest {

    @Test
    void blankRowsAreHardBoundariesAndStayInTheSurfaceShape() {
        assertEquals(List.of(
                        new ParagraphModel.Range(0, 2),
                        new ParagraphModel.Range(3, 3),
                        new ParagraphModel.Range(4, 5)),
                ParagraphModel.ranges(List.of(
                        "Purse: 690,364", "Bits: 450", "07/10/26",
                        "", "Objective", "Enter the lobby")));
    }

    @Test
    void indentationStartsAParagraphWithoutNeedingABlankRow() {
        assertEquals(List.of(
                        new ParagraphModel.Range(0, 1),
                        new ParagraphModel.Range(2, 3),
                        new ParagraphModel.Range(4, 4)),
                ParagraphModel.ranges(List.of(
                        "First paragraph", "continues here",
                        "  Second paragraph", "continues too",
                        "\tThird paragraph")));
    }

    @Test
    void oneSpaceIsAlignmentNotAParagraphIndent() {
        assertEquals(List.of(new ParagraphModel.Range(0, 2)),
                ParagraphModel.ranges(List.of("Heading", " continuation", "last row")));
    }

    @Test
    void joinsAWholeParagraphWithOrderedProtectedBreaks() {
        List<String> rows = List.of(
                "Damage: 209", "Strength: 40", "Intelligence: 463");
        String joined = ParagraphModel.join(rows);
        assertEquals("Damage: 209 ⟦PB0⟧ Strength: 40 ⟦PB1⟧ Intelligence: 463", joined);
        assertEquals(rows, ParagraphModel.split(joined));
    }

    @Test
    void countsBreakTokensIncludingSpacedVariants() {
        assertEquals(0, ParagraphModel.countBreakTokens(null));
        assertEquals(0, ParagraphModel.countBreakTokens("no breaks here"));
        assertEquals(2, ParagraphModel.countBreakTokens("a ⟦PB0⟧ b ⟦PB1⟧ c"));
        assertEquals(1, ParagraphModel.countBreakTokens("a⟦ PB 0 ⟧b"),
                "translator-spaced token variants still count");
    }

    @Test
    void validBreakSequenceAcceptsOnlyExactlyOrderedContiguousIndices() {
        assertTrue(ParagraphModel.validBreakSequence("甲 ⟦PB0⟧ 乙 ⟦PB1⟧ 丙", 2));
        assertTrue(ParagraphModel.validBreakSequence("plain sentence", 0));
        assertFalse(ParagraphModel.validBreakSequence("甲 ⟦PB0⟧ 乙", 2), "dropped token");
        assertFalse(ParagraphModel.validBreakSequence("甲 ⟦PB1⟧ 乙 ⟦PB0⟧ 丙", 2), "reordered");
        assertFalse(ParagraphModel.validBreakSequence("甲 ⟦PB0⟧ 乙 ⟦PB0⟧ 丙", 2), "duplicated");
        assertFalse(ParagraphModel.validBreakSequence("甲 ⟦PB0⟧ 乙", 0),
                "an AI-hallucinated break on a break-free request is invalid");
        assertFalse(ParagraphModel.validBreakSequence("甲 ⟦PB99999999999⟧ 乙", 1),
                "an unparseable index is invalid, never an exception");
    }

    @Test
    void flattenBreakTokensBridgesAsciiWordsAndClosesCjkSeams() {
        assertEquals("甲乙", ParagraphModel.flattenBreakTokens("甲 ⟦PB0⟧ 乙"));
        assertEquals("word next", ParagraphModel.flattenBreakTokens("word ⟦PB0⟧ next"));
        assertEquals("字word", ParagraphModel.flattenBreakTokens("字 ⟦PB0⟧ word"),
                "a mixed CJK/ASCII boundary takes no seam space");
        assertEquals("edge", ParagraphModel.flattenBreakTokens("⟦PB0⟧ edge"));
        assertEquals("plain", ParagraphModel.flattenBreakTokens("plain"));
    }

    // ---- 1.0.7 F3: tolerant item-title verification (row 0 vs the stack's hover name) ----

    @Test
    void sameItemTitleToleratesADifferentStarCount() {
        assertTrue(ParagraphModel.sameItemTitle("Aspect of the End ✪✪✪✪✪", "Aspect of the End ✪✪✪"));
        assertTrue(ParagraphModel.sameItemTitle("⚚ Heroic Hyperion ✪✪✪✪✪➎", "Heroic Hyperion ✪✪✪"),
                "icon prefixes and master stars (other numbers) are decoration too");
        assertTrue(ParagraphModel.sameItemTitle(
                        "⟦CS0⟧Aspect of the End⟦/CS0⟧ ⟦CS1⟧✪✪✪⟦/CS1⟧", "Aspect of the End ✪✪✪"),
                "colour-run markers are formatting, not title text");
    }

    @Test
    void sameItemTitleIgnoresStarsRenderedAsDigits() {
        assertTrue(ParagraphModel.sameItemTitle("Hyperion 10✪", "Hyperion ✪✪✪✪✪✪✪✪✪✪"));
        assertTrue(ParagraphModel.sameItemTitle("10✪ Hyperion", "Hyperion ✪✪✪✪✪"));
        assertTrue(ParagraphModel.sameItemTitle("Fishing Minion XI 64", "Fishing Minion XI"));
    }

    @Test
    void sameItemTitleAcceptsATrailingUpgradeSuffix() {
        assertTrue(ParagraphModel.sameItemTitle("Hyperion (+10)", "Hyperion"));
        assertTrue(ParagraphModel.sameItemTitle("Livid Dagger ✪✪ (Recombobulated)", "Livid Dagger ✪✪"));
    }

    @Test
    void sameItemTitleRejectsAnUnrelatedRow() {
        assertFalse(ParagraphModel.sameItemTitle("Right-click to view recipes!", "Hyperion"));
        assertFalse(ParagraphModel.sameItemTitle("Ability: Wither Impact RIGHT CLICK", "Hyperion"));
        assertFalse(ParagraphModel.sameItemTitle("Hyperionic Staff", "Hyperion"),
                "a longer word is a different name, not a decorated one");
        assertFalse(ParagraphModel.sameItemTitle("NEW! Hyperion", "Hyperion"),
                "text inserted before the name is not accepted");
        assertFalse(ParagraphModel.sameItemTitle("Hyperion", ""));
        assertFalse(ParagraphModel.sameItemTitle(null, "Hyperion"));
        assertFalse(ParagraphModel.sameItemTitle("Hyperion", null));
    }

    @Test
    void sameItemTitleIgnoresSurroundingSpacesAndSectionCodes() {
        assertTrue(ParagraphModel.sameItemTitle("  §6§lHyperion§r  ", "Hyperion"));
        assertTrue(ParagraphModel.sameItemTitle("§dAspect  of   the End", " Aspect of the End "),
                "whitespace runs collapse on both sides");
        assertTrue(ParagraphModel.sameItemTitle("Hyperion §", "Hyperion"),
                "a dangling section sign after the name is not a letter");
    }

    @Test
    void sameItemTitleKeepsExactComparisonForSymbolOnlyNames() {
        assertTrue(ParagraphModel.sameItemTitle("§a✪✪✪", "✪✪✪"));
        assertTrue(ParagraphModel.sameItemTitle("64", "64"));
        assertFalse(ParagraphModel.sameItemTitle("✪✪✪✪", "✪✪✪"),
                "a name without letters cannot be matched loosely");
    }
}
