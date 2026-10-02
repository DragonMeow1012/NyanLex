package com.dragonmeow.nyanslate.translate;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P2: {@link EnchantListComposer} shape-matching and composition, exercised directly
 * (no cache/service involved). Every input is an inline string; no file or network is
 * read.
 */
class EnchantListComposerTest {

    /** Resolve a name from a small fixed dictionary — the "everything is known" happy
     *  path the service uses once every enchant name has a cached translation. */
    private static String composeWith(String line, Map<String, String> dictionary) {
        EnchantListComposer.Match match = EnchantListComposer.match(line);
        assertTrue(match != null, "expected an enchant-list match for: " + line);
        String composed = match.compose(line, dictionary::get);
        assertTrue(composed != null, "expected every name to resolve for: " + line);
        return composed;
    }

    private static final Map<String, String> NAMES = Map.of(
            "Soul Eater", "噬魂者",
            "Toxophilite", "弓箭精通",
            "Chance", "機會",
            "Cubism", "立方",
            "Power", "力量",
            "Snipe", "狙擊");

    // ---- two-row paragraph: 6 items across 2 rows ----

    @Test
    void twoRowParagraphYieldsSixItemsInOrder() {
        String row0 = "Soul Eater V, Toxophilite IV, Chance IV";
        String row1 = "Cubism V, Power VI, Snipe III";
        String joined = ParagraphModel.join(List.of(row0, row1));

        EnchantListComposer.Match match = EnchantListComposer.match(joined);
        assertTrue(match != null);
        assertEquals(6, match.items().size());
        assertEquals(List.of("Soul Eater", "Toxophilite", "Chance", "Cubism", "Power", "Snipe"),
                match.names());
    }

    @Test
    void composePreservesLevelsCommasAndRowBreak() {
        String row0 = "Soul Eater V, Toxophilite IV, Chance IV";
        String row1 = "Cubism V, Power VI, Snipe III";
        String joined = ParagraphModel.join(List.of(row0, row1));

        String composed = composeWith(joined, NAMES);

        String expectedRow0 = "噬魂者 V, 弓箭精通 IV, 機會 IV";
        String expectedRow1 = "立方 V, 力量 VI, 狙擊 III";
        assertEquals(ParagraphModel.join(List.of(expectedRow0, expectedRow1)), composed);
    }

    // ---- CS colour spans and a trailing comma are preserved byte-for-byte ----

    @Test
    void eachItemsOwnCsSpanAndTrailingCommaSurviveComposition() {
        String line = "⟦CS0⟧Soul Eater V⟦/CS0⟧⟦CS1⟧, ⟦/CS1⟧⟦CS2⟧Chance IV⟦/CS2⟧⟦CS1⟧,⟦/CS1⟧";
        String composed = composeWith(line, NAMES);
        String expected = "⟦CS0⟧噬魂者 V⟦/CS0⟧⟦CS1⟧, ⟦/CS1⟧⟦CS2⟧機會 IV⟦/CS2⟧⟦CS1⟧,⟦/CS1⟧";
        assertEquals(expected, composed);
    }

    @Test
    void bareFormatCodesAroundItemsSurviveComposition() {
        String line = "§dSoul Eater V§r, §9Chance IV";
        String composed = composeWith(line, NAMES);
        assertEquals("§d噬魂者 V§r, §9機會 IV", composed);
    }

    // ---- word extraction: apostrophes/hyphens, multi-word names, arabic level ----

    @Test
    void multiWordNameAndArabicLevelAreRecognised() {
        EnchantListComposer.Match match =
                EnchantListComposer.match("Ultimate Wise 5, Chance IV");
        assertTrue(match != null);
        assertEquals(List.of("Ultimate Wise", "Chance"), match.names());
    }

    // ---- lowercase connective words inside a multi-word name (P2 fix #1) ----

    @Test
    void connectiveWordsInsideAnEnchantNameAreToleratedAndComposedCorrectly() {
        // Real SkyBlock enchant names: "of"/"the" previously broke ROW_SHAPE (every word
        // had to start uppercase), so the whole paragraph fell back to whole-line
        // translation instead of decomposing per enchant name.
        String line = "Bane of Arthropods VI, Luck of the Sea III, Mending I";
        EnchantListComposer.Match match = EnchantListComposer.match(line);
        assertTrue(match != null, "connective words must not defeat the row shape");
        assertEquals(List.of("Bane of Arthropods", "Luck of the Sea", "Mending"), match.names());

        Map<String, String> dictionary = Map.of(
                "Bane of Arthropods", "蟲類剋星",
                "Luck of the Sea", "海之眷顧",
                "Mending", "修補");
        String composed = composeWith(line, dictionary);
        assertEquals("蟲類剋星 VI, 海之眷顧 III, 修補 I", composed);
    }

    @Test
    void connectiveWordCannotOpenAnEnchantName() {
        // A connector is only tolerated in the MIDDLE of a name; a row that would need one
        // to be the very FIRST token of an item is not a recognised shape at all (NAME
        // must start with an uppercase WORD), so the whole row -- and so the whole
        // paragraph -- is rejected, same as any other non-matching row.
        assertNull(EnchantListComposer.match("of Arthropods VI, Chance IV"));
    }

    @Test
    void connectiveWordCannotCloseAnEnchantName() {
        // Nothing left for LEVEL to bind to once "of" tries to sit right before it: no
        // valid NAME+LEVEL split exists, so the row (and the whole match) is rejected.
        assertNull(EnchantListComposer.match("Arthropods of VI, Chance IV"));
    }

    // ---- Hypixel's actual tooltip wire shape: each item its own colour run, wrapping the
    // separating comma too; a trailing comma; and up to three items per row ----

    @Test
    void perItemColourRunsWrappingTheCommaAreDecomposedJustLikeASharedRun() {
        // "§9Bane of Arthropods VI§9, §9Critical VI" -- every item (AND the separating
        // ", ") carries its own repeated §9 code, unlike the shared/CS-run fixtures above.
        String line = "§9Bane of Arthropods VI§9, §9Critical VI";
        EnchantListComposer.Match match = EnchantListComposer.match(line);
        assertTrue(match != null, "a colour run wrapping the comma must not break the row shape");
        assertEquals(List.of("Bane of Arthropods", "Critical"), match.names());

        Map<String, String> dictionary = Map.of(
                "Bane of Arthropods", "蟲類剋星",
                "Critical", "暴擊");
        String composed = composeWith(line, dictionary);
        assertEquals("§9蟲類剋星 VI§9, §9暴擊 VI", composed);
    }

    @Test
    void trailingCommaOnAPlainTextRowIsTolerated() {
        EnchantListComposer.Match match = EnchantListComposer.match("Sharpness VII, Mending I,");
        assertTrue(match != null);
        assertEquals(List.of("Sharpness", "Mending"), match.names());
    }

    @Test
    void threeItemRowIsFullyRecognised() {
        EnchantListComposer.Match match =
                EnchantListComposer.match("Sharpness VII, Mending I, Unbreaking III");
        assertTrue(match != null);
        assertEquals(List.of("Sharpness", "Mending", "Unbreaking"), match.names());
    }

    @Test
    void unresolvedNameLeavesComposeNullSoTheCallerCanRequestIt() {
        EnchantListComposer.Match match = EnchantListComposer.match("Soul Eater V, Chance IV");
        assertTrue(match != null);
        String composed = match.compose("Soul Eater V, Chance IV",
                name -> "Chance".equals(name) ? "機會" : null);
        assertNull(composed, "Soul Eater is unresolved: the whole composition is not ready");
    }

    // ---- rejections: isolated single-item rows / mixed content must fall through ----

    @Test
    void isolatedSingleEnchantLineIsNotDecomposed() {
        assertNull(EnchantListComposer.match("Sharpness VII"));
        assertNull(EnchantListComposer.match("Floor VII"));
        assertNull(EnchantListComposer.match("Tier V"));
    }

    @Test
    void twoRowsEachWithOnlyOneItemAreNotDecomposed() {
        // Neither row individually has 2+ items -> no reordering risk, nothing to gain.
        String joined = ParagraphModel.join(List.of("Sharpness VII", "Unbreaking III"));
        assertNull(EnchantListComposer.match(joined));
    }

    @Test
    void paragraphMixedWithOtherContentIsRejectedEntirely() {
        String joined = ParagraphModel.join(
                List.of("Soul Eater V, Toxophilite IV, Chance IV", "Sells for 100 coins"));
        assertNull(EnchantListComposer.match(joined));
    }

    @Test
    void blankRowInsideTheParagraphIsRejected() {
        String joined = ParagraphModel.join(
                List.of("Soul Eater V, Toxophilite IV, Chance IV", ""));
        assertNull(EnchantListComposer.match(joined));
    }

    @Test
    void plainSentenceWithNoCommaIsRejectedByThePrefilter() {
        assertNull(EnchantListComposer.match("Right-click to use this item now"));
    }

    @Test
    void ordinarySentenceUsingOfIsNotMisjudgedAsAnEnchantList() {
        // Carries a comma AND a digit (so the cheap prefilter lets it through) and even
        // reuses one of the newly-tolerated connective words ("of"), but is still an
        // ordinary sentence, not a NAME+LEVEL list: "deals"/"damage"/"enjoy"/"your"/"gift"
        // never fit WORD or CONNECTOR, so no row-wide NAME+LEVEL split exists.
        assertNull(EnchantListComposer.match(
                "The Ring of Fire deals 5 damage, enjoy your gift"));
    }

    @Test
    void titleWithNoLevelHintIsRejected() {
        assertNull(EnchantListComposer.match("Legendary Sword, Epic Bow, Rare Pickaxe"));
    }

    @Test
    void allCapsRarityLineIsNotMistakenForAnEnchantList() {
        assertNull(EnchantListComposer.match("EPIC DUNGEON GLOVES"));
    }

    @Test
    void coreSplitAcrossTwoColourRunsIsRejected() {
        // "Soul Eater" split across two CS runs: the fixed shape this class recognises
        // never produces that, so reject rather than risk corrupting the CS structure.
        String line = "⟦CS0⟧Soul⟦/CS0⟧⟦CS1⟧ Eater V, Chance IV⟦/CS1⟧";
        assertNull(EnchantListComposer.match(line));
    }

    @Test
    void unterminatedMarkerIsRejected() {
        assertNull(EnchantListComposer.match("Soul Eater V, ⟦CS0 Chance IV"));
    }

    @Test
    void barePlaceholderInsideARowIsRejected() {
        assertNull(EnchantListComposer.match("Soul Eater V, ⟦0⟧ Chance IV"));
    }

    @Test
    void nullAndShortInputsAreRejected() {
        assertNull(EnchantListComposer.match(null));
        assertNull(EnchantListComposer.match(""));
        assertNull(EnchantListComposer.match("hi"));
    }

    // ---- memo: bounded, correct across generation rotation ----

    @Test
    void memoStaysBoundedAndCorrectAcrossManyDistinctLines() {
        // A run of distinct real 2-item matches interleaved with distinct rejects (each
        // still carrying a comma and a level-hint so the prefilter lets it through, but
        // failing the actual shape), well past the 512-per-generation size, so both
        // generations rotate at least twice.
        for (int i = 0; i < 1500; i++) {
            String matching = "Chance IV, Item" + suffixLetters(i) + " V";
            EnchantListComposer.match(matching);
            EnchantListComposer.match("Chance IV, " + i); // "5" alone is not a NAME+LEVEL item
        }
        assertTrue(EnchantListComposer.memoSize() <= 1024, "bounded at two generations of 512");
        // Still correct after heavy rotation, for both an early-pattern and a fresh line.
        EnchantListComposer.Match match = EnchantListComposer.match("Soul Eater V, Chance IV");
        assertTrue(match != null, "still correct after heavy rotation");
        assertEquals(List.of("Soul Eater", "Chance"), match.names());
        assertNull(EnchantListComposer.match("Sharpness VII"));
        assertNull(EnchantListComposer.match("Chance IV, 999"));
    }

    /** Turns {@code i} into an upper/lower letters-only suffix so the generated name
     *  still matches the WORD shape ({@code [A-Z][A-Za-z]*}). */
    private static String suffixLetters(int i) {
        StringBuilder out = new StringBuilder();
        int n = i;
        do {
            out.append((char) ('a' + n % 26));
            n /= 26;
        } while (n > 0);
        return out.toString();
    }

    // ---- matchRuns: tolerant of other rows glued to the enchant list via ⟦PBn⟧ ----

    @Test
    void matchRunsFindsTheEnchantListEvenGluedToUnrelatedRows() {
        String joined = ParagraphModel.join(List.of(
                "COMMON PET ITEM", "Soul Eater V, Toxophilite IV, Chance IV", "Sells for 100 coins"));
        // match() keeps its existing contract: the whole text is rejected outright (see
        // paragraphMixedWithOtherContentIsRejectedEntirely above).
        assertNull(EnchantListComposer.match(joined));

        List<EnchantListComposer.Run> runs = EnchantListComposer.matchRuns(joined);
        assertEquals(1, runs.size());
        EnchantListComposer.Run run = runs.get(0);
        // A middle row keeps the ⟦PBn⟧ join's padding on BOTH sides (see
        // ScrollNameListComposerTest's matching note); the item-level NAME spans inside
        // the run are already free of it, as the names() assertion below shows.
        assertEquals(" Soul Eater V, Toxophilite IV, Chance IV ", joined.substring(run.start(), run.end()));
        assertEquals(List.of("Soul Eater", "Toxophilite", "Chance"), run.match().names());
    }

    @Test
    void matchRunsCoversTwoRowsEvenWhenNeitherNeighbourQualifies() {
        String joined = ParagraphModel.join(List.of(
                "COMMON PET ITEM",
                "Soul Eater V, Toxophilite IV", "Cubism V, Power VI",
                "Sells for 100 coins"));
        List<EnchantListComposer.Run> runs = EnchantListComposer.matchRuns(joined);
        assertEquals(1, runs.size());
        assertEquals(4, runs.get(0).match().items().size());
    }

    @Test
    void matchRunsIsEmptyWhenNoRunQualifies() {
        assertEquals(List.of(), EnchantListComposer.matchRuns("COMMON PET ITEM"));
        // Neither row alone has 2+ items: no reordering risk, nothing to gain (same guard
        // as match()'s single-item-row exclusion), so no run qualifies either.
        String joined = ParagraphModel.join(List.of("Sharpness VII", "Unbreaking III"));
        assertEquals(List.of(), EnchantListComposer.matchRuns(joined));
    }

    @Test
    void matchRunsOnASingleIsolatedTwoItemRowBehavesLikeMatch() {
        List<EnchantListComposer.Run> runs =
                EnchantListComposer.matchRuns("Sharpness VII, Mending I");
        assertEquals(1, runs.size());
        assertEquals(0, runs.get(0).start());
        assertEquals("Sharpness VII, Mending I".length(), runs.get(0).end());
        assertEquals(List.of("Sharpness", "Mending"), runs.get(0).match().names());
    }
}
