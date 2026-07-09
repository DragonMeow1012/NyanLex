package com.dragonmeow.mctranslator;

import com.dragonmeow.mctranslator.translate.TextFilter;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    @Test
    void rejectsHalfTransliteratedSingleWord() {
        // The reported bug: "Ja" transliterated, "cob" left as verbatim English.
        assertTrue(TextFilter.isPartialTransliteration("jacob", "傑cob"));
        assertTrue(TextFilter.isPartialTransliteration("Jacob", "傑COB"), "match is case-insensitive");
        // Leading fragment kept, rest transliterated is just as broken.
        assertTrue(TextFilter.isPartialTransliteration("steve", "st史蒂夫"));
    }

    @Test
    void rejectsCjkGluedLatinResidue() {
        // Multi-word source, both words translated, but the final "t" of "Contest" is fused
        // onto 賽 -> per-token evaluation of "Contest" flags the glued 1-letter suffix.
        assertTrue(TextFilter.isPartialTransliteration("Jacob's Contest", "雅各的競賽t"));
        // A single leftover letter glued to CJK is caught (suffix "n" of "Pumpkin" on 瓜).
        assertTrue(TextFilter.isPartialTransliteration("Pumpkin", "南瓜n"));
        // Even one letter glued to a fully-translated apple ("e" suffix on 果).
        assertTrue(TextFilter.isPartialTransliteration("apple", "蘋果e"));
    }

    @Test
    void acceptsFullyTranslatedOrFullyKeptWords() {
        assertFalse(TextFilter.isPartialTransliteration("jacob", "雅各"), "clean full translation");
        assertFalse(TextFilter.isPartialTransliteration("jacob", "jacob"), "fully kept (no CJK)");
        assertFalse(TextFilter.isPartialTransliteration("TNT", "TNT"), "fully kept, no CJK");
        // Leftover ASCII equals the WHOLE source (proper prefix/suffix rule fails) -> not a hybrid.
        assertFalse(TextFilter.isPartialTransliteration("Redstone", "Redstone紅石"));
    }

    @Test
    void acceptsWholeTokenKeptGluedToCjk() {
        // The kept Latin run equals the WHOLE token -> not a PROPER prefix/suffix, even glued.
        assertFalse(TextFilter.isPartialTransliteration("TNT", "TNT炸藥"), "TNT is short + whole token");
        assertFalse(TextFilter.isPartialTransliteration("Java", "Java版"));
        assertFalse(TextFilter.isPartialTransliteration("SkyBlock", "SkyBlock年度"));
        assertFalse(TextFilter.isPartialTransliteration("Diamond Sword", "鑽石劍"), "no leftover Latin");
        assertFalse(TextFilter.isPartialTransliteration("www.hypixel.net", "www.hypixel.net"), "no CJK");
    }

    @Test
    void acceptsLeadingLatinIdiomAndNonGluedResidue() {
        // A single Latin head letter followed by CJK is a legit Chinese idiom, not a residue:
        // the leading-residue floor is 2, so these length-1 leads pass.
        assertFalse(TextFilter.isPartialTransliteration("T-Shirt", "T恤"));
        assertFalse(TextFilter.isPartialTransliteration("A-Grade", "A級"));
        assertFalse(TextFilter.isPartialTransliteration("X-ray", "X光"));
        // The kept "C" is its own whitespace token, not a proper affix of "Vitamin".
        assertFalse(TextFilter.isPartialTransliteration("Vitamin C", "維他命C"));
        // A leftover not glued to CJK (space before "info") is never flagged now.
        assertFalse(TextFilter.isPartialTransliteration("Information", "資訊 info"));
    }

    @Test
    void doesNotFlagShortTokensOrAcronyms() {
        // "OP" is only 2 ASCII letters -> below the 4-letter floor; "OP權限" is keep+gloss.
        assertFalse(TextFilter.isPartialTransliteration("OP", "OP權限"));
        assertFalse(TextFilter.isPartialTransliteration("id", "id編號"));
        // A short kept English word next to CJK stays: "now" is below the 4-char token floor.
        assertFalse(TextFilter.isPartialTransliteration("Buy now", "購買 now"));
    }

    @Test
    void doesNotFlagWhenLeftoverIsNotATokenAffix() {
        // Output keeps an ASCII run that is NOT a prefix/suffix of any qualifying source token
        // (e.g. a unit glued to CJK) -> not a leftover fragment of THAT word.
        assertFalse(TextFilter.isPartialTransliteration("distance", "距離km"));
        assertFalse(TextFilter.isPartialTransliteration("diamond sword", "鑽石劍cob"));
        assertFalse(TextFilter.isPartialTransliteration("Welcome to the server", "歡迎to伺服器"));
        // Single glued letter that is NOT an affix of "apple" ("z") stays accepted.
        assertFalse(TextFilter.isPartialTransliteration("apple", "蘋果z"));
    }

    @Test
    void literalSectionCodesAreStyleNotText() {
        // The 30,892-line disk-append incident: judged RAW, "§d§lSB年500" holds the run
        // "lSB" (the §l code letter fused in), a proper suffix of source token "§d§lSB"
        // glued to 年 → false positive. Judged DE-STYLED it is "SB年500", and "SB" is no
        // affix of any real token → the entry is usable.
        assertFalse(TextFilter.isPartialTransliteration(
                "§f   §d§lSB YEAR 500 §8| §b§lLOADOUTS", "§d§lSB年500 §8| §b§l裝備包"));
        // Stripping § must NOT mask real poison: a genuinely half-transliterated word
        // wrapped in colour codes is still rejected.
        assertTrue(TextFilter.isPartialTransliteration("§ejacob", "§e傑cob"));
    }

    @Test
    void decorativeIconsAreStrippedBeforeTranslatabilityJudgement() {
        // Icon-decorated item names must be judged on their CORE text, not their icons.
        assertTrue(TextFilter.shouldTranslate("⚔ Heroic Spirit Sceptre ✪✪✪✪✪", "zh-TW"));
        assertTrue(TextFilter.shouldTranslate("Gemstones: [🔹] [🔸]", "zh-TW"));
        // A line that is ONLY icons stays untranslatable.
        assertFalse(TextFilter.shouldTranslate("⚔⚔⚔", "zh-TW"));
    }

    @Test
    void decorativeDefinitionExcludesPunctuationMathCurrencyAndGrammar() {
        // CJK punctuation (、！ — translations use them), the math '+' ("+30" travels with
        // its number), and currency signs are NOT decorative.
        assertEquals("好、強！ +30 $5", TextFilter.stripDecorativeSymbols("好、強！ +30 $5"));
        // '§' and the ⟦⟧ token brackets are pipeline grammar, never stripped here.
        assertEquals("§e ⟦MT0⟧", TextFilter.stripDecorativeSymbols("§e☀ ⟦MT0⟧"));
        assertEquals(" Heroic Spirit Sceptre ",
                TextFilter.stripDecorativeSymbols("⚔ Heroic Spirit Sceptre ✪✪✪✪✪"));
        // Resource-pack icon glyphs (private-use area) ARE decorative…
        assertEquals(" Heroic Spirit Sceptre",
                TextFilter.stripDecorativeSymbols(" Heroic Spirit Sceptre"));
        // …but U+FFFD is corruption evidence, never an icon: it must SURVIVE stripping so
        // the mojibake heuristic still fires on decorative-stripped text.
        assertEquals("好�", TextFilter.stripDecorativeSymbols("好�"));
        assertTrue(TextFilter.isLikelyMojibake("好�"));
    }
}
