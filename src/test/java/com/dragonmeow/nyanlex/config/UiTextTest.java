package com.dragonmeow.nyanlex.config;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Line breaking of the self-drawn screens: quoted names stay whole and the last line is never a stub. */
class UiTextTest {

    /** A CJK character is 9 wide, anything else 6. */
    private static int width(String s) {
        int w = 0;
        for (char ch : s.toCharArray()) w += ch >= 0x2E00 ? 9 : 6;
        return w;
    }

    @Test
    void aQuotedNameIsNeverSplit() {
        String text = "之後想重來一次，可以到「翻譯設定 → 一般 → 快速設定」，或按本頁最上方的按鈕。";
        for (int w = 90; w <= 330; w += 3) {
            for (String line : UiText.wrap(text, w, UiTextTest::width)) {
                int open = line.chars().filter(c -> c == '「').count() > 0 ? 1 : 0;
                long left = line.chars().filter(c -> c == '「').count();
                long right = line.chars().filter(c -> c == '」').count();
                assertEquals(left, right, "width " + w + " split a quote: " + line);
            }
        }
        List<String> lines = UiText.wrap("到「快速設定」按完成。", 9 * 8, UiTextTest::width);
        assertTrue(lines.stream().anyMatch(l -> l.contains("「快速設定」")), lines.toString());
    }

    @Test
    void theLastLineHasAtLeastThreeCharactersWhenThereIsAPreviousLine() {
        String text = "按鍵不順手？可以到翻譯設定改成你習慣的按鍵。";
        for (int w = 60; w <= 330; w++) {
            List<String> lines = UiText.wrap(text, w, UiTextTest::width);
            if (lines.size() < 2) continue;
            assertTrue(lines.get(lines.size() - 1).strip().length() >= 3, "width " + w + ": " + lines);
            for (String line : lines) assertTrue(width(line) <= w || line.length() == 1, "width " + w + ": " + line);
        }
    }

    @Test
    void aShortLastLineTakesCharactersFromTheLineAbove() {
        // 10 characters fit a line of 90; the 11th and 12th would be left alone
        List<String> lines = UiText.wrap("一二三四五六七八九十甲乙", 90, UiTextTest::width);
        assertEquals(2, lines.size());
        assertTrue(lines.get(1).length() >= 3, lines.toString());
        assertEquals("一二三四五六七八九十甲乙", String.join("", lines));
    }

    @Test
    void anOpeningBracketDoesNotStayAtTheEndOfALine() {
        for (int w = 40; w <= 200; w++) {
            List<String> lines = UiText.wrap("需要自備 API 金鑰，或登入 ChatGPT（會使用你帳號的額度）。", w, UiTextTest::width);
            for (int i = 0; i < lines.size() - 1; i++) {
                assertFalse(lines.get(i).endsWith("（"), "width " + w + ": " + lines);
            }
        }
    }

    @Test
    void ordinaryWrappingStillKeepsEverythingAndClosingMarksOffTheLineStart() {
        String text = "Click to inspect! 聊天、物品提示、記分板，都可以翻譯。";
        for (int w = 50; w <= 250; w += 7) {
            List<String> lines = UiText.wrap(text, w, UiTextTest::width);
            assertEquals(text.replace(" ", ""), String.join("", lines).replace(" ", ""), "nothing lost at " + w);
            for (String line : lines.subList(1, lines.size())) {
                assertFalse("，。、；：！？）」』】》".indexOf(line.charAt(0)) >= 0, "width " + w + ": " + lines);
            }
        }
    }

    @Test
    void aShortParentheticalStaysWhole() {
        String text = "聊天訊息的顯示方式：不翻譯、雙語（原文＋譯文）或譯文（只看譯文）。";
        for (int w = 120; w <= 330; w++) {
            for (String line : UiText.wrap(text, w, UiTextTest::width)) {
                long open = line.chars().filter(c -> c == '（').count();
                long close = line.chars().filter(c -> c == '）').count();
                assertEquals(open, close, "width " + w + ": " + line);
            }
        }
    }
}
