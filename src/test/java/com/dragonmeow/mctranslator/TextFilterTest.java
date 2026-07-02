package com.dragonmeow.mctranslator;

import com.dragonmeow.mctranslator.translate.TextFilter;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextFilterTest {

    @Test
    void translatesPlainEnglish() {
        assertTrue(TextFilter.shouldTranslate("Welcome to the server", "zh-TW"));
    }

    @Test
    void skipsBlankAndNull() {
        assertFalse(TextFilter.shouldTranslate(null, "zh-TW"));
        assertFalse(TextFilter.shouldTranslate("", "zh-TW"));
        assertFalse(TextFilter.shouldTranslate("   ", "zh-TW"));
    }

    @Test
    void skipsTextWithoutLetters() {
        // Scoreboard score numbers, separators, pure punctuation.
        assertFalse(TextFilter.shouldTranslate("12345", "zh-TW"));
        assertFalse(TextFilter.shouldTranslate("- 42", "zh-TW"));
        assertFalse(TextFilter.shouldTranslate("=====", "zh-TW"));
    }

    @Test
    void skipsAlreadyChineseWhenTargetIsChinese() {
        assertFalse(TextFilter.shouldTranslate("你好世界", "zh-TW"));
        assertFalse(TextFilter.shouldTranslate("歡迎來到伺服器", "zh-TW"));
    }

    @Test
    void translatesMixedTextThatIsMostlyNonChinese() {
        // Mostly English with one Chinese char -> still worth translating.
        assertTrue(TextFilter.shouldTranslate("Hello 世界 everyone here now", "zh-TW"));
    }

    @Test
    void skipsStructuredServerData() {
        assertFalse(TextFilter.shouldTranslate(
                "(\"server\":\"dynamiclobby27H\",\"gametype\":\"MURDER_MYSTERY\",\"lobbyname\":\"mmlobby2\")",
                "zh-TW"));
        assertFalse(TextFilter.shouldTranslate(
                "{\"server\":\"dynamiclobby27H\",\"gametype\":\"MURDER_MYSTERY\"}",
                "zh-TW"));
        assertTrue(TextFilter.shouldTranslate("You are not currently in a party.", "zh-TW"));
        assertTrue(TextFilter.shouldTranslate("[MVP+] SuyftKnight has joined the lobby!", "zh-TW"));
    }

    @Test
    void chineseSourceTranslatesWhenTargetIsNotChinese() {
        // If you flipped target to English, Chinese should be translatable.
        assertTrue(TextFilter.shouldTranslate("你好世界", "en"));
    }

    @Test
    void cjkDetectionHelpers() {
        assertTrue(TextFilter.isCjk('好'));
        assertFalse(TextFilter.isCjk('A'));
        assertTrue(TextFilter.isMostlyCjk("你好嗎"));
        assertFalse(TextFilter.isMostlyCjk("hello"));
    }

    @Test
    void detectsLikelyMojibake() {
        assertTrue(TextFilter.isLikelyMojibake("\u83F4\uF8F0\u8782\uFF7D"));
        assertTrue(TextFilter.isLikelyMojibake("\uFF82\uFF67c"));
        assertFalse(TextFilter.isLikelyMojibake("你好世界"));
        assertFalse(TextFilter.isLikelyMojibake("Hello world"));
    }
}
