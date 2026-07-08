package com.dragonmeow.mctranslator;

import com.dragonmeow.mctranslator.translate.TemplateText;
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
        // Ordinals and plain labels keep their words (numbers still template as NUMBER).
        assertTrue(TemplateText.prepare("3rd place").text().contains("rd place"));
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
}
