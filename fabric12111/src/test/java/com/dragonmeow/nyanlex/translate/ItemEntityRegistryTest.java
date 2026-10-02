package com.dragonmeow.nyanlex.translate;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Item-name entity layer, core half: the reforge split is proven only by the item's own
 * {@code modifier}, never guessed from the name; line matching is exact, whole-word,
 * longest-first and never crosses a colour run; the registry is bounded LRU. Pure unit
 * tests — no service, cache or translator.
 */
class ItemEntityRegistryTest {

    // ---- reforge split: only modifier-proven, only the first word ----

    @Test
    void blendedAdaptiveBeltSplitsWhenTheModifierProvesIt() {
        ItemEntityRegistry r = new ItemEntityRegistry();
        ItemEntityRegistry.Entry e = r.register("Blended Adaptive Belt", "ADAPTIVE_BELT", "blended");
        assertTrue(e.split());
        assertEquals("Blended", e.reforgeWord());
        assertEquals("Adaptive Belt", e.baseName());
        assertEquals("Blended Adaptive Belt", e.coreName());
    }

    @Test
    void giantsSwordIsNeverSplitWithoutOrWithAnUnrelatedModifier() {
        ItemEntityRegistry r = new ItemEntityRegistry();
        assertFalse(r.register("Giant's Sword", "GIANTS_SWORD", null).split());
        assertFalse(r.register("Giant's Sword ✪✪", "GIANTS_SWORD", "fabled").split(),
                "fabled does not spell the first word; Giant's is not the reforge Giant");
        // With the Giant reforge the name really starts with that word: only then split.
        ItemEntityRegistry.Entry reforged = r.register("Giant Giant's Sword", "GIANTS_SWORD", "giant");
        assertTrue(reforged.split());
        assertEquals("Giant's Sword", reforged.baseName());
    }

    @Test
    void wiseDragonSetPieceIsNotSplitWithoutAModifier() {
        ItemEntityRegistry r = new ItemEntityRegistry();
        ItemEntityRegistry.Entry e = r.register("Wise Dragon Chestplate", "WISE_DRAGON_CHESTPLATE", null);
        assertFalse(e.split());
        assertNull(e.reforgeWord());
        assertNull(e.baseName());
        // Another reforge on the set piece strips only that reforge.
        ItemEntityRegistry.Entry fierce =
                r.register("Fierce Wise Dragon Chestplate", "WISE_DRAGON_CHESTPLATE", "fierce");
        assertEquals("Wise Dragon Chestplate", fierce.baseName());
    }

    @Test
    void wiseWiseDragonChestplateSplitsOnlyOneWise() {
        ItemEntityRegistry r = new ItemEntityRegistry();
        ItemEntityRegistry.Entry e =
                r.register("Wise Wise Dragon Chestplate ✪✪✪", "WISE_DRAGON_CHESTPLATE", "wise");
        assertTrue(e.split());
        assertEquals("Wise", e.reforgeWord());
        assertEquals("Wise Dragon Chestplate", e.baseName());
    }

    @Test
    void unknownModifierOrAMismatchedFirstWordNeverSplits() {
        ItemEntityRegistry r = new ItemEntityRegistry();
        assertFalse(r.register("Blended Adaptive Belt", "ADAPTIVE_BELT", "not_a_reforge").split());
        assertFalse(r.register("Fierce Adaptive Belt", "ADAPTIVE_BELT", "blended").split(),
                "the modifier must spell the name's first word");
        assertFalse(r.register("Adaptive Belt", "ADAPTIVE_BELT", "blended").split(),
                "no reforge word at the start of the name");
        assertFalse(r.register("Enchanted Diamond", "ENCHANTED_DIAMOND", "enchanted").split(),
                "Enchanted is not a shipped reforge word: never guessed");
        assertFalse(r.register("Blended", "X", "blended").split(), "no base name left");
    }

    @Test
    void modifierMayCarryAnUnderscoreSuffixAndMultiWordReforgesMatchWhole() {
        assertTrue(ItemEntityRegistry.modifierProves("Rich", "rich_bow"));
        assertTrue(ItemEntityRegistry.modifierProves("Blood-Soaked", "blood_soaked"));
        assertTrue(ItemEntityRegistry.modifierProves("Jerry's", "JERRYS"));
        assertFalse(ItemEntityRegistry.modifierProves("Rich", "richer"));
        assertFalse(ItemEntityRegistry.modifierProves("Rich", "bow_rich"));

        String[] deepFried = ItemEntityRegistry.splitReforge("Deep Fried Rod of Champions", "deep_fried");
        assertNotNull(deepFried);
        assertEquals("Deep Fried", deepFried[0]);
        assertEquals("Rod of Champions", deepFried[1]);
        String[] bloodSoaked = ItemEntityRegistry.splitReforge("Blood-Soaked Axe of the Shredded", "blood_soaked");
        assertEquals("Blood-Soaked", bloodSoaked[0]);
        // The first word is compared case-insensitively.
        assertEquals("Adaptive Belt",
                ItemEntityRegistry.splitReforge("BLENDED Adaptive Belt", "blended")[1]);
    }

    @Test
    void coreNameDropsFormattingCodesAndOuterDecorationOnly() {
        assertEquals("Withered Hyperion", ItemEntityRegistry.coreName("⚚ Withered Hyperion ✪✪✪✪✪➎"));
        assertEquals("Ancient Skeleton Master Chestplate",
                ItemEntityRegistry.coreName("§5Ancient Skeleton Master Chestplate §6✪✪✪✪✪"));
        assertEquals("Bonzo's Staff (Dungeon)", ItemEntityRegistry.coreName("Bonzo's Staff (Dungeon)"));
        assertEquals("", ItemEntityRegistry.coreName("✪✪✪"));
    }

    @Test
    void repeatedRegistrationIsAFastNoOpAndNeverLosesTheModifier() {
        ItemEntityRegistry r = new ItemEntityRegistry();
        ItemEntityRegistry.Entry first = r.register("Blended Adaptive Belt", "ADAPTIVE_BELT", "blended");
        long version = r.version();
        assertSame(first, r.register("Blended Adaptive Belt", "ADAPTIVE_BELT", "blended"));
        assertSame(first, r.register("Blended Adaptive Belt", null, null),
                "a poorer registration keeps the proven data");
        assertEquals(version, r.version(), "no change, no memo invalidation");
        assertEquals(1, r.size());
    }

    @Test
    void protocolTextOrPureDecorationIsNotRegistered() {
        ItemEntityRegistry r = new ItemEntityRegistry();
        assertNull(r.register("✪✪✪", "X", null));
        assertNull(r.register("⟦CS0⟧Evil⟦/CS0⟧ Name", "X", null));
        assertNull(r.register("  ", "X", null));
        assertNull(r.register(null, "X", null));
        assertEquals(0, r.size());
    }

    // ---- line matching ----

    @Test
    void matchesExactCaseWholeWordAndLongestFirst() {
        ItemEntityRegistry r = new ItemEntityRegistry();
        r.register("Wise Dragon Chestplate", "WISE_DRAGON_CHESTPLATE", null);
        r.register("Wise Wise Dragon Chestplate", "WISE_DRAGON_CHESTPLATE", "wise");
        String line = "Chestplate: Wise Wise Dragon Chestplate ✪✪✪";
        List<int[]> spans = r.matchSpans(line);
        assertEquals(1, spans.size(), "the longer name wins over the overlapping shorter one");
        assertEquals("Wise Wise Dragon Chestplate", line.substring(spans.get(0)[0], spans.get(0)[1]));
        assertEquals(1, spans.get(0)[2], "split flag");

        assertTrue(r.matchSpans("chestplate: wise dragon chestplate").isEmpty(), "case must match");
        assertTrue(r.matchSpans("Wise Dragon Chestplates are nice").isEmpty(), "whole word only");
        assertTrue(r.matchSpans("xWise Dragon Chestplate").isEmpty(), "whole word only");
        List<int[]> two = r.matchSpans("Wise Dragon Chestplate or Wise Dragon Chestplate");
        assertEquals(2, two.size());
    }

    @Test
    void unregisteredShortAndDataLessNamesAreNeverMatched() {
        ItemEntityRegistry r = new ItemEntityRegistry();
        r.register("Stick", "STICK", null);                 // one word, shorter than 8
        r.register("Go Back", null, null);                   // menu button: no SkyBlock data
        r.register("Hyperion", "HYPERION", null);            // one word, 8 characters: ok
        assertTrue(r.matchSpans("Use a Stick here").isEmpty(), "short single word");
        assertTrue(r.matchSpans("Click to Go Back").isEmpty(), "no SkyBlock id or modifier");
        assertTrue(r.matchSpans("Aspect of the End is great").isEmpty(), "not registered");
        assertEquals(1, r.matchSpans("Upgrade your Hyperion now").size());
        assertFalse(r.hasMatchableNames() && r.forCore("Stick").matchable());
    }

    @Test
    void neverMatchesAcrossAColourRunOrIntoAProtocolToken() {
        ItemEntityRegistry r = new ItemEntityRegistry();
        r.register("Withered Hyperion ✪✪✪✪✪", "HYPERION", "withered");
        assertTrue(r.matchSpans("⟦CS0⟧Withered⟦/CS0⟧ ⟦CS1⟧Hyperion⟦/CS1⟧ is great").isEmpty());
        String inRun = "⟦CS0⟧Sold ⟦/CS0⟧⟦CS1⟧Withered Hyperion⟦/CS1⟧ ⟦CS2⟧✪✪✪✪✪⟦/CS2⟧";
        List<int[]> spans = r.matchSpans(inRun);
        assertEquals(1, spans.size());
        assertEquals("Withered Hyperion", inRun.substring(spans.get(0)[0], spans.get(0)[1]));
    }

    @Test
    void memoIsBoundedAndFollowsTheRegistryVersion() {
        ItemEntityRegistry r = new ItemEntityRegistry();
        r.register("Withered Hyperion", "HYPERION", "withered");
        for (int i = 0; i < 5_000; i++) {
            List<int[]> spans = r.matchSpans("Line " + i + ": Withered Hyperion");
            assertEquals(1, spans.size());
        }
        assertTrue(r.memoSize() <= 2 * 1024, "two bounded generations");

        String line = "Loadout: Necron's Handle and Withered Hyperion";
        assertEquals(1, r.matchSpans(line).size());
        long before = r.version();
        r.register("Necron's Handle", "NECRON_HANDLE", null);
        assertNotEquals(before, r.version());
        assertEquals(2, r.matchSpans(line).size(), "a new name invalidates the memoised spans");
    }

    @Test
    void registryIsBoundedLeastRecentlyRegisteredFirst() {
        ItemEntityRegistry r = new ItemEntityRegistry(3);
        r.register("Alpha Blade One", "A", null);
        r.register("Bravo Blade Two", "B", null);
        r.register("Charlie Blade Three", "C", null);
        r.register("Alpha Blade One", "A", null);            // touch: Bravo is now eldest
        r.register("Delta Blade Four", "D", null);
        assertEquals(3, r.size());
        assertNull(r.forDisplayName("Bravo Blade Two"), "least recently registered evicted");
        assertNotNull(r.forDisplayName("Alpha Blade One"));
        assertNotNull(r.forDisplayName("Charlie Blade Three"));
        assertNotNull(r.forDisplayName("Delta Blade Four"));
        assertTrue(r.matchSpans("I lost my Bravo Blade Two").isEmpty(), "evicted: no longer matched");
        assertEquals(1, r.matchSpans("I found my Delta Blade Four").size());

        // Shared core names (star variants) stay matchable while any variant is resident.
        ItemEntityRegistry s = new ItemEntityRegistry(2);
        s.register("Withered Hyperion ✪", "HYPERION", "withered");
        s.register("Withered Hyperion ✪✪✪", "HYPERION", "withered");
        s.register("Other Thing Here", "OTHER", null);      // evicts the ✪ variant only
        assertNull(s.forDisplayName("Withered Hyperion ✪"));
        assertTrue(s.forCore("Withered Hyperion").split());
        assertEquals(1, s.matchSpans("Withered Hyperion").size());
    }

    // ---- NameMasker: E slots in the same pass as P slots (B1/B7) ----

    @Test
    void entitySlotsShareTheSinglePassAndIndexByFirstAppearanceWithPSlots() {
        ItemEntityRegistry r = new ItemEntityRegistry();
        r.register("Withered Hyperion ✪✪✪✪✪", "HYPERION", "withered");
        DoNotTranslateMatcher terms = DoNotTranslateMatcher.compile(List.of("SkyBlock"));
        String text = "[Auction] Bob_77 bought Withered Hyperion for 5 SkyBlock coins, thanks Steve";
        NameMasker.Masked m = NameMasker.mask(text, Set.of("Steve"), terms, r);
        assertEquals("[Auction] ⟦0⟧ bought ⟦1⟧ for 5 ⟦2⟧ coins, thanks ⟦3⟧", m.text());
        assertEquals(List.of("Bob_77", "Withered Hyperion", "SkyBlock", "Steve"), m.names());
        assertEquals(List.of(1), m.entitySlots());
        assertEquals(List.of("Bob_77"), m.patternNames());
        assertTrue(m.isEntitySlot(1));
        assertFalse(m.isEntitySlot(0));
    }

    @Test
    void doNotTranslateAndPlayerNamesTakePrecedenceOverAnItemName() {
        ItemEntityRegistry r = new ItemEntityRegistry();
        r.register("Withered Hyperion", "HYPERION", "withered");
        NameMasker.Masked dnt = NameMasker.mask("Sold Withered Hyperion today", List.of(),
                DoNotTranslateMatcher.compile(List.of("Hyperion")), r);
        assertEquals("Sold Withered ⟦0⟧ today", dnt.text());
        assertFalse(dnt.hasEntities());

        NameMasker.Masked tab = NameMasker.mask("Withered gave Withered Hyperion away",
                Set.of("Withered"), DoNotTranslateMatcher.EMPTY, r);
        assertEquals("⟦0⟧ gave ⟦0⟧ Hyperion away", tab.text());
        assertFalse(tab.hasEntities());
    }

    @Test
    void sameSpellingAsAFrameNameAndAnItemNameGetsTwoIndices() {
        ItemEntityRegistry r = new ItemEntityRegistry();
        r.register("Hyperion", "HYPERION", null);
        NameMasker.Masked m = NameMasker.mask("[Auction] Hyperion bought Hyperion for 5 coins",
                List.of(), DoNotTranslateMatcher.EMPTY, r);
        assertEquals("[Auction] ⟦0⟧ bought ⟦1⟧ for 5 coins", m.text());
        assertEquals(List.of("Hyperion", "Hyperion"), m.names());
        assertEquals(List.of(1), m.entitySlots());
        assertEquals(List.of("Hyperion"), m.patternNames());
    }

    @Test
    void nameOnlyLineKeepsOnlySplitItemsAnUnsplitTitleKeepsItsOwnKey() {
        ItemEntityRegistry r = new ItemEntityRegistry();
        r.register("Wise Dragon Chestplate ✪✪✪", "WISE_DRAGON_CHESTPLATE", null);
        r.register("Ancient Skeleton Master Chestplate ✪✪✪✪✪", "SKELETON_MASTER_CHESTPLATE", "ancient");
        String unsplitTitle = "⟦CS0⟧Wise Dragon Chestplate⟦/CS0⟧ ⟦CS1⟧✪✪✪⟦/CS1⟧";
        NameMasker.Masked unsplit = NameMasker.mask(unsplitTitle, List.of(), DoNotTranslateMatcher.EMPTY, r);
        assertEquals(unsplitTitle, unsplit.text(), "unchanged: the title keeps its own key");
        assertFalse(unsplit.hasMasks());

        String splitTitle = "⟦CS0⟧Ancient Skeleton Master Chestplate⟦/CS0⟧ ⟦CS1⟧✪✪✪✪✪⟦/CS1⟧";
        NameMasker.Masked split = NameMasker.mask(splitTitle, List.of(), DoNotTranslateMatcher.EMPTY, r);
        assertEquals("⟦CS0⟧⟦0⟧⟦/CS0⟧ ⟦CS1⟧✪✪✪✪✪⟦/CS1⟧", split.text());
        assertEquals(List.of(0), split.entitySlots());

        // Inside a real line both are substituted.
        NameMasker.Masked line = NameMasker.mask("Chestplate: Wise Dragon Chestplate ✪✪✪",
                List.of(), DoNotTranslateMatcher.EMPTY, r);
        assertEquals("Chestplate: ⟦0⟧ ✪✪✪", line.text());
        assertTrue(line.hasEntities());
    }

    @Test
    void withoutARegistryMaskingIsByteForByteUnchanged() {
        ItemEntityRegistry r = new ItemEntityRegistry();
        r.register("Withered Hyperion", "HYPERION", "withered");
        String text = "Seller: [MVP+] Bob_7 sells Withered Hyperion";
        NameMasker.Masked plain = NameMasker.mask(text, Set.of("Steve"), DoNotTranslateMatcher.EMPTY);
        NameMasker.Masked nullRegistry =
                NameMasker.mask(text, Set.of("Steve"), DoNotTranslateMatcher.EMPTY, null);
        assertEquals(plain, nullRegistry);
        assertArrayEquals(new Object[] {plain.text(), plain.names()},
                new Object[] {nullRegistry.text(), nullRegistry.names()});
        assertFalse(plain.hasEntities());
        // Idempotent with the registry: a masked key is never masked again.
        NameMasker.Masked once = NameMasker.mask("Buy Withered Hyperion", List.of(), DoNotTranslateMatcher.EMPTY, r);
        NameMasker.Masked twice = NameMasker.mask(once.text(), List.of(), DoNotTranslateMatcher.EMPTY, r);
        assertEquals(once.text(), twice.text());
        assertFalse(twice.hasMasks());
    }
}
