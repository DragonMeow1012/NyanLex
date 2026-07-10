package com.dragonmeow.mctranslator;

import com.dragonmeow.mctranslator.translate.ParagraphModel;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
