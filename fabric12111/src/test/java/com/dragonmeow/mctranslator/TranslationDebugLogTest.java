package com.dragonmeow.mctranslator;

import com.dragonmeow.mctranslator.translate.TranslationDebugLog;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TranslationDebugLogTest {

    @Test
    void compactOverlayTextHidesProtocolNoiseButKeepsSlotMeaning() {
        String raw = "⟦CS0⟧[MVP⟦/CS0⟧⟦CS1⟧++] RummelDumm⟦/CS1⟧ "
                + "joined with ⟦MT0⟧ coins for ⟦2⟧";

        assertEquals("[MVP++] RummelDumm joined with {值} coins for {玩家}",
                TranslationDebugLog.compactText(raw));
    }

    @Test
    void compactOverlayTextFlattensLinesAndLegacyFormatting() {
        assertEquals("原文 → 翻譯", TranslationDebugLog.compactText("§c原文\n  →   §a翻譯"));
    }
}
