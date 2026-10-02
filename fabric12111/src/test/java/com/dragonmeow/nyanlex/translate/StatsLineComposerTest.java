package com.dragonmeow.nyanlex.translate;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link StatsLineComposer} row-boundary detection, exercised directly (no cache/service
 * involved). Every input is an inline string; no file or network is read.
 */
class StatsLineComposerTest {

    @Test
    void statRowsAreRecognisedAmongOtherRows() {
        String joined = ParagraphModel.join(List.of(
                "EPIC SWORD", "Strength: +10", "Crit Chance: +10%"));
        StatsLineComposer.Match match = StatsLineComposer.match(joined);
        assertTrue(match != null);
        assertEquals(List.of("Strength: +10", "Crit Chance: +10%"), match.texts());
    }

    @Test
    void composeReplacesOnlyTheStatRows() {
        String joined = ParagraphModel.join(List.of("EPIC SWORD", "Strength: +10"));
        StatsLineComposer.Match match = StatsLineComposer.match(joined);
        assertTrue(match != null);
        String composed = match.compose(joined, text -> "力量：+10");
        String expected = ParagraphModel.join(List.of("EPIC SWORD", "力量：+10"));
        assertEquals(expected, composed);
    }

    @Test
    void differentNumericValuesShareNoCrossContaminationButEachIsItsOwnUnit() {
        StatsLineComposer.Match a = StatsLineComposer.match("Strength: +10");
        StatsLineComposer.Match b = StatsLineComposer.match("Strength: +50");
        assertTrue(a != null && b != null);
        assertEquals("Strength: +10", a.texts().get(0));
        assertEquals("Strength: +50", b.texts().get(0));
    }

    @Test
    void unresolvedRowLeavesComposeNull() {
        StatsLineComposer.Match match = StatsLineComposer.match("Strength: +10");
        assertTrue(match != null);
        assertNull(match.compose("Strength: +10", text -> null));
    }

    // ---- negative cases ----

    @Test
    void sellerRowIsNotAStatLine() {
        // No digit immediately after the colon: never a stat line, even though it starts
        // with an uppercase label too.
        assertNull(StatsLineComposer.match("Seller: Steve"));
    }

    @Test
    void ordinaryProseIsNotAStatLine() {
        assertNull(StatsLineComposer.match("Right-click to use this item now"));
    }

    @Test
    void nullAndShortInputsAreRejected() {
        assertNull(StatsLineComposer.match(null));
        assertNull(StatsLineComposer.match("hi"));
    }

    @Test
    void negativeValueIsRecognised() {
        StatsLineComposer.Match match = StatsLineComposer.match("Speed: -5");
        assertTrue(match != null);
        assertEquals(List.of("Speed: -5"), match.texts());
    }

    // ---- 2026-10-01 real-cache calibration: an already-templated ⟦MTn⟧ value slot ----

    @Test
    void templatedMtTokenValueIsRecognisedJustLikeALiteralDigit() {
        // The export-tool path (HubExportTool#splitLegacyWholeRows) runs this composer on
        // ALREADY-TEMPLATED on-disk cache KEYS, where TemplateText has already folded the
        // sign/percent suffix into the SAME slot ("Strength: +10" -> "Strength: ⟦MT0⟧",
        // confirmed on real Hypixel data -- never "Strength: +⟦MT0⟧").
        assertEquals(List.of("Strength: ⟦MT0⟧"),
                StatsLineComposer.match("Strength: ⟦MT0⟧").texts());
    }

    @Test
    void realCacheShapesWithTrailingWordsAfterTheMtTokenAreRecognised() {
        // Real abandoned-row samples (2026-10-01 quantification): "You have: ⟦MT0⟧ Gems",
        // "Your base limit: ⟦MT0⟧ ⟦MT1⟧ guests" -- trailing words/tokens after the value
        // slot, same as the literal-digit shape already allows ("Strength: +10 (max)").
        assertTrue(StatsLineComposer.matchesRow("You have: ⟦MT0⟧ Gems"));
        assertTrue(StatsLineComposer.matchesRow("Your base limit: ⟦MT0⟧ ⟦MT1⟧ guests"));
    }

    @Test
    void mtTokenImmediatelyFollowedByPercentIsRecognised() {
        // A superset accepted defensively (never observed on real data, since TemplateText
        // itself folds a directly-attached "%" INTO the same slot) -- still gated by the
        // mandatory "Label:" prefix, so this is a harmless widening, not a new false
        // positive risk (e.g. "Seller: 50%" still fails, no digit/token after the colon).
        assertTrue(StatsLineComposer.matchesRow("Crit Chance: ⟦MT0⟧%"));
    }

    @Test
    void aColourWrappedMtShapedRowIsStillRecognised() {
        // Real sample: "⟦CS0⟧Accessory Power:⟦/CS0⟧ ⟦CS1⟧⟦MT0⟧⟦/CS1⟧ ⟦CS2⟧" -- matching runs
        // on the TextFilter#stripFormatting projection, exactly like TradeLineComposer.
        assertTrue(StatsLineComposer.matchesRow(
                "⟦CS0⟧Accessory Power:⟦/CS0⟧ ⟦CS1⟧⟦MT0⟧⟦/CS1⟧"));
    }

    @Test
    void bareDigitStillRejectsAnMtLookingButActuallyUnrelatedToken() {
        // A row whose colon is followed by neither a literal digit nor an ⟦MTn⟧ token (an
        // ordinary masked name slot "⟦0⟧", no MT/WS/CS kind prefix) is still rejected --
        // the relaxation is scoped to the MT kind only, not every bracketed token.
        assertNull(StatsLineComposer.match("Seller: ⟦0⟧"));
    }
}
