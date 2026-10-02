package com.dragonmeow.nyanlex.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.ToIntFunction;

/**
 * The manual screen (說明書), drawn without any Minecraft type: a contents column on the left
 * (a chapter drop-down when the screen is narrow), a reading column on the right that is about
 * 26 characters wide, key caps that show the keys the player really bound, a button at the very
 * top that starts the 快速設定, and 返回 at the bottom. Click a chapter to jump to it; the chapter
 * being read is lit. Tab moves a frame over the buttons and chapters, Enter or Space presses.
 * The glue forwards input and closes the screen when {@link #BACK} comes back (Escape does the same).
 *
 * <p>In a chapter body, a paragraph that starts with {@code {R}}, {@code {P}}, {@code {G}} or
 * {@code {S}} becomes a key cap row; the same markers inside a sentence are replaced by the key name.</p>
 */
public final class ManualPanel {

    public static final int NONE = 0;
    public static final int BACK = 1;
    /** The button at the top was pressed: open the 快速設定. */
    public static final int OPEN_QUICK_SETUP = 2;

    /** One chapter: a heading and its body (paragraphs separated by newlines). */
    public record Section(String title, String body) {}

    private static final int OUTER = 6;
    private static final int PAD = 8;
    private static final int LINE = 10;
    private static final int TITLE_H = 20;
    private static final int BTN_H = 16;
    private static final int BAR_W = 4;
    private static final int MAX_W = 456;
    private static final int MAX_H = 330;
    private static final int STEP = 24;
    private static final int TOC_W = 104;
    private static final int TOC_ROW = 14;
    /** About 26 CJK characters (9 px each) per line. */
    private static final int READ_W = 234;
    /** Below this panel width the contents column becomes a drop-down. */
    private static final int NARROW_BELOW = 380;
    private static final int GAP = 8;

    private static final int KIND_TEXT = 0;
    private static final int KIND_HEADING = 1;
    private static final int KIND_KEYCAP = 2;

    private final ToIntFunction<String> width;
    private final String title;
    private final String backLabel;
    private final List<Section> sections;
    private String topLabel;
    private String chaptersLabel;
    private String unsetText = "-";
    private Map<String, String> keyNames = Map.of();
    private String narrateButton = "%s";
    private String narrateChapter = "%s";
    private int pendingJump = -1;

    private int screenW;
    private int screenH;
    private int px;
    private int py;
    private int pw;
    private int ph;
    private boolean narrow;
    private int bodyX;
    private int bodyY;
    private int bodyW;
    private int readX;
    private int readY;
    private int readW;
    private int readH;
    private int textW;
    private int tocX;
    private int tocY;
    private int topY;
    private int scroll;
    private boolean dragBar;
    private int dragOffset;
    private boolean dropdownOpen;
    private int dropdownHover = -1;
    private int focus = -1;
    private boolean narrationRequested;
    private final List<Line> lines = new ArrayList<>();
    private int[] sectionTop = new int[0];
    private int contentH;
    private int layoutCount;

    private record Line(int kind, String text, int x, int y, int color, int keyW) {}

    public ManualPanel(ToIntFunction<String> width, String title, String backLabel, List<Section> sections) {
        this.width = width;
        this.title = title;
        this.backLabel = backLabel;
        this.sections = List.copyOf(sections);
        this.chaptersLabel = title;
    }

    // ------------------------------------------------------------------ optional features

    /** Adds the button at the very top (it returns {@link #OPEN_QUICK_SETUP}). */
    public ManualPanel withTopButton(String label) {
        this.topLabel = label;
        return this;
    }

    /** The word on the chapter drop-down of a narrow screen. */
    public ManualPanel withChaptersLabel(String label) {
        this.chaptersLabel = label;
        return this;
    }

    /** Key names for {@code R}, {@code P}, {@code G}, {@code S}; {@code unset} is shown for a key that is not bound. */
    public ManualPanel withKeys(Map<String, String> names, String unset) {
        this.keyNames = names == null ? Map.of() : Map.copyOf(names);
        this.unsetText = unset == null ? "-" : unset;
        return this;
    }

    /** Narrator phrases ({@code %s} = the label). */
    public ManualPanel withNarration(String button, String chapter) {
        this.narrateButton = button;
        this.narrateChapter = chapter;
        return this;
    }

    /** Opens at this chapter (0-based) once the screen has a size. */
    public ManualPanel openAt(int chapter) {
        this.pendingJump = chapter;
        return this;
    }

    // ------------------------------------------------------------------ layout

    public void resize(int w, int h) {
        screenW = w;
        screenH = h;
        pw = Math.min(w - 2 * OUTER, MAX_W);
        ph = Math.min(h - 2 * OUTER, MAX_H);
        px = (w - pw) / 2;
        py = (h - ph) / 2;
        narrow = pw < NARROW_BELOW;
        bodyX = px + PAD;
        bodyY = py + TITLE_H + 4;
        bodyW = pw - 2 * PAD;
        int y = bodyY;
        topY = y;
        if (topLabel != null) y += BTN_H + 4;
        int bottom = py + ph - PAD - BTN_H - 4;
        if (narrow) {
            tocX = bodyX;
            tocY = y;
            y += BTN_H + 4;
            readX = bodyX;
            readW = bodyW;
        } else {
            tocX = bodyX;
            tocY = y;
            readX = bodyX + TOC_W + GAP;
            readW = bodyW - TOC_W - GAP;
        }
        readY = y;
        readH = Math.max(LINE * 3, bottom - y);
        textW = Math.max(60, Math.min(READ_W, readW - BAR_W - 6));
        layout();
        if (pendingJump >= 0) {
            jumpTo(pendingJump);
            pendingJump = -1;
        }
    }

    private String keyName(String code) {
        String name = keyNames.get(code);
        return name == null || name.isBlank() ? unsetText : name;
    }

    private String inlineKeys(String text) {
        return text.replace("{R}", keyName("R")).replace("{P}", keyName("P"))
                .replace("{G}", keyName("G")).replace("{S}", keyName("S"));
    }

    private void layout() {
        layoutCount++;
        lines.clear();
        sectionTop = new int[sections.size()];
        int y = 0;
        for (int i = 0; i < sections.size(); i++) {
            Section s = sections.get(i);
            sectionTop[i] = y;
            for (String l : UiText.wrap(inlineKeys(s.title()), textW, width)) {
                lines.add(new Line(KIND_HEADING, l, 0, y, SettingsPanel.C_TITLE, 0));
                y += LINE + 1;
            }
            y += 4;
            for (String paragraph : s.body().split("\n")) {
                if (paragraph.length() > 3 && paragraph.charAt(0) == '{' && paragraph.charAt(2) == '}'
                        && "RPGS".indexOf(paragraph.charAt(1)) >= 0) {
                    String cap = keyName(String.valueOf(paragraph.charAt(1)));
                    int capW = Math.max(20, width.applyAsInt(cap) + 12);
                    lines.add(new Line(KIND_KEYCAP, cap, 0, y, SettingsPanel.C_TITLE, capW));
                    List<String> wrapped = UiText.wrap(inlineKeys(paragraph.substring(3)), textW - capW - 8, width);
                    int ly = y + 3;
                    for (String l : wrapped) {
                        lines.add(new Line(KIND_TEXT, l, capW + 8, ly, SettingsPanel.C_DESC, 0));
                        ly += LINE;
                    }
                    y += Math.max(BTN_H, wrapped.size() * LINE + 6) + 4;
                } else {
                    for (String l : UiText.wrap(inlineKeys(paragraph), textW, width)) {
                        lines.add(new Line(KIND_TEXT, l, 0, y, SettingsPanel.C_DESC, 0));
                        y += LINE;
                    }
                    y += 4;
                }
            }
            y += 8;
        }
        contentH = Math.max(0, y - 8);
        clamp();
    }

    private int maxScroll() { return Math.max(0, contentH - readH); }

    private void clamp() { scroll = Math.max(0, Math.min(maxScroll(), scroll)); }

    private void jumpTo(int chapter) {
        if (chapter < 0 || chapter >= sectionTop.length) return;
        scroll = sectionTop[chapter];
        clamp();
    }

    /** The chapter being read: the last one whose heading has reached the top, the final one at the bottom. */
    public int currentChapter() {
        if (sectionTop.length == 0) return 0;
        if (maxScroll() > 0 && scroll >= maxScroll() - 1) return sectionTop.length - 1;
        int cur = 0;
        for (int i = 0; i < sectionTop.length; i++) if (sectionTop[i] <= scroll + 2) cur = i;
        return cur;
    }

    // ------------------------------------------------------------------ geometry

    private int[] backRect() {
        int w = Math.max(64, width.applyAsInt(backLabel) + 20);
        return new int[] {px + (pw - w) / 2, py + ph - PAD - BTN_H, w, BTN_H};
    }

    private int[] topRect() { return new int[] {bodyX, topY, bodyW, BTN_H}; }

    private int[] tocRowRect(int i) { return new int[] {tocX, tocY + i * TOC_ROW, TOC_W, TOC_ROW}; }

    private int[] dropdownRect() { return new int[] {tocX, tocY, bodyW, BTN_H}; }

    private int dropdownRows() { return Math.min(sections.size(), Math.max(3, (py + ph - (tocY + BTN_H) - 4) / TOC_ROW)); }

    private int[] dropdownPopupRect() {
        int rows = dropdownRows();
        return new int[] {tocX, tocY + BTN_H + 1, bodyW, rows * TOC_ROW + 2};
    }

    private static boolean in(int mx, int my, int[] r) {
        return mx >= r[0] && mx < r[0] + r[2] && my >= r[1] && my < r[1] + r[3];
    }

    private int thumbH() { return Math.max(12, (int) ((long) readH * readH / Math.max(1, contentH))); }

    private int thumbY() {
        int travel = readH - thumbH();
        return readY + (maxScroll() == 0 ? 0 : (int) ((long) travel * scroll / maxScroll()));
    }

    // ------------------------------------------------------------------ focus items

    private static final int F_TOP = 0;
    private static final int F_CHAPTER = 1;
    private static final int F_DROPDOWN = 2;
    private static final int F_BACK = 3;

    private record Focus(int type, int index) {}

    private List<Focus> focusItems() {
        List<Focus> out = new ArrayList<>();
        if (topLabel != null) out.add(new Focus(F_TOP, 0));
        if (narrow) out.add(new Focus(F_DROPDOWN, 0));
        else for (int i = 0; i < sections.size(); i++) out.add(new Focus(F_CHAPTER, i));
        out.add(new Focus(F_BACK, 0));
        return out;
    }

    private int[] focusRect(Focus f) {
        return switch (f.type()) {
            case F_TOP -> topRect();
            case F_CHAPTER -> tocRowRect(f.index());
            case F_DROPDOWN -> dropdownRect();
            default -> backRect();
        };
    }

    // ------------------------------------------------------------------ render

    public void render(UiCanvas c, int mx, int my) {
        c.fill(0, 0, screenW, screenH, 0xA0000000);
        SettingsPanel.rrect(c, px, py, pw, ph, SettingsPanel.C_PANEL);
        SettingsPanel.border(c, px, py, pw, ph, SettingsPanel.C_PANEL_EDGE);
        String t = UiText.fit(title, pw - 2 * PAD, width);
        c.text(t, px + (pw - width.applyAsInt(t)) / 2, py + 7, SettingsPanel.C_TITLE);
        c.fill(px + PAD, py + TITLE_H, pw - 2 * PAD, 1, SettingsPanel.C_PANEL_EDGE);

        if (topLabel != null) {
            int[] r = topRect();
            boolean hover = in(mx, my, r) && !dropdownOpen;
            SettingsPanel.rrect(c, r[0], r[1], r[2], r[3], hover ? SettingsPanel.C_BUTTON_HOVER : SettingsPanel.C_BUTTON);
            String label = UiText.fit(topLabel, r[2] - 12, width);
            c.text(label, r[0] + (r[2] - width.applyAsInt(label)) / 2, r[1] + 4, SettingsPanel.C_TITLE);
        }
        if (narrow) drawDropdown(c, mx, my);
        else drawToc(c, mx, my);

        // the reading column
        c.pushClip(readX, readY, readW, readH);
        for (Line l : lines) {
            int y = readY + l.y() - scroll;
            if (y + LINE < readY || y > readY + readH) continue;
            int x = readX + l.x();
            if (l.kind() == KIND_HEADING) {
                c.text(l.text(), x, y, l.color());
                c.fill(x, y + LINE, Math.min(textW, width.applyAsInt(l.text())), 1, SettingsPanel.C_ACCENT);
            } else if (l.kind() == KIND_KEYCAP) {
                SettingsPanel.rrect(c, x, y, l.keyW(), BTN_H - 2, 0xFF2A2E3C);
                SettingsPanel.border(c, x, y, l.keyW(), BTN_H - 2, SettingsPanel.C_PANEL_EDGE);
                c.text(l.text(), x + (l.keyW() - width.applyAsInt(l.text())) / 2, y + 3, SettingsPanel.C_TITLE);
            } else {
                c.text(l.text(), x, y, l.color());
            }
        }
        c.popClip();
        if (maxScroll() > 0) {
            int sx = readX + readW - BAR_W;
            c.fill(sx, readY, BAR_W, readH, 0x80000000);
            c.fill(sx, thumbY(), BAR_W, thumbH(), dragBar ? 0xFFFFFFFF : 0xFFA0A4B0);
        }
        int[] b = backRect();
        SettingsPanel.rrect(c, b[0], b[1], b[2], b[3], in(mx, my, b) ? SettingsPanel.C_BUTTON_HOVER : SettingsPanel.C_BUTTON);
        c.text(backLabel, b[0] + (b[2] - width.applyAsInt(backLabel)) / 2, b[1] + 4, SettingsPanel.C_TITLE);

        if (narrow && dropdownOpen) drawDropdownPopup(c, mx, my);
        Focus f = focus >= 0 && focus < focusItems().size() ? focusItems().get(focus) : null;
        if (f != null) {
            int[] r = focusRect(f);
            SettingsPanel.border(c, r[0] - 1, r[1] - 1, r[2] + 2, r[3] + 2, SettingsPanel.C_ACCENT);
        }
    }

    private void drawToc(UiCanvas c, int mx, int my) {
        SettingsPanel.rrect(c, tocX - 2, tocY - 2, TOC_W + 4, readH + 4, SettingsPanel.C_SIDEBAR);
        int cur = currentChapter();
        for (int i = 0; i < sections.size(); i++) {
            int[] r = tocRowRect(i);
            if (r[1] + r[3] > readY + readH + 2) break;
            boolean selected = i == cur;
            boolean hover = in(mx, my, r);
            if (selected) {
                c.fill(r[0] + 1, r[1], r[2] - 2, r[3], 0xFF2A3A5C);
                c.fill(r[0] + 1, r[1], 2, r[3], SettingsPanel.C_ACCENT);
            } else if (hover) {
                c.fill(r[0] + 1, r[1], r[2] - 2, r[3], 0x30FFFFFF);
            }
            String label = UiText.fit(sections.get(i).title(), TOC_W - 14, width);
            c.text(label, r[0] + 7, r[1] + 3, selected ? SettingsPanel.C_TITLE : hover ? 0xFFE0E4EE : 0xFFB4B9C8);
        }
    }

    private void drawDropdown(UiCanvas c, int mx, int my) {
        int[] r = dropdownRect();
        boolean hover = in(mx, my, r) && !dropdownOpen;
        SettingsPanel.rrect(c, r[0], r[1], r[2], r[3], hover || dropdownOpen ? SettingsPanel.C_BUTTON_HOVER : SettingsPanel.C_BUTTON);
        String current = sections.isEmpty() ? "" : sections.get(currentChapter()).title();
        String label = UiText.fit(chaptersLabel + (dropdownOpen ? " ▴  " : " ▾  ") + current, r[2] - 12, width);
        c.text(label, r[0] + 6, r[1] + 4, SettingsPanel.C_TITLE);
    }

    private void drawDropdownPopup(UiCanvas c, int mx, int my) {
        int[] pr = dropdownPopupRect();
        SettingsPanel.rrect(c, pr[0], pr[1], pr[2], pr[3], 0xFF161A24);
        SettingsPanel.border(c, pr[0], pr[1], pr[2], pr[3], SettingsPanel.C_ACCENT);
        int cur = currentChapter();
        for (int i = 0; i < dropdownRows(); i++) {
            int[] r = {pr[0] + 1, pr[1] + 1 + i * TOC_ROW, pr[2] - 2, TOC_ROW};
            if (in(mx, my, r) || i == dropdownHover) c.fill(r[0], r[1], r[2], r[3], 0xFF34405E);
            c.text(UiText.fit(sections.get(i).title(), r[2] - 16, width), r[0] + 7, r[1] + 3,
                    i == cur ? SettingsPanel.C_MATCH : SettingsPanel.C_TITLE);
        }
    }

    // ------------------------------------------------------------------ input

    /** @return {@link #BACK} for 返回, {@link #OPEN_QUICK_SETUP} for the top button, else {@link #NONE}. The click is always consumed by the caller. */
    public int mouseClicked(int mx, int my, int button) {
        if (button != 0) return NONE;
        focus = -1;
        if (narrow && dropdownOpen) {
            int[] pr = dropdownPopupRect();
            if (in(mx, my, pr)) {
                int i = (my - pr[1] - 1) / TOC_ROW;
                if (i >= 0 && i < dropdownRows()) jumpTo(i);
            }
            dropdownOpen = false;
            return NONE;
        }
        if (in(mx, my, backRect())) return BACK;
        if (topLabel != null && in(mx, my, topRect())) return OPEN_QUICK_SETUP;
        if (narrow) {
            if (in(mx, my, dropdownRect())) {
                dropdownOpen = true;
                return NONE;
            }
        } else {
            for (int i = 0; i < sections.size(); i++) {
                if (in(mx, my, tocRowRect(i)) && tocRowRect(i)[1] + TOC_ROW <= readY + readH + 2) {
                    jumpTo(i);
                    return NONE;
                }
            }
        }
        if (maxScroll() > 0 && mx >= readX + readW - BAR_W - 2 && mx < readX + readW && my >= readY && my < readY + readH) {
            dragBar = true;
            int ty = thumbY();
            dragOffset = my >= ty && my < ty + thumbH() ? my - ty : thumbH() / 2;
            dragTo(my);
        }
        return NONE;
    }

    public void mouseDragged(int mx, int my) {
        if (dragBar) dragTo(my);
    }

    public void mouseReleased() { dragBar = false; }

    private void dragTo(int my) {
        int travel = readH - thumbH();
        if (travel <= 0) return;
        scroll = Math.round(maxScroll() * ((my - dragOffset - readY) / (float) travel));
        clamp();
    }

    public void mouseScrolled(double scrollY) {
        if (scrollY == 0) return;
        scroll += scrollY > 0 ? -STEP : STEP;
        clamp();
    }

    /** Escape returns {@link #BACK} (it first closes an open chapter list); Tab walks the buttons; Enter or Space presses; arrows, page keys, Home and End scroll. */
    public int keyPressed(int key) { return keyPressed(key, false); }

    public int keyPressed(int key, boolean shift) {
        if (narrow && dropdownOpen) {
            switch (key) {
                case SettingsPanel.KEY_ESCAPE, SettingsPanel.KEY_TAB -> dropdownOpen = false;
                case SettingsPanel.KEY_UP -> dropdownHover = Math.max(0, (dropdownHover < 0 ? currentChapter() : dropdownHover) - 1);
                case SettingsPanel.KEY_DOWN -> dropdownHover = Math.min(dropdownRows() - 1,
                        (dropdownHover < 0 ? currentChapter() : dropdownHover) + 1);
                case SettingsPanel.KEY_ENTER, 335, SettingsPanel.KEY_SPACE -> {
                    if (dropdownHover >= 0) jumpTo(dropdownHover);
                    dropdownOpen = false;
                }
                default -> { }
            }
            if (!dropdownOpen) dropdownHover = -1;
            return NONE;
        }
        switch (key) {
            case SettingsPanel.KEY_ESCAPE -> {
                return BACK;
            }
            case SettingsPanel.KEY_TAB -> {
                moveFocus(shift ? -1 : 1);
                return NONE;
            }
            case SettingsPanel.KEY_ENTER, 335, SettingsPanel.KEY_SPACE -> {
                return activate();
            }
            case SettingsPanel.KEY_UP -> scroll -= STEP;
            case SettingsPanel.KEY_DOWN -> scroll += STEP;
            case SettingsPanel.KEY_PAGE_UP -> scroll -= readH - 12;
            case SettingsPanel.KEY_PAGE_DOWN -> scroll += readH - 12;
            case SettingsPanel.KEY_HOME -> scroll = 0;
            case SettingsPanel.KEY_END -> scroll = maxScroll();
            default -> { }
        }
        clamp();
        return NONE;
    }

    private void moveFocus(int dir) {
        List<Focus> items = focusItems();
        int n = items.size();
        focus = focus < 0 ? (dir > 0 ? 0 : n - 1) : (focus + dir + n) % n;
        narrationRequested = true;
        Focus f = items.get(focus);
        if (f.type() == F_CHAPTER) {
            // a chapter row of the contents is always on screen; nothing to scroll
            return;
        }
    }

    private int activate() {
        List<Focus> items = focusItems();
        if (focus < 0 || focus >= items.size()) return NONE;
        Focus f = items.get(focus);
        narrationRequested = true;
        switch (f.type()) {
            case F_TOP -> {
                return OPEN_QUICK_SETUP;
            }
            case F_BACK -> {
                return BACK;
            }
            case F_DROPDOWN -> {
                dropdownOpen = true;
                dropdownHover = currentChapter();
            }
            default -> jumpTo(f.index());
        }
        return NONE;
    }

    /** What the narrator says for the focused item, or the manual's title when nothing is focused. */
    public String narration() {
        List<Focus> items = focusItems();
        if (focus < 0 || focus >= items.size()) return title;
        Focus f = items.get(focus);
        return switch (f.type()) {
            case F_TOP -> String.format(narrateButton, topLabel);
            case F_BACK -> String.format(narrateButton, backLabel);
            case F_DROPDOWN -> String.format(narrateChapter, chaptersLabel + " " + sections.get(currentChapter()).title());
            default -> String.format(narrateChapter, sections.get(f.index()).title());
        };
    }

    /** True once after the focus moved or a focused item was pressed. */
    public boolean consumeNarrationRequest() {
        boolean was = narrationRequested;
        narrationRequested = false;
        return was;
    }

    // ------------------------------------------------------------------ test hooks

    int scroll() { return scroll; }

    int contentHeight() { return contentH; }

    int listHeight() { return readH; }

    int[] panelRect() { return new int[] {px, py, pw, ph}; }

    int[] backBounds() { return backRect(); }

    int[] topBounds() { return topLabel == null ? null : topRect(); }

    int[] tocBounds(int chapter) { return narrow ? null : tocRowRect(chapter); }

    int[] dropdownBounds() { return narrow ? dropdownRect() : null; }

    int[] dropdownRowBounds(int i) {
        int[] pr = dropdownPopupRect();
        return new int[] {pr[0] + 1, pr[1] + 1 + i * TOC_ROW, pr[2] - 2, TOC_ROW};
    }

    boolean isNarrow() { return narrow; }

    boolean dropdownIsOpen() { return dropdownOpen; }

    int[] readRect() { return new int[] {readX, readY, readW, readH}; }

    int readableWidth() { return textW; }

    int focusIndex() { return focus; }

    int layoutCount() { return layoutCount; }

    int chapterTop(int chapter) { return sectionTop[chapter]; }

    List<String> headingTexts() {
        List<String> out = new ArrayList<>();
        for (Line l : lines) if (l.kind() == KIND_HEADING) out.add(l.text());
        return out;
    }

    List<String> bodyTexts() {
        List<String> out = new ArrayList<>();
        for (Line l : lines) if (l.kind() == KIND_TEXT) out.add(l.text());
        return out;
    }

    List<String> keycapTexts() {
        List<String> out = new ArrayList<>();
        for (Line l : lines) if (l.kind() == KIND_KEYCAP) out.add(l.text());
        return out;
    }
}
