package com.dragonmeow.nyanlex.config;

import com.dragonmeow.nyanlex.warmup.ItemWarmupDriver;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * The card-style settings screen, drawn and driven without any Minecraft type: a category
 * sidebar, a search box that filters every category, and a scrolling list of setting
 * cards (title, description shown in full, one control on the right) with collapsible
 * groups. The loader glue forwards input and supplies a {@link UiCanvas} and a {@link UiHost}.
 */
public final class SettingsPanel {

    // ------------------------------------------------------------------ session state

    /** What survives closing and reopening the screen within one game session. */
    public static final class State {
        SettingsCategory category = SettingsCategory.GENERAL;
        final Map<SettingsCategory, Integer> scroll = new EnumMap<>(SettingsCategory.class);
        final Set<String> expanded = new HashSet<>();
    }

    private static final State SESSION = new State();

    public static State sessionState() { return SESSION; }

    /** The category the next settings screen of this session opens on. */
    public static void rememberCategory(SettingsCategory category) { SESSION.category = category; }

    // ------------------------------------------------------------------ keys (GLFW values)

    public static final int KEY_ESCAPE = 256;
    public static final int KEY_ENTER = 257;
    public static final int KEY_BACKSPACE = 259;
    public static final int KEY_DELETE = 261;
    public static final int KEY_RIGHT = 262;
    public static final int KEY_LEFT = 263;
    public static final int KEY_DOWN = 264;
    public static final int KEY_UP = 265;
    public static final int KEY_PAGE_UP = 266;
    public static final int KEY_PAGE_DOWN = 267;
    public static final int KEY_HOME = 268;
    public static final int KEY_END = 269;
    private static final int KEY_A = 65;
    private static final int KEY_F = 70;
    private static final int KEY_V = 86;
    private static final int KEY_SLASH = 47;

    // ------------------------------------------------------------------ colors

    static final int C_PANEL = 0xD8101218;
    static final int C_PANEL_EDGE = 0xFF3C4256;
    static final int C_SIDEBAR = 0xB00A0C12;
    static final int C_CARD = 0xB0222632;
    static final int C_CARD_HOVER = 0xC02D3344;
    static final int C_CARD_EDGE = 0xFF343A4C;
    static final int C_HEADER = 0xB02A3040;
    static final int C_HEADER_HOVER = 0xC0364058;
    static final int C_TITLE = 0xFFFFFFFF;
    static final int C_DESC = 0xFFA4A9B8;
    static final int C_ACCENT = 0xFF4C9AFF;
    static final int C_ON = 0xFF3DBB6C;
    static final int C_OFF = 0xFF565B6C;
    static final int C_KNOB = 0xFFF2F4F8;
    static final int C_BUTTON = 0xFF38405A;
    static final int C_BUTTON_HOVER = 0xFF4B5578;
    static final int C_DANGER = 0xFF6E2C2C;
    static final int C_DANGER_HOVER = 0xFF8E3A3A;
    static final int C_DISABLED_TEXT = 0xFF70747F;
    static final int C_MATCH = 0xFFFFD75E;
    static final int C_CRUMB = 0xFF6FC3E8;
    static final int C_WARN = 0xFFFFD75E;
    static final int C_GOOD = 0xFF7FE08F;
    static final int C_TRACK = 0xFF2A2E3C;
    static final int C_NOTICE = 0xC03A3118;

    // ------------------------------------------------------------------ metrics

    static final int OUTER = 6;
    static final int INNER = 4;
    static final int SIDEBAR_W = 78;
    static final int SIDEBAR_W_NARROW = 24;
    static final int NARROW_BELOW = 290;
    static final int CAT_ROW_H = 18;
    static final int SEARCH_H = 16;
    static final int HELP_W = 16;
    static final int DONE_H = 16;
    static final int LINE_H = 9;
    static final int CARD_PAD = 6;
    static final int CARD_GAP = 3;
    static final int HEADER_H = 18;
    static final int GROUP_INDENT = 8;
    static final int SCROLLBAR_W = 4;
    static final int MAX_PANEL_W = 456;
    static final int MAX_PANEL_H = 330;
    static final int SCROLL_STEP = 24;
    static final int MAX_QUERY = 40;

    // ------------------------------------------------------------------ fields

    private final UiHost host;
    private final State state;
    private final Function<String, String> lang;

    private int screenW;
    private int screenH;
    private int px, py, pw, ph;
    private boolean narrow;
    private int sbX, sbY, sbW, sbH;
    private int cx, cy, cw;
    private int searchW;
    private int listX, listY, listW, listH;

    private final StringBuilder query = new StringBuilder();
    private int caret;
    private boolean searchFocused;

    private List<Row> rows = new ArrayList<>();
    private int contentH;
    private boolean dirty = true;
    private Map<SettingsCategory, Integer> searchCounts = new EnumMap<>(SettingsCategory.class);

    private String tooltipText;
    private String openDropdownId;
    private String dragSliderId;
    private boolean ignoreSlash;
    private boolean dragScrollbar;
    private int dragScrollOffset;
    private int mouseX = -1;
    private int mouseY = -1;

    public SettingsPanel(UiHost host) {
        this(host, SESSION);
    }

    public SettingsPanel(UiHost host, State state) {
        this.host = host;
        this.state = state;
        this.lang = key -> host.text(key);
    }

    // ------------------------------------------------------------------ rows

    private static final class Row {
        SettingGroup group;
        SettingCard card;
        int indent;
        int y;
        int h;
        String title = "";
        String crumb;
        List<String> desc = List.of();
        boolean stacked;
        int ctrlW;
        int ctrlH;
        /** SURFACE / ALL cards: width of each button (shrunk to fit when the card is stacked). */
        int[] multiW;
        /** FILE cards: index of the path line inside {@link #desc} (-1 otherwise) and the full path. */
        int pathLine = -1;
        String fullPath;
        FileLocations.Entry file;
        boolean header() { return group != null; }
    }

    // ------------------------------------------------------------------ public queries

    public SettingsCategory category() { return state.category; }

    public String query() { return query.toString(); }

    public boolean isSearching() { return query.toString().trim().length() > 0; }

    /** True while the search box has keyboard focus (mod hotkeys must not fire). */
    public boolean isTyping() { return searchFocused; }

    public boolean isExpanded(String groupId) { return state.expanded.contains(groupId); }

    public int scroll() { return state.scroll.getOrDefault(state.category, 0); }

    public boolean narrowLayout() { return narrow; }

    public boolean dropdownOpen() { return openDropdownId != null; }

    // ------------------------------------------------------------------ layout

    public void resize(int width, int height) {
        screenW = width;
        screenH = height;
        pw = Math.min(width - 2 * OUTER, MAX_PANEL_W);
        ph = Math.min(height - 2 * OUTER, MAX_PANEL_H);
        px = (width - pw) / 2;
        py = (height - ph) / 2;
        narrow = pw < NARROW_BELOW;
        sbW = narrow ? SIDEBAR_W_NARROW : SIDEBAR_W;
        sbX = px + INNER;
        sbY = py + INNER;
        sbH = ph - 2 * INNER;
        cx = sbX + sbW + INNER;
        cy = py + INNER;
        cw = px + pw - INNER - cx;
        searchW = cw - HELP_W - 4;
        listX = cx;
        listY = cy + SEARCH_H + 4;
        listW = cw;
        listH = py + ph - INNER - listY;
        dirty = true;
        openDropdownId = null;
    }

    private void ensureLayout() {
        if (!dirty) return;
        dirty = false;
        rows = new ArrayList<>();
        searchCounts = new EnumMap<>(SettingsCategory.class);
        String q = query.toString();
        boolean searching = q.trim().length() > 0;
        int y = 0;
        if (searching) {
            for (SettingCard card : SettingsModel.search(q, lang)) {
                if (hidden(card)) continue;
                searchCounts.merge(card.category(), 1, Integer::sum);
                Row r = cardRow(card, 0, true);
                r.y = y;
                y += r.h + CARD_GAP;
                rows.add(r);
            }
        } else {
            for (SettingsModel.Node node : SettingsModel.nodes(state.category)) {
                if (node.isGroup() && SettingsModel.FILES_GROUP_ID.equals(node.group().id())
                        && host.fileLocations().isEmpty()) {
                    continue; // this loader's glue does not provide file locations
                }
                if (node.isGroup()) {
                    Row header = new Row();
                    header.group = node.group();
                    header.title = SettingsModel.groupTitle(node.group(), lang);
                    header.h = HEADER_H;
                    header.y = y;
                    y += header.h + CARD_GAP;
                    rows.add(header);
                    if (state.expanded.contains(node.group().id())) {
                        for (SettingCard card : node.group().cards()) {
                            Row r = cardRow(card, GROUP_INDENT, false);
                            r.y = y;
                            y += r.h + CARD_GAP;
                            rows.add(r);
                        }
                    }
                } else {
                    Row r = cardRow(node.card(), 0, false);
                    r.y = y;
                    y += r.h + CARD_GAP;
                    rows.add(r);
                }
            }
        }
        contentH = Math.max(0, y - CARD_GAP);
        clampScroll();
    }

    /** FILE cards only exist where the glue can tell the paths. */
    private boolean hidden(SettingCard card) {
        return card.kind() == SettingCard.Kind.FILE && host.fileLocations().isEmpty();
    }

    private int cardWidth(int indent) { return listW - SCROLLBAR_W - 3 - indent; }

    private Row cardRow(SettingCard card, int indent, boolean crumb) {
        Row r = new Row();
        r.card = card;
        r.indent = indent;
        r.title = SettingsModel.title(card, lang);
        if (crumb) {
            String cat = host.text(card.category().nameKey());
            r.crumb = card.groupTitleKey() == null ? cat
                    : cat + " > " + SettingCard.stripState(host.text(card.groupTitleKey()));
        }
        int cardW = cardWidth(indent);
        int inner = cardW - 2 * CARD_PAD;
        measureControl(r);
        boolean hasCtrl = r.ctrlW > 0;
        int textColW = inner - r.ctrlW - 8;
        r.stacked = card.kind() == SettingCard.Kind.WARMUP || card.kind() == SettingCard.Kind.NOTICE
                || (hasCtrl && textColW < 112);
        if (r.multiW != null && r.stacked && r.ctrlW > inner) shrinkMulti(r, inner);
        if (card.kind() == SettingCard.Kind.NOTICE && r.ctrlW > inner) r.ctrlW = inner;
        int textW = r.stacked || !hasCtrl ? inner : textColW;
        String desc = card.kind() == SettingCard.Kind.INFO && card.id().equals("about_info")
                ? aboutText(card) : SettingsModel.description(card, lang);
        r.desc = UiText.wrap(desc, textW, host::textWidth);
        if (card.kind() == SettingCard.Kind.FILE) {
            r.file = fileEntry(card);
            r.fullPath = r.file == null ? "-" : r.file.path().toString();
            r.desc = new ArrayList<>(r.desc);
            r.pathLine = r.desc.size();
            r.desc.add(UiText.fitMiddle(r.fullPath, textW, host::textWidth));
        }
        int head = CARD_PAD - 1 + (r.crumb != null ? LINE_H : 0) + LINE_H;
        int block = head + (r.desc.isEmpty() ? 0 : 2 + r.desc.size() * LINE_H) + CARD_PAD - 1;
        if (card.kind() == SettingCard.Kind.WARMUP) {
            r.h = block - (CARD_PAD - 1) + 4 + LINE_H + 3 + 6 + 5 + 14 + CARD_PAD;
        } else if (r.stacked && hasCtrl) {
            r.h = block - (CARD_PAD - 1) + 4 + r.ctrlH + CARD_PAD;
            if (card.kind() == SettingCard.Kind.SLIDER) r.ctrlW = inner;
        } else {
            r.h = Math.max(block, r.ctrlH + 2 * CARD_PAD);
        }
        return r;
    }

    private FileLocations.Entry fileEntry(SettingCard card) {
        String id = card.id().startsWith("file.") ? card.id().substring(5) : card.id();
        for (FileLocations.Entry e : host.fileLocations()) if (e.id().equals(id)) return e;
        return null;
    }

    private String aboutText(SettingCard card) {
        String version = host.modVersion();
        return version == null || version.isEmpty() ? "" : host.text(SettingsModel.KEY_ABOUT_VERSION, version);
    }

    private void measureControl(Row r) {
        SettingCard card = r.card;
        TranslatorConfig cfg = host.config();
        switch (card.kind()) {
            case TOGGLE -> {
                r.ctrlW = 24;
                r.ctrlH = 12;
                String st = toggleStateText(card.entry(), cfg);
                if (st != null) r.ctrlW += host.textWidth(st) + 6;
            }
            case DROPDOWN -> {
                int w = 0;
                for (StateText label : card.entry().options().labels()) {
                    w = Math.max(w, host.textWidth(resolve(label)));
                }
                r.ctrlW = Math.max(52, Math.min(96, w + 24));
                r.ctrlH = 14;
            }
            case SLIDER -> {
                r.ctrlW = 104;
                r.ctrlH = LINE_H + 2 + 8;
            }
            case BUTTON -> {
                r.ctrlW = Math.max(58, Math.min(112, host.textWidth(buttonText(card.entry())) + 14));
                r.ctrlH = 14;
            }
            case FILE -> {
                r.ctrlW = Math.max(46, Math.min(112, host.textWidth(host.text(SettingsModel.KEY_BTN_OPEN)) + 14));
                r.ctrlH = 14;
            }
            case NOTICE -> {
                r.ctrlW = 24 + 6 + host.textWidth(noticeSwitchText(card.entry(), cfg));
                r.ctrlH = 12;
            }
            case SURFACE, ALL -> {
                r.multiW = multiWidths();
                r.ctrlW = totalWidth(r.multiW);
                r.ctrlH = card.kind() == SettingCard.Kind.ALL ? 14 + COL_HEADER_H : 14;
            }
            default -> {
                r.ctrlW = 0;
                r.ctrlH = 0;
            }
        }
    }

    static final int MULTI_GAP = 4;

    /** The privacy card's status row: "線上翻譯：開（引擎）" / "線上翻譯：關，不會送出任何文字". */
    private String noticeSwitchText(SettingEntry entry, TranslatorConfig cfg) {
        return DialogContent.onlineStatus(cfg, host::text);
    }

    private static int totalWidth(int[] ws) {
        int total = (ws.length - 1) * MULTI_GAP;
        for (int w : ws) total += w;
        return total;
    }

    /** Scales the buttons down so that they fit {@code avail} pixels (their text gets shortened). */
    private static void shrinkMulti(Row r, int avail) {
        int n = r.multiW.length;
        int room = Math.max(n * 12, avail - (n - 1) * MULTI_GAP);
        int natural = totalWidth(r.multiW) - (n - 1) * MULTI_GAP;
        int[] scaled = new int[n];
        for (int i = 0; i < n; i++) scaled[i] = Math.max(12, r.multiW[i] * room / natural);
        r.multiW = scaled;
        r.ctrlW = totalWidth(scaled);
    }

    static final int COL_HEADER_H = 10;

    /**
     * Widths of the (mode, engine) button columns, the same for every surface row and the 全部項目
     * row so that the two columns line up all the way down; wide enough for the column headers.
     */
    private int[] multiWidths() {
        int mode = host.textWidth(host.text(SettingsModel.KEY_ALL_MIXED));
        for (DisplayMode m : DisplayMode.values()) mode = Math.max(mode, host.textWidth(resolve(SettingsCatalog.modeState(m))));
        int engine = Math.max(host.textWidth(host.text(SettingsModel.KEY_ALL_MIXED)),
                Math.max(host.textWidth(resolve(SettingsCatalog.engineState(true))),
                        host.textWidth(resolve(SettingsCatalog.engineState(false)))));
        int headMode = host.textWidth(host.text(SettingsModel.KEY_ALL_COL_MODE)) + 4;
        int headEngine = host.textWidth(host.text(SettingsModel.KEY_ALL_COL_ENGINE)) + 4;
        return new int[] {Math.max(34, Math.max(mode + 12, headMode)), Math.max(32, Math.max(engine + 12, headEngine))};
    }

    /** Button rectangles of a SURFACE / ALL card, left to right, inside its control area (below the column headers on ALL). */
    private int[][] multiRects(Row r) {
        int[] rc = controlRect(r);
        int[] ws = r.multiW;
        int top = r.card.kind() == SettingCard.Kind.ALL ? rc[1] + COL_HEADER_H : rc[1];
        int[][] out = new int[ws.length][];
        int x = rc[0];
        for (int i = 0; i < ws.length; i++) {
            out[i] = new int[] {x, top, ws[i], 14};
            x += ws[i] + MULTI_GAP;
        }
        return out;
    }

    private String toggleStateText(SettingEntry entry, TranslatorConfig cfg) {
        StateText st = entry.state(cfg);
        if (st == null || SettingsCatalog.STATE_ON.equals(st.key()) || SettingsCatalog.STATE_OFF.equals(st.key())) {
            return null;
        }
        return resolve(st);
    }

    private String buttonText(SettingEntry entry) {
        String override = host.buttonLabel(entry);
        if (override != null) return override;
        StateText st = entry.state(host.config());
        if (st != null) return resolve(st);
        SettingAction action = entry.action();
        if (action != null) {
            switch (action) {
                case OPEN_DO_NOT_TRANSLATE:
                    return host.text(SettingsModel.KEY_BTN_EDIT);
                case OPEN_MANUAL:
                    return host.text(SettingsModel.KEY_BTN_OPEN);
                case HUB_DOWNLOAD:
                    return host.text(SettingsModel.KEY_BTN_DETECT);
                case HUB_OPEN_REPO:
                    return host.text(SettingsModel.KEY_BTN_OPEN);
                case EXPORT_TRANSLATIONS:
                    return host.text(SettingsModel.KEY_BTN_EXPORT);
                case IMPORT_TRANSLATIONS:
                    return host.text(SettingsModel.KEY_BTN_IMPORT);
                default:
                    break;
            }
        }
        if (entry.type() == SettingEntry.Type.SUBSCREEN) return host.text(SettingsModel.KEY_BTN_SETTINGS);
        return host.text(entry.destructive() ? SettingsModel.KEY_BTN_CLEAR : SettingsModel.KEY_BTN_RUN);
    }

    String resolve(StateText text) {
        if (text == null) return "";
        if (text.isLiteral()) return text.literalText();
        Object[] args = new Object[text.args().size()];
        for (int i = 0; i < args.length; i++) {
            Object a = text.args().get(i);
            args[i] = a instanceof StateText nested ? resolve(nested) : a;
        }
        return host.text(text.key(), args);
    }

    // ------------------------------------------------------------------ scrolling

    private int maxScroll() { return Math.max(0, contentH - listH); }

    private void clampScroll() {
        int v = Math.max(0, Math.min(maxScroll(), state.scroll.getOrDefault(state.category, 0)));
        state.scroll.put(state.category, v);
    }

    private void scrollBy(int delta) {
        state.scroll.put(state.category, scroll() + delta);
        clampScroll();
        openDropdownId = null;
    }

    private boolean needsScrollbar() { return maxScroll() > 0; }

    private int thumbH() {
        return Math.max(12, (int) ((long) listH * listH / Math.max(1, contentH)));
    }

    private int thumbY() {
        int travel = listH - thumbH();
        return listY + (maxScroll() == 0 ? 0 : (int) ((long) travel * scroll() / maxScroll()));
    }

    // ------------------------------------------------------------------ geometry of one row

    private int rowScreenY(Row r) { return listY + r.y - scroll(); }

    private boolean rowVisible(Row r) {
        int y = rowScreenY(r);
        return y + r.h > listY && y < listY + listH;
    }

    /** Rectangle {x, y, w, h} of a card's control in screen coordinates. */
    private int[] controlRect(Row r) {
        int cardX = listX + r.indent;
        int cardW = cardWidth(r.indent);
        int y = rowScreenY(r);
        int right = cardX + cardW - CARD_PAD;
        if (r.stacked) {
            int textBottom = y + (CARD_PAD - 1) + (r.crumb != null ? LINE_H : 0) + LINE_H
                    + (r.desc.isEmpty() ? 0 : 2 + r.desc.size() * LINE_H);
            int cy0 = textBottom + 4;
            return new int[] {cardX + CARD_PAD, cy0, r.ctrlW, r.ctrlH};
        }
        int cy0 = y + (r.h - r.ctrlH) / 2;
        return new int[] {right - r.ctrlW, cy0, r.ctrlW, r.ctrlH};
    }

    private static boolean in(int mx, int my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    // ------------------------------------------------------------------ render

    public void render(UiCanvas c, int mx, int my) {
        mouseX = mx;
        mouseY = my;
        tooltipText = null;
        ensureLayout();
        String q = query.toString();
        boolean searching = q.trim().length() > 0;

        rrect(c, px, py, pw, ph, C_PANEL);
        border(c, px, py, pw, ph, C_PANEL_EDGE);

        drawSidebar(c, mx, my, searching);
        drawSearch(c, mx, my);

        c.pushClip(listX, listY, listW, listH);
        if (rows.isEmpty() && searching) {
            String none = UiText.fit(host.text(SettingsModel.KEY_SEARCH_EMPTY, q.trim()), listW - 8, host::textWidth);
            c.text(none, listX + (listW - host.textWidth(none)) / 2, listY + 14, C_DESC);
        }
        for (Row r : rows) {
            if (!rowVisible(r)) continue;
            if (r.header()) drawHeader(c, r, mx, my);
            else drawCard(c, r, mx, my);
        }
        c.popClip();

        String status = host.statusText();
        if (status != null && !status.isEmpty()) {
            String shown = UiText.fit(status, listW - 24, host::textWidth);
            int sw2 = host.textWidth(shown) + 12;
            int sx2 = listX + (listW - sw2) / 2;
            int sy2 = listY + listH - 16;
            rrect(c, sx2, sy2, sw2, 13, 0xF0102A18);
            border(c, sx2, sy2, sw2, 13, C_ON);
            c.text(shown, sx2 + 6, sy2 + 2, C_GOOD);
        }

        if (needsScrollbar()) {
            int sx = listX + listW - SCROLLBAR_W;
            c.fill(sx, listY, SCROLLBAR_W, listH, 0x80000000);
            c.fill(sx, thumbY(), SCROLLBAR_W, thumbH(), dragScrollbar ? 0xFFFFFFFF : 0xFFA0A4B0);
        }

        if (openDropdownId != null) drawDropdownPopup(c, mx, my);
        if (tooltipText != null && openDropdownId == null) drawTooltip(c, tooltipText, mx, my);
    }

    private void drawSidebar(UiCanvas c, int mx, int my, boolean searching) {
        rrect(c, sbX, sbY, sbW, sbH, C_SIDEBAR);
        int y = sbY + 3;
        if (!narrow) {
            String title = UiText.fit(host.text(SettingsModel.KEY_SIDEBAR_TITLE), sbW - 8, host::textWidth);
            c.text(title, sbX + 5, y + 1, C_TITLE);
            y += LINE_H + 5;
            c.fill(sbX + 4, y, sbW - 8, 1, C_PANEL_EDGE);
            y += 4;
        }
        for (SettingsCategory cat : SettingsModel.categories()) {
            boolean selected = !searching && cat == state.category;
            boolean hover = in(mx, my, sbX, y, sbW, CAT_ROW_H);
            if (selected) {
                c.fill(sbX + 1, y, sbW - 2, CAT_ROW_H, 0xFF2A3A5C);
                c.fill(sbX + 1, y, 2, CAT_ROW_H, C_ACCENT);
            } else if (hover) {
                c.fill(sbX + 1, y, sbW - 2, CAT_ROW_H, 0x30FFFFFF);
            }
            int color = selected ? C_TITLE : (hover ? 0xFFE0E4EE : 0xFFB4B9C8);
            Integer count = searching ? searchCounts.get(cat) : null;
            if (searching && count == null) color = C_DISABLED_TEXT;
            if (narrow) {
                String s = host.text(cat.shortKey());
                c.text(s, sbX + (sbW - host.textWidth(s)) / 2, y + 5, color);
            } else {
                String badge = count == null ? "" : String.valueOf(count);
                int badgeW = badge.isEmpty() ? 0 : host.textWidth(badge) + 6;
                String s = UiText.fit(host.text(cat.nameKey()), sbW - 14 - badgeW, host::textWidth);
                c.text(s, sbX + 8, y + 5, color);
                if (!badge.isEmpty()) c.text(badge, sbX + sbW - badgeW + 2, y + 5, C_MATCH);
            }
            y += CAT_ROW_H;
        }
        // Footer: translation counters (wide only) and the Done button.
        int doneY = sbY + sbH - DONE_H - 3;
        if (!narrow && y + 2 * LINE_H + 6 <= doneY) {
            String done = UiText.fit(host.text("config.nyanlex.progress.done", host.translatedCount()), sbW - 10, host::textWidth);
            String pend = UiText.fit(host.text(SettingsModel.KEY_STAT_PENDING, host.pendingCount()), sbW - 10, host::textWidth);
            c.text(done, sbX + 5, doneY - 2 * LINE_H - 3, C_GOOD);
            c.text(pend, sbX + 5, doneY - LINE_H - 1, host.pendingCount() > 0 ? 0xFFFFD080 : 0xFF808590);
        }
        boolean hoverDone = in(mx, my, sbX + 3, doneY, sbW - 6, DONE_H);
        rrect(c, sbX + 3, doneY, sbW - 6, DONE_H, hoverDone ? C_BUTTON_HOVER : C_BUTTON);
        String label = narrow ? host.text(SettingsModel.KEY_DONE_SHORT) : host.text("gui.done");
        c.text(label, sbX + 3 + (sbW - 6 - host.textWidth(label)) / 2, doneY + 4, C_TITLE);
    }

    private void drawSearch(UiCanvas c, int mx, int my) {
        int x = cx;
        int y = cy;
        rrect(c, x, y, searchW, SEARCH_H, 0xFF0A0C12);
        border(c, x, y, searchW, SEARCH_H, searchFocused ? C_ACCENT : C_PANEL_EDGE);
        int innerW = searchW - 10;
        String text = query.toString();
        if (text.isEmpty() && !searchFocused) {
            c.text(UiText.fit(host.text(SettingsModel.KEY_SEARCH_HINT), innerW, host::textWidth), x + 5, y + 4, C_DISABLED_TEXT);
        } else {
            int start = searchViewStart(innerW);
            String visible = text.substring(start);
            c.pushClip(x + 3, y + 1, searchW - 6, SEARCH_H - 2);
            c.text(visible, x + 5, y + 4, C_TITLE);
            if (searchFocused && (host.nowMs() / 500) % 2 == 0) {
                int caretX = x + 5 + host.textWidth(text.substring(start, caret));
                c.fill(caretX, y + 3, 1, LINE_H + 1, C_TITLE);
            }
            c.popClip();
        }
        // "?" button
        int hx = x + searchW + 4;
        boolean hover = in(mx, my, hx, y, HELP_W, SEARCH_H);
        rrect(c, hx, y, HELP_W, SEARCH_H, hover ? C_BUTTON_HOVER : C_BUTTON);
        String q = host.text(SettingsCatalog.KEY_HELP_BUTTON);
        c.text(q, hx + (HELP_W - host.textWidth(q)) / 2, y + 4, C_WARN);
    }

    /** First character shown in the search box so that the caret stays inside it. */
    private int searchViewStart(int innerW) {
        String text = query.toString();
        int start = 0;
        while (start < caret && host.textWidth(text.substring(start, caret)) > innerW - 2) start++;
        return start;
    }

    private void drawHeader(UiCanvas c, Row r, int mx, int my) {
        int y = rowScreenY(r);
        int w = cardWidth(0);
        boolean hover = in(mx, my, listX, y, w, r.h) && in(mx, my, listX, listY, listW, listH);
        rrect(c, listX, y, w, r.h, hover ? C_HEADER_HOVER : C_HEADER);
        boolean open = state.expanded.contains(r.group.id());
        int ax = listX + 7;
        int ay = y + (r.h - 5) / 2;
        c.pushClip(listX, y, w, r.h);
        if (open) {
            c.fill(ax, ay + 1, 7, 1, C_TITLE);
            c.fill(ax + 1, ay + 2, 5, 1, C_TITLE);
            c.fill(ax + 2, ay + 3, 3, 1, C_TITLE);
            c.fill(ax + 3, ay + 4, 1, 1, C_TITLE);
        } else {
            c.fill(ax + 1, ay - 1, 1, 7, C_DESC);
            c.fill(ax + 2, ay, 1, 5, C_DESC);
            c.fill(ax + 3, ay + 1, 1, 3, C_DESC);
            c.fill(ax + 4, ay + 2, 1, 1, C_DESC);
        }
        String summary = groupSummary(r.group);
        int sumW = host.textWidth(summary);
        int titleMax = w - 26 - sumW - 10;
        c.text(UiText.fit(r.title, Math.max(20, titleMax), host::textWidth), listX + 20, y + 5, C_TITLE);
        c.text(summary, listX + w - sumW - 8, y + 5, C_DESC);
        c.popClip();
    }

    private String groupSummary(SettingGroup group) {
        TranslatorConfig cfg = host.config();
        StringBuilder sb = new StringBuilder();
        for (SettingCard card : group.cards()) {
            SettingEntry e = card.entry();
            if (e == null) continue;
            StateText st = e.state(cfg);
            if (st == null) continue;
            if (sb.length() > 0) sb.append(" / ");
            sb.append(resolve(st));
        }
        return sb.toString();
    }

    private void drawCard(UiCanvas c, Row r, int mx, int my) {
        SettingCard card = r.card;
        int cardX = listX + r.indent;
        int cardW = cardWidth(r.indent);
        int y = rowScreenY(r);
        boolean hover = in(mx, my, cardX, y, cardW, r.h) && in(mx, my, listX, listY, listW, listH)
                && openDropdownId == null;
        boolean notice = card.kind() == SettingCard.Kind.NOTICE;
        rrect(c, cardX, y, cardW, r.h, notice ? C_NOTICE : hover ? C_CARD_HOVER : C_CARD);
        border(c, cardX, y, cardW, r.h, notice ? C_WARN : C_CARD_EDGE);
        if (r.indent > 0) c.fill(listX + 2, y - 1, 1, r.h + CARD_GAP, C_PANEL_EDGE);

        int tx = cardX + CARD_PAD;
        int ty = y + CARD_PAD - 1;
        c.pushClip(cardX + 1, y + 1, cardW - 2, r.h - 2);
        int textMaxW = cardW - 2 * CARD_PAD;
        if (!r.stacked && r.ctrlW > 0) textMaxW -= r.ctrlW + 8;
        if (r.crumb != null) {
            c.text(UiText.fit(r.crumb, textMaxW, host::textWidth), tx, ty, C_CRUMB);
            ty += LINE_H;
        }
        drawTitle(c, r, tx, ty, textMaxW);
        ty += LINE_H;
        if (!r.desc.isEmpty()) {
            ty += 2;
            for (int li = 0; li < r.desc.size(); li++) {
                String line = r.desc.get(li);
                boolean pathLine = li == r.pathLine;
                c.text(line, tx, ty, pathLine ? C_CRUMB : C_DESC);
                if (pathLine && r.fullPath != null && in(mx, my, tx, ty - 1, textMaxW, LINE_H + 1)
                        && in(mx, my, listX, listY, listW, listH) && openDropdownId == null) {
                    tooltipText = r.fullPath;
                }
                ty += LINE_H;
            }
        }
        switch (card.kind()) {
            case TOGGLE -> drawToggle(c, r, mx, my);
            case DROPDOWN -> drawDropdownBox(c, r, mx, my);
            case SLIDER -> drawSlider(c, r, mx, my);
            case BUTTON -> drawButton(c, r, mx, my);
            case FILE -> drawFileButton(c, r, mx, my);
            case NOTICE -> drawNoticeSwitch(c, r, mx, my);
            case SURFACE -> drawSurface(c, r, mx, my);
            case ALL -> drawAll(c, r, mx, my);
            case WARMUP -> drawWarmup(c, r, ty, mx, my);
            default -> { }
        }
        c.popClip();
    }

    private void drawTitle(UiCanvas c, Row r, int x, int y, int maxW) {
        String title = UiText.fit(r.title, maxW, host::textWidth);
        if (r.card.kind() == SettingCard.Kind.NOTICE) {
            c.text(title, x, y, C_WARN);
            return;
        }
        String[] words = SettingsModel.tokens(query.toString());
        String lower = title.toLowerCase(java.util.Locale.ROOT);
        int ms = -1;
        int me = -1;
        for (String w : words) {
            int i = lower.indexOf(w);
            if (i >= 0 && (ms < 0 || i < ms)) {
                ms = i;
                me = i + w.length();
            }
        }
        if (ms < 0 || me > title.length()) {
            c.text(title, x, y, C_TITLE);
            return;
        }
        String a = title.substring(0, ms);
        String b = title.substring(ms, me);
        String d = title.substring(me);
        c.text(a, x, y, C_TITLE);
        int bx = x + host.textWidth(a);
        c.text(b, bx, y, C_MATCH);
        c.text(d, bx + host.textWidth(b), y, C_TITLE);
    }

    private void drawToggle(UiCanvas c, Row r, int mx, int my) {
        SettingEntry e = r.card.entry();
        TranslatorConfig cfg = host.config();
        boolean on = e.isOn(cfg);
        boolean enabled = host.enabled(e);
        int[] rc = controlRect(r);
        int sx = rc[0] + rc[2] - 24;
        int sy = rc[1];
        String st = toggleStateText(e, cfg);
        if (st != null) {
            c.text(st, sx - 6 - host.textWidth(st), sy + 2, enabled ? C_DESC : C_DISABLED_TEXT);
        }
        int track = !enabled ? 0xFF3A3D48 : on ? C_ON : C_OFF;
        rrect(c, sx, sy, 24, 12, track);
        int kx = on ? sx + 14 : sx + 2;
        c.fill(kx, sy + 2, 8, 8, enabled ? C_KNOB : 0xFF8A8D98);
    }

    private void drawDropdownBox(UiCanvas c, Row r, int mx, int my) {
        SettingEntry e = r.card.entry();
        int[] rc = controlRect(r);
        boolean open = r.card.id().equals(openDropdownId);
        boolean hover = in(mx, my, rc[0], rc[1], rc[2], rc[3]);
        rrect(c, rc[0], rc[1], rc[2], rc[3], open || hover ? C_BUTTON_HOVER : C_BUTTON);
        border(c, rc[0], rc[1], rc[2], rc[3], open ? C_ACCENT : 0xFF4A5170);
        SettingEntry.Options opts = e.options();
        int idx = Math.max(0, Math.min(opts.labels().size() - 1, opts.index().applyAsInt(host.config())));
        String label = UiText.fit(resolve(opts.labels().get(idx)), rc[2] - 22, host::textWidth);
        c.text(label, rc[0] + 6, rc[1] + 3, C_TITLE);
        int ax = rc[0] + rc[2] - 11;
        int ay = rc[1] + 5;
        c.fill(ax, ay, 7, 1, C_TITLE);
        c.fill(ax + 1, ay + 1, 5, 1, C_TITLE);
        c.fill(ax + 2, ay + 2, 3, 1, C_TITLE);
        c.fill(ax + 3, ay + 3, 1, 1, C_TITLE);
    }

    private int[] dropdownPopupRect(Row r) {
        int[] rc = controlRect(r);
        int n = r.card.entry().options().labels().size();
        int h = n * 14 + 2;
        int w = rc[2];
        int y = rc[1] + rc[3] + 1;
        if (y + h > screenH - 2) y = rc[1] - h - 1;
        return new int[] {rc[0], y, w, h};
    }

    private Row rowById(String id) {
        for (Row r : rows) if (r.card != null && r.card.id().equals(id)) return r;
        return null;
    }

    private void drawDropdownPopup(UiCanvas c, int mx, int my) {
        Row r = rowById(openDropdownId);
        if (r == null || !rowVisible(r)) {
            openDropdownId = null;
            return;
        }
        SettingEntry.Options opts = r.card.entry().options();
        int[] pr = dropdownPopupRect(r);
        rrect(c, pr[0], pr[1], pr[2], pr[3], 0xFF161A24);
        border(c, pr[0], pr[1], pr[2], pr[3], C_ACCENT);
        int current = opts.index().applyAsInt(host.config());
        for (int i = 0; i < opts.labels().size(); i++) {
            int oy = pr[1] + 1 + i * 14;
            boolean hover = in(mx, my, pr[0] + 1, oy, pr[2] - 2, 14);
            if (hover) c.fill(pr[0] + 1, oy, pr[2] - 2, 14, 0xFF34405E);
            String label = UiText.fit(resolve(opts.labels().get(i)), pr[2] - 16, host::textWidth);
            c.text(label, pr[0] + 8, oy + 3, i == current ? C_MATCH : C_TITLE);
        }
    }

    private int sliderIndex(SettingEntry e) {
        SettingEntry.Slider s = e.slider();
        return s.indexOf(s.get().applyAsInt(host.config()));
    }

    /** Track rectangle {x, y, w, h} of a slider card. */
    private int[] sliderTrack(Row r) {
        int[] rc = controlRect(r);
        return new int[] {rc[0], rc[1] + LINE_H + 3, rc[2], 6};
    }

    private void drawSlider(UiCanvas c, Row r, int mx, int my) {
        SettingEntry e = r.card.entry();
        SettingEntry.Slider s = e.slider();
        int[] rc = controlRect(r);
        int[] tr = sliderTrack(r);
        int n = s.steps().length;
        int idx = sliderIndex(e);
        String value = resolve(e.state(host.config()));
        c.text(value, rc[0] + rc[2] - host.textWidth(value), rc[1], C_TITLE);
        rrect(c, tr[0], tr[1] + 1, tr[2], 4, C_TRACK);
        int span = tr[2] - 8;
        int knobX = tr[0] + 1 + (n <= 1 ? 0 : idx * span / (n - 1));
        c.fill(tr[0] + 1, tr[1] + 2, Math.max(0, knobX - tr[0] - 1 + 3), 2, C_ACCENT);
        for (int i = 0; i < n; i++) {
            int tx = tr[0] + 4 + (n <= 1 ? 0 : i * span / (n - 1));
            c.fill(tx, tr[1] + 5, 1, 2, 0xFF555B70);
        }
        boolean active = r.card.id().equals(dragSliderId) || in(mx, my, tr[0] - 2, tr[1] - 3, tr[2] + 4, tr[3] + 6);
        c.fill(knobX, tr[1], 8, 6, active ? 0xFFFFFFFF : C_KNOB);
    }

    private void drawButton(UiCanvas c, Row r, int mx, int my) {
        SettingEntry e = r.card.entry();
        int[] rc = controlRect(r);
        boolean enabled = host.enabled(e);
        boolean hover = enabled && in(mx, my, rc[0], rc[1], rc[2], rc[3]);
        boolean danger = e.destructive();
        int bg = !enabled ? 0xFF2C2F3A : danger ? (hover ? C_DANGER_HOVER : C_DANGER) : (hover ? C_BUTTON_HOVER : C_BUTTON);
        rrect(c, rc[0], rc[1], rc[2], rc[3], bg);
        String label = UiText.fit(buttonText(e), rc[2] - 8, host::textWidth);
        c.text(label, rc[0] + (rc[2] - host.textWidth(label)) / 2, rc[1] + 3, enabled ? C_TITLE : C_DISABLED_TEXT);
    }

    private void drawNoticeSwitch(UiCanvas c, Row r, int mx, int my) {
        SettingEntry e = r.card.entry();
        TranslatorConfig cfg = host.config();
        boolean on = e.isOn(cfg);
        int[] rc = controlRect(r);
        rrect(c, rc[0], rc[1], 24, 12, on ? C_ON : C_OFF);
        c.fill(on ? rc[0] + 14 : rc[0] + 2, rc[1] + 2, 8, 8, C_KNOB);
        String caption = UiText.fit(noticeSwitchText(e, cfg), Math.max(20, rc[2] - 30), host::textWidth);
        c.text(caption, rc[0] + 30, rc[1] + 2, on ? C_GOOD : C_DESC);
    }

    private static final int C_AI = 0xFF2E5E9E;
    private static final int C_AI_HOVER = 0xFF3E72B8;

    private void drawSurface(UiCanvas c, Row r, int mx, int my) {
        SettingEntry mode = r.card.entry();
        SettingEntry engine = r.card.engineEntry();
        TranslatorConfig cfg = host.config();
        int[][] rects = multiRects(r);
        int[] m = rects[0];
        boolean hoverMode = in(mx, my, m[0], m[1], m[2], m[3]);
        rrect(c, m[0], m[1], m[2], m[3], hoverMode ? C_BUTTON_HOVER : C_BUTTON);
        int idx = Math.max(0, Math.min(mode.options().labels().size() - 1, mode.options().index().applyAsInt(cfg)));
        int modeColor = idx == 0 ? C_GOOD : idx == 1 ? C_CRUMB : C_DESC;
        centered(c, resolve(mode.options().labels().get(idx)), m, modeColor);
        int[] e = rects[1];
        boolean ai = engine.isOn(cfg);
        boolean hoverEngine = in(mx, my, e[0], e[1], e[2], e[3]);
        rrect(c, e[0], e[1], e[2], e[3], ai ? (hoverEngine ? C_AI_HOVER : C_AI) : (hoverEngine ? C_BUTTON_HOVER : C_BUTTON));
        centered(c, resolve(engine.state(cfg)), e, C_TITLE);
    }

    /** The 全部項目 row: grey column headers over two buttons that show the shared value or "混合". */
    private void drawAll(UiCanvas c, Row r, int mx, int my) {
        TranslatorConfig cfg = host.config();
        int[][] rects = multiRects(r);
        String[] heads = {host.text(SettingsModel.KEY_ALL_COL_MODE), host.text(SettingsModel.KEY_ALL_COL_ENGINE)};
        for (int i = 0; i < 2; i++) {
            String head = UiText.fit(heads[i], rects[i][2], host::textWidth);
            c.text(head, rects[i][0] + (rects[i][2] - host.textWidth(head)) / 2, rects[i][1] - COL_HEADER_H + 1, C_DESC);
        }
        int[] m = rects[0];
        DisplayMode common = SettingsCatalog.commonMode(cfg);
        boolean hoverMode = in(mx, my, m[0], m[1], m[2], m[3]);
        rrect(c, m[0], m[1], m[2], m[3], hoverMode ? C_BUTTON_HOVER : C_BUTTON);
        String modeLabel = common == null ? host.text(SettingsModel.KEY_ALL_MIXED) : resolve(SettingsCatalog.modeState(common));
        int modeColor = common == null ? C_WARN : common == DisplayMode.TRANSLATION ? C_GOOD
                : common == DisplayMode.BOTH ? C_CRUMB : C_DESC;
        centered(c, modeLabel, m, modeColor);
        int[] e = rects[1];
        Boolean ai = SettingsCatalog.commonEngine(cfg);
        boolean hoverEngine = in(mx, my, e[0], e[1], e[2], e[3]);
        boolean blue = ai != null && ai;
        rrect(c, e[0], e[1], e[2], e[3], blue ? (hoverEngine ? C_AI_HOVER : C_AI) : (hoverEngine ? C_BUTTON_HOVER : C_BUTTON));
        centered(c, ai == null ? host.text(SettingsModel.KEY_ALL_MIXED) : resolve(SettingsCatalog.engineState(ai)), e,
                ai == null ? C_WARN : C_TITLE);
    }

    private void centered(UiCanvas c, String text, int[] rc, int color) {
        String label = UiText.fit(text, rc[2] - 6, host::textWidth);
        c.text(label, rc[0] + (rc[2] - host.textWidth(label)) / 2, rc[1] + 3, color);
    }

    private void drawFileButton(UiCanvas c, Row r, int mx, int my) {
        int[] rc = controlRect(r);
        boolean hover = in(mx, my, rc[0], rc[1], rc[2], rc[3]);
        rrect(c, rc[0], rc[1], rc[2], rc[3], hover ? C_BUTTON_HOVER : C_BUTTON);
        String label = UiText.fit(host.text(SettingsModel.KEY_BTN_OPEN), rc[2] - 8, host::textWidth);
        c.text(label, rc[0] + (rc[2] - host.textWidth(label)) / 2, rc[1] + 3, C_TITLE);
    }

    /** Full-text hover box for a shortened path: wrapped by characters (a path has no spaces). */
    private void drawTooltip(UiCanvas c, String text, int mx, int my) {
        int maxW = Math.min(pw - 16, 320);
        List<String> lines = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            cur.append(text.charAt(i));
            if (host.textWidth(cur.toString()) > maxW - 8 && cur.length() > 1) {
                cur.setLength(cur.length() - 1);
                lines.add(cur.toString());
                cur.setLength(0);
                cur.append(text.charAt(i));
            }
        }
        if (cur.length() > 0) lines.add(cur.toString());
        int w = 8;
        for (String line : lines) w = Math.max(w, host.textWidth(line) + 8);
        int h = lines.size() * LINE_H + 6;
        int x = Math.max(px + 2, Math.min(mx + 8, px + pw - w - 2));
        int y = my + 14 + h > py + ph ? my - h - 4 : my + 14;
        rrect(c, x, y, w, h, 0xF0161A24);
        border(c, x, y, w, h, C_ACCENT);
        for (int i = 0; i < lines.size(); i++) c.text(lines.get(i), x + 4, y + 3 + i * LINE_H, C_TITLE);
    }

    // ---- warm-up card

    private record WarmButton(String label, WarmupCommand command, boolean enabled, boolean danger) {}

    private List<WarmButton> warmButtons(WarmupStatus st) {
        List<WarmButton> out = new ArrayList<>();
        if (st.active()) {
            if (st.userPaused()) {
                out.add(new WarmButton(host.text("screen.nyanlex.warmup.resume"), WarmupCommand.RESUME, true, false));
            } else {
                out.add(new WarmButton(host.text("screen.nyanlex.warmup.pause"), WarmupCommand.PAUSE,
                        st.state() == ItemWarmupDriver.State.RUNNING, false));
            }
            out.add(new WarmButton(host.text("screen.nyanlex.warmup.stop"), WarmupCommand.STOP, true, true));
            out.add(new WarmButton(host.text(SettingsModel.KEY_WARMUP_DETAILS), WarmupCommand.OPEN_PROGRESS, true, false));
        } else {
            out.add(new WarmButton(host.text(SettingsModel.KEY_WARMUP_START), WarmupCommand.START, st.available(), false));
        }
        return out;
    }

    private int[][] warmButtonRects(Row r, int rowY, List<WarmButton> buttons) {
        int cardX = listX + r.indent;
        int x = cardX + CARD_PAD;
        int[][] rects = new int[buttons.size()][];
        for (int i = 0; i < buttons.size(); i++) {
            int w = Math.max(40, host.textWidth(buttons.get(i).label()) + 14);
            rects[i] = new int[] {x, rowY, w, 14};
            x += w + 4;
        }
        return rects;
    }

    /** Screen y of the warm-up card's button row. */
    private int warmButtonY(Row r) {
        return rowScreenY(r) + r.h - CARD_PAD - 14;
    }

    private String warmStatusText(WarmupStatus st) {
        if (!st.available() && !st.active()) return host.text(SettingsCatalog.KEY_NEEDS_AI);
        switch (st.state()) {
            case RUNNING:
                return host.text("screen.nyanlex.warmup.state.running");
            case PAUSED:
                return host.text("screen.nyanlex.warmup.reason."
                        + st.reason().name().toLowerCase(java.util.Locale.ROOT));
            case DONE:
                return host.text("screen.nyanlex.warmup.state.done");
            case STOPPED:
                return host.text("screen.nyanlex.warmup.state.stopped");
            default:
                return host.text(SettingsModel.KEY_WARMUP_IDLE);
        }
    }

    private void drawWarmup(UiCanvas c, Row r, int textBottomY, int mx, int my) {
        WarmupStatus st = host.warmupStatus();
        int cardX = listX + r.indent;
        int cardW = cardWidth(r.indent);
        int inner = cardW - 2 * CARD_PAD;
        int y = textBottomY + 4;
        String status = UiText.fit(warmStatusText(st), inner - 70, host::textWidth);
        int color = st.state() == ItemWarmupDriver.State.DONE ? C_GOOD
                : st.state() == ItemWarmupDriver.State.PAUSED ? C_WARN
                : st.state() == ItemWarmupDriver.State.RUNNING ? C_ACCENT
                : (!st.available() ? C_DISABLED_TEXT : C_DESC);
        c.text(status, cardX + CARD_PAD, y, color);
        if (st.total() > 0 && (st.active() || st.state() == ItemWarmupDriver.State.DONE)) {
            String counts = st.scanned() + " / " + st.total() + " (" + st.percent() + "%)";
            c.text(counts, cardX + cardW - CARD_PAD - host.textWidth(counts), y, C_TITLE);
        }
        y += LINE_H + 3;
        rrect(c, cardX + CARD_PAD, y, inner, 6, C_TRACK);
        int fill = (int) (inner * st.fraction());
        if (fill > 0) c.fill(cardX + CARD_PAD + 1, y + 1, Math.max(0, Math.min(inner - 2, fill - 1)), 4,
                st.state() == ItemWarmupDriver.State.PAUSED ? C_WARN : C_ACCENT);
        List<WarmButton> buttons = warmButtons(st);
        int[][] rects = warmButtonRects(r, warmButtonY(r), buttons);
        for (int i = 0; i < buttons.size(); i++) {
            WarmButton b = buttons.get(i);
            int[] rc = rects[i];
            boolean hover = b.enabled() && in(mx, my, rc[0], rc[1], rc[2], rc[3]);
            int bg = !b.enabled() ? 0xFF2C2F3A : b.danger() ? (hover ? C_DANGER_HOVER : C_DANGER)
                    : (hover ? C_BUTTON_HOVER : C_BUTTON);
            rrect(c, rc[0], rc[1], rc[2], rc[3], bg);
            c.text(b.label(), rc[0] + (rc[2] - host.textWidth(b.label())) / 2, rc[1] + 3,
                    b.enabled() ? C_TITLE : C_DISABLED_TEXT);
        }
    }

    // ------------------------------------------------------------------ drawing helpers

    static void rrect(UiCanvas c, int x, int y, int w, int h, int argb) {
        if (w < 3 || h < 3) {
            c.fill(x, y, w, h, argb);
            return;
        }
        c.fill(x + 1, y, w - 2, h, argb);
        c.fill(x, y + 1, w, h - 2, argb);
    }

    static void border(UiCanvas c, int x, int y, int w, int h, int argb) {
        c.fill(x + 1, y, w - 2, 1, argb);
        c.fill(x + 1, y + h - 1, w - 2, 1, argb);
        c.fill(x, y + 1, 1, h - 2, argb);
        c.fill(x + w - 1, y + 1, 1, h - 2, argb);
    }

    // ------------------------------------------------------------------ input: mouse

    /** @return true when the click was consumed by the panel. */
    public boolean mouseClicked(int mx, int my, int button) {
        ensureLayout();
        if (button != 0) return in(mx, my, px, py, pw, ph);
        if (openDropdownId != null) {
            Row r = rowById(openDropdownId);
            if (r != null && rowVisible(r)) {
                int[] pr = dropdownPopupRect(r);
                if (in(mx, my, pr[0], pr[1], pr[2], pr[3])) {
                    int i = (my - pr[1] - 1) / 14;
                    SettingEntry.Options opts = r.card.entry().options();
                    if (i >= 0 && i < opts.labels().size()) {
                        opts.select().accept(host.config(), i);
                        changed();
                    }
                }
            }
            openDropdownId = null;
            return true;
        }
        if (!in(mx, my, px, py, pw, ph)) {
            searchFocused = false;
            return false;
        }
        // search box
        if (in(mx, my, cx, cy, searchW, SEARCH_H)) {
            searchFocused = true;
            caret = caretFromX(mx);
            return true;
        }
        searchFocused = false;
        if (in(mx, my, cx + searchW + 4, cy, HELP_W, SEARCH_H)) {
            host.playClick();
            selectCategory(SettingsCategory.ABOUT); // returning from the manual lands on 關於
            host.runAction(SettingAction.OPEN_MANUAL);
            return true;
        }
        // sidebar
        if (in(mx, my, sbX, sbY, sbW, sbH)) {
            int doneY = sbY + sbH - DONE_H - 3;
            if (in(mx, my, sbX + 3, doneY, sbW - 6, DONE_H)) {
                host.playClick();
                host.close();
                return true;
            }
            int y0 = sbY + 3 + (narrow ? 0 : LINE_H + 5 + 1 + 4);
            int i = (my - y0) / CAT_ROW_H;
            List<SettingsCategory> cats = SettingsModel.categories();
            if (my >= y0 && i >= 0 && i < cats.size()) {
                host.playClick();
                selectCategory(cats.get(i));
            }
            return true;
        }
        // scrollbar
        if (needsScrollbar() && mx >= listX + listW - SCROLLBAR_W - 2 && in(mx, my, listX, listY, listW, listH)) {
            dragScrollbar = true;
            int ty = thumbY();
            dragScrollOffset = my >= ty && my < ty + thumbH() ? my - ty : thumbH() / 2;
            dragScrollTo(my);
            return true;
        }
        if (!in(mx, my, listX, listY, listW, listH)) return true;
        for (Row r : rows) {
            if (!rowVisible(r)) continue;
            int y = rowScreenY(r);
            int w = cardWidth(r.indent);
            if (!in(mx, my, listX + r.indent, y, w, r.h)) continue;
            host.playClick();
            if (r.header()) {
                String id = r.group.id();
                if (!state.expanded.remove(id)) state.expanded.add(id);
                dirty = true;
            } else {
                clickCard(r, mx, my);
            }
            return true;
        }
        return true;
    }

    private void selectCategory(SettingsCategory cat) {
        query.setLength(0);
        caret = 0;
        state.category = cat;
        openDropdownId = null;
        dirty = true;
    }

    private void clickCard(Row r, int mx, int my) {
        SettingCard card = r.card;
        SettingEntry e = card.entry();
        int[] rc = card.kind() == SettingCard.Kind.WARMUP || card.kind() == SettingCard.Kind.INFO ? null : controlRect(r);
        switch (card.kind()) {
            case TOGGLE -> {
                if (!host.enabled(e)) return;
                if (!host.beforeToggle(e)) return;
                e.press(host.config());
                host.sideEffect(e.sideEffect());
                changed();
            }
            case NOTICE -> {
                // only the switch reacts; the text is just text
                if (in(mx, my, rc[0], rc[1] - 1, rc[2], rc[3] + 2) && host.beforeToggle(e)) {
                    e.press(host.config());
                    host.sideEffect(e.sideEffect());
                    changed();
                }
            }
            case DROPDOWN -> {
                if (in(mx, my, rc[0] - 2, rc[1] - 2, rc[2] + 4, rc[3] + 4)) {
                    openDropdownId = card.id();
                }
            }
            case SLIDER -> {
                int[] tr = sliderTrack(r);
                if (in(mx, my, tr[0] - 3, tr[1] - 4, tr[2] + 6, tr[3] + 8)) {
                    dragSliderId = card.id();
                    setSliderFromX(r, mx);
                }
            }
            case BUTTON -> {
                if (host.enabled(e) && in(mx, my, rc[0], rc[1], rc[2], rc[3])) {
                    host.runAction(e.action());
                }
            }
            case FILE -> {
                if (r.file != null && in(mx, my, rc[0], rc[1], rc[2], rc[3])) host.openFileLocation(r.file);
            }
            case SURFACE -> {
                int[][] rects = multiRects(r);
                if (in(mx, my, rects[0][0], rects[0][1], rects[0][2], rects[0][3])) {
                    card.entry().press(host.config());
                    changed();
                } else if (in(mx, my, rects[1][0], rects[1][1], rects[1][2], rects[1][3])) {
                    card.engineEntry().press(host.config());
                    changed();
                }
            }
            case ALL -> {
                int[][] rects = multiRects(r);
                if (in(mx, my, rects[0][0], rects[0][1], rects[0][2], rects[0][3])) {
                    SettingsCatalog.cycleAllModes(host.config());
                    changed();
                } else if (in(mx, my, rects[1][0], rects[1][1], rects[1][2], rects[1][3])) {
                    SettingsCatalog.toggleAllEngines(host.config());
                    changed();
                }
            }
            case WARMUP -> {
                WarmupStatus st = host.warmupStatus();
                List<WarmButton> buttons = warmButtons(st);
                int[][] rects = warmButtonRects(r, warmButtonY(r), buttons);
                for (int i = 0; i < buttons.size(); i++) {
                    int[] b = rects[i];
                    if (buttons.get(i).enabled() && in(mx, my, b[0], b[1], b[2], b[3])) {
                        host.warmupCommand(buttons.get(i).command());
                        return;
                    }
                }
            }
            default -> { }
        }
    }

    private void setSliderFromX(Row r, int mx) {
        SettingEntry.Slider s = r.card.entry().slider();
        int[] tr = sliderTrack(r);
        int n = s.steps().length;
        int span = tr[2] - 8;
        int idx = n <= 1 ? 0 : Math.round((mx - tr[0] - 4) * (n - 1) / (float) span);
        idx = Math.max(0, Math.min(n - 1, idx));
        if (idx != sliderIndex(r.card.entry())) {
            s.set().accept(host.config(), s.steps()[idx]);
            host.saveConfig();
        }
    }

    private void changed() {
        host.saveConfig();
        dirty = true;
    }

    public boolean mouseDragged(int mx, int my) {
        mouseX = mx;
        mouseY = my;
        if (dragScrollbar) {
            dragScrollTo(my);
            return true;
        }
        if (dragSliderId != null) {
            Row r = rowById(dragSliderId);
            if (r != null) setSliderFromX(r, mx);
            return true;
        }
        return false;
    }

    private void dragScrollTo(int my) {
        int travel = listH - thumbH();
        if (travel <= 0) return;
        int target = my - dragScrollOffset - listY;
        state.scroll.put(state.category, Math.round(maxScroll() * (target / (float) travel)));
        clampScroll();
    }

    public boolean mouseReleased(int mx, int my, int button) {
        boolean was = dragScrollbar || dragSliderId != null;
        dragScrollbar = false;
        if (dragSliderId != null) {
            dragSliderId = null;
            host.saveConfig();
            dirty = true;
        }
        return was;
    }

    public boolean mouseScrolled(int mx, int my, double scrollY) {
        ensureLayout();
        if (scrollY == 0 || !in(mx, my, px, py, pw, ph)) return false;
        scrollBy(scrollY > 0 ? -SCROLL_STEP : SCROLL_STEP);
        return true;
    }

    private int caretFromX(int mx) {
        String text = query.toString();
        int start = searchViewStart(searchW - 10);
        int x = cx + 5;
        for (int i = start; i < text.length(); i++) {
            int w = host.textWidth(text.substring(i, i + 1));
            if (mx < x + w / 2) return i;
            x += w;
        }
        return text.length();
    }

    // ------------------------------------------------------------------ input: keyboard

    /** @return true when the character went into the search box. */
    public boolean charTyped(char ch) {
        if (ignoreSlash && ch == '/') {
            ignoreSlash = false;
            return true;
        }
        ignoreSlash = false;
        if (!searchFocused || ch < 32 || ch == 127) return false;
        if (query.length() >= MAX_QUERY) return true;
        query.insert(caret, ch);
        caret++;
        queryChanged();
        return true;
    }

    /** @return true when the key was consumed. */
    public boolean keyPressed(int key, boolean ctrl, boolean shift) {
        ensureLayout();
        if (searchFocused) {
            switch (key) {
                case KEY_ESCAPE -> {
                    if (query.length() > 0) {
                        query.setLength(0);
                        caret = 0;
                        queryChanged();
                    } else {
                        searchFocused = false;
                    }
                    return true;
                }
                case KEY_ENTER -> {
                    searchFocused = false;
                    return true;
                }
                case KEY_BACKSPACE -> {
                    if (caret > 0) {
                        query.deleteCharAt(caret - 1);
                        caret--;
                        queryChanged();
                    }
                    return true;
                }
                case KEY_DELETE -> {
                    if (caret < query.length()) {
                        query.deleteCharAt(caret);
                        queryChanged();
                    }
                    return true;
                }
                case KEY_LEFT -> {
                    caret = Math.max(0, caret - 1);
                    return true;
                }
                case KEY_RIGHT -> {
                    caret = Math.min(query.length(), caret + 1);
                    return true;
                }
                case KEY_HOME -> {
                    caret = 0;
                    return true;
                }
                case KEY_END -> {
                    caret = query.length();
                    return true;
                }
                case KEY_UP, KEY_DOWN, KEY_PAGE_UP, KEY_PAGE_DOWN -> {
                    return scrollKey(key);
                }
                default -> { }
            }
            if (ctrl && key == KEY_A) {
                caret = query.length();
                return true;
            }
            if (ctrl && key == KEY_V) {
                String clip = host.clipboard();
                if (clip != null) {
                    for (char ch : clip.toCharArray()) {
                        if (ch >= 32 && ch != 127 && query.length() < MAX_QUERY) {
                            query.insert(caret++, ch);
                        }
                    }
                    queryChanged();
                }
                return true;
            }
            return true; // swallow everything else while typing
        }
        if (key == KEY_ESCAPE) return escape();
        if ((ctrl && key == KEY_F) || key == KEY_SLASH) {
            ignoreSlash = key == KEY_SLASH; // the matching char event must not land in the box
            searchFocused = true;
            caret = query.length();
            return true;
        }
        return scrollKey(key);
    }

    private boolean scrollKey(int key) {
        switch (key) {
            case KEY_UP -> scrollBy(-SCROLL_STEP);
            case KEY_DOWN -> scrollBy(SCROLL_STEP);
            case KEY_PAGE_UP -> scrollBy(-(listH - 12));
            case KEY_PAGE_DOWN -> scrollBy(listH - 12);
            case KEY_HOME -> scrollBy(-scroll());
            case KEY_END -> scrollBy(maxScroll());
            default -> {
                return false;
            }
        }
        return true;
    }

    /**
     * Escape: closes an open drop-down, then clears the search, then unfocuses it.
     * @return false when nothing was left to dismiss (the screen should close).
     */
    public boolean escape() {
        if (openDropdownId != null) {
            openDropdownId = null;
            return true;
        }
        if (query.length() > 0) {
            query.setLength(0);
            caret = 0;
            queryChanged();
            return true;
        }
        if (searchFocused) {
            searchFocused = false;
            return true;
        }
        return false;
    }

    private void queryChanged() {
        state.scroll.put(state.category, 0);
        openDropdownId = null;
        dirty = true;
    }

    /** Sets the search text directly (used by tests and by deep links). */
    public void setQuery(String text) {
        query.setLength(0);
        query.append(text == null ? "" : text);
        caret = query.length();
        queryChanged();
    }

    public void focusSearch() {
        searchFocused = true;
        caret = query.length();
    }

    /** Re-reads titles and states (language change, config change from outside). */
    public void invalidate() { dirty = true; }

    public void setCategory(SettingsCategory category) { selectCategory(category); }

    public void toggleGroup(String id) {
        if (!state.expanded.remove(id)) state.expanded.add(id);
        dirty = true;
    }

    // ------------------------------------------------------------------ test hooks

    /** Screen rectangles of every visible row {x, y, w, h}, for layout checks. */
    List<int[]> rowRects() {
        ensureLayout();
        List<int[]> out = new ArrayList<>();
        for (Row r : rows) out.add(new int[] {listX + r.indent, rowScreenY(r), cardWidth(r.indent), r.h});
        return out;
    }

    int[] listRect() { return new int[] {listX, listY, listW, listH}; }

    int[] panelRect() { return new int[] {px, py, pw, ph}; }

    int[] sidebarRect() { return new int[] {sbX, sbY, sbW, sbH}; }

    int[] searchRect() { return new int[] {cx, cy, searchW, SEARCH_H}; }

    int contentHeight() { ensureLayout(); return contentH; }

    /** Control rectangle {x, y, w, h} of a visible-or-not card, or null when the card is not listed. */
    int[] controlBounds(String cardId) {
        ensureLayout();
        Row r = rowById(cardId);
        return r == null ? null : controlRect(r);
    }

    /** Rectangle of the i-th button of a SURFACE / ALL card, or null. */
    int[] buttonBounds(String cardId, int index) {
        ensureLayout();
        Row r = rowById(cardId);
        if (r == null) return null;
        int[][] rects = multiRects(r);
        return index < rects.length ? rects[index] : null;
    }

    /** Rectangle of the i-th warm-up button, or null. */
    int[] warmButtonBounds(int index) {
        ensureLayout();
        Row r = rowById("warmup");
        if (r == null) return null;
        List<WarmButton> buttons = warmButtons(host.warmupStatus());
        if (index >= buttons.size()) return null;
        return warmButtonRects(r, warmButtonY(r), buttons)[index];
    }

    /** Rectangle {x, y, w, h} of a card row on screen, or null. */
    int[] cardBounds(String cardId) {
        ensureLayout();
        Row r = rowById(cardId);
        return r == null ? null : new int[] {listX + r.indent, rowScreenY(r), cardWidth(r.indent), r.h};
    }

    /** Rectangle of the i-th group header row, or null. */
    int[] headerBounds(String groupId) {
        ensureLayout();
        for (Row r : rows) {
            if (r.header() && r.group.id().equals(groupId)) return new int[] {listX, rowScreenY(r), cardWidth(0), r.h};
        }
        return null;
    }

    int rowCount() { ensureLayout(); return rows.size(); }

    /** Rectangle of the sidebar category row {x, y, w, h}. */
    int[] categoryBounds(int index) {
        int y0 = sbY + 3 + (narrow ? 0 : LINE_H + 5 + 1 + 4);
        return new int[] {sbX, y0 + index * CAT_ROW_H, sbW, CAT_ROW_H};
    }

    int[] doneBounds() { return new int[] {sbX + 3, sbY + sbH - DONE_H - 3, sbW - 6, DONE_H}; }
}
