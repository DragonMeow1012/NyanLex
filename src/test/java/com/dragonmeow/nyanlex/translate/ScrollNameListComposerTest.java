package com.dragonmeow.nyanlex.translate;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ScrollNameListComposer} shape-matching and composition, exercised directly (no
 * cache/service involved). Every input is an inline string; no file or network is read.
 * {@code ●} stands in for Hypixel's actual ability-list bullet glyph throughout.
 */
class ScrollNameListComposerTest {

    private static final Map<String, String> NAMES = Map.of(
            "Implosion", "內爆",
            "Wither Shield", "凋零盾",
            "Shadow Warp", "暗影躍動");

    @Test
    void threeRowScrollListYieldsThreeNamesInOrder() {
        String joined = ParagraphModel.join(List.of(
                "● Implosion", "● Wither Shield", "● Shadow Warp"));
        ScrollNameListComposer.Match match = ScrollNameListComposer.match(joined);
        assertTrue(match != null, "expected a scroll-name-list match");
        assertEquals(List.of("Implosion", "Wither Shield", "Shadow Warp"), match.names());
    }

    @Test
    void composePreservesIconsAndRowBreaks() {
        String joined = ParagraphModel.join(List.of(
                "● Implosion", "● Wither Shield", "● Shadow Warp"));
        ScrollNameListComposer.Match match = ScrollNameListComposer.match(joined);
        assertTrue(match != null);
        String composed = match.compose(joined, NAMES::get);
        String expected = ParagraphModel.join(List.of(
                "● 內爆", "● 凋零盾", "● 暗影躍動"));
        assertEquals(expected, composed);
    }

    @Test
    void swappingOneScrollOnlyChangesThatOneNameNoOtherKeyShifts() {
        String hyperionA = ParagraphModel.join(List.of(
                "● Implosion", "● Wither Shield", "● Shadow Warp"));
        String hyperionB = ParagraphModel.join(List.of(
                "● Implosion", "● Wither Impact", "● Shadow Warp"));
        ScrollNameListComposer.Match a = ScrollNameListComposer.match(hyperionA);
        ScrollNameListComposer.Match b = ScrollNameListComposer.match(hyperionB);
        assertTrue(a != null && b != null);
        // Only the swapped row's name differs; the other two names are byte-identical
        // translation units regardless of which combo they appear in.
        assertEquals(a.names().get(0), b.names().get(0));
        assertEquals(a.names().get(2), b.names().get(2));
        assertTrue(!a.names().get(1).equals(b.names().get(1)));
    }

    @Test
    void unresolvedNameLeavesComposeNull() {
        String joined = ParagraphModel.join(List.of("● Implosion", "● Wither Shield"));
        ScrollNameListComposer.Match match = ScrollNameListComposer.match(joined);
        assertTrue(match != null);
        String composed = match.compose(joined,
                name -> "Implosion".equals(name) ? "內爆" : null);
        assertNull(composed, "Wither Shield is unresolved: the whole composition is not ready");
    }

    @Test
    void multiWordNameIsRecognised() {
        ScrollNameListComposer.Match match =
                ScrollNameListComposer.match(ParagraphModel.join(List.of("● Wither Shield", "● Shadow Warp")));
        assertTrue(match != null);
        assertEquals(List.of("Wither Shield", "Shadow Warp"), match.names());
    }

    // ---- rejections ----

    @Test
    void isolatedSingleNameRowIsNotDecomposed() {
        assertNull(ScrollNameListComposer.match("● Implosion"));
    }

    @Test
    void nameRowWithNoLeadingIconIsNotAScrollList() {
        // No icon prefix: indistinguishable from ordinary Title-Case prose, so this shape
        // is deliberately NOT recognised (see class javadoc).
        assertNull(ScrollNameListComposer.match(
                ParagraphModel.join(List.of("Implosion", "Wither Shield"))));
    }

    @Test
    void mixedContentIsRejectedByTheWholeTextEntryPoint() {
        String joined = ParagraphModel.join(List.of("● Implosion", "Sells for 100 coins"));
        assertNull(ScrollNameListComposer.match(joined));
    }

    @Test
    void nullAndShortInputsAreRejected() {
        assertNull(ScrollNameListComposer.match(null));
        assertNull(ScrollNameListComposer.match(""));
        assertNull(ScrollNameListComposer.match("hi"));
    }

    // ---- matchRuns: tolerant of rows the whole-text match() rejects ----

    @Test
    void matchRunsFindsTheScrollListEvenGluedToUnrelatedRows() {
        String joined = ParagraphModel.join(List.of(
                "COMMON PET ITEM", "● Implosion", "● Wither Shield", "● Shadow Warp",
                "Sells for 100 coins"));
        List<ScrollNameListComposer.Run> runs = ScrollNameListComposer.matchRuns(joined);
        assertEquals(1, runs.size());
        ScrollNameListComposer.Run run = runs.get(0);
        assertEquals(List.of("Implosion", "Wither Shield", "Shadow Warp"), run.match().names());
        // The run's own span, read back out of the ORIGINAL text, covers exactly its 3
        // rows (ParagraphModel.join() pads every ⟦PBn⟧ token with a space on each side, so
        // a run that is not the very first/last row of the WHOLE text carries that padding
        // at its own edges too — the item-level NAME spans above are already free of it).
        String runText = joined.substring(run.start(), run.end());
        assertEquals(" ● Implosion ⟦PB1⟧ ● Wither Shield ⟦PB2⟧ ● Shadow Warp ", runText);
        String composed = run.match().compose(runText, NAMES::get);
        assertEquals(" ● 內爆 ⟦PB1⟧ ● 凋零盾 ⟦PB2⟧ ● 暗影躍動 ", composed);
    }

    @Test
    void matchRunsIsEmptyWhenNoRunQualifies() {
        assertEquals(List.of(), ScrollNameListComposer.matchRuns("COMMON PET ITEM"));
        assertEquals(List.of(), ScrollNameListComposer.matchRuns(
                ParagraphModel.join(List.of("COMMON PET ITEM", "● Implosion"))));
    }

    // ---- memo: bounded, correct across generation rotation ----

    @Test
    void memoStaysBoundedAndCorrectAcrossManyDistinctLines() {
        for (int i = 0; i < 1500; i++) {
            String matching = ParagraphModel.join(List.of("● Item" + suffixLetters(i), "● Other"));
            ScrollNameListComposer.match(matching);
            ScrollNameListComposer.match("● Item" + suffixLetters(i)); // single row: rejected
        }
        assertTrue(ScrollNameListComposer.memoSize() <= 1024, "bounded at two generations of 512");
        ScrollNameListComposer.Match match = ScrollNameListComposer.match(
                ParagraphModel.join(List.of("● Implosion", "● Wither Shield")));
        assertTrue(match != null, "still correct after heavy rotation");
        assertEquals(List.of("Implosion", "Wither Shield"), match.names());
    }

    // ---- 2026-10-01 real-cache calibration: a templated ⟦MTn⟧/⟦WSn⟧ leading icon ----

    @Test
    void templatedLeadingMtTokenIsRecognisedAsTheIcon() {
        // Real Hypixel on-disk cache key (confirmed 2026-10-01): TemplateText's own
        // SYMBOL_RUN pattern replaces each row's decorative bullet glyph with its own
        // ⟦MTn⟧ slot BEFORE this composer ever runs on the export-tool/cache-key path, so
        // the raw icon character this composer's old ICON class expected is never actually
        // there on disk -- only the already-templated token is.
        String stored = "⟦MT0⟧ Implosion ⟦PB0⟧ ⟦MT1⟧ Wither Shield "
                + "⟦PB1⟧ ⟦MT2⟧ Shadow Warp";
        ScrollNameListComposer.Match match = ScrollNameListComposer.match(stored);
        assertTrue(match != null, "a leading ⟦MTn⟧ token must be recognised as the icon");
        assertEquals(List.of("Implosion", "Wither Shield", "Shadow Warp"), match.names());
    }

    @Test
    void templatedLeadingMtTokenComposesBackLeavingTheTokenUntouched() {
        String stored = "⟦MT0⟧ Implosion ⟦PB0⟧ ⟦MT1⟧ Wither Shield";
        ScrollNameListComposer.Match match = ScrollNameListComposer.match(stored);
        assertTrue(match != null);
        String composed = match.compose(stored, NAMES::get);
        assertEquals("⟦MT0⟧ 內爆 ⟦PB0⟧ ⟦MT1⟧ 凋零盾", composed);
    }

    @Test
    void templatedLeadingWsTokenIsAlsoRecognisedAsTheIcon() {
        String stored = "⟦WS0⟧ Implosion ⟦PB0⟧ ⟦WS1⟧ Wither Shield";
        ScrollNameListComposer.Match match = ScrollNameListComposer.match(stored);
        assertTrue(match != null);
        assertEquals(List.of("Implosion", "Wither Shield"), match.names());
    }

    @Test
    void aBareMaskedPSlotIsNeverTreatedAsALeadingIcon() {
        // A bare numbered P-slot (⟦0⟧, no MT/WS kind prefix) is a masked/protected VALUE
        // (a name, a do-not-translate term), never a decorative icon -- treating it as one
        // would silently fold real content into "the icon" and lose it on compose.
        assertNull(ScrollNameListComposer.match(
                "⟦0⟧ Implosion ⟦PB0⟧ ⟦0⟧ Wither Shield"));
    }

    @Test
    void aSecondValueTokenInsideTheNameSpanStillDeclinesTheRow() {
        // The leading-token path is only authoritative for ONE token at the very start;
        // anything else bracket-shaped later in the same row is still a hard reject (same
        // safety rule the old ICON-char path already applied), never a guess.
        assertNull(ScrollNameListComposer.match(ParagraphModel.join(List.of(
                "⟦MT0⟧ Impl⟦MT1⟧osion", "⟦MT2⟧ Wither Shield"))));
    }

    private static String suffixLetters(int i) {
        StringBuilder out = new StringBuilder();
        int n = i;
        do {
            out.append((char) ('a' + n % 26));
            n /= 26;
        } while (n > 0);
        return out.toString();
    }
}
