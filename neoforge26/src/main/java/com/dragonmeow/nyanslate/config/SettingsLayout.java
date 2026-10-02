package com.dragonmeow.nyanslate.config;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure geometry of the settings screen (GUI-scaled pixels), shared by every Minecraft
 * glue so all versions lay out identically and the rules are unit-testable:
 *
 * <pre>
 *  y=8   title (left) / progress (right of it) / help button "?" (top right)
 *  y=24  tab row, 20 high             (compact: y=4, left of the "?" button, no title)
 *  y=50  scrolling list, rows 22 high (+11 when the first-run hint line is shown)
 *  h-50  help line(s), 9 px per line  (compact: 1 line at h-38)
 *  h-26  Done button, 20 high
 * </pre>
 *
 * Two columns when the GUI is at least {@value #TWO_COLUMN_MIN_WIDTH} wide, one column
 * otherwise. The list scrolls by whole rows so no button is ever half clipped.
 */
public final class SettingsLayout {

    public static final int TWO_COLUMN_MIN_WIDTH = 260;
    public static final int COMPACT_BELOW_HEIGHT = 200;
    public static final int ROW_H = 22;
    public static final int BUTTON_H = 20;
    public static final int TAB_H = 20;
    public static final int LINE_H = 9;
    public static final int GAP = 6;
    public static final int SCROLLBAR_W = 4;
    public static final int INTRO_H = 11;

    /** A placed button: absolute x/width, row index within the page's row list. */
    public record Cell(SettingEntry entry, int row, int x, int width, boolean compactLabel) {}

    public final int width;
    public final int height;
    public final boolean twoColumns;
    public final boolean compact;
    public final boolean intro;

    public final int titleX = 8;
    public final int titleY;
    public final int tabsX;
    public final int tabsY;
    public final int tabW;
    public final int tabGap = 2;
    public final int helpBtnX;
    public final int helpBtnY = 4;
    public final int helpBtnW = 16;
    public final int helpBtnH = 14;
    public final int introY;
    public final int listTop;
    public final int listBottom;
    public final int visibleRows;
    public final int contentX;
    public final int contentW;
    public final int colW;
    public final int scrollbarX;
    public final int helpY;
    public final int helpLines;
    public final int doneX;
    public final int doneY;
    public final int doneW;

    private SettingsLayout(int width, int height, boolean intro) {
        this.width = width;
        this.height = height;
        this.intro = intro;
        this.twoColumns = width >= TWO_COLUMN_MIN_WIDTH;
        this.compact = height < COMPACT_BELOW_HEIGHT;

        this.helpBtnX = width - 6 - helpBtnW;
        this.titleY = compact ? -1 : 8;
        this.tabsY = compact ? 4 : 24;
        int tabSpan = Math.min(width - 12 - (compact ? helpBtnW + 6 : 0), 360);
        this.tabW = Math.max(20, (tabSpan - 5 * tabGap) / 6);
        int tabsTotal = 6 * tabW + 5 * tabGap;
        this.tabsX = compact ? 6 : (width - tabsTotal) / 2;

        this.introY = tabsY + TAB_H + 3;
        this.listTop = tabsY + TAB_H + 6 + (intro ? INTRO_H : 0);

        this.helpLines = compact ? 1 : 2;
        this.helpY = height - (compact ? 38 : 50);
        this.listBottom = helpY - 2;
        this.visibleRows = Math.max(1, (listBottom - listTop) / ROW_H);

        this.colW = twoColumns ? Math.min(150, (width - 24) / 2) : Math.min(220, width - 24);
        this.contentW = twoColumns ? colW * 2 + GAP : colW;
        this.contentX = (width - contentW) / 2;
        this.scrollbarX = Math.min(contentX + contentW + 3, width - SCROLLBAR_W - 1);

        this.doneW = Math.min(200, width - 24);
        this.doneX = (width - doneW) / 2;
        this.doneY = height - 26;
    }

    public static SettingsLayout of(int width, int height, boolean introLine) {
        return new SettingsLayout(width, height, introLine);
    }

    /** Page rows for this layout's column count. */
    public List<SettingsRow> rowsFor(SettingsPage page) {
        return twoColumns ? SettingsCatalog.rows(page) : SettingsCatalog.singleColumnRows(page);
    }

    /** Absolute button placement for every entry of {@code rows}. */
    public List<Cell> place(List<SettingsRow> rows) {
        List<Cell> cells = new ArrayList<>();
        for (int r = 0; r < rows.size(); r++) {
            SettingsRow row = rows.get(r);
            if (row.secondary() == null) {
                // A lone entry takes the whole row in two columns only when it is the last
                // odd one out; keep it column-sized so it lines up with the rows above.
                cells.add(new Cell(row.primary(), r, contentX, colW, false));
                continue;
            }
            if (row.secondary().compactInPair()) {
                int engineW = Math.min(80, contentW / 3);
                int modeW = contentW - engineW - GAP;
                cells.add(new Cell(row.primary(), r, contentX, modeW, false));
                cells.add(new Cell(row.secondary(), r, contentX + modeW + GAP, engineW, true));
            } else {
                cells.add(new Cell(row.primary(), r, contentX, colW, false));
                cells.add(new Cell(row.secondary(), r, contentX + colW + GAP, colW, false));
            }
        }
        return cells;
    }

    public int rowY(int row, int firstRow) { return listTop + (row - firstRow) * ROW_H; }

    public boolean rowVisible(int row, int firstRow) {
        return row >= firstRow && row < firstRow + visibleRows;
    }

    public int maxFirstRow(int totalRows) { return Math.max(0, totalRows - visibleRows); }

    public int clampFirstRow(int firstRow, int totalRows) {
        return Math.max(0, Math.min(firstRow, maxFirstRow(totalRows)));
    }

    public boolean needsScrollbar(int totalRows) { return totalRows > visibleRows; }

    public int trackHeight() { return visibleRows * ROW_H; }

    public int thumbHeight(int totalRows) {
        if (!needsScrollbar(totalRows)) return trackHeight();
        return Math.max(8, trackHeight() * visibleRows / totalRows);
    }

    public int thumbY(int firstRow, int totalRows) {
        int max = maxFirstRow(totalRows);
        if (max == 0) return listTop;
        return listTop + (trackHeight() - thumbHeight(totalRows)) * clampFirstRow(firstRow, totalRows) / max;
    }

    /** First visible row that puts the thumb's centre at pixel {@code y} of the track. */
    public int firstRowForTrackY(double y, int totalRows) {
        int max = maxFirstRow(totalRows);
        if (max == 0) return 0;
        double span = trackHeight() - thumbHeight(totalRows);
        if (span <= 0) return 0;
        double frac = (y - listTop - thumbHeight(totalRows) / 2.0) / span;
        return clampFirstRow((int) Math.round(frac * max), totalRows);
    }
}
