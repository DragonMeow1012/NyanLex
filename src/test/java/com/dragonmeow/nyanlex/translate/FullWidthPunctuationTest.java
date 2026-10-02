package com.dragonmeow.nyanlex.translate;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/** R3: display-only full-width punctuation after Chinese text. */
class FullWidthPunctuationTest {

    @Test
    void halfWidthPunctuationAfterChineseBecomesFullWidth() {
        assertEquals("傷害： +10", TemplateText.fullWidthPunctuationAfterCjk("傷害: +10"));
        assertEquals("暴擊傷害：⟦MT0⟧",
                TemplateText.fullWidthPunctuationAfterCjk("暴擊傷害:⟦MT0⟧"));
        assertEquals("賣家：⟦0⟧，你好！真的嗎？",
                TemplateText.fullWidthPunctuationAfterCjk("賣家:⟦0⟧,你好!真的嗎?"
                        .replace(",", "，").replace("!", "！").replace("?", "？")));
        assertEquals("力量；防禦", TemplateText.fullWidthPunctuationAfterCjk("力量;防禦"));
    }

    @Test
    void aClosingColourMarkerBetweenTheCharacterAndThePunctuationIsSeenThrough() {
        assertEquals("⟦CS0⟧傷害⟦/CS0⟧： 5",
                TemplateText.fullWidthPunctuationAfterCjk("⟦CS0⟧傷害⟦/CS0⟧: 5"));
    }

    @Test
    void thingsThatAreNotChineseTextStayExactlyAsWritten() {
        for (String text : new String[] {"Strength: +10", "時間 10:30", "網址:https://example.com",
                "標籤:value", "ratio 3:1 中文", "plain, text!", ""}) {
            assertSame(text, TemplateText.fullWidthPunctuationAfterCjk(text), text);
        }
        assertEquals(null, TemplateText.fullWidthPunctuationAfterCjk(null));
    }
}
