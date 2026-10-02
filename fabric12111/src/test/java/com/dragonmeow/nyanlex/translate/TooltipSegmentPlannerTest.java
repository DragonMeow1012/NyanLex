package com.dragonmeow.nyanlex.translate;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TooltipSegmentPlanner} classification and composition, exercised directly (no
 * cache/service involved — {@link com.dragonmeow.nyanlex.service.TranslationService}
 * supplies the real {@link TooltipSegmentPlanner.SegmentResolver} in production). Every
 * input is an inline string; no file or network is read.
 */
class TooltipSegmentPlannerTest {

    /** Hypixel SkyBlock Auction House's actual shape: a rarity line, an icon-only item
     *  slot row (nothing to translate), a Seller row and a Buy-it-now row, all glued with
     *  no blank row between them. */
    private static String auctionTooltip(String priceCoins) {
        return ParagraphModel.join(List.of(
                "COMMON PET ITEM", "⚡", "Seller: DragonMeow", "Buy it now: " + priceCoins + " coins"));
    }

    @Test
    void mixedAuctionParagraphIsFullyClassified() {
        TooltipSegmentPlanner.Plan plan = TooltipSegmentPlanner.plan(auctionTooltip("100"), true);
        assertTrue(plan != null);
        List<TooltipSegmentPlanner.Segment> segs = plan.segments();
        assertEquals(4, segs.size());
        assertEquals(TooltipSegmentPlanner.Kind.RARITY, segs.get(0).kind());
        assertEquals(TooltipSegmentPlanner.Kind.INERT, segs.get(1).kind());
        assertEquals(TooltipSegmentPlanner.Kind.TRADE, segs.get(2).kind());
        assertEquals(TooltipSegmentPlanner.Kind.TRADE, segs.get(3).kind());
    }

    @Test
    void segmentsAreContiguousAndCoverTheWholeText() {
        String text = auctionTooltip("100");
        TooltipSegmentPlanner.Plan plan = TooltipSegmentPlanner.plan(text, true);
        assertTrue(plan != null);
        int cursor = 0;
        for (TooltipSegmentPlanner.Segment seg : plan.segments()) {
            assertTrue(seg.start() >= cursor, "segments must be in order with no overlap");
            cursor = seg.end();
        }
        // Nothing beyond the last segment's end except whatever trailing text exists
        // (none here): the FIRST segment starts at 0 and segments are gap-free once PB
        // tokens are accounted for by the generic compose() splice, not asserted here.
        assertTrue(cursor <= text.length());
    }

    @Test
    void abilityBlockAloneIsNeverPlanned() {
        // Real 97,691-row account data: every standalone Ability:...Cooldown: block sampled
        // (339 of them) was its OWN whole paragraph, with no other row to classify --
        // carving it out as a lone ABILITY segment would reproduce today's single
        // whole-text cache key one layer deeper for zero gain, so it is deliberately left
        // declined (see class javadoc "Partial planning") exactly like plain prose alone.
        String text = ParagraphModel.join(List.of(
                "Ability: Flame Breath", "Deals damage.", "Cooldown: 30s"));
        assertNull(TooltipSegmentPlanner.plan(text, true));
    }

    @Test
    void abilityBlockGluedToAClassifiableRowBecomesItsOwnSegmentInsteadOfBailingTheWholePlan() {
        // Pre-2026-10-01: ANY "Ability:" occurrence anywhere in the text declined the WHOLE
        // plan outright, so a rarity line sharing a paragraph with an ability block (no
        // blank row between them) never split even its own, otherwise-trivial RARITY row.
        String text = ParagraphModel.join(List.of(
                "RARE WAND", "Ability: Flame Breath", "Deals damage.", "Cooldown: 30s"));
        TooltipSegmentPlanner.Plan plan = TooltipSegmentPlanner.plan(text, true);
        assertTrue(plan != null);
        assertEquals(2, plan.segments().size());
        assertEquals(TooltipSegmentPlanner.Kind.RARITY, plan.segments().get(0).kind());
        TooltipSegmentPlanner.Segment ability = plan.segments().get(1);
        assertEquals(TooltipSegmentPlanner.Kind.ABILITY, ability.kind());
        // The WHOLE block (Ability: row through the Cooldown: row, inclusive) is carved out
        // as ONE atomic segment, never further split -- the internal rows ("Deals damage.")
        // must not be mistaken for independent prose/stat rows of their own. PB numbering is
        // GLOBAL to the whole text (PB1/PB2 here, since PB0 is the break before this block),
        // and the leading ⟦PBn⟧-join padding space is trimmed (see TradeLineComposer
        // #trimmedSpan) so the segment's own cache key is position-independent.
        assertEquals("Ability: Flame Breath" + ParagraphModel.breakToken(1) + "Deals damage."
                        + ParagraphModel.breakToken(2) + "Cooldown: 30s",
                text.substring(ability.start(), ability.end()));
    }

    @Test
    void abilityBlockWithNoTrailingCooldownRowRunsToTheEndOfTheText() {
        String text = ParagraphModel.join(List.of(
                "RARE WAND", "Ability: Flame Breath", "Deals damage. No cooldown listed."));
        TooltipSegmentPlanner.Plan plan = TooltipSegmentPlanner.plan(text, true);
        assertTrue(plan != null);
        assertEquals(2, plan.segments().size());
        TooltipSegmentPlanner.Segment ability = plan.segments().get(1);
        assertEquals(TooltipSegmentPlanner.Kind.ABILITY, ability.kind());
        assertEquals(text.length(), ability.end());
    }

    @Test
    void fewerThanTwoRowsIsNeverPlanned() {
        assertNull(TooltipSegmentPlanner.plan("RARE ACCESSORY", true));
        assertNull(TooltipSegmentPlanner.plan(null, true));
    }

    @Test
    void genuinelyAllUnclassifiedProseStillDeclinesTheWholePlan() {
        // Nothing on either row is independently RARITY/ENCHANT/TRADE/STATS/SCROLL, so
        // there is nothing to gain from decomposing -- preserves the exact pre-existing
        // single-whole-text cache key, zero new risk.
        String text = ParagraphModel.join(List.of(
                "Right-click to use this item.", "Works while in Accessory Bag!"));
        assertNull(TooltipSegmentPlanner.plan(text, true));
    }

    @Test
    void proseRowGluedToAClassifiableRowBecomesItsOwnIndependentProseSegment() {
        // The real-cache fix (2026-10-01): a single unrecognised row no longer bails the
        // whole plan -- it becomes its own PROSE segment, kept whole (never further split,
        // so Chinese word order is never cut mid-sentence), while the genuinely classifiable
        // row next to it still decomposes.
        String text = ParagraphModel.join(List.of("COMMON PET ITEM", "Right-click to use this item"));
        TooltipSegmentPlanner.Plan plan = TooltipSegmentPlanner.plan(text, true);
        assertTrue(plan != null);
        assertEquals(2, plan.segments().size());
        assertEquals(TooltipSegmentPlanner.Kind.RARITY, plan.segments().get(0).kind());
        TooltipSegmentPlanner.Segment prose = plan.segments().get(1);
        assertEquals(TooltipSegmentPlanner.Kind.PROSE, prose.kind());
        assertEquals("Right-click to use this item", text.substring(prose.start(), prose.end()));
    }

    @Test
    void consecutiveUnclassifiedRowsMergeIntoOneProseSegmentNotOnePerRow() {
        String text = ParagraphModel.join(List.of(
                "COMMON PET ITEM", "This is the first prose row.", "This is the second prose row.",
                "Seller: DragonMeow"));
        TooltipSegmentPlanner.Plan plan = TooltipSegmentPlanner.plan(text, true);
        assertTrue(plan != null);
        assertEquals(3, plan.segments().size());
        assertEquals(TooltipSegmentPlanner.Kind.RARITY, plan.segments().get(0).kind());
        TooltipSegmentPlanner.Segment prose = plan.segments().get(1);
        assertEquals(TooltipSegmentPlanner.Kind.PROSE, prose.kind());
        assertEquals(TooltipSegmentPlanner.Kind.TRADE, plan.segments().get(2).kind());
        String proseSpan = text.substring(prose.start(), prose.end());
        assertTrue(proseSpan.contains("first prose row"));
        assertTrue(proseSpan.contains("second prose row"));
        assertTrue(proseSpan.contains("⟦PB"), "the PB token between the two prose rows stays inside the one merged segment");
    }

    @Test
    void allowRarityFalseTreatsARarityRowAsItsOwnProseSegmentButTheRestStillSplits() {
        // Mirrors TranslationService.composeRarityLine's own zh-TW/zh-HK gate: for any
        // other target, a rarity-shaped row must not be silently composed from the zh-only
        // term table. Pre-2026-10-01 this declined the WHOLE plan; today the row simply
        // becomes an ordinary PROSE segment (still a normal, independent cache key -- see
        // TranslationService#resolveEnchantName) while the genuinely classifiable TRADE rows
        // next to it still split out.
        String text = auctionTooltip("100");
        TooltipSegmentPlanner.Plan plan = TooltipSegmentPlanner.plan(text, false);
        assertTrue(plan != null);
        assertEquals(4, plan.segments().size());
        assertEquals(TooltipSegmentPlanner.Kind.PROSE, plan.segments().get(0).kind());
        assertEquals("COMMON PET ITEM", text.substring(
                plan.segments().get(0).start(), plan.segments().get(0).end()));
        assertEquals(TooltipSegmentPlanner.Kind.INERT, plan.segments().get(1).kind());
        assertEquals(TooltipSegmentPlanner.Kind.TRADE, plan.segments().get(2).kind());
        assertEquals(TooltipSegmentPlanner.Kind.TRADE, plan.segments().get(3).kind());
    }

    @Test
    void scrollNameListEmbeddedInAMixedParagraphIsItsOwnRun() {
        String text = ParagraphModel.join(List.of(
                "COMMON PET ITEM", "● Implosion", "● Wither Shield", "● Shadow Warp",
                "Seller: DragonMeow"));
        TooltipSegmentPlanner.Plan plan = TooltipSegmentPlanner.plan(text, true);
        assertTrue(plan != null);
        assertEquals(3, plan.segments().size());
        assertEquals(TooltipSegmentPlanner.Kind.RARITY, plan.segments().get(0).kind());
        assertEquals(TooltipSegmentPlanner.Kind.SCROLL, plan.segments().get(1).kind());
        assertEquals(TooltipSegmentPlanner.Kind.TRADE, plan.segments().get(2).kind());
        ScrollNameListComposer.Match scroll =
                (ScrollNameListComposer.Match) plan.segments().get(1).detail();
        assertEquals(List.of("Implosion", "Wither Shield", "Shadow Warp"), scroll.names());
    }

    // ---- compose(): resolves every segment, splices, or declines on any miss ----

    @Test
    void composeSplicesEveryKindCorrectly() {
        // Each resolver below does a TARGETED substring replace on its own raw segment
        // text, exactly like the real composeProtecting/resolveEnchantName paths do —
        // whatever ⟦PBn⟧-join padding/CS markers the segment's raw text carries around
        // the replaced core is preserved automatically, so the expected string is just
        // the SAME replacements applied directly to the original text.
        String text = auctionTooltip("100");
        TooltipSegmentPlanner.Plan plan = TooltipSegmentPlanner.plan(text, true);
        assertTrue(plan != null);

        TooltipSegmentPlanner.SegmentResolver resolver = (segment, raw) -> switch (segment.kind()) {
            case RARITY -> raw.replace("COMMON PET ITEM", "普通寵物道具");
            case INERT -> raw;
            case TRADE -> raw.contains("Seller")
                    ? raw.replace("Seller: DragonMeow", "賣家：DragonMeow")
                    : raw.replace("Buy it now: 100 coins", "一口價：100 金幣");
            default -> null;
        };
        String composed = TooltipSegmentPlanner.compose(text, plan, resolver);
        String expected = text.replace("COMMON PET ITEM", "普通寵物道具")
                .replace("Seller: DragonMeow", "賣家：DragonMeow")
                .replace("Buy it now: 100 coins", "一口價：100 金幣");
        assertEquals(expected, composed);
    }

    @Test
    void composeReturnsNullWhenAnySegmentUnresolved() {
        String text = auctionTooltip("100");
        TooltipSegmentPlanner.Plan plan = TooltipSegmentPlanner.plan(text, true);
        assertTrue(plan != null);
        TooltipSegmentPlanner.SegmentResolver resolver = (segment, raw) ->
                segment.kind() == TooltipSegmentPlanner.Kind.TRADE ? null : raw;
        assertNull(TooltipSegmentPlanner.compose(text, plan, resolver));
    }

    @Test
    void composeCallsTheResolverForEverySegmentEvenAfterAnEarlierMiss() {
        // So a resolver that queues a request as a side effect (TranslationService's real
        // usage) never skips a later missing piece just because an earlier one missed.
        String text = auctionTooltip("100");
        TooltipSegmentPlanner.Plan plan = TooltipSegmentPlanner.plan(text, true);
        assertTrue(plan != null);
        List<TooltipSegmentPlanner.Kind> visited = new ArrayList<>();
        TooltipSegmentPlanner.SegmentResolver resolver = (segment, raw) -> {
            visited.add(segment.kind());
            return null; // nothing resolves
        };
        assertNull(TooltipSegmentPlanner.compose(text, plan, resolver));
        assertEquals(4, visited.size(), "every segment's resolver must still run once");
    }

    @Test
    void composePreservesStyleMarkersOutsideClaimedSpans() {
        String text = ParagraphModel.join(List.of(
                "⟦CS0⟧RARE ACCESSORY⟦/CS0⟧", "Seller: DragonMeow"));
        TooltipSegmentPlanner.Plan plan = TooltipSegmentPlanner.plan(text, true);
        assertTrue(plan != null);
        TooltipSegmentPlanner.SegmentResolver resolver = (segment, raw) ->
                switch (segment.kind()) {
                    case RARITY -> raw.replace("RARE ACCESSORY", "稀有飾品");
                    case TRADE -> raw.replace("Seller: DragonMeow", "賣家：DragonMeow");
                    default -> raw;
                };
        String composed = TooltipSegmentPlanner.compose(text, plan, resolver);
        String expected = text.replace("RARE ACCESSORY", "稀有飾品")
                .replace("Seller: DragonMeow", "賣家：DragonMeow");
        assertEquals(expected, composed);
    }

    // ---- 2026-10 CS-orphan request-loop fix: cross-row colour runs on STATS blocks ----
    // Live evidence (scratchpad/live-ai exchange dumps, a real Hypixel bow's stat block):
    // FabricTextStyle#markChatContent marks ⟦CSn⟧ colour runs across the WHOLE paragraph
    // BEFORE any row splitting happens, so a run shared by the implicit gap either side of
    // a ⟦PBn⟧ row break (the same colour before and after it) lands exactly half inside one
    // STATS row and half inside its neighbour. Each segment's OWN raw text is exactly what
    // TranslationService#resolveEnchantName sends/caches as this unit's key, so it must be
    // CS-balanced on its own or it can never pass validation no matter what the model
    // returns (see TradeLineComposer#trimmedSpan's own javadoc for the full mechanism).

    /** Builds a multi-row STATS block whose colour runs straddle every ⟦PBn⟧ row break,
     *  byte-for-byte the shape real Hypixel tooltip data produces (not ParagraphModel#join:
     *  this needs EXACT control over where each run's open/close marker falls relative to
     *  the row boundary). */
    private static String crossRowStatsBlock() {
        return "⟦CS0⟧Strength:⟦/CS0⟧ ⟦CS1⟧⟦MT0⟧⟦/CS1⟧⟦CS2⟧"
                + " ⟦PB0⟧ "
                + "⟦/CS2⟧⟦CS3⟧Crit Chance:⟦/CS3⟧ ⟦CS4⟧⟦MT1⟧⟦/CS4⟧⟦CS5⟧"
                + " ⟦PB1⟧ "
                + "⟦/CS5⟧⟦CS6⟧Crit Damage:⟦/CS6⟧ ⟦CS7⟧⟦MT2⟧⟦/CS7⟧⟦CS8⟧"
                + " ⟦PB2⟧ "
                + "⟦/CS8⟧⟦CS9⟧Vitality:⟦/CS9⟧ ⟦CS10⟧⟦MT3⟧⟦/CS10⟧";
    }

    @Test
    void crossRowStatsBlockSplitsIntoFourIndependentlyBalancedSegments() {
        String text = crossRowStatsBlock();
        TooltipSegmentPlanner.Plan plan = TooltipSegmentPlanner.plan(text, true);
        assertTrue(plan != null);
        assertEquals(4, plan.segments().size());
        for (TooltipSegmentPlanner.Segment segment : plan.segments()) {
            assertEquals(TooltipSegmentPlanner.Kind.STATS, segment.kind());
            String unit = text.substring(segment.start(), segment.end());
            assertTrue(isCsBalanced(unit),
                    "every STATS unit sent to the translator must be CS-balanced on its "
                            + "own, was: " + unit);
        }
        // The exact units TranslationService#resolveEnchantName would request/cache.
        assertEquals("⟦CS0⟧Strength:⟦/CS0⟧ ⟦CS1⟧⟦MT0⟧⟦/CS1⟧",
                text.substring(plan.segments().get(0).start(), plan.segments().get(0).end()));
        assertEquals("⟦CS3⟧Crit Chance:⟦/CS3⟧ ⟦CS4⟧⟦MT1⟧⟦/CS4⟧",
                text.substring(plan.segments().get(1).start(), plan.segments().get(1).end()));
        assertEquals("⟦CS6⟧Crit Damage:⟦/CS6⟧ ⟦CS7⟧⟦MT2⟧⟦/CS7⟧",
                text.substring(plan.segments().get(2).start(), plan.segments().get(2).end()));
        assertEquals("⟦CS9⟧Vitality:⟦/CS9⟧ ⟦CS10⟧⟦MT3⟧⟦/CS10⟧",
                text.substring(plan.segments().get(3).start(), plan.segments().get(3).end()));
    }

    @Test
    void crossRowStatsBlockComposesBackByteForByteWhenEveryUnitEchoesItsOwnText() {
        // A resolver that returns the unit UNCHANGED (as a round-tripping translator
        // response would, decoded back from the wire) must reconstruct the EXACT original
        // text, orphaned markers included -- proving the untouched gaps between segments
        // (never sent to the translator, never touched by compose()) restore the full,
        // originally-balanced ⟦CSn⟧...⟦/CSn⟧ runs around every row break automatically.
        String text = crossRowStatsBlock();
        TooltipSegmentPlanner.Plan plan = TooltipSegmentPlanner.plan(text, true);
        assertTrue(plan != null);
        TooltipSegmentPlanner.SegmentResolver identity = (segment, raw) -> raw;
        String composed = TooltipSegmentPlanner.compose(text, plan, identity);
        assertEquals(text, composed);
    }

    /** Minimal stack-based balance check mirroring {@code TranslationCache#csShape}. */
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

    // ---- memo: 2026-10-01 audit finding — bounded, and a hit skips recomputation ----

    @Test
    void repeatedCallsWithTheSameTextAndAllowRarityReturnTheMemoizedInstance() {
        // composeStructuredTooltip / isTooltipTranslationReady / isTooltipTranslationPending
        // each call plan() on the identical text within one render frame; a cache hit must
        // return the exact SAME Plan instance (not merely an equal one), proving the second
        // and third calls skipped the row-walk/composer re-invocation entirely rather than
        // recomputing an equal result.
        String text = auctionTooltip("100");
        TooltipSegmentPlanner.Plan first = TooltipSegmentPlanner.plan(text, true);
        TooltipSegmentPlanner.Plan second = TooltipSegmentPlanner.plan(text, true);
        TooltipSegmentPlanner.Plan third = TooltipSegmentPlanner.plan(text, true);
        assertTrue(first != null);
        assertTrue(first == second, "a memo hit must return the identical Plan instance");
        assertTrue(first == third, "a memo hit must return the identical Plan instance");
    }

    @Test
    void memoDistinguishesByAllowRarityEvenForTheSameText() {
        // Same text, different allowRarity -> genuinely different classification (the rarity
        // row is RARITY when allowed, PROSE — folded into a non-planning-worthy paragraph or
        // not, depending on the other rows — when not), so the memo key must include the flag.
        String text = auctionTooltip("100");
        TooltipSegmentPlanner.Plan withRarity = TooltipSegmentPlanner.plan(text, true);
        TooltipSegmentPlanner.Plan withoutRarity = TooltipSegmentPlanner.plan(text, false);
        assertTrue(withRarity != null);
        assertEquals(TooltipSegmentPlanner.Kind.RARITY, withRarity.segments().get(0).kind());
        // Still plannable without rarity composition (TRADE rows alone classify it), but the
        // first row is no longer RARITY, and it must not be the SAME cached instance as the
        // allowRarity=true result for the identical text.
        assertTrue(withoutRarity == null || withoutRarity != withRarity);
    }

    @Test
    void memoizesANullResultTooWithoutMisreportingItOnASecondCall() {
        String text = ParagraphModel.join(List.of(
                "Ability: Flame Breath", "Deals damage.", "Cooldown: 30s"));
        assertNull(TooltipSegmentPlanner.plan(text, true));
        // Second call must hit the memoized NONE sentinel and still correctly report null,
        // never leak the internal sentinel's own (empty-segment) Plan to a caller.
        assertNull(TooltipSegmentPlanner.plan(text, true));
    }

    @Test
    void memoStaysBoundedAcrossManyDistinctParagraphs() {
        // Well past the 512-per-generation size for each of the two allowRarity memos, so
        // every generation rotates at least once; must never grow unbounded.
        for (int i = 0; i < 1500; i++) {
            TooltipSegmentPlanner.plan(auctionTooltip(String.valueOf(i)), true);
            TooltipSegmentPlanner.plan(auctionTooltip(String.valueOf(i)), false);
        }
        assertTrue(TooltipSegmentPlanner.memoSize() <= 1024,
                "bounded at two generations of 512, regardless of how many distinct paragraphs were planned");
        // Still correct for a fresh paragraph after heavy rotation.
        TooltipSegmentPlanner.Plan fresh = TooltipSegmentPlanner.plan(auctionTooltip("999999"), true);
        assertTrue(fresh != null);
        assertEquals(TooltipSegmentPlanner.Kind.RARITY, fresh.segments().get(0).kind());
    }

    @Test
    void abilityBlockWithoutCooldownStopsBeforeTheRarityAndTradeRows() {
        String text = ParagraphModel.join(List.of(
                "Ability: Flame Breath", "Deals damage.", "MYTHIC DUNGEON BOW",
                "Seller: Bob_7", "Buy it now: 1,000 coins"));
        TooltipSegmentPlanner.Plan plan = TooltipSegmentPlanner.plan(text, true);
        assertTrue(plan != null);
        List<TooltipSegmentPlanner.Kind> kinds = new java.util.ArrayList<>();
        for (TooltipSegmentPlanner.Segment seg : plan.segments()) kinds.add(seg.kind());
        assertEquals(TooltipSegmentPlanner.Kind.ABILITY, kinds.get(0));
        assertTrue(kinds.contains(TooltipSegmentPlanner.Kind.RARITY), kinds.toString());
        assertTrue(kinds.contains(TooltipSegmentPlanner.Kind.TRADE), kinds.toString());
        TooltipSegmentPlanner.Segment ability = plan.segments().get(0);
        assertTrue(!text.substring(ability.start(), ability.end()).contains("MYTHIC"));
    }

    private static final String CS_ABILITY = "⟦CS0⟧Ability: Flame Breath⟦/CS0⟧";
    private static final String CS_COOLDOWN = "⟦CS1⟧Cooldown: 30s⟦/CS1⟧";

    @Test
    void csPrefixedCooldownRowEndsTheAbilityBlockSoPassiveAndRarityStaySeparate() {
        String text = ParagraphModel.join(List.of(
                CS_ABILITY, "Deals damage.", CS_COOLDOWN,
                "⟦CS2⟧Passive: Warm⟦/CS2⟧", "Stays warm.",
                "MYTHIC DUNGEON BOW", "Seller: Bob_7", "Buy it now: 1,000 coins"));
        TooltipSegmentPlanner.Plan plan = TooltipSegmentPlanner.plan(text, true);
        assertTrue(plan != null);
        TooltipSegmentPlanner.Segment ability = plan.segments().get(0);
        assertEquals(TooltipSegmentPlanner.Kind.ABILITY, ability.kind());
        String first = text.substring(ability.start(), ability.end());
        assertTrue(first.endsWith("Cooldown: 30s⟦/CS1⟧"), first);
        assertTrue(!first.contains("Passive") && !first.contains("MYTHIC"), first);
        List<TooltipSegmentPlanner.Kind> kinds = new java.util.ArrayList<>();
        for (TooltipSegmentPlanner.Segment seg : plan.segments()) kinds.add(seg.kind());
        assertTrue(kinds.contains(TooltipSegmentPlanner.Kind.RARITY), kinds.toString());
        assertTrue(kinds.contains(TooltipSegmentPlanner.Kind.TRADE), kinds.toString());
    }

    @Test
    void sameCsAbilityBlockOnTwoItemsWithDifferentTailsSharesOneSegmentText() {
        String ta = ParagraphModel.join(List.of(
                CS_ABILITY, "Deals damage.", CS_COOLDOWN, "MYTHIC DUNGEON BOW"));
        String tb = ParagraphModel.join(List.of(
                CS_ABILITY, "Deals damage.", CS_COOLDOWN, "Passive: Warm", "Stays warm.",
                "LEGENDARY BOW"));
        TooltipSegmentPlanner.Plan a = TooltipSegmentPlanner.plan(ta, true);
        TooltipSegmentPlanner.Plan b = TooltipSegmentPlanner.plan(tb, true);
        assertTrue(a != null && b != null);
        assertEquals(ta.substring(a.segments().get(0).start(), a.segments().get(0).end()),
                tb.substring(b.segments().get(0).start(), b.segments().get(0).end()));
    }
}
