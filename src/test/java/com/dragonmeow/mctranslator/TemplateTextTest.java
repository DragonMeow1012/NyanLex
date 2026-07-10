package com.dragonmeow.mctranslator;

import com.dragonmeow.mctranslator.translate.TemplateText;
import com.dragonmeow.mctranslator.translate.TextFilter;
import org.junit.jupiter.api.Test;

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
    void rankedLobbyJoinMessagesHidePlayerIdsAndShareOneTemplate() {
        TemplateText.Prepared first = TemplateText.prepare("[MVP+] Life joined the lobby!");
        TemplateText.Prepared second = TemplateText.prepare("[VIP] DashieBrot joined the lobby!");

        assertEquals(first.text(), second.text(), "rank and username variants share one request key");
        assertFalse(first.text().contains("Life"));
        assertFalse(second.text().contains("DashieBrot"));
        assertEquals("[MVP+] Life 加入了大廳！",
                first.restore("⟦MT0⟧ 加入了大廳！"));
        assertEquals("[VIP] DashieBrot 加入了大廳！",
                second.restore("⟦MT0⟧ 加入了大廳！"));
    }

    @Test
    void rainbowMvpPlusPlusJoinMessagesHideTheWholeStyledIdentity() {
        String first = "⟦CS0⟧>⟦/CS0⟧⟦CS1⟧>⟦/CS1⟧⟦CS2⟧> ⟦/CS2⟧"
                + "⟦CS3⟧[MVP⟦/CS3⟧⟦CS4⟧++⟦/CS4⟧⟦CS5⟧] Big_Thief_⟦/CS5⟧ "
                + "⟦CS6⟧joined the lobby!⟦/CS6⟧";
        String second = first.replace("Big_Thief_", "IGBLF");
        TemplateText.Prepared a = TemplateText.prepare(first);
        TemplateText.Prepared b = TemplateText.prepare(second);

        assertEquals(a.text(), b.text(), "rainbow ranks and names must share one backend key");
        assertFalse(a.text().contains("Big_Thief_"));
        assertFalse(b.text().contains("IGBLF"));
        String restored = a.restore(a.text().replace("joined the lobby!", "加入了大廳！"));
        assertEquals(">>> [MVP++] Big_Thief_ 加入了大廳！",
                TextFilter.stripFormatting(restored));
    }

    @Test
    void tabMaskTimingDoesNotCreateAnotherPlayerEventFamily() {
        assertEquals(TemplateText.prepare("[MVP++] Big_Thief_ joined the lobby!").text(),
                TemplateText.prepare("[MVP++] ⟦0⟧ joined the lobby!").text());
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
}
