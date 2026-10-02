package com.dragonmeow.nyanlex.config;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToIntFunction;

/**
 * The manual screen (說明書), drawn without any Minecraft type: a title, scrolling sections
 * (a conspicuous heading, normal-size body text) and a 返回 button at the bottom. The glue
 * forwards input and closes the screen when {@link #BACK} comes back (Escape does the same).
 */
public final class ManualPanel {

    public static final int NONE = 0;
    public static final int BACK = 1;

    /** One chapter: a heading and its body (paragraphs separated by newlines). */
    public record Section(String title, String body) {}

    private static final int C_PANEL = 0xE0101218;
    private static final int C_EDGE = 0xFF3C4256;
    private static final int C_TITLE = 0xFFFFFFFF;
    private static final int C_HEADING = 0xFFFFD75E;
    private static final int C_RULE = 0xFF4C9AFF;
    private static final int C_BODY = 0xFFD7DBE6;
    private static final int C_BTN = 0xFF38405A;
    private static final int C_BTN_HOVER = 0xFF4B5578;

    private static final int OUTER = 6;
    private static final int PAD = 8;
    private static final int LINE = 10;
    private static final int TITLE_H = 20;
    private static final int BTN_H = 16;
    private static final int BAR_W = 4;
    private static final int MAX_W = 456;
    private static final int MAX_H = 330;
    private static final int STEP = 24;

    private final ToIntFunction<String> width;
    private final String title;
    private final String backLabel;
    private final List<Section> sections;

    private int screenW;
    private int screenH;
    private int px;
    private int py;
    private int pw;
    private int ph;
    private int listX;
    private int listY;
    private int listW;
    private int listH;
    private int scroll;
    private boolean dragBar;
    private int dragOffset;
    private final List<Line> lines = new ArrayList<>();
    private int contentH;

    private record Line(String text, int y, int color, boolean heading) {}

    public ManualPanel(ToIntFunction<String> width, String title, String backLabel, List<Section> sections) {
        this.width = width;
        this.title = title;
        this.backLabel = backLabel;
        this.sections = List.copyOf(sections);
    }

    public void resize(int w, int h) {
        screenW = w;
        screenH = h;
        pw = Math.min(w - 2 * OUTER, MAX_W);
        ph = Math.min(h - 2 * OUTER, MAX_H);
        px = (w - pw) / 2;
        py = (h - ph) / 2;
        listX = px + PAD;
        listY = py + TITLE_H + 4;
        listW = pw - 2 * PAD;
        listH = ph - TITLE_H - 4 - BTN_H - 2 * PAD + 2;
        layout();
    }

    private void layout() {
        lines.clear();
        int textW = listW - BAR_W - 6;
        int y = 0;
        for (Section s : sections) {
            for (String l : UiText.wrap(s.title(), textW, width)) {
                lines.add(new Line(l, y, C_HEADING, true));
                y += LINE + 1;
            }
            y += 3;
            for (String paragraph : s.body().split("\n")) {
                for (String l : UiText.wrap(paragraph, textW, width)) {
                    lines.add(new Line(l, y, C_BODY, false));
                    y += LINE;
                }
                y += 3;
            }
            y += 8;
        }
        contentH = Math.max(0, y - 8);
        clamp();
    }

    private int maxScroll() { return Math.max(0, contentH - listH); }

    private void clamp() { scroll = Math.max(0, Math.min(maxScroll(), scroll)); }

    private int[] backRect() {
        int w = Math.max(64, width.applyAsInt(backLabel) + 20);
        return new int[] {px + (pw - w) / 2, py + ph - PAD - BTN_H, w, BTN_H};
    }

    private static boolean in(int mx, int my, int[] r) {
        return mx >= r[0] && mx < r[0] + r[2] && my >= r[1] && my < r[1] + r[3];
    }

    private int thumbH() { return Math.max(12, (int) ((long) listH * listH / Math.max(1, contentH))); }

    private int thumbY() {
        int travel = listH - thumbH();
        return listY + (maxScroll() == 0 ? 0 : (int) ((long) travel * scroll / maxScroll()));
    }

    public void render(UiCanvas c, int mx, int my) {
        c.fill(0, 0, screenW, screenH, 0xA0000000);
        c.fill(px + 1, py, pw - 2, ph, C_PANEL);
        c.fill(px, py + 1, pw, ph - 2, C_PANEL);
        c.fill(px + 1, py, pw - 2, 1, C_EDGE);
        c.fill(px + 1, py + ph - 1, pw - 2, 1, C_EDGE);
        c.fill(px, py + 1, 1, ph - 2, C_EDGE);
        c.fill(px + pw - 1, py + 1, 1, ph - 2, C_EDGE);
        String t = UiText.fit(title, pw - 2 * PAD, width);
        c.text(t, px + (pw - width.applyAsInt(t)) / 2, py + 7, C_TITLE);
        c.fill(px + PAD, py + TITLE_H, pw - 2 * PAD, 1, C_EDGE);

        c.pushClip(listX, listY, listW, listH);
        for (Line l : lines) {
            int y = listY + l.y() - scroll;
            if (y + LINE < listY || y > listY + listH) continue;
            c.text(l.text(), listX, y, l.color());
            if (l.heading()) c.fill(listX, y + LINE, Math.min(listW - BAR_W - 6, width.applyAsInt(l.text())), 1, C_RULE);
        }
        c.popClip();
        if (maxScroll() > 0) {
            int sx = listX + listW - BAR_W;
            c.fill(sx, listY, BAR_W, listH, 0x80000000);
            c.fill(sx, thumbY(), BAR_W, thumbH(), dragBar ? 0xFFFFFFFF : 0xFFA0A4B0);
        }
        int[] b = backRect();
        c.fill(b[0] + 1, b[1], b[2] - 2, b[3], in(mx, my, b) ? C_BTN_HOVER : C_BTN);
        c.fill(b[0], b[1] + 1, b[2], b[3] - 2, in(mx, my, b) ? C_BTN_HOVER : C_BTN);
        c.text(backLabel, b[0] + (b[2] - width.applyAsInt(backLabel)) / 2, b[1] + 4, 0xFFFFFFFF);
    }

    /** @return {@link #BACK} when the 返回 button was hit. The click is always consumed by the caller. */
    public int mouseClicked(int mx, int my, int button) {
        if (button != 0) return NONE;
        if (in(mx, my, backRect())) return BACK;
        if (maxScroll() > 0 && mx >= listX + listW - BAR_W - 2 && mx < listX + listW && my >= listY && my < listY + listH) {
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
        int travel = listH - thumbH();
        if (travel <= 0) return;
        scroll = Math.round(maxScroll() * ((my - dragOffset - listY) / (float) travel));
        clamp();
    }

    public void mouseScrolled(double scrollY) {
        if (scrollY == 0) return;
        scroll += scrollY > 0 ? -STEP : STEP;
        clamp();
    }

    /** Escape returns {@link #BACK}; arrows, page keys, Home and End scroll. */
    public int keyPressed(int key) {
        switch (key) {
            case SettingsPanel.KEY_ESCAPE -> {
                return BACK;
            }
            case SettingsPanel.KEY_UP -> scroll -= STEP;
            case SettingsPanel.KEY_DOWN -> scroll += STEP;
            case SettingsPanel.KEY_PAGE_UP -> scroll -= listH - 12;
            case SettingsPanel.KEY_PAGE_DOWN -> scroll += listH - 12;
            case SettingsPanel.KEY_HOME -> scroll = 0;
            case SettingsPanel.KEY_END -> scroll = maxScroll();
            default -> { }
        }
        clamp();
        return NONE;
    }

    // ------------------------------------------------------------------ test hooks

    int scroll() { return scroll; }

    int contentHeight() { return contentH; }

    int listHeight() { return listH; }

    int[] panelRect() { return new int[] {px, py, pw, ph}; }

    int[] backBounds() { return backRect(); }

    List<String> headingTexts() {
        List<String> out = new ArrayList<>();
        for (Line l : lines) if (l.heading()) out.add(l.text());
        return out;
    }
}
