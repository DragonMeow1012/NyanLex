package com.dragonmeow.nyanlex.translate;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P1.7: {@link RarityLineComposer} shape-matching and composition, exercised directly
 * (no cache/service involved) against every shape in term-table-draft.md §2. Every
 * input is an inline string; no file or network is read.
 */
class RarityLineComposerTest {

    /** Resolve every word straight from the shipped table (the "everything is known"
     *  happy path the service uses when there is no learned/overridden word). */
    private static final BiFunction<RarityLineComposer.Kind, String, String> DEFAULTS_ONLY =
            (kind, word) -> kind == RarityLineComposer.Kind.RARITY
                    ? TermTableDefaults.RARITY.get(word)
                    : TermTableDefaults.TYPE.get(word);

    private static String composeWithDefaults(String line) {
        RarityLineComposer.Match match = RarityLineComposer.match(line);
        assertTrue(match != null, "expected a rarity-line match for: " + line);
        String composed = match.compose(line, DEFAULTS_ONLY);
        assertTrue(composed != null, "expected every word to resolve for: " + line);
        return composed;
    }

    // ---- the 8 shapes from term-table-draft.md §2 ----

    @Test
    void rarityAndType() {
        assertEquals("稀有飾品", composeWithDefaults("RARE ACCESSORY"));
    }

    @Test
    void bareRarityAlone() {
        assertEquals("傳奇", composeWithDefaults("LEGENDARY"));
    }

    @Test
    void rarityDungeonType() {
        assertEquals("史詩地城手套", composeWithDefaults("EPIC DUNGEON GLOVES"));
    }

    @Test
    void confusionLettersWrapRarityAndType_plainNoCs() {
        assertEquals("a 神話飾品 a", composeWithDefaults("a MYTHIC ACCESSORY a"));
    }

    @Test
    void confusionLettersWrapRarityDungeonType_withCsTags() {
        String line = "⟦CS0⟧a⟦/CS0⟧ ⟦CS1⟧MYTHIC DUNGEON SWORD"
                + "⟦/CS1⟧ ⟦CS2⟧a⟦/CS2⟧";
        String expected = "⟦CS0⟧a⟦/CS0⟧ ⟦CS1⟧神話地城劍"
                + "⟦/CS1⟧ ⟦CS2⟧a⟦/CS2⟧";
        assertEquals(expected, composeWithDefaults(line));
    }

    @Test
    void familyShardWithLiteralIdSuffix() {
        assertEquals("罕見戰鬥碎片 (ID U36)", composeWithDefaults("UNCOMMON COMBAT SHARD (ID U36)"));
    }

    @Test
    void shinyDungeonTypeNoConfusionLetters() {
        assertEquals("閃亮傳奇地城頭盔", composeWithDefaults("SHINY LEGENDARY DUNGEON HELMET"));
    }

    @Test
    void shinyDungeonTypeWithConfusionLettersAndCsTags() {
        String line = "⟦CS0⟧a⟦/CS0⟧ ⟦CS1⟧SHINY MYTHIC DUNGEON SWORD"
                + "⟦/CS1⟧ ⟦CS2⟧a⟦/CS2⟧";
        String expected = "⟦CS0⟧a⟦/CS0⟧ ⟦CS1⟧閃亮神話地城劍"
                + "⟦/CS1⟧ ⟦CS2⟧a⟦/CS2⟧";
        assertEquals(expected, composeWithDefaults(line));
    }

    // ---- word extraction (what the service asks the term table / cache to resolve) ----

    @Test
    void wordsListsEveryComponentInOutputOrder() {
        RarityLineComposer.Match match = RarityLineComposer.match("SHINY EPIC DUNGEON NECKLACE");
        List<RarityLineComposer.Word> words = match.words();
        assertEquals(List.of(
                new RarityLineComposer.Word(RarityLineComposer.Kind.TYPE, "SHINY"),
                new RarityLineComposer.Word(RarityLineComposer.Kind.RARITY, "EPIC"),
                new RarityLineComposer.Word(RarityLineComposer.Kind.TYPE, "DUNGEON"),
                new RarityLineComposer.Word(RarityLineComposer.Kind.TYPE, "NECKLACE")),
                words);
    }

    @Test
    void unknownTypeWordLeavesComposeNullSoTheCallerCanLearnIt() {
        RarityLineComposer.Match match = RarityLineComposer.match("EPIC FUTURE ARMOR");
        assertTrue(match != null);
        assertEquals("FUTURE ARMOR", match.words().get(1).text());
        assertNull(match.compose("EPIC FUTURE ARMOR", DEFAULTS_ONLY));
        // Once the unknown word is "learned" (any resolver that knows it), composition
        // succeeds without re-matching the line.
        String composed = match.compose("EPIC FUTURE ARMOR", (kind, word) ->
                kind == RarityLineComposer.Kind.RARITY ? TermTableDefaults.RARITY.get(word)
                        : Map.of("FUTURE ARMOR", "未來裝甲").get(word));
        assertEquals("史詩未來裝甲", composed);
    }

    // ---- do-not-translate terms must never be overridden (Bug #2 regression) ----

    @Test
    void doNotTranslateTermInsideTypePhraseIsKeptLiteralNotComposedFromTable() {
        // The reported bug: composeRarityLine ran entirely BEFORE mask()/doNotTranslateTerms,
        // so "GARDEN CHIP" (a single whole-phrase table entry -> "花園晶片") silently
        // translated "GARDEN" even when the user configured it as a do-not-translate term.
        RarityLineComposer.Match match = RarityLineComposer.match("EPIC GARDEN CHIP");
        assertTrue(match != null);
        DoNotTranslateMatcher dnt = DoNotTranslateMatcher.compile(List.of("Garden"));

        String composed = match.composeProtecting("EPIC GARDEN CHIP", dnt, DEFAULTS_ONLY);

        assertEquals("史詩GARDEN CHIP", composed,
                "EPIC still composes from the table; the whole GARDEN CHIP phrase that "
                        + "contains the protected word is kept verbatim instead of translated");
    }

    @Test
    void composeProtectingKeepsOnlyTheOverlappingWordOthersStillComposeFromTable() {
        RarityLineComposer.Match match = RarityLineComposer.match("SHINY EPIC DUNGEON NECKLACE");
        DoNotTranslateMatcher dnt = DoNotTranslateMatcher.compile(List.of("Necklace"));

        String composed = match.composeProtecting("SHINY EPIC DUNGEON NECKLACE", dnt, DEFAULTS_ONLY);

        assertEquals("閃亮史詩地城NECKLACE", composed,
                "only the word overlapping the protected term is kept literal");
    }

    @Test
    void composeProtectingMatchesComposeWhenNoDoNotTranslateIsConfigured() {
        RarityLineComposer.Match match = RarityLineComposer.match("EPIC DUNGEON GLOVES");

        assertEquals(match.compose("EPIC DUNGEON GLOVES", DEFAULTS_ONLY),
                match.composeProtecting("EPIC DUNGEON GLOVES", DoNotTranslateMatcher.EMPTY, DEFAULTS_ONLY));
        assertEquals(match.compose("EPIC DUNGEON GLOVES", DEFAULTS_ONLY),
                match.composeProtecting("EPIC DUNGEON GLOVES", null, DEFAULTS_ONLY));
    }

    @Test
    void unprotectedWordsExcludesWordsCoveredByDoNotTranslate() {
        RarityLineComposer.Match match = RarityLineComposer.match("SHINY EPIC DUNGEON NECKLACE");
        DoNotTranslateMatcher dnt = DoNotTranslateMatcher.compile(List.of("Necklace"));

        List<RarityLineComposer.Word> unprotected = match.unprotectedWords(
                "SHINY EPIC DUNGEON NECKLACE", dnt);

        assertEquals(List.of(
                new RarityLineComposer.Word(RarityLineComposer.Kind.TYPE, "SHINY"),
                new RarityLineComposer.Word(RarityLineComposer.Kind.RARITY, "EPIC"),
                new RarityLineComposer.Word(RarityLineComposer.Kind.TYPE, "DUNGEON")),
                unprotected, "NECKLACE is covered by the do-not-translate term and excluded");
        assertEquals(match.words(), match.unprotectedWords("SHINY EPIC DUNGEON NECKLACE",
                DoNotTranslateMatcher.EMPTY), "nothing configured -> every word is eligible");
    }

    // ---- rejections: must fall through to the ordinary lookup path ----

    @Test
    void ordinaryUppercaseLineIsNotMisidentifiedAsARarityLine() {
        assertNull(RarityLineComposer.match("RIGHT-CLICK TO USE"));
    }

    @Test
    void plainSentenceWithNoRarityWordIsRejectedByThePrefilter() {
        assertNull(RarityLineComposer.match("HELLO WORLD"));
    }

    @Test
    void multiLineParagraphUnitIsRejected() {
        // A ⟦PBn⟧ token means this is a paragraph key joining several rendered lines —
        // P1.7 §4 explicitly leaves multi-line paragraphs alone.
        assertNull(RarityLineComposer.match("EPIC DUNGEON GLOVES⟦PB0⟧More lore text"));
    }

    @Test
    void titleCaseTextIsNotMistakenForAnAllCapsRarityLine() {
        // Reforge composition (§6.2, Title Case names) is explicitly out of scope for P1.7.
        assertNull(RarityLineComposer.match("Epic Dungeon Gloves"));
    }

    @Test
    void coreSplitAcrossTwoColourRunsIsRejected() {
        // EPIC in one CS run, " DUNGEON GLOVES" in another: the fixed 8 shapes never
        // split the rarity phrase itself across styles, so this is left alone rather
        // than risk corrupting the CS structure by splicing across the boundary.
        String line = "⟦CS0⟧EPIC⟦/CS0⟧⟦CS1⟧ DUNGEON GLOVES⟦/CS1⟧";
        assertNull(RarityLineComposer.match(line));
    }

    @Test
    void leadingFormatCodeOutsideTheCoreIsPreserved() {
        // A bare §-format code (as opposed to a ⟦CSn⟧ pair) styling the WHOLE line sits
        // entirely outside the matched core and must survive untouched, same as a
        // confusion letter or a literal ID suffix.
        String line = "§dEPIC DUNGEON GLOVES";
        assertEquals("§d史詩地城手套", composeWithDefaults(line));
    }

    @Test
    void formatCodeInsideTheCoreIsRejectedRatherThanSilentlyDropped() {
        // A §-reset in the MIDDLE of the phrase would be silently deleted by a naive
        // splice; reject instead, exactly like a colour-run split.
        String line = "EPIC §rDUNGEON GLOVES";
        assertNull(RarityLineComposer.match(line));
    }

    @Test
    void unterminatedMarkerIsRejected() {
        assertNull(RarityLineComposer.match("EPIC ⟦CS0 DUNGEON GLOVES"));
    }

    @Test
    void bareProtectedPlaceholderInsideLineIsRejected() {
        // A masked player name / do-not-translate term (⟦0⟧) has no place in a rarity
        // line; treat it like any other unexpected marker and leave the line alone.
        assertNull(RarityLineComposer.match("EPIC DUNGEON ⟦0⟧"));
    }

    @Test
    void nullAndShortInputsAreRejected() {
        assertNull(RarityLineComposer.match(null));
        assertNull(RarityLineComposer.match(""));
        assertNull(RarityLineComposer.match("hi"));
    }

    // ---- memo: bounded, correct across generation rotation ----

    @Test
    void memoStaysBoundedAndCorrectAcrossManyDistinctLines() {
        // A run of distinct real matches (RARE X0 / EPIC DUNGEON X1 / ... cycling shapes)
        // interleaved with distinct rejects (each with a rarity-word substring so the
        // prefilter lets it through, but not a matching whole-line shape), well past the
        // 512-per-generation size, so both generations rotate at least twice.
        for (int i = 0; i < 1500; i++) {
            String matching = i % 2 == 0 ? "RARE ACCESSORY" + suffixLetters(i)
                    : "EPIC DUNGEON " + suffixLetters(i);
            RarityLineComposer.match(matching);
            RarityLineComposer.match("RARE " + i); // digits break the shape: memoised as NONE
        }
        assertTrue(RarityLineComposer.memoSize() <= 1024, "bounded at two generations of 512");
        // Still correct after heavy rotation, for both an early-pattern and a fresh line.
        assertEquals("稀有飾品", composeWithDefaults("RARE ACCESSORY"));
        assertEquals("史詩地城手套", composeWithDefaults("EPIC DUNGEON GLOVES"));
        assertNull(RarityLineComposer.match("RIGHT-CLICK TO USE"));
        assertNull(RarityLineComposer.match("RARE 999"));
    }

    /** Turns {@code i} into an upper-case letters-only suffix so the generated line still
     *  matches the TYPE group's {@code [A-Z][A-Z-]*} shape (digits would break it). */
    private static String suffixLetters(int i) {
        StringBuilder out = new StringBuilder();
        int n = i;
        do {
            out.append((char) ('A' + n % 26));
            n /= 26;
        } while (n > 0);
        return out.toString();
    }

    // ---- matchRuns: tolerant of other rows glued to the rarity line via ⟦PBn⟧ ----

    @Test
    void matchRunsFindsTheRarityRowEvenGluedToUnrelatedRows() {
        // Today's bug: match() rejects the whole text the instant ANY ⟦PBn⟧ token appears
        // anywhere (the item-name slot and the trade field glued to it with no blank row
        // between them). matchRuns finds the one row that genuinely is a rarity line.
        String joined = ParagraphModel.join(List.of(
                "RARE ACCESSORY", "Seller: DragonMeow", "Buy it now: 100 coins"));
        assertNull(RarityLineComposer.match(joined), "match() keeps its existing reject-on-PB contract");

        List<RarityLineComposer.Run> runs = RarityLineComposer.matchRuns(joined);
        assertEquals(1, runs.size());
        RarityLineComposer.Run run = runs.get(0);
        // Row 0 still carries the ⟦PB0⟧ join's trailing space (ParagraphModel.join() pads
        // both sides of the token; only a row's OWN far edge is unpadded) — the match's
        // own span inside it already excludes that space from the composed CORE, so it
        // survives untouched after the replacement, exactly like any other such gap.
        assertEquals("RARE ACCESSORY ", joined.substring(run.start(), run.end()));
        String composed = run.match().compose(joined.substring(run.start(), run.end()), DEFAULTS_ONLY);
        assertEquals("稀有飾品 ", composed);
    }

    @Test
    void matchRunsIsEmptyWhenNoRowQualifies() {
        String joined = ParagraphModel.join(List.of("Seller: DragonMeow", "Buy it now: 100 coins"));
        assertEquals(List.of(), RarityLineComposer.matchRuns(joined));
    }

    @Test
    void matchRunsOnASingleIsolatedRowBehavesLikeMatch() {
        List<RarityLineComposer.Run> runs = RarityLineComposer.matchRuns("LEGENDARY");
        assertEquals(1, runs.size());
        assertEquals(0, runs.get(0).start());
        assertEquals("LEGENDARY".length(), runs.get(0).end());
    }

    // ---- lenient (word-aligned colour runs) — 2026-10-02 colour-format-only sharing ----

    @Test
    void lenientAcceptsColourRunsBetweenWordsAndKeepsEveryMarkerWithItsOwnWord() {
        String line = "⟦CS0⟧EPIC⟦/CS0⟧ ⟦CS1⟧DUNGEON GLOVES⟦/CS1⟧";
        assertNull(RarityLineComposer.match(line), "strict contract unchanged");
        RarityLineComposer.Match match = RarityLineComposer.matchLenient(line);
        assertTrue(match != null && match.wordwise());
        assertEquals("⟦CS0⟧史詩⟦/CS0⟧⟦CS1⟧地城手套⟦/CS1⟧", match.compose(line, DEFAULTS_ONLY));
    }

    @Test
    void lenientThreeColourWordsEachKeepTheirOwnMarker() {
        String line = "⟦CS5⟧MYTHIC⟦/CS5⟧ ⟦CS6⟧DUNGEON⟦/CS6⟧ ⟦CS7⟧BOW⟦/CS7⟧";
        RarityLineComposer.Match match = RarityLineComposer.matchLenient(line);
        assertEquals("⟦CS5⟧神話⟦/CS5⟧⟦CS6⟧地城⟦/CS6⟧⟦CS7⟧弓⟦/CS7⟧", match.compose(line, DEFAULTS_ONLY));
    }

    @Test
    void lenientStillRejectsAMarkerInsideOneWordOrAFormatCodeBetweenWords() {
        assertNull(RarityLineComposer.matchLenient("⟦CS0⟧EP⟦/CS0⟧⟦CS1⟧IC DUNGEON GLOVES⟦/CS1⟧"));
        assertNull(RarityLineComposer.matchLenient("EPIC §rDUNGEON GLOVES"));
    }

    @Test
    void lenientOnAnUnsplitLineBehavesLikeStrict() {
        RarityLineComposer.Match match = RarityLineComposer.matchLenient("EPIC DUNGEON GLOVES");
        assertTrue(!match.wordwise());
        assertEquals("史詩地城手套", match.compose("EPIC DUNGEON GLOVES", DEFAULTS_ONLY));
    }
}
