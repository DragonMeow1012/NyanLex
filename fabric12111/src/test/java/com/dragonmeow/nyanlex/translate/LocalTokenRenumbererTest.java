package com.dragonmeow.nyanlex.translate;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalTokenRenumbererTest {

    @Test
    void alreadyLocalPairIsUnchanged() {
        LocalTokenRenumberer.Renumbered r =
                LocalTokenRenumberer.renumber("Seller: ⟦0⟧", "賣家：⟦0⟧");
        assertEquals("Seller: ⟦0⟧", r.key());
        assertEquals("賣家：⟦0⟧", r.value());
    }

    @Test
    void globalIndexIsRenumberedToZero() {
        // "Seller: ⟦2⟧" as it sat inside a larger paragraph (two earlier names already
        // claimed slots 0/1) must come back out as the STANDALONE request's own "⟦0⟧".
        LocalTokenRenumberer.Renumbered r =
                LocalTokenRenumberer.renumber("Seller: ⟦2⟧", "賣家：⟦2⟧");
        assertEquals("Seller: ⟦0⟧", r.key());
        assertEquals("賣家：⟦0⟧", r.value());
    }

    @Test
    void mtSlotGlobalIndexIsRenumberedIndependentlyOfPSlots() {
        // MT and bare P-slots have independent 0,1,2… sequences: a row carrying global
        // MT3 (because two earlier rows each used one MT slot) still renumbers to MT0 on
        // its own, with no bearing from the bare-slot sequence.
        LocalTokenRenumberer.Renumbered r = LocalTokenRenumberer.renumber(
                "Buy it now: ⟦MT3⟧ coins", "一口價：⟦MT3⟧ 金幣");
        assertEquals("Buy it now: ⟦MT0⟧ coins", r.key());
        assertEquals("一口價：⟦MT0⟧ 金幣", r.value());
    }

    @Test
    void csOpenAndCloseShareOneRenumberedIndex() {
        LocalTokenRenumberer.Renumbered r = LocalTokenRenumberer.renumber(
                "⟦CS5⟧Seller: ⟦2⟧⟦/CS5⟧",
                "⟦CS5⟧賣家：⟦2⟧⟦/CS5⟧");
        assertEquals("⟦CS0⟧Seller: ⟦0⟧⟦/CS0⟧", r.key());
        assertEquals("⟦CS0⟧賣家：⟦0⟧⟦/CS0⟧", r.value());
    }

    @Test
    void multipleDistinctTokensOfTheSameKindEachGetTheirOwnLocalIndexInOrder() {
        LocalTokenRenumberer.Renumbered r = LocalTokenRenumberer.renumber(
                "⟦5⟧ and ⟦7⟧", "⟦5⟧ 和 ⟦7⟧");
        assertEquals("⟦0⟧ and ⟦1⟧", r.key());
        assertEquals("⟦0⟧ 和 ⟦1⟧", r.value());
    }

    @Test
    void valueTokenNotPresentInKeyMakesTheWholeRowUnrenumberable() {
        // The value references MT index 2, which keyRow never mentions at all -- the real
        // scope of that token is some OTHER row of the original paragraph, not this one.
        LocalTokenRenumberer.Renumbered r = LocalTokenRenumberer.renumber(
                "Buy it now: ⟦MT3⟧ coins", "一口價：⟦MT2⟧ 金幣");
        assertNull(r);
    }

    @Test
    void valueTokenWithTheSameIndexButADifferentKindIsAlsoRejected() {
        // keyRow only ever saw bare index "3"; the value instead references MT3 -- a
        // different namespace entirely, not the same token under a typo.
        LocalTokenRenumberer.Renumbered r = LocalTokenRenumberer.renumber(
                "Seller: ⟦3⟧", "賣家：⟦MT3⟧");
        assertNull(r);
    }

    @Test
    void noTokensAtAllIsAHarmlessNoOp() {
        LocalTokenRenumberer.Renumbered r = LocalTokenRenumberer.renumber("Ends in: 2h 30m", "剩餘：2h 30m");
        assertEquals("Ends in: 2h 30m", r.key());
        assertEquals("剩餘：2h 30m", r.value());
    }

    @Test
    void nullInputsReturnNull() {
        assertNull(LocalTokenRenumberer.renumber(null, "x"));
        assertNull(LocalTokenRenumberer.renumber("x", null));
    }

    // ---- localize/restore: single-string local renumbering for the LIVE fresh-request
    // path (composeStructuredTooltip/warmStructuredTooltipComponents), not just the
    // legacy-whole-cache conversion renumber(key,value) above. ----

    @Test
    void localizeRenumbersEachKindIndependentlyFromFirstAppearance() {
        // Same shape as two sibling items' "Seller:" row, but sitting at a DIFFERENT
        // global CS/bare-slot offset purely because of how many OTHER style runs/slots
        // happened to precede it in each item's own tooltip paragraph.
        LocalTokenRenumberer.Localized a = LocalTokenRenumberer.localize(
                "⟦CS11⟧Seller:⟦/CS11⟧ ⟦CS12⟧[MVP⟦/CS12⟧⟦CS13⟧+⟦/CS13⟧⟦CS14⟧] ⟦2⟧⟦/CS14⟧");
        LocalTokenRenumberer.Localized b = LocalTokenRenumberer.localize(
                "⟦CS4⟧Seller:⟦/CS4⟧ ⟦CS5⟧[MVP⟦/CS5⟧⟦CS6⟧+⟦/CS6⟧⟦CS7⟧] ⟦0⟧⟦/CS7⟧");
        assertEquals(a.text(), b.text(),
                "the same semantic row must localize to the identical cache key regardless "
                        + "of its global CS/bare-slot offset in either item's own paragraph");
        assertEquals("⟦CS0⟧Seller:⟦/CS0⟧ ⟦CS1⟧[MVP⟦/CS1⟧⟦CS2⟧+⟦/CS2⟧⟦CS3⟧] ⟦0⟧⟦/CS3⟧", a.text());
    }

    @Test
    void restoreInvertsLocalizeBackToTheOriginalGlobalIndices() {
        String row = "⟦CS11⟧Seller:⟦/CS11⟧ ⟦CS12⟧[MVP⟦/CS12⟧⟦CS13⟧+⟦/CS13⟧⟦CS14⟧] ⟦2⟧⟦/CS14⟧";
        LocalTokenRenumberer.Localized localized = LocalTokenRenumberer.localize(row);
        // A fake "translated" local-numbered value, same token skeleton.
        String localizedValue = "⟦CS0⟧賣家：⟦/CS0⟧ ⟦CS1⟧[MVP⟦/CS1⟧⟦CS2⟧+⟦/CS2⟧⟦CS3⟧] ⟦0⟧⟦/CS3⟧";
        String restored = LocalTokenRenumberer.restore(localizedValue, localized.newToOld());
        assertEquals("⟦CS11⟧賣家：⟦/CS11⟧ ⟦CS12⟧[MVP⟦/CS12⟧⟦CS13⟧+⟦/CS13⟧⟦CS14⟧] ⟦2⟧⟦/CS14⟧", restored);
    }

    @Test
    void localizeRoundTripsThroughRestoreForNoOpText() {
        LocalTokenRenumberer.Localized localized = LocalTokenRenumberer.localize("Ends in: 2h 30m");
        assertEquals("Ends in: 2h 30m", localized.text());
        assertTrue(localized.newToOld().isEmpty());
        assertEquals("剩餘：2h 30m", LocalTokenRenumberer.restore("剩餘：2h 30m", localized.newToOld()));
    }

    @Test
    void localizeAndRestoreAreNullSafe() {
        assertNull(LocalTokenRenumberer.localize(null));
        assertNull(LocalTokenRenumberer.restore(null, java.util.Map.of()));
        assertEquals("x", LocalTokenRenumberer.restore("x", null));
    }

    @Test
    void localizeKeepsMtAndBareSlotsIndependentJustLikeRenumber() {
        LocalTokenRenumberer.Localized localized =
                LocalTokenRenumberer.localize("⟦CS16⟧Buy it now:⟦/CS16⟧ ⟦CS17⟧⟦MT0⟧ coins⟦/CS17⟧");
        assertEquals("⟦CS0⟧Buy it now:⟦/CS0⟧ ⟦CS1⟧⟦MT0⟧ coins⟦/CS1⟧", localized.text());
    }

    @Test
    void rowBreakTokensAreRenumberedFromZeroAndRestoredToTheirGlobalIndices() {
        String global = "Ability: A⟦PB14⟧Deals ⟦MT3⟧ damage.⟦PB15⟧More";
        LocalTokenRenumberer.Localized local = LocalTokenRenumberer.localize(global);
        assertEquals("Ability: A⟦PB0⟧Deals ⟦MT0⟧ damage.⟦PB1⟧More", local.text());
        assertEquals(global, LocalTokenRenumberer.restore(local.text(), local.newToOld()));
    }
}
