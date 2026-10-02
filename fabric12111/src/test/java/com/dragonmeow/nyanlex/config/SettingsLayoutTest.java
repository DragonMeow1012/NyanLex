package com.dragonmeow.nyanlex.config;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SettingsLayoutTest {

    private static final int[][] SIZES = {
            {240, 180}, {240, 240}, {320, 180}, {320, 240}, {320, 270}, {427, 240}, {480, 270}, {640, 360}
    };

    @Test
    void designCoordinatesAt320x240() {
        SettingsLayout l = SettingsLayout.of(320, 240, false);
        assertTrue(l.twoColumns);
        assertFalse(l.compact);
        assertEquals(8, l.titleY);
        assertEquals(24, l.tabsY);
        assertEquals(50, l.listTop);
        assertEquals(190, l.helpY);
        assertEquals(188, l.listBottom);
        assertEquals(6, l.visibleRows);
        assertEquals(214, l.doneY);
        assertEquals(148, l.colW);
        assertEquals(302, l.contentW);
        assertEquals(2, l.helpLines);
    }

    @Test
    void sevenRowsAt320x270() {
        assertEquals(7, SettingsLayout.of(320, 270, false).visibleRows);
    }

    @Test
    void narrowWidthSwitchesToSingleColumn() {
        assertTrue(SettingsLayout.of(260, 240, false).twoColumns);
        SettingsLayout narrow = SettingsLayout.of(259, 240, false);
        assertFalse(narrow.twoColumns);
        assertEquals(narrow.colW, narrow.contentW);
        List<SettingsRow> rows = narrow.rowsFor(SettingsPage.DISPLAY);
        assertEquals(18, rows.size());
    }

    @Test
    void shortScreenIsCompactWithoutTitleAndOneHelpLine() {
        SettingsLayout l = SettingsLayout.of(320, 180, false);
        assertTrue(l.compact);
        assertEquals(-1, l.titleY);
        assertEquals(1, l.helpLines);
        assertEquals(4, l.tabsY);
        assertTrue(l.tabsX + 6 * l.tabW + 5 * l.tabGap <= l.helpBtnX, "tabs must not overlap the ? button");
        assertTrue(l.visibleRows >= 4);
    }

    @Test
    void introLineShiftsListDown() {
        SettingsLayout plain = SettingsLayout.of(320, 240, false);
        SettingsLayout intro = SettingsLayout.of(320, 240, true);
        assertEquals(plain.listTop + SettingsLayout.INTRO_H, intro.listTop);
        assertEquals(plain.visibleRows - 1, intro.visibleRows);
    }

    @Test
    void everyPlacementStaysInsideTheScreenAndNeverOverlaps() {
        for (int[] size : SIZES) {
            for (boolean intro : new boolean[]{false, true}) {
                SettingsLayout l = SettingsLayout.of(size[0], size[1], intro);
                assertTrue(l.listBottom > l.listTop, size[0] + "x" + size[1]);
                assertTrue(l.doneY >= l.helpY + SettingsLayout.LINE_H * l.helpLines - 2,
                        "done button must not overlap help text at " + size[0] + "x" + size[1]);
                for (SettingsPage page : SettingsPage.values()) {
                    List<SettingsRow> rows = l.rowsFor(page);
                    List<SettingsLayout.Cell> cells = l.place(rows);
                    int expected = 0;
                    for (SettingsRow r : rows) expected += r.entries().size();
                    assertEquals(expected, cells.size());
                    for (SettingsLayout.Cell c : cells) {
                        assertTrue(c.x() >= 0 && c.x() + c.width() <= l.width,
                                page + " " + c.entry().id() + " outside at " + size[0]);
                        assertTrue(c.width() >= 60, "too narrow: " + c.entry().id() + " " + c.width());
                    }
                    for (int i = 0; i < cells.size(); i++) {
                        for (int j = i + 1; j < cells.size(); j++) {
                            SettingsLayout.Cell a = cells.get(i), b = cells.get(j);
                            if (a.row() != b.row()) continue;
                            assertTrue(a.x() + a.width() <= b.x() || b.x() + b.width() <= a.x(),
                                    "overlap in row " + a.row());
                        }
                    }
                    assertTrue(l.scrollbarX + SettingsLayout.SCROLLBAR_W <= l.width);
                    assertTrue(l.scrollbarX >= l.contentX + l.contentW || !l.needsScrollbar(rows.size()));
                }
            }
        }
    }

    @Test
    void scrollingClampsAndThumbTracksPosition() {
        SettingsLayout l = SettingsLayout.of(320, 240, false);
        int total = 9; // display page
        assertEquals(3, l.maxFirstRow(total));
        assertEquals(0, l.clampFirstRow(-5, total));
        assertEquals(3, l.clampFirstRow(99, total));
        assertFalse(l.needsScrollbar(6));
        assertEquals(0, l.maxFirstRow(6));
        assertEquals(l.listTop, l.thumbY(0, total));
        assertEquals(l.listTop + l.trackHeight() - l.thumbHeight(total), l.thumbY(3, total));
        assertEquals(0, l.firstRowForTrackY(l.listTop - 50, total));
        assertEquals(3, l.firstRowForTrackY(l.listTop + l.trackHeight() + 50, total));
        assertTrue(l.rowVisible(0, 0) && l.rowVisible(5, 0) && !l.rowVisible(6, 0));
        assertEquals(l.listTop, l.rowY(2, 2));
    }

    @Test
    void displayRowGivesEngineButtonTheNarrowCell() {
        SettingsLayout l = SettingsLayout.of(320, 240, false);
        List<SettingsLayout.Cell> cells = l.place(l.rowsFor(SettingsPage.DISPLAY));
        SettingsLayout.Cell mode = cells.get(0), engine = cells.get(1);
        assertTrue(engine.compactLabel());
        assertFalse(mode.compactLabel());
        assertTrue(mode.width() > engine.width());
        assertEquals(l.contentX + l.contentW, engine.x() + engine.width());
    }
}
