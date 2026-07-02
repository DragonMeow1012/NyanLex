package com.dragonmeow.mctranslator;

import com.dragonmeow.mctranslator.translate.LayoutPreserver;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Whitespace/layout preservation: the translation keeps the original's indentation. */
class LayoutPreserverTest {

    @Test
    void reappliesLeadingWhitespace() {
        // A centred / indented server line keeps its indentation on the translation.
        assertEquals("    哈托網址", LayoutPreserver.matchOuterWhitespace("    WEBSITE", "哈托網址"));
    }

    @Test
    void reappliesLeadingAndTrailingWhitespace() {
        assertEquals("  你好  ", LayoutPreserver.matchOuterWhitespace("  hi  ", "你好"));
    }

    @Test
    void noSurroundingWhitespaceIsUnchanged() {
        assertEquals("鑽石劍", LayoutPreserver.matchOuterWhitespace("Diamond Sword", "鑽石劍"));
    }

    @Test
    void isIdempotent() {
        String once = LayoutPreserver.matchOuterWhitespace("   X ", "甲");
        String twice = LayoutPreserver.matchOuterWhitespace("   X ", once);
        assertEquals(once, twice);
        assertEquals("   甲 ", twice);
    }

    @Test
    void stripsAnyWhitespaceTheBackendLeftOnTheTranslation() {
        // Backend may return its own surrounding spaces; only the original's are kept.
        assertEquals("  你好  ", LayoutPreserver.matchOuterWhitespace("  hi  ", "  你好 "));
    }

    @Test
    void nullSafe() {
        assertNull(LayoutPreserver.matchOuterWhitespace(null, null));
        assertNull(LayoutPreserver.matchOuterWhitespace("  x ", null));
        assertEquals("你好", LayoutPreserver.matchOuterWhitespace(null, "你好"));
    }
}
