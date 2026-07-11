package com.dragonmeow.mctranslator;

import com.dragonmeow.mctranslator.translate.ParagraphModel;
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
}
