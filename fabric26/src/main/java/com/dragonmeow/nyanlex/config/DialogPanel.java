package com.dragonmeow.nyanlex.config;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToIntFunction;

/**
 * A small modal card drawn without any Minecraft type: a title, wrapped text blocks and
 * rows of buttons. Used for the first-start card and the on-the-spot consent box. Buttons
 * are never focused by default, Enter does nothing, Escape returns the cancel id. The glue
 * draws it over whatever screen is open and forwards input, so the screen underneath (a
 * chest, a server menu) is never replaced or closed.
 */
public final class DialogPanel {

    public static final int NONE = -1;

    /** One block of the card: a paragraph or a row of buttons. */
    public interface Block {}

    public record Text(String text, int color) implements Block {}

    public record Btn(int id, String label, boolean primary) {}

    public record Row(List<Btn> buttons) implements Block {}

    /** Everything the card shows. */
    public record Content(String title, List<Block> blocks, int cancelId) {}

    static final int C_DIM = 0xA0000000;
    static final int C_BOX = 0xF0141822;
    static final int C_EDGE = 0xFF4C9AFF;
    static final int C_TITLE = 0xFFFFD75E;
    static final int C_TEXT = 0xFFE4E7EF;
    static final int C_BTN = 0xFF38405A;
    static final int C_BTN_HOVER = 0xFF4B5578;
    static final int C_PRIMARY = 0xFF2E5E9E;
    static final int C_PRIMARY_HOVER = 0xFF3E72B8;

    private static final int PAD = 8;
    private static final int LINE = 10;
    private static final int BTN_H = 16;
    private static final int GAP = 4;
    private static final int MAX_W = 300;

    private final ToIntFunction<String> width;
    private Content content;
    private int screenW;
    private int screenH;
    private int bx;
    private int by;
    private int bw;
    private int bh;
    private final List<Laid> laid = new ArrayList<>();

    private record Laid(Btn btn, int x, int y, int w) {}

    private record LaidText(String text, int x, int y, int color) {}

    private final List<LaidText> texts = new ArrayList<>();
    private String titleShown = "";

    public DialogPanel(ToIntFunction<String> width) {
        this.width = width;
    }

    public void set(Content content) {
        this.content = content;
        layout();
    }

    public void resize(int w, int h) {
        screenW = w;
        screenH = h;
        layout();
    }

    public boolean hasContent() { return content != null; }

    private void layout() {
        laid.clear();
        texts.clear();
        if (content == null || screenW <= 0) return;
        bw = Math.min(screenW - 16, MAX_W);
        int inner = bw - 2 * PAD;
        int y = PAD;
        titleShown = UiText.fit(content.title(), inner, width);
        y += LINE + 4;
        List<Laid> rowsAcc = new ArrayList<>();
        for (Block b : content.blocks()) {
            if (b instanceof Text t) {
                for (String line : UiText.wrap(t.text(), inner, width)) {
                    texts.add(new LaidText(line, PAD, y, t.color() == 0 ? C_TEXT : t.color()));
                    y += LINE;
                }
                y += 3;
            } else if (b instanceof Row r) {
                // flow the buttons of one row onto as many lines as the width needs
                int x = 0;
                List<Laid> line = new ArrayList<>();
                int lineW = 0;
                for (Btn btn : r.buttons()) {
                    int w = Math.min(inner, Math.max(48, width.applyAsInt(btn.label()) + 16));
                    if (!line.isEmpty() && lineW + GAP + w > inner) {
                        y = placeLine(line, lineW, inner, y);
                        line = new ArrayList<>();
                        lineW = 0;
                    }
                    line.add(new Laid(btn, 0, 0, w));
                    lineW += (line.size() > 1 ? GAP : 0) + w;
                }
                if (!line.isEmpty()) y = placeLine(line, lineW, inner, y);
                y += 3;
            }
        }
        bh = y + PAD - 3;
        bx = (screenW - bw) / 2;
        by = Math.max(4, (screenH - bh) / 2);
        // shift everything laid out so far (coordinates were relative to the box)
        for (int i = 0; i < texts.size(); i++) {
            LaidText t = texts.get(i);
            texts.set(i, new LaidText(t.text(), bx + t.x(), by + t.y(), t.color()));
        }
        for (int i = 0; i < laid.size(); i++) {
            Laid l = laid.get(i);
            laid.set(i, new Laid(l.btn(), bx + l.x(), by + l.y(), l.w()));
        }
    }

    private int placeLine(List<Laid> line, int lineW, int inner, int y) {
        int x = PAD + (inner - lineW) / 2;
        for (Laid l : line) {
            laid.add(new Laid(l.btn(), x, y, l.w()));
            x += l.w() + GAP;
        }
        return y + BTN_H + GAP;
    }

    public void render(UiCanvas c, int mx, int my) {
        if (content == null) return;
        c.fill(0, 0, screenW, screenH, C_DIM);
        c.fill(bx + 1, by, bw - 2, bh, C_BOX);
        c.fill(bx, by + 1, bw, bh - 2, C_BOX);
        c.fill(bx + 1, by, bw - 2, 1, C_EDGE);
        c.fill(bx + 1, by + bh - 1, bw - 2, 1, C_EDGE);
        c.fill(bx, by + 1, 1, bh - 2, C_EDGE);
        c.fill(bx + bw - 1, by + 1, 1, bh - 2, C_EDGE);
        c.text(titleShown, bx + (bw - width.applyAsInt(titleShown)) / 2, by + PAD, C_TITLE);
        for (LaidText t : texts) c.text(t.text(), t.x(), t.y(), t.color());
        for (Laid l : laid) {
            boolean hover = mx >= l.x() && mx < l.x() + l.w() && my >= l.y() && my < l.y() + BTN_H;
            int bg = l.btn().primary() ? (hover ? C_PRIMARY_HOVER : C_PRIMARY) : (hover ? C_BTN_HOVER : C_BTN);
            c.fill(l.x() + 1, l.y(), l.w() - 2, BTN_H, bg);
            c.fill(l.x(), l.y() + 1, l.w(), BTN_H - 2, bg);
            String label = UiText.fit(l.btn().label(), l.w() - 6, width);
            c.text(label, l.x() + (l.w() - width.applyAsInt(label)) / 2, l.y() + 4, 0xFFFFFFFF);
        }
    }

    /** Left click: the id of the button hit, else {@link #NONE}. The click itself is always consumed by the caller. */
    public int mouseClicked(int mx, int my, int button) {
        if (content == null || button != 0) return NONE;
        for (Laid l : laid) {
            if (mx >= l.x() && mx < l.x() + l.w() && my >= l.y() && my < l.y() + BTN_H) return l.btn().id();
        }
        return NONE;
    }

    /** Escape returns the cancel id; Enter and every other key return {@link #NONE} (nothing is ever focused). */
    public int keyPressed(int key) {
        return content != null && key == SettingsPanel.KEY_ESCAPE ? content.cancelId() : NONE;
    }

    // ------------------------------------------------------------------ test hooks

    int[] boxRect() { return new int[] {bx, by, bw, bh}; }

    int[] buttonRect(int id) {
        for (Laid l : laid) if (l.btn().id() == id) return new int[] {l.x(), l.y(), l.w(), BTN_H};
        return null;
    }

    List<String> shownTexts() {
        List<String> out = new ArrayList<>();
        for (LaidText t : texts) out.add(t.text());
        return out;
    }
}
