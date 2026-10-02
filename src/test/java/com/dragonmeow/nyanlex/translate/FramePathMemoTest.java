package com.dragonmeow.nyanlex.translate;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The pure per-frame helpers answer exactly what they answered before they were memoised or
 * given a fast path, and a memo never leaks an answer from one context into another.
 */
class FramePathMemoTest {

    @Test
    void templatePreparationIsStableAndSharedAcrossInstances() {
        String line = "⟦CS0⟧Damage: ⟦/CS0⟧⟦CS1⟧+319⟦/CS1⟧ and 1,000 coins";
        TranslationTemplate.Snapshot first = new TranslationTemplate().prepare(line);
        TranslationTemplate.Snapshot second = new TranslationTemplate().prepare(line);
        assertSame(first, second, "an immutable snapshot is shared, not rebuilt every frame");
        assertEquals(line, first.source());
        assertEquals(TemplateText.prepare(line.strip()).text(), first.key());
        // Different text, different snapshot.
        assertFalse(first.key().equals(new TranslationTemplate().prepare("Damage: +5").key()));
        // Null still means "empty", as before.
        assertEquals("", new TranslationTemplate().prepare(null).normalized());
    }

    @Test
    void shouldTranslateIsRememberedPerLanguageAndHintNeverAcrossThem() {
        String text = "最大 FPS";
        for (int i = 0; i < 3; i++) {
            assertFalse(TextFilter.shouldTranslate(text, "zh-TW"));
            assertTrue(TextFilter.shouldTranslate(text, "zh-TW", "ja_jp"), "hint changes the verdict");
            assertTrue(TextFilter.shouldTranslate(text, "en"), "a non-Chinese target still translates");
            assertFalse(TextFilter.shouldTranslate(text, "zh-CN"));
            assertFalse(TextFilter.shouldTranslate(null, "zh-TW"));
        }
    }

    @Test
    void sectionCodesAreStrippedExactlyAsBeforeAndUntouchedTextIsReturnedAsIs() {
        String plain = "Plain text without any code";
        assertSame(plain, TextFilter.stripSectionCodes(plain));
        assertEquals("Red text", TextFilter.stripSectionCodes("§cRed §ltext"));
        assertEquals("a§⟦MT0⟧b", TextFilter.stripSectionCodes("a§⟦MT0⟧b"),
                "a section sign in front of a protocol token is not a style code");
        assertNull(TextFilter.stripSectionCodes(null));
    }

    @Test
    void churnSignatureAndVariantAreUnchanged() {
        String marked = "⟦CS0⟧Hello World⟦/CS0⟧ 123 ⟦MT0⟧";
        for (int i = 0; i < 3; i++) {
            assertEquals("helloworld", ChurnGuard.signatureOf(marked));
            assertEquals("Hello World 123 ⟦MT0⟧", ChurnGuard.variantOf(marked));
            assertEquals("plainline", ChurnGuard.signatureOf("Plain line 42!"));
            assertEquals("Plain line 42!", ChurnGuard.variantOf("Plain line 42!"));
            assertEquals("", ChurnGuard.signatureOf("123 ⟦MT0⟧"));
        }
    }

    @Test
    void theChurnGuardStillSuppressesAChurningFamily() {
        long[] now = {0L};
        ChurnGuard guard = new ChurnGuard(3, 10_000L, 30_000L, () -> now[0]);
        assertFalse(guard.shouldSuppress("Players online: 1"));
        assertFalse(guard.shouldSuppress("Players online: 2"));
        assertTrue(guard.shouldSuppress("Players online: 3"), "the third variant trips the guard");
        // Asked again and again (a render loop), the answer stays "suppressed" for the cooldown.
        for (int i = 0; i < 5; i++) assertTrue(guard.shouldSuppress("Players online: 3"));
        assertFalse(guard.shouldSuppress("A completely different line"));
    }
}
