package com.dragonmeow.nyanlex.config;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToIntFunction;

/**
 * The one framed card that the 快速設定 questionnaire, the on-the-spot consent box and every
 * small confirmation share, drawn without any Minecraft type: a title row (with progress dots),
 * a scrolling content area (paragraphs, option boxes, button rows, a list, key caps) and a
 * footer whose left buttons sit at the left edge and whose main action sits at the right edge.
 *
 * <p>Nothing is focused when a page opens: Tab and Shift+Tab move a frame through every option
 * and button, Enter or Space presses the focused one, Escape returns the cancel id. Layout is
 * computed once per {@link #set} / {@link #resize}, not per frame. The glue draws it over
 * whatever screen is open and forwards input, so a chest or server menu underneath is never
 * replaced or closed.</p>
 */
public final class DialogPanel {

    public static final int NONE = -1;

    /** One block of the content area. */
    public interface Block {}

    /** A wrapped paragraph; color 0 means the normal body color. */
    public record Text(String text, int color) implements Block {}

    /** A button; {@code selected} marks the chosen one of a group of choices, {@code enabled} false greys it out. */
    public record Btn(int id, String label, boolean primary, boolean selected, boolean enabled) {
        public Btn(int id, String label, boolean primary) { this(id, label, primary, false, true); }
        public Btn(int id, String label) { this(id, label, false, false, true); }
        public Btn withEnabled(boolean value) { return new Btn(id, label, primary, selected, value); }
    }

    /** Buttons of equal width side by side that share one row (a segmented choice when one is selected). */
    public record Row(List<Btn> buttons) implements Block {}

    /** A large option box with a title and an explanation; all choices of a page are drawn equally tall. */
    public record Choice(int id, String title, String desc, boolean selected) implements Block {}

    /** A scrolling list of text lines with at most {@code maxVisible} lines on screen. */
    public record ListBox(List<String> lines, int maxVisible) implements Block {}

    /** A key cap followed by what the key does. */
    public record KeyLine(String key, String desc) implements Block {}

    /** Empty vertical space. */
    public record Gap(int height) implements Block {}

    /** One mode or engine button of a {@link Grid} row; {@code color} 0 means white, {@code blue} the AI look. */
    public record Cell(int id, String label, int color, boolean blue) {}

    /** One row of a {@link Grid}: a name and its two buttons. */
    public record GridRow(String name, Cell mode, Cell engine) {}

    /**
     * A table of rows with two buttons each, the same buttons and column widths as the settings
     * screen's 顯示 category (the 快速設定 display page). {@code modeLabels} and {@code engineLabels}
     * list every text a button may show, so the columns keep their width while a choice changes;
     * {@code note} is a grey paragraph under row {@code noteAfterRow} (-1 for none).
     */
    public record Grid(String modeHead, String engineHead, List<String> modeLabels, List<String> engineLabels,
                       String mixedLabel, List<GridRow> rows, int noteAfterRow, String note) implements Block {}

    /** Buttons pinned to the bottom: {@code left} at the left edge, {@code right} at the right edge. */
    public record Footer(List<Btn> left, List<Btn> right) {
        public static Footer of(Btn left, Btn right) {
            return new Footer(left == null ? List.of() : List.of(left), right == null ? List.of() : List.of(right));
        }
    }

    /** Everything a page shows. {@code steps} 0 hides the progress dots. */
    public record Content(String title, int steps, int step, List<Block> blocks, Footer footer, int cancelId) {
        public Content(String title, List<Block> blocks, Footer footer, int cancelId) {
            this(title, 0, 0, blocks, footer, cancelId);
        }
    }

    /** Narrator phrases ({@code %s} = the label), set once by the glue from its lang files. */
    public record Narration(String button, String choice, String chosen, String list, String disabled) {
        static final Narration PLAIN = new Narration("%s", "%s", "%s", "%s", "%s");
    }

    // -------- palette: the settings screen's, nothing else
    static final int C_DIM = 0xA0000000;
    static final int C_BOX = 0xF0101218;
    static final int C_EDGE = 0xFF3C4256;
    static final int C_TITLE = 0xFFFFFFFF;
    static final int C_TEXT = 0xFFE4E7EF;
    static final int C_MUTED = 0xFFA4A9B8;
    static final int C_ACCENT = 0xFF4C9AFF;
    static final int C_BTN = 0xFF38405A;
    static final int C_BTN_HOVER = 0xFF4B5578;
    static final int C_PRIMARY = 0xFF2E5E9E;
    static final int C_PRIMARY_HOVER = 0xFF3E72B8;
    static final int C_OFF_BG = 0xFF2C2F3A;
    static final int C_OFF_TEXT = 0xFF70747F;
    static final int C_CARD = 0xB0222632;
    static final int C_CARD_HOVER = 0xC02D3344;
    static final int C_CARD_SELECTED = 0xFF2A3A5C;
    static final int C_KEYCAP = 0xFF2A2E3C;

    // -------- metrics (4, 8, 12, 16 only)
    private static final int PAD = 12;
    private static final int LINE = 10;
    private static final int BTN_H = 16;
    private static final int GAP = 4;
    private static final int BLOCK_GAP = 8;
    private static final int PAD_Y = 8;
    private static final int TITLE_H = 16;
    private static final int MAX_W = 320;
    private static final int MIN_BTN_W = 56;
    private static final int STEP = 20;
    private static final int BAR_W = 4;
    private static final int CHOICE_PAD_TOP = 6;
    private static final int CHOICE_PAD_TOP_COMPACT = 4;
    private static final int CELL_H = 14;
    private static final int HEAD_H = 10;

    private final ToIntFunction<String> width;
    private Narration narration = Narration.PLAIN;
    private Content content;
    private int screenW;
    private int screenH;
    private int bx;
    private int by;
    private int bw;
    private int bh;
    private int viewX;
    private int viewY;
    private int viewW;
    private int viewH;
    private int contentH;
    private int scroll;
    private int footerY;
    private String titleShown = "";
    private int dotsX;
    private final List<Item> items = new ArrayList<>();
    private final List<Item> footerItems = new ArrayList<>();
    private final List<LaidText> texts = new ArrayList<>();
    private final List<LaidList> lists = new ArrayList<>();
    private int focus = -1;
    private boolean narrationRequested;
    private int dragList = -1;

    /** One pressable thing: a button or a choice box. y is relative to the content area for content items. */
    private record Item(int id, String label, String desc, int x, int y, int w, int h, boolean choice,
                        boolean selected, boolean primary, boolean enabled, boolean inFooter,
                        boolean cell, int color, boolean blue, String narrate, int pad) {
        Item(int id, String label, String desc, int x, int y, int w, int h, boolean choice,
             boolean selected, boolean primary, boolean enabled, boolean inFooter) {
            this(id, label, desc, x, y, w, h, choice, selected, primary, enabled, inFooter, false, 0, false, null,
                    CHOICE_PAD_TOP);
        }
    }

    /** {@code keyW} > 0 marks a key cap of that width. */
    private record LaidText(String text, int x, int y, int color, int keyW) {}

    private record LaidList(List<String> lines, int x, int y, int w, int h, int visible) {}

    private final java.util.Map<Integer, Integer> listScroll = new java.util.HashMap<>();

    public DialogPanel(ToIntFunction<String> width) {
        this.width = width;
    }

    public void setNarration(Narration narration) {
        this.narration = narration == null ? Narration.PLAIN : narration;
    }

    /** Shows a page; nothing is focused and the content starts at the top. */
    public void set(Content content) {
        this.content = content;
        this.focus = -1;
        this.scroll = 0;
        this.listScroll.clear();
        layout();
    }

    /** Replaces the content of the page that is already showing (a choice was made): the focus frame and scroll stay. */
    public void update(Content content) {
        int keepFocus = focus;
        int keepScroll = scroll;
        java.util.Map<Integer, Integer> keepLists = new java.util.HashMap<>(listScroll);
        this.content = content;
        layout();
        listScroll.putAll(keepLists);
        scroll = Math.max(0, Math.min(maxScroll(), keepScroll));
        focus = keepFocus < order().size() ? keepFocus : -1;
    }

    public void resize(int w, int h) {
        screenW = w;
        screenH = h;
        layout();
    }

    public boolean hasContent() { return content != null; }

    // ------------------------------------------------------------------ layout

    private int innerW() { return bw - 2 * PAD; }

    private int layouts;

    private void layout() {
        layouts++;
        items.clear();
        footerItems.clear();
        texts.clear();
        lists.clear();
        if (content == null || screenW <= 0) return;
        bw = Math.min(screenW - 16, MAX_W);
        int inner = bw - 2 * PAD;
        titleShown = UiText.fit(content.title(), inner - (content.steps() > 0 ? content.steps() * 8 + 8 : 0), width);

        // 1) the frame around the content (it does not depend on the content)
        Footer footer = content.footer();
        int footerH = footer == null || (footer.left().isEmpty() && footer.right().isEmpty()) ? 0 : BTN_H;
        int headH = TITLE_H + 4;
        int chrome = PAD_Y + headH + (footerH > 0 ? 8 + footerH : 0) + PAD_Y;
        int maxViewH = Math.max(LINE * 3, screenH - 8 - chrome);

        // 2) content, laid out relative to the content area's top-left; when the equally tall option
        // boxes do not fit the screen, each box takes only the height its own text needs
        contentH = layoutBlocks(inner, false);
        if (contentH > maxViewH && content.blocks().stream().filter(b -> b instanceof Choice).count() >= 2) {
            items.clear();
            texts.clear();
            lists.clear();
            contentH = layoutBlocks(inner, true);
        }
        viewH = Math.min(contentH, maxViewH);
        viewW = inner;
        bh = chrome + viewH;
        bx = (screenW - bw) / 2;
        by = Math.max(4, (screenH - bh) / 2);
        viewX = bx + PAD;
        viewY = by + PAD_Y + headH;
        footerY = viewY + viewH + 8;
        dotsX = bx + bw - PAD;
        scroll = Math.max(0, Math.min(maxScroll(), scroll));

        // 3) footer buttons, equal width
        if (footerH > 0) {
            int w = MIN_BTN_W;
            for (Btn b : footer.left()) w = Math.max(w, width.applyAsInt(b.label()) + 16);
            for (Btn b : footer.right()) w = Math.max(w, width.applyAsInt(b.label()) + 16);
            int count = Math.max(1, footer.left().size() + footer.right().size());
            w = Math.min(w, Math.max(MIN_BTN_W - 16, (inner - GAP * (count - 1)) / count));
            int x = bx + PAD;
            for (Btn b : footer.left()) {
                footerItems.add(new Item(b.id(), b.label(), null, x, footerY, w, BTN_H, false, false, b.primary(), b.enabled(), true));
                x += w + GAP;
            }
            x = bx + bw - PAD - footer.right().size() * w - Math.max(0, footer.right().size() - 1) * GAP;
            for (Btn b : footer.right()) {
                footerItems.add(new Item(b.id(), b.label(), null, x, footerY, w, BTN_H, false, false, b.primary(), b.enabled(), true));
                x += w + GAP;
            }
        }
    }

    /** Lays the blocks out below each other and returns their total height. */
    private int layoutBlocks(int inner, boolean compact) {
        int y = 0;
        int choiceH = 0;
        for (Block b : content.blocks()) {
            if (b instanceof Choice c) choiceH = Math.max(choiceH, choiceHeight(c, inner, compact));
        }
        int pad = compact ? CHOICE_PAD_TOP_COMPACT : CHOICE_PAD_TOP;
        Block previous = null;
        for (Block b : content.blocks()) {
            if (previous != null && !(b instanceof Gap) && !(previous instanceof Gap)) y += gapBetween(previous, b);
            previous = b;
            if (b instanceof Text t) {
                for (String line : UiText.wrap(t.text(), inner, width)) {
                    texts.add(new LaidText(line, 0, y, t.color() == 0 ? C_TEXT : t.color(), 0));
                    y += LINE;
                }
            } else if (b instanceof Gap g) {
                y += g.height();
            } else if (b instanceof Choice c) {
                int h = compact ? choiceHeight(c, inner, true) : choiceH;
                items.add(new Item(c.id(), c.title(), c.desc(), 0, y, inner, h, true, c.selected(), false, true, false,
                        false, 0, false, null, pad));
                y += h;
            } else if (b instanceof Row r) {
                int n = Math.max(1, r.buttons().size());
                int each = (inner - (n - 1) * GAP) / n;
                if (n == 1) each = Math.min(inner, Math.max(MIN_BTN_W, width.applyAsInt(r.buttons().get(0).label()) + 24));
                int x = 0;
                for (Btn btn : r.buttons()) {
                    items.add(new Item(btn.id(), btn.label(), null, x, y, each, BTN_H, false, btn.selected(),
                            btn.primary(), btn.enabled(), false));
                    x += each + GAP;
                }
                y += BTN_H;
            } else if (b instanceof ListBox l) {
                int visible = Math.max(1, Math.min(l.maxVisible(), l.lines().size()));
                int h = visible * LINE + 8;
                lists.add(new LaidList(l.lines(), 0, y, inner, h, visible));
                y += h;
            } else if (b instanceof KeyLine k) {
                int capW = Math.max(18, width.applyAsInt(k.key()) + 10);
                texts.add(new LaidText(k.key(), 0, y, C_TITLE, capW));
                List<String> wrapped = UiText.wrap(k.desc(), inner - capW - 8, width);
                int ly = y + 3;
                for (String line : wrapped) {
                    texts.add(new LaidText(line, capW + 8, ly, C_TEXT, 0));
                    ly += LINE;
                }
                y += Math.max(BTN_H, wrapped.size() * LINE + 6);
            } else if (b instanceof Grid g) {
                y = layoutGrid(g, inner, y);
            }
        }
        return y;
    }

    /** Column headers, then one row per entry (name on the left, mode and engine buttons on the right). */
    private int layoutGrid(Grid g, int inner, int top) {
        int[] cw = SettingsPanel.columnWidths(width, g.modeLabels(), g.engineLabels(), g.mixedLabel(),
                g.modeHead(), g.engineHead());
        int engineX = inner - cw[1];
        int modeX = engineX - GAP - cw[0];
        int nameW = Math.max(40, modeX - 8);
        int y = top;
        texts.add(new LaidText(g.modeHead(), modeX + (cw[0] - width.applyAsInt(g.modeHead())) / 2, y + 1, C_MUTED, 0));
        texts.add(new LaidText(g.engineHead(), engineX + (cw[1] - width.applyAsInt(g.engineHead())) / 2, y + 1,
                C_MUTED, 0));
        y += HEAD_H;
        for (int i = 0; i < g.rows().size(); i++) {
            GridRow row = g.rows().get(i);
            texts.add(new LaidText(UiText.fit(row.name(), nameW, width), 0, y + 3, C_TEXT, 0));
            items.add(new Item(row.mode().id(), row.mode().label(), null, modeX, y, cw[0], CELL_H, false, false, false,
                    true, false, true, row.mode().color(), row.mode().blue(),
                    row.name() + " " + g.modeHead() + " " + row.mode().label(), CHOICE_PAD_TOP));
            items.add(new Item(row.engine().id(), row.engine().label(), null, engineX, y, cw[1], CELL_H, false, false,
                    false, true, false, true, row.engine().color(), row.engine().blue(),
                    row.name() + " " + g.engineHead() + " " + row.engine().label(), CHOICE_PAD_TOP));
            y += CELL_H + 4;
            if (i == g.noteAfterRow() && g.note() != null && !g.note().isEmpty()) {
                for (String line : UiText.wrap(g.note(), inner, width)) {
                    texts.add(new LaidText(line, 0, y - 2, C_MUTED, 0));
                    y += LINE;
                }
                y += 2;
            }
        }
        return y - 4;
    }

    /** Space between two blocks: tight inside a group of the same kind, looser between groups. */
    private static int gapBetween(Block a, Block b) {
        boolean sameKind = a.getClass() == b.getClass();
        // a question sits close to what answers it
        boolean question = a instanceof Text && (b instanceof Choice || b instanceof Row || b instanceof Grid);
        return sameKind || question ? 4 : BLOCK_GAP;
    }

    private int choiceHeight(Choice c, int inner, boolean compact) {
        int textW = inner - 24;
        return (compact ? CHOICE_PAD_TOP_COMPACT : CHOICE_PAD_TOP) + LINE + 2
                + UiText.wrap(c.desc(), textW, width).size() * LINE + (compact ? 3 : 4);
    }

    private int maxScroll() { return Math.max(0, contentH - viewH); }

    private int screenY(Item it) { return it.inFooter() ? it.y() : viewY + it.y() - scroll; }

    private int screenX(Item it) { return it.inFooter() ? it.x() : viewX + it.x(); }

    private List<Item> order() {
        List<Item> all = new ArrayList<>(items);
        all.addAll(footerItems);
        return all;
    }

    private boolean visible(Item it) {
        if (it.inFooter()) return true;
        int y = screenY(it);
        return y + it.h() > viewY && y < viewY + viewH;
    }

    // ------------------------------------------------------------------ render

    public void render(UiCanvas c, int mx, int my) {
        if (content == null) return;
        c.fill(0, 0, screenW, screenH, C_DIM);
        SettingsPanel.rrect(c, bx, by, bw, bh, C_BOX);
        SettingsPanel.border(c, bx, by, bw, bh, C_EDGE);
        c.text(titleShown, bx + PAD, by + PAD_Y, C_TITLE);
        if (content.steps() > 0) {
            for (int i = 0; i < content.steps(); i++) {
                int dx = dotsX - (content.steps() - i) * 8 + 2;
                c.fill(dx, by + PAD_Y + 3, 4, 4, i < content.step() ? C_ACCENT : C_EDGE);
            }
        }
        c.fill(bx + PAD, by + PAD_Y + TITLE_H - 3, bw - 2 * PAD, 1, C_EDGE);

        c.pushClip(viewX - 2, viewY, viewW + 4, viewH);
        for (LaidText t : texts) {
            int ty = viewY + t.y() - scroll;
            if (ty + LINE < viewY || ty > viewY + viewH) continue;
            if (t.keyW() > 0) {
                int capW = t.keyW();
                SettingsPanel.rrect(c, viewX, ty, capW, BTN_H - 2, C_KEYCAP);
                SettingsPanel.border(c, viewX, ty, capW, BTN_H - 2, C_EDGE);
                c.text(t.text(), viewX + (capW - width.applyAsInt(t.text())) / 2, ty + 3, C_TITLE);
            } else {
                c.text(t.text(), viewX + t.x(), ty, t.color());
            }
        }
        for (int li = 0; li < lists.size(); li++) {
            LaidList l = lists.get(li);
            int ly = viewY + l.y() - scroll;
            SettingsPanel.rrect(c, viewX, ly, l.w(), l.h(), C_CARD);
            SettingsPanel.border(c, viewX, ly, l.w(), l.h(), C_EDGE);
            int first = listScroll.getOrDefault(li, 0);
            c.pushClip(viewX + 1, ly + 1, l.w() - 2, l.h() - 2);
            for (int i = 0; i < l.visible() && first + i < l.lines().size(); i++) {
                c.text(UiText.fit(l.lines().get(first + i), l.w() - 16, width), viewX + 6, ly + 4 + i * LINE, C_TEXT);
            }
            c.popClip();
            if (l.lines().size() > l.visible()) {
                int trackH = l.h() - 4;
                int thumbH = Math.max(8, trackH * l.visible() / l.lines().size());
                int travel = trackH - thumbH;
                int thumbY = ly + 2 + (l.lines().size() - l.visible() == 0 ? 0
                        : travel * first / (l.lines().size() - l.visible()));
                c.fill(viewX + l.w() - BAR_W - 2, thumbY, BAR_W, thumbH, 0xFFA0A4B0);
            }
        }
        for (Item it : items) {
            if (!visible(it)) continue;
            drawItem(c, it, mx, my);
        }
        c.popClip();
        if (maxScroll() > 0) {
            int sx = bx + bw - 6;
            c.fill(sx, viewY, BAR_W, viewH, 0x80000000);
            int thumbH = Math.max(12, viewH * viewH / Math.max(1, contentH));
            int travel = viewH - thumbH;
            c.fill(sx, viewY + (maxScroll() == 0 ? 0 : travel * scroll / maxScroll()), BAR_W, thumbH, 0xFFA0A4B0);
        }
        for (Item it : footerItems) drawItem(c, it, mx, my);
        drawFocus(c);
    }

    private void drawItem(UiCanvas c, Item it, int mx, int my) {
        int x = screenX(it);
        int y = screenY(it);
        boolean hover = it.enabled() && mx >= x && mx < x + it.w() && my >= y && my < y + it.h()
                && (it.inFooter() || (my >= viewY && my < viewY + viewH));
        if (it.choice()) {
            SettingsPanel.rrect(c, x, y, it.w(), it.h(), it.selected() ? C_CARD_SELECTED : hover ? C_CARD_HOVER : C_CARD);
            SettingsPanel.border(c, x, y, it.w(), it.h(), it.selected() ? C_ACCENT : C_EDGE);
            c.text(UiText.fit(it.label(), it.w() - 16, width), x + 12, y + it.pad(), C_TITLE);
            int ly = y + it.pad() + LINE + 2;
            for (String line : UiText.wrap(it.desc(), it.w() - 24, width)) {
                c.text(line, x + 12, ly, C_MUTED);
                ly += LINE;
            }
            return;
        }
        if (it.cell()) {
            SettingsPanel.drawChoiceCell(c, x, y, it.w(), it.h(), it.label(), it.color() == 0 ? C_TITLE : it.color(),
                    it.blue(), hover, width);
            return;
        }
        int bg = !it.enabled() ? C_OFF_BG
                : it.selected() ? C_CARD_SELECTED
                : it.primary() ? (hover ? C_PRIMARY_HOVER : C_PRIMARY)
                : (hover ? C_BTN_HOVER : C_BTN);
        SettingsPanel.rrect(c, x, y, it.w(), it.h(), bg);
        if (it.selected()) SettingsPanel.border(c, x, y, it.w(), it.h(), C_ACCENT);
        String label = UiText.fit(it.label(), it.w() - 8, width);
        c.text(label, x + (it.w() - width.applyAsInt(label)) / 2, y + 4, it.enabled() ? C_TITLE : C_OFF_TEXT);
    }

    private void drawFocus(UiCanvas c) {
        Item it = focused();
        if (it == null) return;
        int x = screenX(it);
        int y = screenY(it);
        boolean clip = !it.inFooter();
        if (clip) c.pushClip(viewX - 2, viewY - 1, viewW + 4, viewH + 2);
        SettingsPanel.border(c, x - 1, y - 1, it.w() + 2, it.h() + 2, C_ACCENT);
        if (clip) c.popClip();
    }

    // ------------------------------------------------------------------ input

    /** Left click: the id of the enabled button or choice hit, else {@link #NONE}. The click itself is always consumed by the caller. */
    public int mouseClicked(int mx, int my, int button) {
        if (content == null || button != 0) return NONE;
        focus = -1;
        for (int li = 0; li < lists.size(); li++) {
            LaidList l = lists.get(li);
            int ly = viewY + l.y() - scroll;
            if (l.lines().size() > l.visible() && mx >= viewX + l.w() - BAR_W - 6 && mx < viewX + l.w()
                    && my >= ly && my < ly + l.h()) {
                dragList = li;
                dragListTo(my);
                return NONE;
            }
        }
        if (maxScroll() > 0 && mx >= bx + bw - 10 && mx < bx + bw - 2 && my >= viewY && my < viewY + viewH) {
            scroll = Math.round(maxScroll() * ((my - viewY) / (float) viewH));
            scroll = Math.max(0, Math.min(maxScroll(), scroll));
            return NONE;
        }
        for (Item it : order()) {
            if (!it.enabled() || !visible(it)) continue;
            int x = screenX(it);
            int y = screenY(it);
            if (mx >= x && mx < x + it.w() && my >= y && my < y + it.h()
                    && (it.inFooter() || (my >= viewY && my < viewY + viewH))) {
                return it.id();
            }
        }
        return NONE;
    }

    public void mouseDragged(int mx, int my) {
        if (dragList >= 0) dragListTo(my);
    }

    public void mouseReleased() { dragList = -1; }

    private void dragListTo(int my) {
        LaidList l = lists.get(dragList);
        int ly = viewY + l.y() - scroll;
        int maxFirst = l.lines().size() - l.visible();
        int first = Math.round(maxFirst * ((my - ly) / (float) l.h()));
        listScroll.put(dragList, Math.max(0, Math.min(maxFirst, first)));
    }

    /** Wheel: over a list it scrolls the list, elsewhere the content. */
    public void mouseScrolled(int mx, int my, double dy) {
        if (content == null || dy == 0) return;
        for (int li = 0; li < lists.size(); li++) {
            LaidList l = lists.get(li);
            int ly = viewY + l.y() - scroll;
            if (mx >= viewX && mx < viewX + l.w() && my >= ly && my < ly + l.h()
                    && l.lines().size() > l.visible()) {
                int maxFirst = l.lines().size() - l.visible();
                int first = listScroll.getOrDefault(li, 0) + (dy > 0 ? -1 : 1);
                listScroll.put(li, Math.max(0, Math.min(maxFirst, first)));
                return;
            }
        }
        scroll = Math.max(0, Math.min(maxScroll(), scroll + (dy > 0 ? -STEP : STEP)));
    }

    /**
     * Escape returns the cancel id; Tab and Shift+Tab move the focus frame; Enter or Space returns
     * the focused item's id (never anything when nothing is focused); the arrow keys scroll.
     */
    public int keyPressed(int key, boolean shift) {
        if (content == null) return NONE;
        switch (key) {
            case SettingsPanel.KEY_ESCAPE -> {
                return content.cancelId();
            }
            case SettingsPanel.KEY_TAB -> {
                moveFocus(shift ? -1 : 1);
                return NONE;
            }
            case SettingsPanel.KEY_ENTER, 335, SettingsPanel.KEY_SPACE -> {
                Item it = focused();
                return it != null && it.enabled() ? it.id() : NONE;
            }
            case SettingsPanel.KEY_UP -> scroll(-STEP);
            case SettingsPanel.KEY_DOWN -> scroll(STEP);
            case SettingsPanel.KEY_PAGE_UP -> scroll(-(viewH - 12));
            case SettingsPanel.KEY_PAGE_DOWN -> scroll(viewH - 12);
            default -> { }
        }
        return NONE;
    }

    /** Escape only (for callers that have no modifier state). */
    public int keyPressed(int key) { return keyPressed(key, false); }

    private void scroll(int delta) {
        scroll = Math.max(0, Math.min(maxScroll(), scroll + delta));
    }

    private Item focused() {
        List<Item> all = order();
        return focus >= 0 && focus < all.size() ? all.get(focus) : null;
    }

    /** Moves the frame to the next (+1) or previous (-1) enabled item, wrapping; the first Tab lands on the first item. */
    public void moveFocus(int dir) {
        List<Item> all = order();
        if (all.isEmpty()) return;
        int n = all.size();
        int i = focus;
        for (int tries = 0; tries < n; tries++) {
            i = i < 0 ? (dir > 0 ? 0 : n - 1) : (i + dir + n) % n;
            if (all.get(i).enabled()) {
                focus = i;
                scrollTo(all.get(i));
                narrationRequested = true;
                return;
            }
        }
    }

    private void scrollTo(Item it) {
        if (it.inFooter()) return;
        if (it.y() < scroll) scroll = it.y();
        else if (it.y() + it.h() > scroll + viewH) scroll = it.y() + it.h() - viewH;
        scroll = Math.max(0, Math.min(maxScroll(), scroll));
    }

    /** What the narrator says for the focused item, or the page title when nothing is focused. */
    public String narration() {
        Item it = focused();
        if (it == null) return content == null ? "" : content.title();
        if (it.choice()) {
            String base = String.format(narration.choice(), it.label() + (it.desc() == null ? "" : "。" + it.desc()));
            return it.selected() ? String.format(narration.chosen(), base) : base;
        }
        String label = String.format(narration.button(), it.narrate() != null ? it.narrate() : it.label());
        return it.enabled() ? label : String.format(narration.disabled(), label);
    }

    /** True once after focus moved. */
    public boolean consumeNarrationRequest() {
        boolean was = narrationRequested;
        narrationRequested = false;
        return was;
    }

    // ------------------------------------------------------------------ test hooks

    /** How many times the layout was computed (it must not grow while only rendering). */
    int layoutCount() { return layouts; }

    int[] boxRect() { return new int[] {bx, by, bw, bh}; }

    int[] viewRect() { return new int[] {viewX, viewY, viewW, viewH}; }

    int contentHeight() { return contentH; }

    int scrollOffset() { return scroll; }

    int focusIndex() { return focus; }

    /** Rectangle {x, y, w, h} of the button or choice with this id, or null. */
    int[] buttonRect(int id) {
        for (Item it : order()) {
            if (it.id() == id) return new int[] {screenX(it), screenY(it), it.w(), it.h()};
        }
        return null;
    }

    List<String> shownTexts() {
        List<String> out = new ArrayList<>();
        for (LaidText t : texts) if (t.keyW() == 0) out.add(t.text());
        return out;
    }

    List<String> shownLabels() {
        List<String> out = new ArrayList<>();
        for (Item it : order()) out.add(it.label());
        return out;
    }
}
