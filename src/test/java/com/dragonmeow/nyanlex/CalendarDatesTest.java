package com.dragonmeow.nyanlex;

import com.dragonmeow.nyanlex.translate.CalendarDates;
import com.dragonmeow.nyanlex.translate.TemplateText;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** English calendar dates in every common shape become "2026年10月14日" (never "14月2026日"). */
class CalendarDatesTest {

    @Test
    void everyCommonEnglishShapeConvertsToTheSameChineseDate() {
        for (String date : List.of("Oct 14, 2026", "14 Oct 2026", "October 14th, 2026",
                "10/14/2026", "Oct 14 2026", "14th of October, 2026", "Oct. 14, 2026",
                "14/10/2026", "14 October 2026")) {
            assertEquals("取得時間：2026年10月14日", CalendarDates.toChinese("取得時間：" + date), date);
        }
    }

    @Test
    void datesInsideSentencesAndMultipleDatesAreAllConverted() {
        assertEquals("從 2026年1月3日 到 2027年12月31日 結束",
                CalendarDates.toChinese("從 Jan 3, 2026 到 31 Dec 2027 結束"));
    }

    @Test
    void nonDatesAreLeftAlone() {
        for (String text : List.of("Lot 30, 2026", "Price 13/45/2026", "07/10/26", "1,699,999.0",
                "May I have 5 2026 coins", "Oct 14, 20267", "version 1.2.3.2026")) {
            assertEquals(text, CalendarDates.toChinese(text), text);
        }
    }

    @Test
    void dateShapesBecomeOneSharedTemplateSlot() {
        for (String line : List.of("Obtained: Oct 14, 2026", "Obtained: 14 Oct 2026",
                "Obtained: October 14th, 2026", "Obtained: 10/14/2026")) {
            TemplateText.Prepared p = TemplateText.prepare(line);
            assertEquals("Obtained: ⟦MT0⟧", p.text(), line);
            assertEquals(line, p.restore(p.text()), line);
        }
        assertFalse(TemplateText.prepare("Obtained: Oct 14, 2026").values().isEmpty());
    }
}
