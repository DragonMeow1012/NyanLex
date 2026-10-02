package com.dragonmeow.nyanlex.hub.tool;

import com.dragonmeow.nyanlex.translate.NameMasker;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link UnmaskedNameConverter} is package-private, so its correctness contract is
 *  tested here rather than indirectly through {@link HubExportTool} alone. */
class UnmaskedNameConverterTest {

    // ------------------------------------------------------------------ the core contract

    /**
     * The whole point of this converter: the row's KEY is a legacy, PARTIALLY masked
     * string (some terms already turned into bare {@code ⟦n⟧} slots by an older release
     * that did not yet recognise a given {@code PlayerNamePatterns} frame), and a raw
     * player name still sits literally in it. Converting that legacy key must produce the
     * EXACT SAME string {@link NameMasker#mask} would produce from scratch on the
     * original (never-masked) text — otherwise the current mod can never look the
     * imported row back up by key.
     *
     * <p>Here "Alice" is masked by the legacy capture (a TAB name match, independent of
     * any frame table version) while "Bob123" was NOT — it only became maskable once the
     * "ah.field" {@code Seller: NAME} frame was added. A fresh {@code NameMasker.mask}
     * call masks BOTH in one pass (frame detection is unconditional); the legacy key only
     * has "Alice" masked. Converting the legacy key must reconstruct the fresh result
     * byte-for-byte, including renumbering "Alice" from whatever index it already has
     * (0, assigned first by position) and assigning "Bob123" the next index by its own,
     * later position — exactly what a fresh mask would do.</p>
     */
    @Test
    void convertedKeyMatchesFreshMaskOfSameOriginalTextEvenWhenAFrameIsNewerThanTheCapture() {
        String originalRawText = "Hello Alice!\nSeller: Bob123\nBuy it now: 50 coins";
        String freshKey = NameMasker.mask(originalRawText, Set.of("Alice")).text();

        // What the row would have looked like written by an OLDER release that only had
        // TAB-name masking and did not yet recognise the "Seller: NAME" frame: "Alice" is
        // masked (TAB matching does not depend on the frame table), "Bob123" is still raw.
        String legacyKey = "Hello ⟦0⟧!\nSeller: Bob123\nBuy it now: 50 coins";
        String legacyValue = "哈囉 ⟦0⟧！\n賣家：Bob123\n立即購買：50 金幣";

        UnmaskedNameConverter.Result result = UnmaskedNameConverter.convert(legacyKey, legacyValue);

        assertTrue(result.ok(), "conversion must succeed: Bob123 literally appears in the value");
        assertEquals(freshKey, result.key(),
                "the converted key must equal NameMasker.mask()'s result on the same original text,"
                        + " or the current mod can never look the imported row back up");
        assertEquals("Hello ⟦0⟧!\nSeller: ⟦1⟧\nBuy it now: 50 coins", result.key());
        assertEquals("哈囉 ⟦0⟧！\n賣家：⟦1⟧\n立即購買：50 金幣",
                result.value());
    }

    // ------------------------------------------------------------------ basic behavior

    @Test
    void convertsASimpleUnprefixedKeyWithNoPriorMasking() {
        UnmaskedNameConverter.Result result =
                UnmaskedNameConverter.convert("Seller: AceRay51", "賣家：AceRay51");

        assertTrue(result.ok());
        assertEquals("Seller: ⟦0⟧", result.key());
        assertEquals("賣家：⟦0⟧", result.value());
    }

    @Test
    void noNameSpansIsANoOpAndAlwaysSucceeds() {
        UnmaskedNameConverter.Result result = UnmaskedNameConverter.convert("Diamond Sword", "鋻石劍");

        assertTrue(result.ok());
        assertEquals("Diamond Sword", result.key());
        assertEquals("鋻石劍", result.value());
    }

    @Test
    void failsWhenTheExactNameTextIsMissingFromTheValue() {
        // The AI reshaped the proper noun (case change here, but any reshaping fails the
        // same way): the exact substring "AceRay51" is not found in the value.
        UnmaskedNameConverter.Result result =
                UnmaskedNameConverter.convert("Seller: AceRay51", "賣家：Aceray51");

        assertFalse(result.ok());
        assertEquals("name-not-found-in-value", result.reason());
    }

    @Test
    void renumbersAnExistingSlotThatEndsUpAfterTheNewlyMaskedName() {
        // The raw name comes first in the key; a pre-existing bare ⟦0⟧ (a buyer, already
        // masked by TAB matching) sits on the NEXT line. Position-based renumbering must
        // give the new name index 0 and shift the old slot from 0 to 1, in BOTH key and
        // value — a plain "append new names at the end" strategy would get this wrong.
        String key = "Seller: AceRay51\nBuyer: ⟦0⟧";
        String value = "賣家：AceRay51\n買家：⟦0⟧";

        UnmaskedNameConverter.Result result = UnmaskedNameConverter.convert(key, value);

        assertTrue(result.ok());
        assertEquals("Seller: ⟦0⟧\nBuyer: ⟦1⟧", result.key());
        assertEquals("賣家：⟦0⟧\n買家：⟦1⟧", result.value());
    }

    @Test
    void handlesTwoDistinctRawNamesInOneRow() {
        String key = "Seller: AceRay51\nBuyer: BobTheBuilder";
        String value = "賣家：AceRay51\n買家：BobTheBuilder";

        UnmaskedNameConverter.Result result = UnmaskedNameConverter.convert(key, value);

        assertTrue(result.ok());
        assertEquals("Seller: ⟦0⟧\nBuyer: ⟦1⟧", result.key());
        assertEquals("賣家：⟦0⟧\n買家：⟦1⟧", result.value());
    }

    @Test
    void replacesEveryOccurrenceOfTheSameNameInTheValue() {
        String key = "Seller: AceRay51";
        String value = "賣家：AceRay51（AceRay51）";

        UnmaskedNameConverter.Result result = UnmaskedNameConverter.convert(key, value);

        assertTrue(result.ok());
        assertEquals("Seller: ⟦0⟧", result.key());
        assertEquals("賣家：⟦0⟧（⟦0⟧）", result.value());
    }

    @Test
    void convertedKeyNeverLeavesARawNameForTheBackstopToCatch() {
        UnmaskedNameConverter.Result result =
                UnmaskedNameConverter.convert("Seller: AceRay51", "賣家：AceRay51");

        assertTrue(result.ok());
        assertTrue(com.dragonmeow.nyanlex.translate.PlayerNamePatterns.nameSpans(result.key()).isEmpty(),
                "a correctly converted key must never still show a raw name");
    }
}
