package com.dragonmeow.nyanslate.translate;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TradeLineComposer} row-boundary detection, exercised directly (no cache/service
 * involved). Every input is an inline string; no file or network is read.
 */
class TradeLineComposerTest {

    @Test
    void sellerRowIsRecognisedAmongOtherRows() {
        String joined = ParagraphModel.join(List.of(
                "COMMON PET ITEM", "Seller: DragonMeow", "Buy it now: 1,000,000 coins"));
        TradeLineComposer.Match match = TradeLineComposer.match(joined);
        assertTrue(match != null);
        assertEquals(List.of("Seller: DragonMeow", "Buy it now: 1,000,000 coins"), match.texts());
    }

    @Test
    void buyerAndBidderRowsAreRecognised() {
        assertTrue(TradeLineComposer.matchesRow("Buyer: Someone"));
        assertTrue(TradeLineComposer.matchesRow("Bidder: Someone"));
        assertTrue(TradeLineComposer.matchesRow("Starting bid: 500,000 coins"));
        assertTrue(TradeLineComposer.matchesRow("Top bid: 750,000 coins"));
        assertTrue(TradeLineComposer.matchesRow("Current bid: 750,000 coins"));
        assertTrue(TradeLineComposer.matchesRow("Ends in: 2h 30m"));
        assertTrue(TradeLineComposer.matchesRow("Time left: 10m"));
    }

    @Test
    void composeReplacesOnlyTheClaimedRows() {
        String joined = ParagraphModel.join(List.of(
                "COMMON PET ITEM", "Seller: DragonMeow", "Buy it now: 1,000,000 coins"));
        TradeLineComposer.Match match = TradeLineComposer.match(joined);
        assertTrue(match != null);
        Map<String, String> dictionary = Map.of(
                "Seller: DragonMeow", "賣家：DragonMeow",
                "Buy it now: 1,000,000 coins", "一口價：1,000,000 金幣");
        String composed = match.compose(joined, dictionary::get);
        String expected = ParagraphModel.join(List.of(
                "COMMON PET ITEM", "賣家：DragonMeow", "一口價：1,000,000 金幣"));
        assertEquals(expected, composed);
    }

    @Test
    void sameSellerDifferentPriceAreTwoIndependentUnits() {
        // Two different auctions of the same seller share the Seller row's key, but the
        // price row differs -- each is its own independent translation unit (no cross
        // reuse implied, no cross contamination either).
        String auctionA = ParagraphModel.join(List.of("Seller: Steve", "Buy it now: 100 coins"));
        String auctionB = ParagraphModel.join(List.of("Seller: Steve", "Buy it now: 200 coins"));
        TradeLineComposer.Match matchA = TradeLineComposer.match(auctionA);
        TradeLineComposer.Match matchB = TradeLineComposer.match(auctionB);
        assertTrue(matchA != null && matchB != null);
        assertEquals("Seller: Steve", matchA.texts().get(0));
        assertEquals("Seller: Steve", matchB.texts().get(0));
        assertEquals("Buy it now: 100 coins", matchA.texts().get(1));
        assertEquals("Buy it now: 200 coins", matchB.texts().get(1));
    }

    @Test
    void unresolvedRowLeavesComposeNull() {
        String joined = ParagraphModel.join(List.of("Seller: Steve", "Buy it now: 100 coins"));
        TradeLineComposer.Match match = TradeLineComposer.match(joined);
        assertTrue(match != null);
        String composed = match.compose(joined,
                text -> "Seller: Steve".equals(text) ? "賣家：Steve" : null);
        assertNull(composed, "Buy it now row is unresolved: the whole composition is not ready");
    }

    // ---- negative cases ----

    @Test
    void ordinaryStatLineIsNotATradeField() {
        assertNull(TradeLineComposer.match("Strength: +10"));
    }

    @Test
    void ordinaryProseIsNotATradeField() {
        assertNull(TradeLineComposer.match("Right-click to use this item now"));
    }

    @Test
    void textWithNoRowsAtAllYieldsNoMatch() {
        assertNull(TradeLineComposer.match("Soul Eater V, Toxophilite IV"));
        assertNull(TradeLineComposer.match(null));
        assertNull(TradeLineComposer.match("hi"));
    }

    @Test
    void aColourWrappedSellerRowIsStillRecognisedAndKeptWhole() {
        String line = "⟦CS0⟧Seller: DragonMeow⟦/CS0⟧";
        TradeLineComposer.Match match = TradeLineComposer.match(line);
        assertTrue(match != null);
        assertEquals(line, match.items().get(0).text());
    }

    // ---- 2026-10 CS-orphan request-loop fix (trimmedSpan) ----
    // Live evidence (scratchpad/live-ai exchange dumps): a colour run that starts on one
    // row and ends on the NEXT lands exactly half inside each row once FabricTextStyle
    // marks the whole multi-row paragraph up front and TooltipSegmentPlanner/
    // TradeLineComposer/StatsLineComposer only split it into rows AFTERWARDS. Sending that
    // half-pair as a unit's own text can never pass TranslationCache#matchingCsShape no
    // matter what the model returns -- a deterministic, permanently-retried failure. See
    // TradeLineComposer#trimmedSpan's own 2026-10 javadoc for the full mechanism.

    @Test
    void trimmedSpanExcludesALeadingOrphanCloseMarker() {
        // "⟦/CS3⟧ Seller: DragonMeow" -- CS3 opened on a row that is not part of this span
        // at all; nothing earlier in [start, end) can ever balance it.
        String text = "⟦/CS3⟧ Seller: DragonMeow";
        int[] trimmed = TradeLineComposer.trimmedSpan(text, 0, text.length());
        assertEquals("Seller: DragonMeow", text.substring(trimmed[0], trimmed[1]));
    }

    @Test
    void trimmedSpanExcludesATrailingOrphanOpenMarker() {
        // "Seller: DragonMeow ⟦CS8⟧" -- CS8 only closes on the NEXT row, outside this span.
        String text = "Seller: DragonMeow ⟦CS8⟧";
        int[] trimmed = TradeLineComposer.trimmedSpan(text, 0, text.length());
        assertEquals("Seller: DragonMeow", text.substring(trimmed[0], trimmed[1]));
    }

    @Test
    void trimmedSpanExcludesBothOrphansAtOnceAndKeepsCompletePairsIntact() {
        // Exact shape from the live dump (exchange-...-50.txt, "Crit Chance" row):
        // "⟦/CS12⟧ ⟦CS13⟧Crit Chance:⟦/CS13⟧ ⟦CS14⟧⟦MT0⟧ (⟦MT1⟧)⟦/CS14⟧ ⟦CS15⟧(⟦MT2⟧)⟦/CS15⟧ ⟦CS16⟧"
        String text = "⟦/CS12⟧ ⟦CS13⟧Crit Chance:⟦/CS13⟧ ⟦CS14⟧⟦MT0⟧ (⟦MT1⟧)⟦/CS14⟧ "
                + "⟦CS15⟧(⟦MT2⟧)⟦/CS15⟧ ⟦CS16⟧";
        int[] trimmed = TradeLineComposer.trimmedSpan(text, 0, text.length());
        String unit = text.substring(trimmed[0], trimmed[1]);
        assertEquals("⟦CS13⟧Crit Chance:⟦/CS13⟧ ⟦CS14⟧⟦MT0⟧ (⟦MT1⟧)⟦/CS14⟧ ⟦CS15⟧(⟦MT2⟧)⟦/CS15⟧",
                unit);
        assertTrue(isCsBalanced(unit), "the unit sent to the translator must be CS-balanced");
    }

    @Test
    void trimmedSpanLeavesAnOrdinaryBalancedRowUnchanged() {
        String text = "⟦CS0⟧Strength: +10⟦/CS0⟧";
        int[] trimmed = TradeLineComposer.trimmedSpan(text, 0, text.length());
        assertEquals(text, text.substring(trimmed[0], trimmed[1]));
    }

    @Test
    void adjacentSegmentsReconstituteTheSharedColourRunAcrossTheGap() {
        // Two STATS rows sharing one colour run across their ⟦PB0⟧ boundary (Strength's
        // trailing ⟦CS8⟧ open / Crit Chance's leading ⟦/CS8⟧ close -- same index, exactly
        // the live-dump shape: exchange-...-60.txt ends "... ⟦CS8⟧", exchange-...-61.txt
        // begins "⟦/CS8⟧ ...").
        String full = "⟦CS0⟧Strength:⟦/CS0⟧ ⟦CS1⟧⟦MT0⟧⟦/CS1⟧⟦CS8⟧ ⟦PB0⟧ "
                + "⟦/CS8⟧⟦CS9⟧Crit Chance:⟦/CS9⟧ ⟦CS10⟧⟦MT1⟧⟦/CS10⟧";
        List<int[]> rows = ParagraphModel.splitRawRows(full);
        assertEquals(2, rows.size());
        int[] rowA = TradeLineComposer.trimmedSpan(full, rows.get(0)[0], rows.get(0)[1]);
        int[] rowB = TradeLineComposer.trimmedSpan(full, rows.get(1)[0], rows.get(1)[1]);
        String unitA = full.substring(rowA[0], rowA[1]);
        String unitB = full.substring(rowB[0], rowB[1]);
        assertTrue(isCsBalanced(unitA), "unit A must be CS-balanced on its own: " + unitA);
        assertTrue(isCsBalanced(unitB), "unit B must be CS-balanced on its own: " + unitB);
        // The untouched gap BETWEEN the two trimmed spans (what TooltipSegmentPlanner#
        // compose/Match#compose always copy through byte-for-byte) reconstitutes the
        // complete ⟦CS8⟧...⟦/CS8⟧ pair around the row break on its own.
        String gap = full.substring(rowA[1], rowB[0]);
        assertEquals("⟦CS8⟧ ⟦PB0⟧ ⟦/CS8⟧", gap);
    }

    /** Minimal stack-based balance check mirroring {@code TranslationCache#csShape}'s own
     *  validation, used only to assert a unit this test builds is actually sendable. */
    private static boolean isCsBalanced(String text) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\\u27E6\\s*(/?)\\s*CS\\s*(\\d+)\\s*\\u27E7").matcher(text);
        java.util.ArrayDeque<String> open = new java.util.ArrayDeque<>();
        while (m.find()) {
            boolean closing = !m.group(1).isEmpty();
            String index = m.group(2);
            if (!closing) {
                open.push(index);
            } else if (open.isEmpty() || !open.pop().equals(index)) {
                return false;
            }
        }
        return open.isEmpty();
    }
}
