package com.dragonmeow.mctranslator;

import com.dragonmeow.mctranslator.translate.NameMasker;
import org.junit.jupiter.api.Test;

import java.util.List;

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

    @Test
    void longerNamesMaskedBeforeShorterOverlaps() {
        // "BobBuilder" must be masked as a whole, not partially via "Bob".
        NameMasker.Masked m = NameMasker.mask("BobBuilder greeted Bob", List.of("Bob", "BobBuilder"));
        assertFalse(m.text().contains("BobBuilder"));
        assertFalse(m.text().contains("Bob"));
        assertEquals("BobBuilder greeted Bob", NameMasker.unmask(m.text(), m.names()));
    }
}
