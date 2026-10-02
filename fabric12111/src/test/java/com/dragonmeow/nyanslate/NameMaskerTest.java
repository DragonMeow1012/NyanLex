package com.dragonmeow.nyanslate;

import com.dragonmeow.nyanslate.translate.DoNotTranslateMatcher;
import com.dragonmeow.nyanslate.translate.NameMasker;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NameMaskerTest {

    @Test
    void masksAndRoundTrips() {
        NameMasker.Masked m = NameMasker.mask("Steve123: hello world", List.of("Steve123"));
        assertFalse(m.text().contains("Steve123"), "name must not appear in the text sent out");
        assertTrue(m.text().contains("hello world"));
        // Simulate the backend translating only the non-masked part, keeping the placeholder.
        String translatedMasked = m.text().replace("hello world", "你好世界");
        assertEquals("Steve123: 你好世界", NameMasker.unmask(translatedMasked, m.names()));
    }

    @Test
    void masksMultipleNames() {
        NameMasker.Masked m = NameMasker.mask("Alice waved at Bob", List.of("Alice", "Bob"));
        assertFalse(m.text().contains("Alice"));
        assertFalse(m.text().contains("Bob"));
        assertEquals("Alice waved at Bob", NameMasker.unmask(m.text(), m.names()));
    }

    @Test
    void onlyMasksWholeWordsNotSubstrings() {
        // Name "in" must NOT match inside "lobby"/"window".
        NameMasker.Masked m = NameMasker.mask("Bob is in the window", List.of("in"));
        // "in" appears as a standalone word once; "window" must be untouched.
        assertTrue(m.text().contains("window"), "substring inside a word must not be masked");
        assertEquals("Bob is in the window", NameMasker.unmask(m.text(), m.names()));
    }

    @Test
    void repeatedNameUsesOnePlaceholder() {
        NameMasker.Masked m = NameMasker.mask("Bob hit Bob again", List.of("Bob"));
        assertEquals(1, m.names().size(), "the same name should map to a single placeholder");
        assertFalse(m.text().contains("Bob"));
        assertEquals("Bob hit Bob again", NameMasker.unmask(m.text(), m.names()));
    }

    @Test
    void noNamesIsNoOp() {
        NameMasker.Masked m = NameMasker.mask("hello world", List.of());
        assertEquals("hello world", m.text());
        assertFalse(m.hasMasks());
        // With no masks, unmask returns the translated text unchanged.
        assertEquals("你好世界", NameMasker.unmask("你好世界", m.names()));
    }

    @Test
    void nonEmptyNamesWithoutAMatchReturnsTheOriginalString() {
        String original = "Welcome to the village";

        NameMasker.Masked m = NameMasker.mask(original, java.util.Set.of("Steve123"));

        assertSame(original, m.text(), "the no-match path must not build a replacement string");
        assertFalse(m.hasMasks());
    }

    @Test
    void firstLateMatchPreservesTheUncopiedPrefixAndSuffix() {
        NameMasker.Masked m = NameMasker.mask(
                "Welcome, Steve123, to the village", java.util.Set.of("Steve123"));

        assertEquals("Welcome, Steve123, to the village", NameMasker.unmask(m.text(), m.names()));
        assertEquals(List.of("Steve123"), m.names());
    }

    // ---- P1.2: strong-frame player names share the single masking pass ----

    @Test
    void frameNameIsMaskedEvenWithoutTabNamesOrTerms() {
        NameMasker.Masked m = NameMasker.mask("Seller: [MVP+] Seller_42", List.of(),
                DoNotTranslateMatcher.EMPTY);
        assertEquals("Seller: ⟦0⟧ ⟦1⟧", m.text(), "rank badge AND name both leave the key");
        assertEquals(List.of("[MVP+]", "Seller_42"), m.names());
        assertEquals(List.of("[MVP+]", "Seller_42"), m.patternNames(), "isolated only by a frame");
        assertEquals("Seller: [MVP+] Seller_42", NameMasker.unmask(m.text(), m.names()));
    }

    @Test
    void tabNameFrameNameAndTermShareOneIndexSpaceInOrderOfFirstAppearance() {
        DoNotTranslateMatcher terms = DoNotTranslateMatcher.compile(List.of("SkyBlock"));
        String text = "Alice likes SkyBlock ⟦PB0⟧ Seller: [MVP+] Bob_7 ⟦PB1⟧ Alice and SkyBlock";
        NameMasker.Masked m = NameMasker.mask(text, Set.of("Alice"), terms);
        assertEquals("⟦0⟧ likes ⟦1⟧ ⟦PB0⟧ Seller: ⟦2⟧ ⟦3⟧ ⟦PB1⟧ ⟦0⟧ and ⟦1⟧", m.text());
        assertEquals(List.of("Alice", "SkyBlock", "[MVP+]", "Bob_7"), m.names());
        assertEquals(List.of("[MVP+]", "Bob_7"), m.patternNames());

        String frameFirst = "Seller: [MVP+] Bob_7 ⟦PB0⟧ Alice likes SkyBlock";
        NameMasker.Masked second = NameMasker.mask(frameFirst, Set.of("Alice"), terms);
        assertEquals("Seller: ⟦0⟧ ⟦1⟧ ⟦PB0⟧ ⟦2⟧ likes ⟦3⟧", second.text());
        assertEquals(List.of("[MVP+]", "Bob_7", "Alice", "SkyBlock"), second.names());
        assertEquals(text, NameMasker.unmask(m.text(), m.names()));
    }

    @Test
    void aFrameNameThatIsAlsoTabListedIsStrictAndSharesItsIndex() {
        NameMasker.Masked m = NameMasker.mask("Seller: [VIP] Bob_7 ⟦PB0⟧ Bob_7 says hi",
                Set.of("Bob_7"), DoNotTranslateMatcher.EMPTY);
        assertEquals("Seller: ⟦0⟧ ⟦1⟧ ⟦PB0⟧ ⟦1⟧ says hi", m.text());
        assertEquals(List.of("[VIP]", "Bob_7"), m.names());
        assertEquals(List.of("[VIP]"), m.patternNames(), "only the rank badge is frame-isolated");
        assertTrue(!m.patternNames().contains("Bob_7"), "the TAB list claims it: keep the strict R17 rule");
    }

    @Test
    void maskingIsIdempotent() {
        DoNotTranslateMatcher terms = DoNotTranslateMatcher.compile(List.of("SkyBlock"));
        String text = "⟦CS0⟧Seller: ⟦/CS0⟧⟦CS1⟧[MVP⟦/CS1⟧⟦CS2⟧+⟦/CS2⟧⟦CS1⟧] Bob_7⟦/CS1⟧"
                + " ⟦PB0⟧ Alice likes SkyBlock";
        NameMasker.Masked once = NameMasker.mask(text, Set.of("Alice"), terms);
        NameMasker.Masked twice = NameMasker.mask(once.text(), Set.of("Alice"), terms);
        assertEquals(once.text(), twice.text());
        assertFalse(twice.hasMasks(), "masked output carries nothing left to mask");
    }

    @Test
    void changingTabListNeverStalesTheMemoisedFrameSpans() {
        String text = "Seller: [MVP+] Bob_7 ⟦PB0⟧ Carol_9 waves";
        NameMasker.Masked none = NameMasker.mask(text, Set.of(), DoNotTranslateMatcher.EMPTY);
        assertEquals("Seller: ⟦0⟧ ⟦1⟧ ⟦PB0⟧ Carol_9 waves", none.text());
        NameMasker.Masked carol = NameMasker.mask(text, Set.of("Carol_9"), DoNotTranslateMatcher.EMPTY);
        assertEquals("Seller: ⟦0⟧ ⟦1⟧ ⟦PB0⟧ ⟦2⟧ waves", carol.text());
        assertEquals(List.of("[MVP+]", "Bob_7", "Carol_9"), carol.names());
        NameMasker.Masked again = NameMasker.mask(text, Set.of(), DoNotTranslateMatcher.EMPTY);
        assertEquals(none.text(), again.text(), "the TAB name left: only the frame name remains");
        assertEquals(List.of("[MVP+]", "Bob_7"), again.names());
    }

    @Test
    void everySellerOfOneTooltipShapeGetsTheSameMaskedKey() {
        String shape = "LEGENDARY SWORD ⟦PB0⟧ Seller: %s ⟦PB1⟧ Buy it now: 1,000 coins";
        String first = NameMasker.mask(String.format(shape, "[MVP+] Alpha_1"), List.of(),
                DoNotTranslateMatcher.EMPTY).text();
        assertEquals(first, NameMasker.mask(String.format(shape, "[MVP+] zz9"), List.of(),
                DoNotTranslateMatcher.EMPTY).text());
        assertEquals(first, NameMasker.mask(String.format(shape, "[MVP+] Seller_Of_Doom"), List.of(),
                DoNotTranslateMatcher.EMPTY).text());
    }

    @Test
    void longerNamesMaskedBeforeShorterOverlaps() {
        // "BobBuilder" must be masked as a whole, not partially via "Bob".
        NameMasker.Masked m = NameMasker.mask("BobBuilder greeted Bob", List.of("Bob", "BobBuilder"));
        assertFalse(m.text().contains("BobBuilder"));
        assertFalse(m.text().contains("Bob"));
        assertEquals("BobBuilder greeted Bob", NameMasker.unmask(m.text(), m.names()));
    }
}
