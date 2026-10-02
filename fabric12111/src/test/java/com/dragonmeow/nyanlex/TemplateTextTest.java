package com.dragonmeow.nyanlex;

import com.dragonmeow.nyanlex.translate.TemplateText;
import com.dragonmeow.nyanlex.translate.TextFilter;
import com.dragonmeow.nyanlex.translate.TranslationTemplate;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TemplateTextTest {

    @Test
    void numbersAreTemplatedAndRestored() {
        TemplateText.Prepared p = TemplateText.prepare("You got 5 coins");
        assertTrue(p.changed());
        assertFalse(p.text().contains("5"), p.text());
        // Restore substitutes the value AND tightens CJK spacing (uniform 「得到5硬幣」style).
        String token = p.text().substring(p.text().indexOf('⟦'), p.text().indexOf('⟧') + 1);
        assertEquals("你得到5硬幣", p.restore("你得到 " + token + " 硬幣"));
    }

    @Test
    void sectionCodeNumbersKeepStyleCodeOutsideValue() {
        TemplateText.Prepared p = TemplateText.prepare("§62,525/2,150 Mana");
        assertEquals("§6⟦MT0⟧/⟦MT1⟧ Mana", p.text());
        assertEquals(java.util.List.of("2,525", "2,150"), p.values());
        assertEquals("§62,525/2,150魔力", p.restore("§6⟦MT0⟧/⟦MT1⟧ 魔力"));
    }

    @Test
    void variantsShareTheSameTemplate() {
        assertEquals(TemplateText.prepare("You got 5 coins").text(),
                TemplateText.prepare("You got 99 coins").text(),
                "different numbers must normalise to the same request key");
    }

    @Test
    void restoreToleratesSpacedTokens() {
        TemplateText.Prepared p = TemplateText.prepare("Level 42");
        String token = p.text().substring(p.text().indexOf('⟦'), p.text().indexOf('⟧') + 1);
        String spaced = p.text().replace(token, token.charAt(0) + " MT0 " + '⟧');
        assertTrue(p.restore(spaced).contains("42"), "spaced token must still restore");
    }

    @Test
    void plainTextIsUntouched() {
        TemplateText.Prepared p = TemplateText.prepare("Diamond Sword");
        assertFalse(p.changed());
        assertEquals("Diamond Sword", p.text());
    }

    @Test
    void urlsAndTimesAreProtectedAsSingleTokens() {
        TemplateText.Prepared p = TemplateText.prepare("Vote at https://vote.example.com/x?y=1 before 12:30");
        assertTrue(p.changed());
        assertFalse(p.text().contains("vote.example.com"), p.text());
        assertFalse(p.text().contains("12:30"), p.text());
        String restored = p.restore(p.text());
        assertTrue(restored.contains("https://vote.example.com/x?y=1"));
        assertTrue(restored.contains("12:30"));
    }

    @Test
    void bareDomainPathsAreProtectedAndRestoredVerbatim() {
        TemplateText.Prepared p = TemplateText.prepare("前往 hypixel.net/ptl");
        assertTrue(p.changed());
        assertFalse(p.text().contains("hypixel.net"), p.text());
        assertEquals("前往 hypixel.net/ptl", p.restore(p.text()));
    }

    @Test
    void damageBroadcastsWithColorMarkersShareOneTemplate() {
        // Marked multi-colour chat ("hit 5 enemies for 33,749.9 damage." with red numbers):
        // the damage value must template away (one cached translation serves every hit),
        // while the ⟦CS#⟧ marker indices themselves must never be templated.
        String a = "⟦CS0⟧Your Sceptre hit ⟦/CS0⟧⟦CS1⟧5⟦/CS1⟧⟦CS2⟧ enemies for ⟦/CS2⟧⟦CS3⟧33,749.9⟦/CS3⟧ damage.";
        String b = "⟦CS0⟧Your Sceptre hit ⟦/CS0⟧⟦CS1⟧5⟦/CS1⟧⟦CS2⟧ enemies for ⟦/CS2⟧⟦CS3⟧29,134.5⟦/CS3⟧ damage.";
        assertEquals(TemplateText.prepare(a).text(), TemplateText.prepare(b).text(),
                "different damage numbers must normalise to the same cached template");
        assertTrue(TemplateText.prepare(a).text().contains("⟦CS3⟧"), "marker indices must survive templating");
        assertFalse(TemplateText.prepare(a).text().contains("33,749.9"));
        // restoring puts each message's own number back
        TemplateText.Prepared pa = TemplateText.prepare(a);
        assertTrue(pa.restore(pa.text()).contains("33,749.9"));
    }

    @Test
    void progressBarRunsAreMaskedAsTokens() {
        // "──────── 90/100 XP": the bar run and both numbers must all be tokens, so the
        // model only sees "⟦MT0⟧ ⟦MT1⟧/⟦MT2⟧ XP" and can't derail batch line alignment.
        TemplateText.Prepared p = TemplateText.prepare("──────── 90/100 XP");
        assertTrue(p.changed());
        assertFalse(p.text().contains("────"), p.text());
        assertFalse(p.text().contains("90"), p.text());
        String restored = p.restore(p.text().replace("XP", "經驗值"));
        assertTrue(restored.contains("────────"));
        assertTrue(restored.contains("90/100"));
    }

    // ---- durations (records/countdowns: "59s" ticking every second must share ONE key) ----

    @Test
    void countdownSecondsShareOneTemplate() {
        TemplateText.Prepared p = TemplateText.prepare("Ends in 59s");
        assertTrue(p.changed());
        assertFalse(p.text().contains("59"), p.text());
        assertEquals(TemplateText.prepare("Ends in 59s").text(),
                TemplateText.prepare("Ends in 58s").text(),
                "each countdown tick must normalise to the same request key");
        assertEquals("Ends in 59s", p.restore(p.text()), "restore must put the original duration back");
    }

    @Test
    void multiSegmentDurationsCollapseToOneToken() {
        for (String src : new String[]{"2h 30m", "1m30s", "1d 2h 3m 4s", "5 seconds", "10min"}) {
            TemplateText.Prepared p = TemplateText.prepare("Reset in " + src);
            assertTrue(p.changed(), src);
            assertEquals("Reset in ⟦MT0⟧", p.text(), "whole duration run must be ONE token: " + src);
            assertEquals("Reset in " + src, p.restore(p.text()), src);
        }
    }

    @Test
    void cjkDurationsCollapseToOneToken() {
        TemplateText.Prepared p = TemplateText.prepare("剩餘時間 1天2小時3分30秒");
        assertTrue(p.changed());
        assertEquals("剩餘時間 ⟦MT0⟧", p.text(), p.text());
        // restore also tightens CJK↔digit spacing (uniform 「剩餘時間1天…」 typography).
        assertEquals("剩餘時間1天2小時3分30秒", p.restore(p.text()));
        assertEquals(TemplateText.prepare("剩餘 30秒").text(), TemplateText.prepare("剩餘 29秒").text(),
                "CJK countdown ticks must share one request key");
    }

    @Test
    void durationUnitsDoNotEatOrdinaryWords() {
        // "may" starts with the m unit letter but continues with letters -> not a duration;
        // if the pattern had eaten "5 m", the word would come out shredded as "ay".
        assertTrue(TemplateText.prepare("5 may").text().contains("may"),
                TemplateText.prepare("5 may").text());
        // Ordinals are one live slot and restore their suffix intact.
        TemplateText.Prepared ordinal = TemplateText.prepare("3rd place");
        assertEquals("⟦MT0⟧ place", ordinal.text());
        assertEquals("3rd place", ordinal.restore(ordinal.text()));
        assertTrue(TemplateText.prepare("Room 5").text().startsWith("Room"));
        // A unit-free English sentence is untouched.
        TemplateText.Prepared plain = TemplateText.prepare("Seconds matter most");
        assertFalse(plain.changed(), plain.text());
    }

    @Test
    void clockTimesStillWinOverDurations() {
        TemplateText.Prepared p = TemplateText.prepare("Vote before 12:30");
        assertFalse(p.text().contains("12:30"), p.text());
        assertTrue(p.restore(p.text()).contains("12:30"));
    }

    @Test
    void prepareIsMemoised() {
        assertSame(TemplateText.prepare("You got 5 coins"), TemplateText.prepare("You got 5 coins"),
                "repeated prepare of the same string must return the memoised instance");
    }

    @Test
    void fullWidthNumberSeparatorIsRestoredToAscii() {
        // A backend sometimes renders "1,950" as "1，950" (full-width comma) in CJK output;
        // between digits that is always wrong. restore() normalises it back — this also
        // self-heals such a value already cached from an older build.
        TemplateText.Prepared p = TemplateText.prepare("earned 5 coins"); // any token so restore runs
        assertEquals("1,950", p.restore("1，950"));
        assertEquals("位：1,950", p.restore("位：1，950"));
        assertEquals("3.5", p.restore("3．5"));
        // A full-width comma between WORDS (correct Chinese prose) must be left alone.
        assertEquals("你好，世界", p.restore("你好，世界"));
    }

    @Test
    void decorativeIconRunsBecomeSlots() {
        TemplateText.Prepared p = TemplateText.prepare("⚔ Heroic Spirit Sceptre ✪✪✪✪✪");
        assertEquals("⟦MT0⟧ Heroic Spirit Sceptre ⟦MT1⟧", p.text(),
                "one slot per icon RUN; the icons never reach the translator");
        assertEquals("⚔ 英雄之靈權杖 ✪✪✪✪✪", p.restore("⟦MT0⟧ 英雄之靈權杖 ⟦MT1⟧"));
    }

    @Test
    void circledAndDingbatDigitIconsBecomeSlotsToo() {
        // U+2460-24FF (①-⑳...) and U+2776-2793 (➊-➓...) are Unicode category "No"
        // (Number, other), not "So", so the pre-P1.4 SYMBOL_RUN class silently missed
        // them (D3e): a rarity/slot counter drawn with these glyphs was sent to the
        // translator as if it were ordinary text.
        TemplateText.Prepared circled = TemplateText.prepare("Slot ① item");
        assertEquals("Slot ⟦MT0⟧ item", circled.text());
        assertEquals("Slot ① 物品", circled.restore("Slot ⟦MT0⟧ 物品"));

        TemplateText.Prepared dingbat = TemplateText.prepare("Rank ❶ today");
        assertEquals("Rank ⟦MT0⟧ today", dingbat.text());
        assertEquals("Rank ❶ 今天", dingbat.restore("Rank ⟦MT0⟧ 今天"));

        // A run of several such icons is still ONE slot, like other decorative runs.
        TemplateText.Prepared run = TemplateText.prepare("①②③ combo");
        assertEquals("⟦MT0⟧ combo", run.text());
        assertEquals("①②③ 連擊", run.restore("⟦MT0⟧ 連擊"));
    }

    @Test
    void starUpgradeVariantsShareOneTemplateKey() {
        TemplateText.Prepared three = TemplateText.prepare("⚔ Foo ✪✪✪");
        TemplateText.Prepared five = TemplateText.prepare("⚔ Foo ✪✪✪✪✪");
        assertEquals(three.text(), five.text(), "a star upgrade must not mint a new key");
        assertEquals("⚔ 譯 ✪✪✪", three.restore("⟦MT0⟧ 譯 ⟦MT1⟧"));
        assertEquals("⚔ 譯 ✪✪✪✪✪", five.restore("⟦MT0⟧ 譯 ⟦MT1⟧"));
    }

    @Test
    void rankTagsBecomeSlotsAndRestoreVerbatim() {
        // "[MVP+]" translated as "[最有價值球員+]" was nonsense: rank badges ride as slots,
        // never reach a translator, and come back verbatim in place.
        TemplateText.Prepared p = TemplateText.prepare("[MVP+] hello");
        assertEquals("⟦MT0⟧ hello", p.text());
        assertEquals("[MVP+] 你好", p.restore("⟦MT0⟧ 你好"));
        // The user-reported line: the badge must not reach a translator.
        TemplateText.Prepared claim =
                TemplateText.prepare("You claimed Day Crystal from [MVP+] Aand_'s auction!");
        assertFalse(claim.text().contains("MVP"), claim.text());
        // Bonus: different ranks on the same sentence share ONE key.
        assertEquals(TemplateText.prepare("[VIP] hello").text(),
                TemplateText.prepare("[MVP++] hello").text());
    }

    @Test
    void scoreboardCalendarOrdinalsReuseOneKey() {
        TemplateText.Prepared day23 = TemplateText.prepare("Late Summer 23rd");
        TemplateText.Prepared day24 = TemplateText.prepare("Late Summer 24th");
        assertEquals(day23.text(), day24.text());
        assertEquals("Late Summer 23rd", day23.restore(day23.text()));
        assertEquals("Late Summer 24th", day24.restore(day24.text()));
    }

    @Test
    void scoreboardDateAndShardAreOneDynamicSlot() {
        TemplateText.Prepared first = TemplateText.prepare("07/10/26 m6GA5");
        TemplateText.Prepared second = TemplateText.prepare("07/11/26 m8BC2");
        assertEquals("⟦MT0⟧", first.text());
        assertEquals(first.text(), second.text());
        assertEquals("07/10/26 m6GA5", first.restore(first.text()));
        assertEquals("07/11/26 m8BC2", second.restore(second.text()));
    }

    @Test
    void scoreboardDateAndShardStillShareOneSlotAcrossAWideColumnGap() {
        // D3c: TranslationTemplate.prepare() collapses a >=2-space column gap into
        // " ⟦WSn⟧ " BEFORE TemplateText ever runs, so on a WIDE Hypixel scoreboard footer
        // gap, SCOREBOARD_DATE_SHARD used to see "07/10/26 ⟦WS0⟧ m6GA5" instead of a plain
        // run of spaces and never matched at all — leaving the date+shard completely
        // untemplated (a brand-new key per date AND per shard).
        TranslationTemplate template = new TranslationTemplate();
        TranslationTemplate.Snapshot first = template.prepare("07/10/26     m6GA5");
        TranslationTemplate.Snapshot second = template.prepare("07/11/26     m8BC2");

        assertEquals(first.key(), second.key(),
                "a wide column gap must not defeat the shared date+shard slot");
        assertEquals("07/10/26     m6GA5", first.restore(first.key()));
        assertEquals("07/11/26     m8BC2", second.restore(second.key()));
    }

    @Test
    void calendarDateBecomesOneSharedSlotAcrossMonthsDaysAndYears() {
        // "Obtained: November 15, 2026" previously templated only the day and year
        // separately, leaving the month name as untemplated prose: 12 otherwise-
        // identical price-footer paragraphs (one per month) each bought their own
        // translation.
        TemplateText.Prepared nov = TemplateText.prepare("Obtained: November 15, 2026");
        TemplateText.Prepared jan = TemplateText.prepare("Obtained: January 3, 2027");
        assertEquals("Obtained: ⟦MT0⟧", nov.text());
        assertEquals(nov.text(), jan.text(), "different months/days/years must share one request key");
        assertEquals(List.of("November 15, 2026"), nov.values());
        assertEquals("Obtained: November 15, 2026", nov.restore(nov.text()));
        assertEquals("Obtained: January 3, 2027", jan.restore(jan.text()));
    }

    @Test
    void calendarDateSlotAlsoAcceptsAbbreviatedMonthNames() {
        TemplateText.Prepared p = TemplateText.prepare("Obtained: Sep 9, 2026");
        assertEquals("Obtained: ⟦MT0⟧", p.text());
        assertEquals("Obtained: Sep 9, 2026", p.restore(p.text()));
    }

    @Test
    void templateTextDoesNotGuessPlayerNames() {
        TemplateText.Prepared plain = TemplateText.prepare("You were killed by Steve");
        assertFalse(plain.changed());
        assertEquals("You were killed by Steve", plain.text());

        TemplateText.Prepared ranked = TemplateText.prepare("[MVP+] Life joined the lobby!");
        assertTrue(ranked.changed(), "the rank badge itself is still a protected slot");
        assertTrue(ranked.text().contains("Life"),
                "player names are masked upstream only when they are present in TAB");
        assertEquals("[MVP+] Life joined the lobby!", ranked.restore(ranked.text()));
    }

    @Test
    void lowercaseOrMixedBracketsAreNotRankTags() {
        // [Lv5] / [dungeon] are real content, not badges — they stay translatable.
        assertFalse(TemplateText.prepare("[Lv5] hello").changed());
        assertFalse(TemplateText.prepare("[dungeon] hello").changed());
        // CJK brackets in a TRANSLATION are untouched by restore.
        assertEquals("[MVP+] 【拍賣】",
                TemplateText.prepare("[MVP+] auction").restore("⟦MT0⟧ 【拍賣】"));
    }

    @Test
    void bracketedIconsRestoreInPlaceAndCjkPunctuationIsUntouched() {
        TemplateText.Prepared p = TemplateText.prepare("Gemstones: [🔹] [🔸]");
        assertEquals("Gemstones: [⟦MT0⟧] [⟦MT1⟧]", p.text());
        assertEquals("寶石: [🔹] [🔸]", p.restore("寶石: [⟦MT0⟧] [⟦MT1⟧]"));
        // CJK punctuation is prose, not decoration: nothing to template in plain text.
        assertFalse(TemplateText.prepare("好、強！").changed());
    }

    @Test
    void hudColumnsWithLongWhitespaceStillShareOneNumericTemplateAndRestoreExactly() {
        String first = "2,556/2,131❤          Defense 1,042          Mana 1,707/1,707";
        String second = "3,000/3,000❤          Defense 1,500          Mana 2,000/2,000";
        TemplateText.Prepared prepared = TemplateText.prepare(first);

        assertEquals(prepared.text(), TemplateText.prepare(second).text(),
                "live HUD numbers must not mint a new template when wide column gaps are present");
        assertTrue(prepared.text().contains("          "),
                "TemplateText must not silently consume layout whitespace");
        assertEquals(first, prepared.restore(prepared.text()));
        assertEquals("2,556/2,131❤          防禦1,042          魔力1,707/1,707",
                prepared.restore(prepared.text()
                        .replace("Defense", "防禦")
                        .replace("Mana", "魔力")));
    }

    // ---- symptom 4: template spacing eaten by the translator is restored ----

    @Test
    void restoredDateTokensGetTheirLostAsciiSpacingBack() {
        // "Lot 30, 2026" (not a real calendar month) keeps this fixture as a generic
        // two-number/comma template shape, distinct from P1.4's CALENDAR_DATE slot,
        // which now collapses an actual "Month d, yyyy" run into a single token.
        TemplateText.Prepared p = TemplateText.prepare("Lot 30, 2026");
        assertEquals("Lot ⟦MT0⟧, ⟦MT1⟧", p.text());
        // The AI ate the template's spaces: "編號30,2026" must come back as "編號30, 2026".
        assertEquals("編號30, 2026", p.restore("編號⟦MT0⟧,⟦MT1⟧"));
        // A translation that KEPT the spacing is not double-spaced.
        assertEquals("編號30, 2026", p.restore("編號⟦MT0⟧, ⟦MT1⟧"));
    }

    @Test
    void fullWidthPunctuationAndCjkNeighboursGetNoInjectedSpace() {
        TemplateText.Prepared price = TemplateText.prepare("Price: 50");
        assertEquals("價格：50", price.restore("價格：⟦MT0⟧"),
                "a full-width colon neighbour earns no ASCII space");
        TemplateText.Prepared coins = TemplateText.prepare("You got 5 coins");
        assertEquals("你拿到5枚", coins.restore("你拿到⟦MT0⟧枚"),
                "CJK on both sides of the value earns no ASCII space");
    }

    @Test
    void adjacentRestoredTokensSeparateWhenTheTemplateHadASpace() {
        TemplateText.Prepared p = TemplateText.prepare("Lot 30, 2026");
        // "⟦MT0⟧⟦MT1⟧": while restoring MT1 its left neighbour is the already-restored
        // "30" (ASCII digit) and the template carried a space before MT1 -> re-added.
        assertEquals("30 2026", p.restore("⟦MT0⟧⟦MT1⟧"));
    }

    // ---- symptom 2: translated tooltip column gaps collapse to two spaces ----

    @Test
    void collapseTranslatedColumnGapsTightensOnlyTranslatedCjkLines() {
        assertEquals("NPC 出售價格：  50,000",
                TemplateText.collapseTranslatedColumnGaps("NPC 出售價格：     50,000"));
        assertEquals("NPC Sell Price:     50,000",
                TemplateText.collapseTranslatedColumnGaps("NPC Sell Price:     50,000"),
                "a line without CJK was not translated and keeps its layout");
        assertEquals("    縮排段落文字",
                TemplateText.collapseTranslatedColumnGaps("    縮排段落文字"),
                "leading indentation is paragraph semantics, never collapsed");
        assertEquals("標籤:  數值",
                TemplateText.collapseTranslatedColumnGaps("標籤:  數值"),
                "an exact two-space gap is already tight");
        assertEquals("中文行:  值\nplain row:     value",
                TemplateText.collapseTranslatedColumnGaps("中文行:     值\nplain row:     value"),
                "multi-line input collapses only the CJK lines");
    }

    @Test
    void serverInstanceIdsPlayerCountsAndHubNumbersShareOneTemplate() {
        String first = "SkyBlock Hub #11  Players: 48/60  Server: mega33A";
        String second = "SkyBlock Hub #13  Players: 44/60  Server: mega4E";
        TemplateText.Prepared a = TemplateText.prepare(first);
        TemplateText.Prepared b = TemplateText.prepare(second);

        assertEquals(a.text(), b.text(), "a shard change must not create another key");
        assertFalse(a.text().contains("mega33A"));
        assertEquals(first, a.restore(a.text()));
        assertEquals(second, b.restore(b.text()));
    }

    @Test
    void unknownAndDigitFreeServerInstanceIdsAreStillOneDynamicSlot() {
        TemplateText.Prepared first = TemplateText.prepare("Server: alphaShard");
        TemplateText.Prepared second = TemplateText.prepare("Server: xxxxx");

        assertEquals(first.text(), second.text());
        assertFalse(first.text().contains("alphaShard"));
        assertEquals("Server: xxxxx", second.restore(second.text()));
    }

    @Test
    void sendingToServerTransferNoticeSharesOneTemplateAcrossShards() {
        // D3a/D3b: "mini186DR" is letters directly followed by digits, so NUMBER's own
        // DYNAMIC_START lookbehind (which must not split an ordinary word like "Lv24")
        // refuses to start a match after "mini" — the whole shard id was left completely
        // untemplated, so it never shared a key with any other shard.
        TemplateText.Prepared first = TemplateText.prepare("Sending to server mini186DR");
        TemplateText.Prepared second = TemplateText.prepare("Sending to server mega4E");

        assertEquals(first.text(), second.text(), "different shards must share one request key");
        assertFalse(first.text().contains("mini186DR"), first.text());
        assertEquals("Sending to server mini186DR", first.restore(first.text()));
        assertEquals("Sending to server mega4E", second.restore(second.text()));
    }

    // ---- NUMBER tokenizer: atomic group + quantity-x prefix/suffix (key-shredding fix) ----

    @Test
    void quantityPrefixVariantsShareOneKeyAndRestoreTheirOwnValues() {
        TemplateText.Prepared qty100 = TemplateText.prepare("Reward x100 Diamonds");
        TemplateText.Prepared qty250 = TemplateText.prepare("Reward X250 Diamonds");
        TemplateText.Prepared grouped = TemplateText.prepare("Reward x1,000 Diamonds");
        TemplateText.Prepared decimal = TemplateText.prepare("Reward x1.5 Diamonds");
        TemplateText.Prepared bare100 = TemplateText.prepare("Reward 100 Diamonds");

        assertEquals("Reward ⟦MT0⟧ Diamonds", qty100.text(),
                "the whole x-prefixed quantity must be ONE slot");
        assertEquals(qty100.text(), qty250.text());
        assertEquals(qty100.text(), grouped.text());
        assertEquals(qty100.text(), decimal.text());
        assertEquals(qty100.text(), bare100.text(),
                "x100 / X250 / x1,000 / 100 variants must fold into one request key");
        assertEquals(java.util.List.of("x100"), qty100.values());
        assertEquals("Reward x100 Diamonds", qty100.restore(qty100.text()));
        assertEquals("Reward X250 Diamonds", qty250.restore(qty250.text()));
        assertEquals(java.util.List.of("x1,000"), grouped.values(),
                "a grouped quantity must not be shredded into a partial-number slot");
        assertEquals("Reward x1,000 Diamonds", grouped.restore(grouped.text()));
        assertEquals("Reward x1.5 Diamonds", decimal.restore(decimal.text()));
        assertEquals("Reward 100 Diamonds", bare100.restore(bare100.text()));
    }

    @Test
    void literalReservedMtTokenCannotCollideWithGeneratedQuantitySlot() {
        String source = "Literal ⟦MT0⟧ Reward x1";
        TemplateText.Prepared prepared = TemplateText.prepare(source);

        assertEquals("Literal ⟦MT0⟧ Reward ⟦MT1⟧", prepared.text());
        assertEquals(List.of("x1"), prepared.values());
        assertEquals(List.of(1), prepared.slotIndices());
        assertEquals("字面 ⟦MT0⟧ 獎勵 x1",
                prepared.restore("字面 ⟦MT0⟧ 獎勵 ⟦MT1⟧"));

        TranslationTemplate.Snapshot snapshot = new TranslationTemplate().prepare(source);
        assertEquals("字面 ⟦MT0⟧ 獎勵 ⟦MT1⟧",
                snapshot.retokenize("字面 ⟦MT0⟧ 獎勵 x1"));
    }

    @Test
    void literalMtMarkerVariantsAreProtectedAsCompleteSpans() {
        for (String literal : java.util.List.of("⟦MT0⟧", "⟦ MT 0 ⟧", "⟦ mt 42 ⟧")) {
            TemplateText.Prepared literalOnly = TemplateText.prepare(literal);
            assertFalse(literalOnly.changed(), literalOnly.text());
            assertEquals(literal, literalOnly.text());
            assertEquals(literal, literalOnly.restore(literalOnly.text()));

            TemplateText.Prepared withQuantity = TemplateText.prepare(literal + " x100");
            assertEquals(literal + " ⟦MT" + (literal.contains("42") ? "0" : "1") + "⟧",
                    withQuantity.text(), withQuantity.text());
            assertEquals(literal + " x100", withQuantity.restore(withQuantity.text()));
        }
    }

    @Test
    void quantityPrefixesRespectMinecraftStyleBoundaries() {
        TemplateText.Prepared section = TemplateText.prepare("§ax1,000 Coins");
        assertEquals("§a⟦MT0⟧ Coins", section.text());
        assertEquals(java.util.List.of("x1,000"), section.values());
        assertEquals("§ax1,000 Coins", section.restore(section.text()));

        TemplateText.Prepared cs = TemplateText.prepare("⟦CS1⟧x100⟦/CS1⟧ Coins");
        assertEquals("⟦CS1⟧⟦MT0⟧⟦/CS1⟧ Coins", cs.text());
        assertEquals("⟦CS1⟧x100⟦/CS1⟧ Coins", cs.restore(cs.text()));
    }

    @Test
    void quantitySuffixVariantsShareOneKeyAndRestoreTheirOwnValues() {
        TemplateText.Prepared qty31x = TemplateText.prepare("Sold 31x String");
        TemplateText.Prepared qty1x = TemplateText.prepare("Sold 1x String");
        TemplateText.Prepared bare31 = TemplateText.prepare("Sold 31 String");

        assertEquals("Sold ⟦MT0⟧ String", qty31x.text(),
                "the whole quantity including its x suffix must be ONE slot");
        assertEquals(qty31x.text(), qty1x.text());
        assertEquals(qty31x.text(), bare31.text(),
                "31x / 1x / 31 variants must fold into the same request key");
        assertEquals("Sold 31x String", qty31x.restore(qty31x.text()));
        assertEquals("Sold 1x String", qty1x.restore(qty1x.text()));
        assertEquals("Sold 31 String", bare31.restore(bare31.text()));
    }

    @Test
    void hexDimensionsAndGluedWordsStayUntouchedAndExistingKeysAreStable() {
        // The x suffix guard: hex literals, dimensions and letter-glued runs are prose.
        assertFalse(TemplateText.prepare("0x1F").changed(), TemplateText.prepare("0x1F").text());
        assertFalse(TemplateText.prepare("2x2").changed(), TemplateText.prepare("2x2").text());
        assertFalse(TemplateText.prepare("1920x1080").changed(), TemplateText.prepare("1920x1080").text());
        assertFalse(TemplateText.prepare("4xp").changed(), TemplateText.prepare("4xp").text());
        assertFalse(TemplateText.prepare("x100foo").changed(), TemplateText.prepare("x100foo").text());
        assertFalse(TemplateText.prepare("x100kg").changed(), TemplateText.prepare("x100kg").text());
        assertFalse(TemplateText.prepare("x100xp").changed(), TemplateText.prepare("x100xp").text());
        assertFalse(TemplateText.prepare("_x100").changed(), TemplateText.prepare("_x100").text());
        assertFalse(TemplateText.prepare("box100").changed(), TemplateText.prepare("box100").text());
        // Existing key shapes must not move.
        assertEquals("Balance ⟦MT0⟧", TemplateText.prepare("Balance 10k").text());
        assertEquals("Progress ⟦MT0⟧", TemplateText.prepare("Progress 5%").text());
        assertEquals("Purse: ⟦MT0⟧", TemplateText.prepare("Purse: 1,605").text());
        assertEquals("⟦MT0⟧ place", TemplateText.prepare("23rd place").text());
        assertEquals("Vote before ⟦MT0⟧", TemplateText.prepare("Vote before 2:30").text());
        assertEquals("Reset in ⟦MT0⟧", TemplateText.prepare("Reset in 2h 30m").text());
    }

    @Test
    void unitGluedNumbersNoLongerShredIntoHalfKeys() {
        // Pre-fix, "10kg" backtracked into a half-number slot ("⟦MT0⟧0kg"): the atomic
        // group now fails the whole match instead, leaving the glued run untouched.
        TemplateText.Prepared glued = TemplateText.prepare("Weight 10kg");
        assertFalse(glued.changed(), glued.text());
        assertFalse(TemplateText.prepare("10kg").changed(),
                TemplateText.prepare("10kg").text());
    }

    @Test
    void itemNamesAndUnlistedPlayersRemainLiteral() {
        TemplateText.Prepared item = TemplateText.prepare("Bloom Boat with Chest");
        assertFalse(item.changed());
        assertEquals("Bloom Boat with Chest", item.text());

        TemplateText.Prepared playerEvent = TemplateText.prepare("You were killed by Steve");
        assertFalse(playerEvent.changed());
        assertEquals("You were killed by Steve", playerEvent.text());
    }

    @Test
    void restoreIsStableAcrossRepeatedCallsAndSharedSlotPatterns() {
        // restore()/restoreLayout() run every render frame, so their per-slot and per-gap
        // regexes are compiled once and shared. The expected strings below were captured
        // from the pre-cache implementation: repeated restores, different translations and
        // different snapshots reusing the same slot indices must stay byte-identical.
        TranslationTemplate template = new TranslationTemplate();

        TranslationTemplate.Snapshot scoreboard =
                template.prepare("Coins: 1,250   Kills: 17   Time: 12:30");
        assertEquals("Coins: ⟦MT0⟧ ⟦WS0⟧ Kills: ⟦MT1⟧ ⟦WS1⟧ Time: ⟦MT2⟧", scoreboard.key());
        assertEquals(List.of("1,250", "17", "12:30"), scoreboard.base().values());
        assertEquals(2, scoreboard.layoutGaps().size());
        String tidy = "硬幣: ⟦MT0⟧ ⟦WS0⟧ 擊殺: ⟦MT1⟧ ⟦WS1⟧ 時間: ⟦MT2⟧";
        String messy = "金幣：⟦ MT 0 ⟧⟦WS0⟧擊殺數：⟦MT1⟧ ⟦ WS1 ⟧時間：⟦MT2⟧";
        for (int pass = 0; pass < 2; pass++) {
            assertEquals("硬幣: 1,250   擊殺: 17   時間: 12:30",
                    scoreboard.restore(tidy), "pass " + pass);
            assertEquals("硬幣: 1,250 ⟦WS0⟧ 擊殺: 17 ⟦WS1⟧ 時間: 12:30",
                    scoreboard.base().restore(tidy), "pass " + pass);
            assertEquals("金幣：1,250   擊殺數：17   時間：12:30",
                    scoreboard.restore(messy), "pass " + pass);
            assertEquals("金幣：1,250⟦WS0⟧擊殺數：17 ⟦ WS1 ⟧時間：12:30",
                    scoreboard.base().restore(messy), "pass " + pass);
        }

        TranslationTemplate.Snapshot hud = template.prepare("HP: 20/20\t\tLv 5   XP 75%");
        assertEquals("HP: ⟦MT0⟧/⟦MT1⟧ ⟦WS0⟧ Lv ⟦MT2⟧ ⟦WS1⟧ XP ⟦MT3⟧", hud.key());
        assertEquals(List.of("20", "20", "5", "75%"), hud.base().values());
        assertEquals(2, hud.layoutGaps().size());
        String hudTidy = "生命: ⟦MT0⟧/⟦MT1⟧ ⟦WS0⟧ 等級 ⟦MT2⟧ ⟦WS1⟧ 經驗 ⟦MT3⟧";
        String hudMessy = "生命值：⟦MT0⟧ / ⟦MT1⟧⟦ WS 0 ⟧等級⟦ MT2 ⟧ ⟦WS1⟧經驗值 ⟦MT3⟧";
        for (int pass = 0; pass < 2; pass++) {
            assertEquals("生命: 20/20\t\t等級5   經驗75%", hud.restore(hudTidy), "pass " + pass);
            assertEquals("生命: 20/20 ⟦WS0⟧ 等級5 ⟦WS1⟧ 經驗75%",
                    hud.base().restore(hudTidy), "pass " + pass);
            assertEquals("生命值：20 / 20\t\t等級5   經驗值75%",
                    hud.restore(hudMessy), "pass " + pass);
            assertEquals("生命值：20 / 20⟦ WS 0 ⟧等級5 ⟦WS1⟧經驗值75%",
                    hud.base().restore(hudMessy), "pass " + pass);
        }
    }

    // ---- P1.1 S3: styleSlotShapeMatches can also track a bare ⟦n⟧ placeholder ----

    @Test
    void styleSlotShapeMatchesDefaultOverloadIgnoresBarePlaceholders() {
        String source = "⟦CS0⟧⟦0⟧⟦/CS0⟧ ⟦CS1⟧Sword⟦/CS1⟧";
        // Drifted into the wrong CS region, and dropped entirely: the plain (pre-S3)
        // 2-arg overload has no opinion on either, since it only ever tracked MT slots.
        String drifted = "⟦CS0⟧⟦/CS0⟧ ⟦CS1⟧⟦0⟧劍⟦/CS1⟧";
        String lost = "⟦CS0⟧⟦/CS0⟧ ⟦CS1⟧劍⟦/CS1⟧";
        assertTrue(TranslationTemplate.styleSlotShapeMatches(source, drifted));
        assertTrue(TranslationTemplate.styleSlotShapeMatches(source, lost));
    }

    @Test
    void styleSlotShapeMatchesWithPlaceholderTrackingCatchesDriftAndLoss() {
        String source = "⟦CS0⟧⟦0⟧⟦/CS0⟧ ⟦CS1⟧Sword⟦/CS1⟧";
        String correct = "⟦CS0⟧⟦0⟧⟦/CS0⟧ ⟦CS1⟧劍⟦/CS1⟧";
        String drifted = "⟦CS0⟧⟦/CS0⟧ ⟦CS1⟧⟦0⟧劍⟦/CS1⟧";
        String lost = "⟦CS0⟧⟦/CS0⟧ ⟦CS1⟧劍⟦/CS1⟧";
        String duplicated = "⟦CS0⟧⟦0⟧⟦/CS0⟧ ⟦CS1⟧⟦0⟧劍⟦/CS1⟧";

        assertTrue(TranslationTemplate.styleSlotShapeMatches(source, correct, true),
                "the placeholder stayed in its own CS region");
        assertFalse(TranslationTemplate.styleSlotShapeMatches(source, drifted, true),
                "the placeholder moved into a different CS region");
        assertFalse(TranslationTemplate.styleSlotShapeMatches(source, lost, true),
                "the placeholder disappeared entirely");
        assertFalse(TranslationTemplate.styleSlotShapeMatches(source, duplicated, true),
                "the placeholder was duplicated");
    }
}
