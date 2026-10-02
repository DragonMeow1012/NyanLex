package com.dragonmeow.nyanlex.hub;

import com.dragonmeow.nyanlex.translate.TextFilter;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Covers both halves of {@link HubImportValidator#acceptsOnHit}: the shared
 *  {@code TranslationCache.usableForBulkTransfer} shape checks, and the hub-specific
 *  {@link TextFilter#hasForeignUrl} guard. */
class HubImportValidatorTest {

    @Test
    void acceptsAnOrdinaryTranslatedRow() {
        assertTrue(HubImportValidator.acceptsOnHit("Diamond Sword", "鑽石劍"));
    }

    @Test
    void rejectsUntranslatedEcho() {
        assertFalse(HubImportValidator.acceptsOnHit("Diamond Sword", "Diamond Sword"));
    }

    @Test
    void rejectsMismatchedProtectedPlaceholderCount() {
        // usableForBulkTransfer requires the exact same ⟦n⟧ multiset.
        assertFalse(HubImportValidator.acceptsOnHit("Hello ⟦0⟧, welcome", "你好，歡迎"));
    }

    @Test
    void rejectsATranslationThatIntroducesAUrlTheSourceNeverHad() {
        assertFalse(HubImportValidator.acceptsOnHit("Buy VIP now", "立即購買 VIP，詳見 http://evil.example.com"));
    }

    @Test
    void acceptsATranslationThatKeepsTheSourcesOwnUrl() {
        assertTrue(HubImportValidator.acceptsOnHit(
                "Visit hypixel.net/store for more", "造訪 hypixel.net/store 瞭解更多"));
    }

    @Test
    void hasForeignUrlDetectsHttpLink() {
        assertTrue(TextFilter.hasForeignUrl("hello", "hello http://example.com/x"));
    }

    @Test
    void hasForeignUrlDetectsWwwAddress() {
        assertTrue(TextFilter.hasForeignUrl("hello", "hello www.example.com"));
    }

    @Test
    void hasForeignUrlDetectsBareDomain() {
        assertTrue(TextFilter.hasForeignUrl("hello", "造訪 example.com 了解更多"));
    }

    @Test
    void hasForeignUrlIgnoresUrlAlreadyInSource() {
        assertFalse(TextFilter.hasForeignUrl("visit example.com now", "現在造訪 example.com"));
    }

    @Test
    void hasForeignUrlFalseWhenNeitherSideHasOne() {
        assertFalse(TextFilter.hasForeignUrl("Diamond Sword", "鑽石劍"));
    }

    @Test
    void mergeCheckIsSourceFreeAndRejectsAnyUrlOrEmptyValue() {
        assertTrue(HubImportValidator.acceptsOnMerge("鑽石劍"));
        assertFalse(HubImportValidator.acceptsOnMerge(""));
        assertFalse(HubImportValidator.acceptsOnMerge("造訪 example.com"));
    }
}
