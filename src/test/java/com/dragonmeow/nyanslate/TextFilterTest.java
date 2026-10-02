package com.dragonmeow.nyanslate;

import com.dragonmeow.nyanslate.translate.TextFilter;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextFilterTest {

    @Test
    void detectsIndentedParagraphStartsWithoutMistakingOneSpace() {
        assertFalse(TextFilter.startsIndentedParagraph(" continuation"));
        assertTrue(TextFilter.startsIndentedParagraph("  New paragraph"));
        assertTrue(TextFilter.startsIndentedParagraph("\tNew paragraph"));
        assertTrue(TextFilter.startsIndentedParagraph("　New paragraph"));
        assertFalse(TextFilter.startsIndentedParagraph("New paragraph"));
    }

    @Test
    void internalDebugOverlayCanNeverTranslateItself() {
        assertFalse(TextFilter.shouldTranslate("[AI #91 …] Mana Cost: ⟦MT0⟧", "zh-TW"));
        assertFalse(TextFilter.shouldTranslate("[AI x3 #⟦MT0⟧ ✗] nested debug row", "zh-TW"));
        assertFalse(TextFilter.shouldTranslate("[Google #12 ✓] Hello", "zh-TW"));
        assertFalse(TextFilter.shouldTranslate("[GT #12 ✓] Hello → 你好", "zh-TW"));
        assertFalse(TextFilter.shouldTranslate("MT DEBUG｜實際送出 12 筆", "zh-TW"));
        assertFalse(TextFilter.shouldTranslate("MT DEBUG  最近 5 項", "zh-TW"));
    }

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
    void skipsTemplateOnlyHudRowsWithShortMachineCodeResidue() {
        assertFalse(TextFilter.shouldTranslate(
                "⟦MT0⟧/⟦MT1⟧/⟦MT2⟧ n6400", "zh-TW"));
        assertFalse(TextFilter.shouldTranslate("HP 100/200", "zh-TW"));
        assertTrue(TextFilter.shouldTranslate("Mana 100/200", "zh-TW"),
                "a real stat label still needs translation");
    }

    @Test
    void skipsAlreadyChineseWhenTargetIsChinese() {
        assertFalse(TextFilter.shouldTranslate("你好世界", "zh-TW"));
        assertFalse(TextFilter.shouldTranslate("歡迎來到伺服器", "zh-TW"));
    }

    @Test
    void japaneseAndKoreanTextAreNotMistakenForAlreadyChinese() {
        assertTrue(TextFilter.shouldTranslate("錆止めされた酸化した銅の金網", "zh-TW"));
        assertTrue(TextFilter.shouldTranslate("チェリーボート", "zh-TW"));
        assertTrue(TextFilter.shouldTranslate("산화된 구리 창살", "zh-TW"));
        assertTrue(TextFilter.shouldTranslate("金床", "zh-TW", "ja_jp"));
        assertFalse(TextFilter.shouldTranslate("金床", "zh-TW"));
        assertFalse(TextFilter.shouldTranslate("防鏽氧化銅網", "zh-TW"));
    }

    @Test
    void internalMarkersNumbersAndBareUrlsDoNotTurnChineseBackIntoEnglish() {
        assertFalse(TextFilter.shouldTranslate(
                "⟦CS0⟧人氣鑽石: ⟦MT0⟧/⟦MT1⟧⟦/CS0⟧", "zh-TW"));
        assertFalse(TextFilter.shouldTranslate("前往 hypixel.net/ptl", "zh-TW"));
        assertFalse(TextFilter.shouldTranslate("前往 ⟦MT0⟧", "zh-TW"));
        assertTrue(TextFilter.shouldTranslate("Visit hypixel.net/ptl", "zh-TW"));
    }

    @Test
    void colourMarkedPlaceholderOnlyHudRowIsNeverSentForTranslation() {
        // A Hypixel-style multi-coloured action-bar/scoreboard row — each numeric run
        // wrapped in its own ⟦CSn⟧…⟦/CSn⟧ colour marker for style preservation, with NO
        // real word anywhere (just values and a wide-column gap). Before the fix, the
        // literal "CS" letters INSIDE the unstripped marker text made
        // hasLettersOutsideVolatileTokens() think this "has letters", so it was sent to
        // the AI/GT backend, which has nothing to translate and fails — the exact debug
        // overlay symptom "原:{值}/{值}{欄距}{值}/{值}{欄距}... -> 譯: failed (unknown)".
        assertFalse(TextFilter.shouldTranslate(
                "⟦CS0⟧⟦MT0⟧/⟦MT1⟧⟦/CS0⟧"
                        + " ⟦WS0⟧ "
                        + "⟦CS1⟧⟦MT2⟧/⟦MT3⟧⟦/CS1⟧",
                "zh-TW"));
        // One lone CS-wrapped value (no WS gap at all) is just as untranslatable.
        assertFalse(TextFilter.shouldTranslate("⟦CS0⟧⟦MT0⟧⟦/CS0⟧",
                "zh-TW"));
        // Real wording inside a colour run must still translate (no regression): the
        // fix must detect the ABSENCE of real letters, not merely the PRESENCE of a CS
        // marker.
        assertTrue(TextFilter.shouldTranslate(
                "⟦CS0⟧Defense⟦/CS0⟧ ⟦CS1⟧⟦MT0⟧⟦/CS1⟧",
                "zh-TW"));
    }

    @Test
    void actionBarEnglishTranslatesButAlreadyChineseStatsDoNotLoop() {
        assertTrue(TextFilter.shouldTranslate("You received 10,518.2 coins!", "zh-TW"));
        assertFalse(TextFilter.shouldTranslate(
                "2,556/2,131♥ 1,042❈ 防禦 1,707/1,707✎ 魔力 200/200", "zh-TW"));
        assertFalse(TextFilter.shouldTranslate(
                "⟦CS0⟧2,556/2,131♥⟦/CS0⟧ ⟦CS1⟧1,042❈ 防禦⟦/CS1⟧ "
                        + "⟦CS2⟧1,707/1,707✎ 魔力 200/200⟦/CS2⟧", "zh-TW"));
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
    void detectsReshapedProtocolTokens() {
        // The real user-observed shape: a translator echoes a reserved token name
        // (PB paragraph break, CS/WS style run, MT value slot) using a look-alike CJK
        // quotation bracket instead of the real ⟦...⟧ delimiter.
        assertTrue(TextFilter.hasReshapedProtocolToken("召喚一個『PB0』鬧鬼骷髏"));
        assertTrue(TextFilter.hasReshapedProtocolToken("「CS1」紅色文字"));
        assertTrue(TextFilter.hasReshapedProtocolToken("數值為【MT0】枚金幣"));
        assertTrue(TextFilter.hasReshapedProtocolToken("關閉標籤『/CS1』"), "a reshaped closing tag too");
        assertFalse(TextFilter.hasReshapedProtocolToken(null));
        assertFalse(TextFilter.hasReshapedProtocolToken("鬧鬼骷髏⟦PB0⟧出現"),
                "the real ⟦...⟧ delimiter is not itself flagged");
        assertFalse(TextFilter.hasReshapedProtocolToken("這是 CS50 這門課"),
                "a bare reserved-looking name with no adjacent bracket is ordinary text "
                        + "(TranslationCache#matchingCsShape deliberately allows this too)");
        assertFalse(TextFilter.hasReshapedProtocolToken("他說『你好』"),
                "an ordinary CJK quotation with no reserved token name inside is untouched");
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
    void templatedSectionCodesAreNotLiteralSectionCodes() {
        assertEquals("§⟦MT0⟧Hello", TextFilter.stripSectionCodes("§⟦MT0⟧§aHello"));
        assertTrue(TextFilter.isTemplatedSectionCode("§⟦MT0⟧Hello", 0));
        assertFalse(TextFilter.isTemplatedSectionCode("§aHello", 0));
    }

    @Test
    void rejectsTranslationThatLeavesAnAnchoredLocationInEnglish() {
        String source = "§6⟦MT0⟧/⟦MT1⟧ §7⟦MT2⟧ §2Foraging Camp §b⟦MT3⟧ Mana";
        assertTrue(TextFilter.hasUntranslatedAnchoredField(source,
                "§6⟦MT0⟧/⟦MT1⟧ §7⟦MT2⟧ §2Foraging Camp §b⟦MT3⟧ 魔力"));
        assertTrue(TextFilter.hasUntranslatedAnchoredField(
                "⟦MT0⟧ Forest ⟦MT1⟧ Mana", "⟦MT0⟧ Forest ⟦MT1⟧ 魔力"));
        assertFalse(TextFilter.hasUntranslatedAnchoredField(source,
                "§6⟦MT0⟧/⟦MT1⟧ §7⟦MT2⟧ §2採集營地 §b⟦MT3⟧ 魔力"));
    }

    @Test
    void romanNumeralAnchoredFieldsAreNotTreatedAsStuckEnglish() {
        // P1.1: "Sharpness VII"-style numbering is level/rank numbering, not English
        // prose that must be translated — before the fix this rejected an otherwise
        // perfectly good translation forever.
        assertFalse(TextFilter.hasUntranslatedAnchoredField(
                "⟦MT0⟧ VII ⟦MT1⟧ Mana", "⟦MT0⟧ VII ⟦MT1⟧ 魔力"));
        assertFalse(TextFilter.hasUntranslatedAnchoredField(
                "⟦MT0⟧ XVI ⟦MT1⟧ Mana", "⟦MT0⟧ XVI ⟦MT1⟧ 魔力"));
        assertFalse(TextFilter.hasUntranslatedAnchoredField(
                "⟦MT0⟧ IX ⟦MT1⟧ Mana", "⟦MT0⟧ IX ⟦MT1⟧ 魔力"));
        // An ordinary English word left untranslated must still be caught (B8/S3
        // counter-example: this rule must not become a blanket "3-letter word" escape).
        assertTrue(TextFilter.hasUntranslatedAnchoredField(
                "⟦MT0⟧ Forest ⟦MT1⟧ Mana", "⟦MT0⟧ Forest ⟦MT1⟧ 魔力"));
    }

    @Test
    void romanNumeralHelperRecognisesStrictUppercaseShapesOnly() {
        assertTrue(TextFilter.isRomanNumeral("VII"));
        assertTrue(TextFilter.isRomanNumeral("XVI"));
        assertTrue(TextFilter.isRomanNumeral("I"));
        assertTrue(TextFilter.isRomanNumeral("MCMXCIX"));
        assertFalse(TextFilter.isRomanNumeral("vii"), "case-sensitive: lowercase is prose, not numbering");
        assertFalse(TextFilter.isRomanNumeral("Forest"));
        assertFalse(TextFilter.isRomanNumeral(""));
        assertFalse(TextFilter.isRomanNumeral(null));
    }

    @Test
    void possessiveSuffixIsStrippedBeforeHalfTransliterationJudgement() {
        // The reported bug: a fully-kept name whose possessive "'s" is dropped by
        // Chinese grammar was misjudged as a half-transliteration residue, because the
        // untouched "'s" suffix was still part of the ASCII run compared against the
        // CJK-glued kept name.
        assertFalse(TextFilter.isPartialTransliteration("Bonzo's", "Bonzo的"),
                "a fully-kept name with its possessive dropped by CJK grammar is not a hybrid");
        assertFalse(TextFilter.isPartialTransliteration("Bonzo's Mask", "Bonzo的面具"));
        assertFalse(TextFilter.isPartialTransliteration("Bonzo’s", "Bonzo的"),
                "the curly apostrophe variant must be recognised too");
        // Trailing punctuation alone (no possessive) must not confuse the same judgement.
        assertFalse(TextFilter.isPartialTransliteration("SkyBlock,", "SkyBlock年度"));
        // A genuine half-transliteration elsewhere in the same sentence must still be
        // caught (unchanged from the existing rejectsCjkGluedLatinResidue coverage).
        assertTrue(TextFilter.isPartialTransliteration("Jacob's Contest", "雅各的競賽t"));
    }

    @Test
    void possessiveStrippedNameWithEmbeddedDigitOrUnderscoreIsNotAHybrid() {
        // 1.0.7 P1 regression: stripping the trailing "'s" (see
        // possessiveSuffixIsStrippedBeforeHalfTransliterationJudgement above) exposed the
        // bare name to hasGluedResidueOf's ASCII-letter-only run scan. A digit or
        // underscore INSIDE a Hypixel account name (very common) is not an ASCII letter,
        // so it splits an otherwise fully-kept name into two runs; the fragment after the
        // split can then accidentally equal a proper suffix of the whole name and get
        // misjudged as half-transliteration residue glued to the following Chinese
        // possessive/particle. None of these are hybrids: the whole name survived intact.
        assertFalse(TextFilter.isPartialTransliteration(
                        "Hello Ab0cdefgh's world", "你好 Ab0cdefgh的世界"),
                "a digit inside the kept name must not be misread as a residue boundary");
        assertFalse(TextFilter.isPartialTransliteration(
                        "_Example's auction!", "_Example拍賣！"),
                "a leading underscore inside the kept name must not be misread either");
        // A genuine hybrid must still be caught even when the SAME name also contains a
        // digit elsewhere: only "Ab" was transliterated away here, "0cdefgh" is real
        // leftover residue (not the whole token), so this must still be rejected.
        assertTrue(TextFilter.isPartialTransliteration(
                        "Hello Ab0cdefgh's world", "你好 傑0cdefgh的世界"),
                "a real partial transliteration next to a digit must still be flagged");
    }

    @Test
    void wholeTokenKeptNextToNonLetterGlueIsNotAHybridEither() {
        // Found while re-running the real-data cache replay after the digit/underscore
        // fix above (same mechanism, two more "glue" characters WHITESPACE.split() never
        // separates from a fully-kept word): a "⟦CS0⟧" colour marker with no separating
        // space, and a command name's leading "/". Both are non-ASCII-letter characters
        // that split an intact kept word into two letter runs, exactly like the digit
        // case, so the fix generalises from "word chars" to "any non-whitespace, non-CJK
        // character" (still always bounded by the real CJK ideograph hasGluedResidueOf
        // already requires — see isWholeTokenPreservedAround's javadoc).
        assertFalse(TextFilter.isPartialTransliteration(
                        "⟦CS0⟧Bonzo's Staff⟦/CS0⟧ ⟦CS1⟧⟦MT0⟧⟦/CS1⟧",
                        "⟦CS0⟧Bonzo的法杖⟦/CS0⟧ ⟦CS1⟧⟦MT0⟧⟦/CS1⟧"),
                "a CS colour marker glued directly onto a fully-kept name is not a residue boundary");
        assertFalse(TextFilter.isPartialTransliteration(
                        "You can cancel a pending invite using /coopclear.",
                        "你可以使用 /coopclear來取消待處理的邀請。"),
                "a command's leading slash must not be misread as a residue boundary either");
    }

    @Test
    void shouldTranslateMemoDoesNotChangeAnyPreviouslyAssertedOutput() {
        // P1.6: memo must be output-transparent. Every call is made TWICE (once to
        // populate the memo, once to hit it) and both must agree with each other and
        // with this file's own hand-written expectations above.
        Object[][] cases = {
                {"12345", "zh-TW", null, false},
                {"Mana 100/200", "zh-TW", null, true},
                {"你好世界", "zh-TW", null, false},
                {"錆止めされた酸化した銅の金網", "zh-TW", null, true},
                {"金床", "zh-TW", "ja_jp", true},
                {"金床", "zh-TW", null, false},
                {"산화된 구리 창살", "zh-TW", null, true},
                {"Welcome to the server", "zh-TW", null, true},
                {"你好世界", "en", null, true},
                {"Hello 世界 everyone here now", "zh-TW", null, true},
        };
        for (Object[] c : cases) {
            String text = (String) c[0], lang = (String) c[1], hint = (String) c[2];
            boolean expected = (Boolean) c[3];
            boolean first = TextFilter.shouldTranslate(text, lang, hint);
            boolean second = TextFilter.shouldTranslate(text, lang, hint);
            assertEquals(expected, first, "text=" + text + " hint=" + hint);
            assertEquals(first, second, "memoised call must equal the first (uncached) call");
        }
    }

    @Test
    void shouldTranslateMemoStaysCorrectAcrossCapacityRotation() {
        // Force well past SHOULD_TRANSLATE_MEMO_MAX (4096) with distinct keys so the
        // bounded memo must evict, then re-check a representative sample (including the
        // very first keys inserted) for correctness after the rotation.
        String targetLang = "zh-rotation-test";
        for (int i = 0; i < 4300; i++) {
            String english = "English row number " + i;
            String chinese = "中文第" + i + "行";
            assertTrue(TextFilter.shouldTranslate(english, targetLang, null), "row " + i);
            assertFalse(TextFilter.shouldTranslate(chinese, targetLang, null), "row " + i);
        }
        // Re-check a sample spanning old (possibly evicted) and recent entries: results
        // must still be correct whether served from the memo or recomputed.
        for (int i : new int[] {0, 1, 2000, 4000, 4299}) {
            assertTrue(TextFilter.shouldTranslate("English row number " + i, targetLang, null));
            assertFalse(TextFilter.shouldTranslate("中文第" + i + "行", targetLang, null));
        }
    }

    @Test
    void formattingProjectionRemovesRealTooltipCsMarkers() {
        assertEquals("Aspect of the End Damage: 100", TextFilter.stripFormatting(
                "⟦CS0⟧Aspect of the End⟦/CS0⟧ ⟦CS1⟧Damage: 100⟦/CS1⟧"));
    }

    @Test
    void skipsPureDynamicClockLinesWithSectionCodes() {
        assertFalse(TextFilter.shouldTranslate(" \u00a7711:20pm \u00a7b\u263d\u00a7v", "zh-TW"));
        assertFalse(TextFilter.shouldTranslate(" \u00a7712:00am \u00a7b\u263d\u00a7v", "zh-TW"));
        assertFalse(TextFilter.shouldTranslate("§⟦MT0⟧:⟦MT1⟧0pm §b⟦MT2⟧§v", "zh-TW"));
        assertTrue(TextFilter.shouldTranslate("Mana Cost: 99", "zh-TW"));
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

    // --- hasForeignUrl: audit findings A1/A2 (2026-10-01) ---

    @Test
    void hasForeignUrlDetectsAPlainIntroducedDomain() {
        assertTrue(TextFilter.hasForeignUrl("Click here", "evil.com 領獎"));
        assertFalse(TextFilter.hasForeignUrl("evil.com 領獎", "evil.com 領獎"),
                "a domain already present in the source is not foreign");
    }

    @Test
    void hasForeignUrlSeesThroughASectionCodeHiddenInsideTheDomain_A1() {
        // '§' + the next char is swallowed by Minecraft's renderer: "evil§r.com" is DISPLAYED
        // as "evil.com", so the detector must strip section codes before it scans, or this
        // slips straight past the literal "." regex.
        assertTrue(TextFilter.hasForeignUrl("Click here", "evil§r.com 領獎"));
        assertTrue(TextFilter.hasForeignUrl("Click here", "§bevil§r.§ccom"));
    }

    @Test
    void hasForeignUrlSeesThroughFullwidthDomainPunctuation_A2() {
        // U+FF0E FULLWIDTH FULL STOP folds to ASCII '.' under NFKC.
        assertTrue(TextFilter.hasForeignUrl("Click here", "evil．com 領獎"));
        // U+3002 IDEOGRAPHIC FULL STOP and U+FF61 HALFWIDTH IDEOGRAPHIC FULL STOP do NOT fold
        // to ASCII '.' under NFKC and must be handled explicitly.
        assertTrue(TextFilter.hasForeignUrl("Click here", "evil。com 領獎"));
        assertTrue(TextFilter.hasForeignUrl("Click here", "evil｡com 領獎"));
        // Fullwidth ASCII letters/digits also fold under NFKC.
        assertTrue(TextFilter.hasForeignUrl("Click here", "ｅｖｉｌ.com"));
    }

    @Test
    void hasForeignUrlSeesThroughCommonEvasionSpellings() {
        assertTrue(TextFilter.hasForeignUrl("Click here", "hxxp://evil.com/prize"),
                "defanged hxxp(s) scheme");
        assertTrue(TextFilter.hasForeignUrl("Click here", "hxxps://evil.com/prize"));
        assertTrue(TextFilter.hasForeignUrl("Click here", "go to evil dot com now"),
                "spelled-out \"dot\"");
        assertTrue(TextFilter.hasForeignUrl("Click here", "[領獎在這裡](evil.com)"),
                "Markdown link syntax must not hide the domain from the scanner");
    }

    @Test
    void hasForeignUrlStillRejectsPlainTextThatIsNotADomain() {
        // A sentence containing "dot"/parentheses as ordinary language must not be
        // misclassified just because the evasion-hardening regexes exist.
        assertFalse(TextFilter.hasForeignUrl("Connect the dots", "請連接這些點 (dot) 來完成圖案"));
        assertFalse(TextFilter.hasForeignUrl("Hello", "你好 (打招呼)"));
    }
}
