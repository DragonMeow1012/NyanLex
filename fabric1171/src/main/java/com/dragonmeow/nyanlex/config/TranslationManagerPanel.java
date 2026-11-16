package com.dragonmeow.nyanlex.config;

import com.dragonmeow.nyanlex.service.TranslationManagerModel;
import com.dragonmeow.nyanlex.service.TranslationManagerModel.ColorVersion;
import com.dragonmeow.nyanlex.service.TranslationManagerModel.EditData;
import com.dragonmeow.nyanlex.service.TranslationManagerModel.Page;
import com.dragonmeow.nyanlex.service.TranslationManagerModel.Row;
import com.dragonmeow.nyanlex.service.TranslationManagerModel.SaveOutcome;
import com.dragonmeow.nyanlex.service.TranslationManagerModel.Source;
import com.dragonmeow.nyanlex.translate.ManualTextCodec;
import com.dragonmeow.nyanlex.translate.ManualTextCodec.Diff;
import com.dragonmeow.nyanlex.translate.ManualTextCodec.Span;
import com.dragonmeow.nyanlex.translate.ManualTextCodec.Tag;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.ToIntFunction;

/**
 * The translation manager screen (譯文管理), drawn without any Minecraft type: a list of the saved
 * translations with a search box, and an editor for one of them. The list is read from a
 * {@link TranslationManagerModel}, which scans in the background; this panel only ever reads the latest
 * published page, so nothing here walks a cache on the render thread.
 *
 * <p><b>List mode</b>: search box (focused when the screen opens), the filter "all / manual only", the
 * rows (original on the first line, translation on the second, a tag for where it comes from), and 返回.
 * Up/Down/PageUp/PageDown move, Enter or a click opens a row, Escape clears the search and then leaves.</p>
 *
 * <p><b>Edit mode</b>: the original, a multi-line box for the translation, the markers of the original
 * as buttons that insert them at the cursor, one line for the problem or the result, and the buttons
 * 儲存 / 還原為自動翻譯 / 刪除並重新翻譯 (asks twice) / 返回清單. Ctrl+Enter saves; Escape goes back, and
 * asks once more when there are unsaved changes. A sentence that has colour versions is edited with its
 * colour tags {@code {C0}...{/C0}}: the text between a pair is shaded the same in the original and in the
 * translation so the player can match them up (the shades are not the real colours); with several versions
 * a switch picks the version being edited.</p>
 *
 * <p>The text boxes are drawn and edited here (cursor, Backspace, Delete, arrows, Home, End, Ctrl+A,
 * Ctrl+C, Ctrl+V, click to place the cursor), so no version of the game's own text widget is needed.
 * Nothing is clipped: only whole rows and whole lines are drawn. The glue forwards input and
 * {@link #resize}, calls {@link #render} every frame, closes the screen when an input call returns
 * {@link #BACK}, and treats {@link #isTyping()} as "do not run the game's own letter shortcuts". After a
 * change of a manual translation the panel calls {@link Host#onChanged()}.</p>
 */
public final class TranslationManagerPanel {

    /** What the surrounding game provides. */
    public interface Host {
        /** Localized text for {@code key} ({@code %s} filled from {@code args}); the key itself if unknown. */
        String text(String key, Object... args);

        /** Width in GUI pixels of {@code text} in the font the canvas draws with. */
        int textWidth(String text);

        default String clipboard() { return ""; }

        /** Puts text on the clipboard (Ctrl+C). */
        default void copy(String text) { }

        default long nowMs() { return System.currentTimeMillis(); }

        default void playClick() { }

        /** A manual translation was saved, reverted or deleted: forget what the game has already rendered. */
        default void onChanged() { }
    }

    public static final int NONE = 0;
    public static final int BACK = 1;

    // ------------------------------------------------------------------ lang keys

    public static final String KEY_TITLE = "nyanlex.manager.title";
    public static final String KEY_SEARCH = "nyanlex.manager.search";
    public static final String KEY_FILTER_ALL = "nyanlex.manager.filter.all";
    public static final String KEY_FILTER_MANUAL = "nyanlex.manager.filter.manual";
    public static final String KEY_TAG_MANUAL = "nyanlex.manager.tag.manual";
    public static final String KEY_STATS = "nyanlex.manager.stats";
    public static final String KEY_CAPPED = "nyanlex.manager.capped";
    public static final String KEY_EMPTY = "nyanlex.manager.empty";
    public static final String KEY_NONE = "nyanlex.manager.none";
    public static final String KEY_NONE_MANUAL = "nyanlex.manager.none_manual";
    public static final String KEY_LOADING = "nyanlex.manager.loading";
    public static final String KEY_EDIT_TITLE = "nyanlex.manager.edit.title";
    public static final String KEY_EDIT_ORIGINAL = "nyanlex.manager.edit.original";
    public static final String KEY_EDIT_TRANSLATION = "nyanlex.manager.edit.translation";
    public static final String KEY_EDIT_AUTO = "nyanlex.manager.edit.auto";
    public static final String KEY_EDIT_INSERT = "nyanlex.manager.edit.insert";
    public static final String KEY_EDIT_COLOR = "nyanlex.manager.edit.color";
    public static final String KEY_EDIT_COLOR_HINT = "nyanlex.manager.edit.color.hint";
    public static final String KEY_HINT_PRIORITY = "nyanlex.manager.hint.priority";
    public static final String KEY_BTN_SAVE = "nyanlex.manager.btn.save";
    public static final String KEY_BTN_DELETE = "nyanlex.manager.btn.delete";
    public static final String KEY_BTN_DELETE_CONFIRM = "nyanlex.manager.btn.delete.confirm";
    public static final String KEY_BTN_REVERT = "nyanlex.manager.btn.revert";
    public static final String KEY_BTN_BACK = "nyanlex.manager.btn.back";
    public static final String KEY_ERR_EMPTY = "nyanlex.manager.err.empty";
    public static final String KEY_ERR_SAME = "nyanlex.manager.err.same";
    public static final String KEY_ERR_MISSING = "nyanlex.manager.err.missing";
    public static final String KEY_ERR_EXTRA = "nyanlex.manager.err.extra";
    public static final String KEY_ERR_ORDER = "nyanlex.manager.err.order";
    public static final String KEY_ERR_NEWLINES = "nyanlex.manager.err.newlines";
    public static final String KEY_ERR_PARTIAL = "nyanlex.manager.err.partial";
    public static final String KEY_ERR_CONTROL = "nyanlex.manager.err.control";
    public static final String KEY_ERR_LONG = "nyanlex.manager.err.long";
    public static final String KEY_ERR_FULL = "nyanlex.manager.err.full";
    public static final String KEY_ERR_IO = "nyanlex.manager.err.io";
    public static final String KEY_ERR_COLOR_PAIRS = "nyanlex.manager.err.color_pairs";
    public static final String KEY_DISCARD = "nyanlex.manager.discard.changes";
    public static final String KEY_STATUS_SAVED = "nyanlex.manager.status.saved";
    public static final String KEY_STATUS_REVERTED = "nyanlex.manager.status.reverted";
    public static final String KEY_STATUS_DELETED = "nyanlex.manager.status.deleted";
    public static final String KEY_STATUS_DELETED_OFF = "nyanlex.manager.status.deleted_off";
    public static final String KEY_NARRATE_ROW = "nyanlex.narrate.manager.row";
    public static final String KEY_NARRATE_EDIT = "nyanlex.narrate.manager.edit";
    public static final String KEY_NARRATE_SEARCH = "nyanlex.narrate.manager.search";

    /** Every lang key the panel asks for (for the lang-file test); the card on 進階 and the file entry are listed elsewhere. */
    public static List<String> allLangKeys() {
        return List.of(KEY_TITLE, KEY_SEARCH, KEY_FILTER_ALL, KEY_FILTER_MANUAL, KEY_TAG_MANUAL, KEY_STATS,
                KEY_CAPPED, KEY_EMPTY, KEY_NONE, KEY_NONE_MANUAL, KEY_LOADING,
                KEY_EDIT_TITLE, KEY_EDIT_ORIGINAL, KEY_EDIT_TRANSLATION, KEY_EDIT_AUTO, KEY_EDIT_INSERT,
                KEY_EDIT_COLOR, KEY_EDIT_COLOR_HINT, KEY_HINT_PRIORITY,
                KEY_BTN_SAVE, KEY_BTN_DELETE, KEY_BTN_DELETE_CONFIRM, KEY_BTN_REVERT, KEY_BTN_BACK,
                KEY_ERR_EMPTY, KEY_ERR_SAME, KEY_ERR_MISSING, KEY_ERR_EXTRA, KEY_ERR_ORDER, KEY_ERR_NEWLINES,
                KEY_ERR_PARTIAL, KEY_ERR_CONTROL, KEY_ERR_LONG, KEY_ERR_FULL, KEY_ERR_IO, KEY_ERR_COLOR_PAIRS,
                KEY_DISCARD, KEY_STATUS_SAVED, KEY_STATUS_REVERTED, KEY_STATUS_DELETED, KEY_STATUS_DELETED_OFF,
                KEY_NARRATE_ROW, KEY_NARRATE_EDIT, KEY_NARRATE_SEARCH);
    }

    // ------------------------------------------------------------------ constants

    private static final int OUTER = 6;
    private static final int PAD = 8;
    private static final int LINE = 10;
    private static final int TITLE_H = 20;
    private static final int BTN_H = 16;
    private static final int EDIT_BTN_H = 14;
    private static final int BOX_H = 14;
    private static final int ROW_H = 24;
    private static final int BAR_W = 4;
    private static final int MAX_W = 456;
    private static final int MAX_H = 330;
    private static final int BOX_PAD = 3;
    private static final int GAP = 4;
    private static final int CHIP_H = 13;

    private static final int MAX_QUERY = 60;
    private static final int SEARCH_DEBOUNCE_MS = 120;
    private static final int DELETE_CONFIRM_MS = 3000;
    private static final int STATUS_MS = 6000;
    private static final int ROW_TEXT_CAP = 160;

    private static final int KEY_A = 65;
    private static final int KEY_C = 67;
    private static final int KEY_V = 86;
    private static final int KEY_X = 88;
    private static final int KEY_KP_ENTER = 335;

    private static final int C_ERR = 0xFFFF8080;
    private static final int C_BOX = 0xFF0E1018;
    private static final int C_BOX_RO = 0xB00A0C12;
    private static final int C_SELECT = 0x704C9AFF;
    private static final int C_TAG_MANUAL = 0xFF2F6B44;

    /** One colour per colour-tag index; the text between a pair of tags is shaded with it (only for matching up). */
    private static final int[] RUN_RGB = {0x3D7BFF, 0xE0B040, 0x3DBB6C, 0xD0508C, 0x5CD0D8, 0x9E6BE0};

    // focus ring ids
    private static final int F_SEARCH = 1;
    private static final int F_EDIT = 2;
    private static final int B_BACK = 10;
    private static final int B_ALL = 11;
    private static final int B_MANUAL = 12;
    private static final int B_CLEAR = 13;
    private static final int B_SAVE = 20;
    private static final int B_REVERT = 21;
    private static final int B_DELETE = 22;
    private static final int B_EDIT_BACK = 23;
    private static final int B_VPREV = 24;
    private static final int B_VNEXT = 25;

    // ------------------------------------------------------------------ state

    private final Host host;
    private final TranslationManagerModel model;
    private final ToIntFunction<String> width;

    private int screenW;
    private int screenH;
    private int px;
    private int py;
    private int pw;
    private int ph;
    private int bodyX;
    private int bodyW;

    private boolean editing;
    private int focus = F_SEARCH;
    private boolean narrationRequested;
    private boolean narrateRow;
    private int lastMx = -1;
    private int lastMy = -1;
    private final Map<Integer, int[]> rects = new LinkedHashMap<>();

    // labels fetched once
    private final String title;
    private final String searchHint;
    private final String filterAll;
    private final String filterManual;
    private final String tagManual;
    private final String tagAi;
    private final String tagMachine;
    private final String backLabel;
    private final String listBackLabel;
    private final String saveLabel;
    private final String revertLabel;
    private final String deleteLabel;
    private final String deleteConfirmLabel;
    private final String editTitleLabel;
    private final String originalLabel;
    private final String translationLabel;

    // list mode
    private final Field search;
    private boolean manualOnly;
    private boolean searchPending;
    private long searchEditedAt;
    private Page shown;
    private List<RowView> rows = List.of();
    private int scrollRow;
    private int selected = -1;
    private int listTop;
    private int listH;
    private int visibleRows = 1;
    private boolean dragBar;
    private String statsText = "";
    private String footerText = "";
    private String status;
    private long statusUntil;
    private int statsY;
    private int footerY;
    private int footerW;

    // edit mode
    private final Field original;
    private final Field edit;
    private EditData data;
    private List<ColorVersion> versions = List.of();
    private int version;
    private String[] drafts = new String[] {""};
    private String[] baselines = new String[] {""};
    private Source editSource = Source.AI;
    private String autoText = "";
    private String colorLabel = "";
    private List<String> footerLines = List.of();
    private boolean discardArmed;
    private long deleteArmedAt = -1;
    private String message;
    private int messageColor;
    private String liveProblem;
    private int headerY;
    private int origLabelY;
    private int transLabelY;
    private int autoY;
    private int messageY;
    private int chipsY;
    private int hintY;
    private final List<String> hintLines = new ArrayList<>();
    private final List<Chip> chips = new ArrayList<>();
    private String chipLabel = "";
    private int chipLabelRow;
    private boolean showAuto;

    private record RowView(Row row, String original, String translation, String fitOriginal, String fitTranslation) {}

    private record Chip(Tag tag, String text, int[] rect) {}

    public TranslationManagerPanel(Host host, TranslationManagerModel model) {
        this.host = host;
        this.model = model;
        this.width = host::textWidth;
        this.title = host.text(KEY_TITLE);
        this.searchHint = host.text(KEY_SEARCH);
        this.filterAll = host.text(KEY_FILTER_ALL);
        this.filterManual = host.text(KEY_FILTER_MANUAL);
        this.tagManual = host.text(KEY_TAG_MANUAL);
        this.tagAi = host.text(SettingsCatalog.STATE_AI);
        this.tagMachine = host.text(SettingsCatalog.STATE_MACHINE);
        this.backLabel = host.text(KEY_BTN_BACK);
        this.listBackLabel = host.text(SettingsModel.KEY_MANUAL_BACK);
        this.saveLabel = host.text(KEY_BTN_SAVE);
        this.revertLabel = host.text(KEY_BTN_REVERT);
        this.deleteLabel = host.text(KEY_BTN_DELETE);
        this.deleteConfirmLabel = host.text(KEY_BTN_DELETE_CONFIRM);
        this.editTitleLabel = host.text(KEY_EDIT_TITLE);
        this.originalLabel = host.text(KEY_EDIT_ORIGINAL);
        this.translationLabel = host.text(KEY_EDIT_TRANSLATION);
        this.search = new Field(false, MAX_QUERY, false);
        this.original = new Field(true, 4000, true);
        this.original.readOnly = true;
        this.edit = new Field(true, TranslationManagerModel.MAX_TRANSLATION_LENGTH, true);
        model.searchAsync("", false);
        model.refreshAsync();
        shown = model.page();
        adopt(shown, true);
    }

    // ------------------------------------------------------------------ layout

    public void resize(int w, int h) {
        screenW = w;
        screenH = h;
        pw = Math.min(w - 2 * OUTER, MAX_W);
        ph = Math.min(h - 2 * OUTER, MAX_H);
        px = (w - pw) / 2;
        py = (h - ph) / 2;
        bodyX = px + PAD;
        bodyW = pw - 2 * PAD;
        layout();
    }

    private void layout() {
        if (pw <= 0) return;
        rects.clear();
        if (editing) layoutEdit();
        else layoutList();
    }

    private void layoutList() {
        int y = py + TITLE_H + 5;
        statsY = y;
        y += LINE + 3;
        int wAll = Math.max(30, width.applyAsInt(filterAll) + 10);
        int wMan = Math.max(30, width.applyAsInt(filterManual) + 10);
        int filtersW = wAll + 2 + wMan;
        boolean sameRow = bodyW - filtersW - 6 >= 84;
        search.x = bodyX;
        search.y = y;
        search.h = BOX_H;
        search.w = sameRow ? bodyW - filtersW - 6 : bodyW;
        int fy = sameRow ? y : y + BOX_H + 3;
        int fx = bodyX + bodyW - filtersW;
        if (!sameRow) fx = bodyX;
        rects.put(B_ALL, new int[] {fx, fy, wAll, BOX_H});
        rects.put(B_MANUAL, new int[] {fx + wAll + 2, fy, wMan, BOX_H});
        y = fy + BOX_H + 4;
        rects.put(B_CLEAR, new int[] {search.x + search.w - 12, search.y + 1, 11, BOX_H - 2});
        listTop = y;
        int footerH = 2 * LINE;
        int bw = Math.max(52, width.applyAsInt(listBackLabel) + 20);
        rects.put(B_BACK, new int[] {bodyX + bodyW - bw, py + ph - PAD - BTN_H, bw, BTN_H});
        footerY = py + ph - PAD - footerH;
        footerW = bodyW - bw - 8;
        listH = Math.max(ROW_H, footerY - 3 - listTop);
        visibleRows = Math.max(1, listH / ROW_H);
        clampScroll();
        refit();
        updateFooter();
    }

    private static int blocks(int lines) { return lines * LINE + 2 * BOX_PAD; }

    private void layoutEdit() {
        int top = py + TITLE_H + 5;
        int bottom = py + ph - PAD;
        boolean multiVersion = versions.size() > 1;
        String sourceRaw = currentSourceRaw();
        original.setText(ManualTextCodec.toDisplay(sourceRaw));

        // buttons: flow left to right, new row when full
        List<Integer> ids = new ArrayList<>();
        ids.add(B_SAVE);
        if (editSource == Source.MANUAL) ids.add(B_REVERT);
        ids.add(B_DELETE);
        ids.add(B_EDIT_BACK);
        Map<Integer, Integer> bw = new LinkedHashMap<>();
        for (int id : ids) {
            int w = width.applyAsInt(id == B_DELETE ? widest(deleteLabel, deleteConfirmLabel) : labelOf(id)) + 12;
            bw.put(id, Math.min(bodyW, Math.max(40, w)));
        }
        int btnRows = 1;
        int rowX = 0;
        for (int id : ids) {
            int w = bw.get(id);
            if (rowX > 0 && rowX + w > bodyW) {
                btnRows++;
                rowX = 0;
            }
            rowX += w + GAP;
        }
        int btnBlockH = btnRows * (EDIT_BTN_H + 2);

        // chips: the markers of the original, as a flow with an inline label; at most two rows
        chips.clear();
        List<Tag> tags = ManualTextCodec.distinctTags(sourceRaw);
        chipLabel = host.text(KEY_EDIT_INSERT);
        int chipRows = 0;
        if (!tags.isEmpty()) {
            int labelW = width.applyAsInt(chipLabel) + 6;
            chipRows = 1;
            int cx = labelW;
            for (Tag t : tags) {
                String text = t.display();
                int w = width.applyAsInt(text) + 8;
                if (cx + w > bodyW && cx > 0) {
                    if (chipRows >= 2) break;
                    chipRows++;
                    cx = 0;
                }
                chips.add(new Chip(t, text, new int[] {cx, chipRows - 1, w}));
                cx += w + 3;
            }
        }
        int chipsH = chipRows * (CHIP_H + 2);

        original.w = bodyW;
        original.setInnerWidth(bodyW - 2 * BOX_PAD - 2);
        original.relayout();
        int origWanted = Math.max(1, Math.min(4, original.lineCount()));

        int headerH = LINE + 3;
        int origLabelH = LINE + 1;
        int transLabelH = multiVersion ? BTN_H : LINE + 1;
        int messageH = LINE + 3;
        int fixed = headerH + origLabelH + transLabelH + chipsH + messageH + btnBlockH + 5 * 2;
        int free = bottom - top - fixed - blocks(1) - blocks(2);
        int origLines = 1;
        int editLines = 2;
        // what is left after the smallest boxes goes first to the translation box (up to four lines), then to
        // the original (all its lines, up to four), then to the automatic translation and the hints (each only
        // when it fits completely), and last to a taller translation box (up to eight lines)
        int give = Math.max(0, Math.min(2, free / LINE));
        editLines += give;
        free -= give * LINE;
        give = Math.max(0, Math.min(origWanted - 1, free / LINE));
        origLines += give;
        free -= give * LINE;
        showAuto = editSource == Source.MANUAL && data != null && data.automaticTranslation() != null
                && !data.automaticTranslation().isBlank();
        int autoH = showAuto ? LINE + 2 : 0;
        if (showAuto && free < autoH) {
            showAuto = false;
            autoH = 0;
        }
        free -= autoH;
        hintLines.clear();
        List<String> hintTexts = new ArrayList<>();
        if (ManualTextCodec.hasColorTags(sourceRaw)) hintTexts.add(host.text(KEY_EDIT_COLOR_HINT));
        hintTexts.add(host.text(KEY_HINT_PRIORITY));
        for (String h : hintTexts) {
            List<String> wrapped = UiText.wrap(h, bodyW, width);
            int need = wrapped.size() * LINE + 3;
            if (need <= free && wrapped.size() <= 4) {
                hintLines.addAll(wrapped);
                free -= need;
            }
        }
        give = Math.max(0, Math.min(4, free / LINE));
        editLines += give;

        int y = top;
        headerY = y;
        y += headerH;
        origLabelY = y;
        y += origLabelH;
        original.x = bodyX;
        original.y = y;
        original.h = blocks(origLines);
        original.visibleLines = origLines;
        y += original.h + 2;
        transLabelY = y;
        y += transLabelH;
        edit.x = bodyX;
        edit.y = y;
        edit.w = bodyW;
        edit.h = blocks(editLines);
        edit.visibleLines = editLines;
        edit.setInnerWidth(bodyW - 2 * BOX_PAD - 2 - BAR_W);
        edit.relayout();
        edit.ensureCaretVisible();
        y += edit.h + 2;
        if (chipRows > 0) {
            chipsY = y;
            for (int i = 0; i < chips.size(); i++) {
                Chip c = chips.get(i);
                int[] r = c.rect();
                chips.set(i, new Chip(c.tag(), c.text(), new int[] {bodyX + r[0], chipsY + r[1] * (CHIP_H + 2), r[2], CHIP_H}));
            }
            chipLabelRow = chipsY;
            y += chipsH;
        }
        messageY = y;
        y += messageH;
        if (showAuto) {
            autoY = y;
            y += autoH;
        }
        if (!hintLines.isEmpty()) {
            hintY = y;
            y += hintLines.size() * LINE + 3;
        }
        // buttons from the bottom of the panel upwards would leave a gap; they follow the content instead
        int by = Math.min(y + 2, bottom - btnBlockH);
        int bx = 0;
        int row = 0;
        for (int id : ids) {
            int w = bw.get(id);
            if (bx > 0 && bx + w > bodyW) {
                row++;
                bx = 0;
            }
            rects.put(id, new int[] {bodyX + bx, by + row * (EDIT_BTN_H + 2), w, EDIT_BTN_H});
            bx += w + GAP;
        }
        colorLabel = multiVersion ? host.text(KEY_EDIT_COLOR, version + 1, versions.size()) : "";
        if (multiVersion) {
            int vy = transLabelY - 2;
            int h = BTN_H - 2;
            int rx = bodyX + bodyW;
            rects.put(B_VNEXT, new int[] {rx - 16, vy, 16, h});
            int lw = width.applyAsInt(colorLabel);
            rects.put(B_VPREV, new int[] {rx - 16 - 4 - lw - 4 - 16, vy, 16, h});
        }
        autoText = showAuto
                ? host.text(KEY_EDIT_AUTO, sourceLabel(data.automaticSource() == null ? Source.AI : data.automaticSource()),
                        listText(ManualTextCodec.toDisplay(data.automaticTranslation())))
                : "";
    }

    private static String widest(String a, String b) { return a.length() >= b.length() ? a : b; }

    // ------------------------------------------------------------------ the page

    private void syncPage() {
        Page p = model.page();
        if (p != shown) adopt(p, false);
    }

    private void adopt(Page p, boolean first) {
        String selectedKey = selected >= 0 && selected < rows.size() ? rows.get(selected).row().key() : null;
        boolean newQuery = first || shown == null || !p.query().equals(shown.query()) || p.manualOnly() != shown.manualOnly();
        shown = p;
        List<RowView> views = new ArrayList<>(p.rows().size());
        for (Row r : p.rows()) {
            views.add(new RowView(r, listText(ManualTextCodec.toDisplay(r.key())),
                    listText(ManualTextCodec.toDisplay(r.translation())), "", ""));
        }
        rows = views;
        if (newQuery) {
            scrollRow = 0;
            selected = -1;
        } else {
            selected = -1;
            if (selectedKey != null) {
                for (int i = 0; i < rows.size(); i++) {
                    if (rows.get(i).row().key().equals(selectedKey)) selected = i;
                }
            }
        }
        statsText = p.loading() && p.totalSaved() == 0 ? "" : host.text(KEY_STATS, count(p.totalSaved()), count(p.manualCount()));
        clampScroll();
        refit();
        updateFooter();
    }

    private static String count(int n) { return String.format(Locale.ROOT, "%,d", n); }

    private static String listText(String display) {
        String s = display.replace('\n', ' ');
        return s.length() > ROW_TEXT_CAP ? s.substring(0, ROW_TEXT_CAP) : s;
    }

    /** Cuts every row's two lines to the width they are drawn at. */
    private void refit() {
        if (rows.isEmpty() || pw <= 0) return;
        int rowW = bodyW - BAR_W - 3;
        List<RowView> fitted = new ArrayList<>(rows.size());
        for (RowView v : rows) {
            int tagW = tagWidth(v.row().source());
            int flagW = v.row().hasColorVersions() ? 18 : 0;
            fitted.add(new RowView(v.row(), v.original(), v.translation(),
                    UiText.fit(v.original(), rowW - 7 - tagW - 8, width),
                    UiText.fit(v.translation(), rowW - 7 - 4 - flagW, width)));
        }
        rows = fitted;
    }

    private int tagWidth(Source s) { return width.applyAsInt(sourceLabel(s)) + 8; }

    private String sourceLabel(Source s) {
        return switch (s) {
            case MANUAL -> tagManual;
            case AI -> tagAi;
            case MACHINE -> tagMachine;
        };
    }

    private void updateFooter() {
        if (status != null && host.nowMs() < statusUntil) {
            footerText = status;
        } else if (shown != null && shown.loading() && !rows.isEmpty()) {
            footerText = host.text(KEY_LOADING);
        } else if (shown != null && shown.truncated()) {
            footerText = host.text(KEY_CAPPED, count(TranslationManagerModel.MAX_PAGE_ROWS));
        } else {
            footerText = "";
        }
        footerLines = footerText.isEmpty() ? List.of() : UiText.wrap(footerText, Math.max(40, footerW), width);
    }

    private int maxScrollRow() { return Math.max(0, rows.size() - visibleRows); }

    private void clampScroll() { scrollRow = Math.max(0, Math.min(maxScrollRow(), scrollRow)); }

    private void fireSearch() {
        searchPending = false;
        model.searchAsync(search.get().trim(), manualOnly);
    }

    private void queryEdited() {
        searchPending = true;
        searchEditedAt = host.nowMs();
    }

    // ------------------------------------------------------------------ render

    public void render(UiCanvas c, int mx, int my) {
        lastMx = mx;
        lastMy = my;
        syncPage();
        long now = host.nowMs();
        if (searchPending && now - searchEditedAt >= SEARCH_DEBOUNCE_MS) fireSearch();
        if (deleteArmedAt >= 0 && now - deleteArmedAt > DELETE_CONFIRM_MS) deleteArmedAt = -1;
        if (status != null && now >= statusUntil) {
            status = null;
            updateFooter();
        }
        c.fill(0, 0, screenW, screenH, 0xA0000000);
        SettingsPanel.rrect(c, px, py, pw, ph, SettingsPanel.C_PANEL);
        SettingsPanel.border(c, px, py, pw, ph, SettingsPanel.C_PANEL_EDGE);
        String t = UiText.fit(title, pw - 2 * PAD, width);
        c.text(t, px + (pw - width.applyAsInt(t)) / 2, py + 7, SettingsPanel.C_TITLE);
        c.fill(px + PAD, py + TITLE_H, pw - 2 * PAD, 1, SettingsPanel.C_PANEL_EDGE);
        if (editing) renderEdit(c, mx, my);
        else renderList(c, mx, my);
        int[] f = focusRect();
        if (f != null) SettingsPanel.border(c, f[0] - 1, f[1] - 1, f[2] + 2, f[3] + 2, SettingsPanel.C_ACCENT);
    }

    private int[] focusRect() {
        // the text boxes draw their own highlighted border
        if (focus == F_SEARCH || focus == F_EDIT) return null;
        return rects.get(focus);
    }

    private static boolean in(int mx, int my, int[] r) {
        return r != null && mx >= r[0] && mx < r[0] + r[2] && my >= r[1] && my < r[1] + r[3];
    }

    private void drawButton(UiCanvas c, int id, int mx, int my, boolean selected) {
        int[] r = rects.get(id);
        if (r == null) return;
        boolean enabled = enabled(id);
        boolean hover = enabled && in(mx, my, r);
        boolean danger = id == B_DELETE;
        int bg;
        if (!enabled) bg = SettingsPanel.C_TRACK;
        else if (danger) bg = hover || deleteArmedAt >= 0 ? SettingsPanel.C_DANGER_HOVER : SettingsPanel.C_DANGER;
        else if (selected) bg = hover ? SettingsPanel.C_AI_HOVER : SettingsPanel.C_AI;
        else bg = hover ? SettingsPanel.C_BUTTON_HOVER : SettingsPanel.C_BUTTON;
        SettingsPanel.rrect(c, r[0], r[1], r[2], r[3], bg);
        String label = UiText.fit(labelOf(id), r[2] - 8, width);
        c.text(label, r[0] + (r[2] - width.applyAsInt(label)) / 2, r[1] + (r[3] - 8) / 2,
                enabled ? SettingsPanel.C_TITLE : SettingsPanel.C_DISABLED_TEXT);
    }

    // ---- list

    private void renderList(UiCanvas c, int mx, int my) {
        if (!statsText.isEmpty()) {
            c.text(UiText.fit(statsText, bodyW, width), bodyX, statsY, SettingsPanel.C_DESC);
        }
        drawSearch(c);
        drawButton(c, B_ALL, mx, my, !manualOnly);
        drawButton(c, B_MANUAL, mx, my, manualOnly);
        // rows: only whole rows are drawn
        int rowW = bodyW - BAR_W - 3;
        int shownRows = Math.min(visibleRows, rows.size() - scrollRow);
        for (int i = 0; i < shownRows; i++) {
            int idx = scrollRow + i;
            RowView v = rows.get(idx);
            int ry = listTop + i * ROW_H;
            boolean hover = mx >= bodyX && mx < bodyX + rowW && my >= ry && my < ry + ROW_H;
            boolean sel = idx == selected;
            SettingsPanel.rrect(c, bodyX, ry, rowW, ROW_H - 2, sel || hover ? SettingsPanel.C_CARD_HOVER : SettingsPanel.C_CARD);
            if (sel) SettingsPanel.border(c, bodyX, ry, rowW, ROW_H - 2, SettingsPanel.C_ACCENT);
            boolean manual = v.row().source() == Source.MANUAL;
            c.fill(bodyX + 1, ry + 2, 2, ROW_H - 6, manual ? SettingsPanel.C_GOOD : SettingsPanel.C_OFF);
            c.text(v.fitOriginal(), bodyX + 7, ry + 3, SettingsPanel.C_TITLE);
            drawSourceTag(c, v.row().source(), bodyX + rowW - 3, ry + 3);
            c.text(v.fitTranslation(), bodyX + 7, ry + 3 + LINE + 1, manual ? 0xFFC8EBD0 : SettingsPanel.C_DESC);
            if (v.row().hasColorVersions()) drawSwatches(c, bodyX + rowW - 3 - 14, ry + ROW_H - 9);
        }
        if (rows.isEmpty()) drawEmptyNote(c);
        if (maxScrollRow() > 0) {
            int sx = bodyX + bodyW - BAR_W;
            c.fill(sx, listTop, BAR_W, trackH(), 0x80000000);
            int th = thumbH();
            int travel = trackH() - th;
            int ty = listTop + (maxScrollRow() == 0 ? 0 : (int) ((long) travel * scrollRow / maxScrollRow()));
            c.fill(sx, ty, BAR_W, th, dragBar ? 0xFFFFFFFF : 0xFFA0A4B0);
        }
        int footerColor = status != null && footerText.equals(status) ? SettingsPanel.C_GOOD : SettingsPanel.C_DESC;
        for (int i = 0; i < Math.min(2, footerLines.size()); i++) {
            String l = footerLines.get(i);
            if (i == 1 && footerLines.size() > 2) l = UiText.fit(l + " " + footerLines.get(2), footerW, width);
            c.text(l, bodyX, footerY + i * LINE, footerColor);
        }
        drawButton(c, B_BACK, mx, my, false);
    }

    private void drawEmptyNote(UiCanvas c) {
        String text;
        if (shown != null && shown.loading()) text = host.text(KEY_LOADING);
        else if (search.get().isBlank()) text = host.text(manualOnly ? KEY_NONE_MANUAL : KEY_NONE);
        else text = host.text(KEY_EMPTY, search.get().trim());
        List<String> lines = UiText.wrap(text, bodyW - 16, width);
        int y = listTop + 8;
        for (int i = 0; i < Math.min(lines.size(), Math.max(1, (listH - 8) / LINE)); i++) {
            String l = lines.get(i);
            c.text(l, bodyX + (bodyW - width.applyAsInt(l)) / 2, y + i * LINE, SettingsPanel.C_DESC);
        }
    }

    /** The word for where a translation comes from, right-aligned at {@code rightX}; a manual one has a green plate. */
    private int drawSourceTag(UiCanvas c, Source s, int rightX, int y) {
        String label = sourceLabel(s);
        int w = width.applyAsInt(label) + 8;
        int x = rightX - w;
        if (s == Source.MANUAL) {
            SettingsPanel.rrect(c, x, y - 1, w, LINE + 1, C_TAG_MANUAL);
            c.text(label, x + 4, y, SettingsPanel.C_TITLE);
        } else {
            c.text(label, x + 4, y, s == Source.AI ? 0xFF8FB4E8 : SettingsPanel.C_DESC);
        }
        return w;
    }

    private static void drawSwatches(UiCanvas c, int x, int y) {
        for (int i = 0; i < 3; i++) c.fill(x + i * 5, y, 4, 4, 0xFF000000 | RUN_RGB[i]);
    }

    private void drawSearch(UiCanvas c) {
        int[] box = {search.x, search.y, search.w, search.h};
        SettingsPanel.rrect(c, box[0], box[1], box[2], box[3], C_BOX);
        SettingsPanel.border(c, box[0], box[1], box[2], box[3], focus == F_SEARCH ? SettingsPanel.C_ACCENT : SettingsPanel.C_PANEL_EDGE);
        boolean hasText = search.text.length() > 0;
        int innerW = search.w - 8 - (hasText ? 12 : 0);
        if (!hasText) {
            c.text(UiText.fit(searchHint, innerW, width), box[0] + 4, box[1] + 3, SettingsPanel.C_DISABLED_TEXT);
        } else {
            int off = search.singleOffset(innerW);
            String shownText = search.sliceToWidth(off, innerW);
            if (search.all) c.fill(box[0] + 4, box[1] + 2, width.applyAsInt(shownText), LINE + 1, C_SELECT);
            c.text(shownText, box[0] + 4, box[1] + 3, SettingsPanel.C_TITLE);
            if (focus == F_SEARCH && caretOn()) {
                int cx = box[0] + 4 + width.applyAsInt(search.text.substring(off, Math.min(search.caret, search.text.length())));
                c.fill(cx, box[1] + 2, 1, LINE + 1, SettingsPanel.C_TITLE);
            }
            int[] x = rects.get(B_CLEAR);
            if (x != null) {
                boolean hover = in(lastMx, lastMy, x);
                c.text("x", x[0] + 3, x[1] + 2, hover ? SettingsPanel.C_TITLE : SettingsPanel.C_DESC);
            }
        }
        if (!hasText && focus == F_SEARCH && caretOn()) c.fill(box[0] + 4, box[1] + 2, 1, LINE + 1, SettingsPanel.C_TITLE);
    }

    private boolean caretOn() { return (host.nowMs() / 500) % 2 == 0; }

    // ---- edit

    private void renderEdit(UiCanvas c, int mx, int my) {
        int tagW = width.applyAsInt(sourceLabel(editSource)) + 8;
        c.text(UiText.fit(editTitleLabel, bodyW - tagW - 6, width), bodyX, headerY, SettingsPanel.C_TITLE);
        drawSourceTag(c, editSource, bodyX + bodyW, headerY);
        c.text(originalLabel, bodyX, origLabelY, SettingsPanel.C_DESC);
        original.draw(c, false);
        c.text(translationLabel, bodyX, transLabelY, SettingsPanel.C_DESC);
        if (versions.size() > 1) {
            drawButton(c, B_VPREV, mx, my, false);
            drawButton(c, B_VNEXT, mx, my, false);
            int[] p = rects.get(B_VPREV);
            c.text(colorLabel, p[0] + p[2] + 4, p[1] + 3, SettingsPanel.C_TITLE);
        }
        edit.draw(c, focus == F_EDIT);
        if (!chips.isEmpty()) {
            c.text(UiText.fit(chipLabel, bodyW, width), bodyX, chipLabelRow + 2, SettingsPanel.C_DESC);
            for (Chip chip : chips) {
                int[] r = chip.rect();
                boolean hover = in(mx, my, r);
                int base = tagColor(chip.tag());
                SettingsPanel.rrect(c, r[0], r[1], r[2], r[3], hover ? brighten(base) : base);
                c.text(chip.text(), r[0] + 4, r[1] + 3, SettingsPanel.C_TITLE);
            }
        }
        String line = currentMessage();
        if (line != null) c.text(UiText.fit(line, bodyW, width), bodyX, messageY + 1, currentMessageColor());
        if (showAuto) c.text(UiText.fit(autoText, bodyW, width), bodyX, autoY, SettingsPanel.C_DESC);
        for (int i = 0; i < hintLines.size(); i++) {
            c.text(hintLines.get(i), bodyX, hintY + i * LINE, SettingsPanel.C_DISABLED_TEXT);
        }
        drawButton(c, B_SAVE, mx, my, false);
        drawButton(c, B_REVERT, mx, my, false);
        drawButton(c, B_DELETE, mx, my, false);
        drawButton(c, B_EDIT_BACK, mx, my, false);
    }

    private String currentMessage() {
        if (message != null) return message;
        return liveProblem;
    }

    private int currentMessageColor() {
        if (message != null) return messageColor;
        return SettingsPanel.C_WARN;
    }

    private static int brighten(int argb) {
        int r = Math.min(255, ((argb >> 16) & 0xFF) + 40);
        int g = Math.min(255, ((argb >> 8) & 0xFF) + 40);
        int b = Math.min(255, (argb & 0xFF) + 40);
        return (argb & 0xFF000000) | (r << 16) | (g << 8) | b;
    }

    private static int tagColor(Tag t) {
        return switch (t.kind()) {
            case VALUE -> 0xFF2E5E9E;
            case NAME -> 0xFF3A8F5A;
            case SPACE -> 0xFF5A5F70;
            case BREAK -> 0xFF7A4FA0;
            case COLOR_OPEN, COLOR_CLOSE -> 0xFF000000 | RUN_RGB[t.index() % RUN_RGB.length];
        };
    }

    private String labelOf(int id) {
        return switch (id) {
            case B_BACK -> listBackLabel;
            case B_ALL -> filterAll;
            case B_MANUAL -> filterManual;
            case B_SAVE -> saveLabel;
            case B_REVERT -> revertLabel;
            case B_DELETE -> deleteArmedAt >= 0 ? deleteConfirmLabel : deleteLabel;
            case B_EDIT_BACK -> backLabel;
            case B_VPREV -> "<";
            case B_VNEXT -> ">";
            default -> "";
        };
    }

    private boolean enabled(int id) {
        if (id == B_SAVE) return canSave();
        return true;
    }

    // ------------------------------------------------------------------ edit mode: data

    private String currentSourceRaw() {
        if (data == null) return "";
        return versions.isEmpty() ? data.original() : versions.get(version).sourceKey();
    }

    private void openEdit(int rowIndex) {
        if (rowIndex < 0 || rowIndex >= rows.size()) return;
        Row row = rows.get(rowIndex).row();
        EditData d = model.editData(row.key());
        if (d == null) {
            model.refreshAsync();
            return;
        }
        data = d;
        versions = d.colorVersions();
        version = 0;
        int n = Math.max(1, versions.size());
        drafts = new String[n];
        baselines = new String[n];
        for (int i = 0; i < n; i++) {
            String raw = versions.isEmpty() ? d.translation() : versions.get(i).translation();
            drafts[i] = ManualTextCodec.toDisplay(raw);
            baselines[i] = drafts[i];
        }
        editSource = d.source();
        message = null;
        discardArmed = false;
        deleteArmedAt = -1;
        editing = true;
        focus = F_EDIT;
        narrateRow = false;
        edit.setText(drafts[0]);
        selected = rowIndex;
        liveProblem = computeProblem();
        layout();
        narrationRequested = true;
    }

    private void leaveEdit(String statusMessage) {
        editing = false;
        data = null;
        versions = List.of();
        message = null;
        discardArmed = false;
        deleteArmedAt = -1;
        focus = F_SEARCH;
        if (statusMessage != null) {
            status = statusMessage;
            statusUntil = host.nowMs() + STATUS_MS;
        }
        layout();
        syncPage();
        updateFooter();
        narrationRequested = true;
    }

    private boolean dirty() {
        for (int i = 0; i < drafts.length; i++) {
            String now = i == version ? edit.get() : drafts[i];
            if (!now.equals(baselines[i])) return true;
        }
        return false;
    }

    private boolean canSave() {
        if (!editing || data == null || liveProblem != null) return false;
        return editSource != Source.MANUAL || dirty();
    }

    /** The problem the player should fix before saving, in words, or null when the text can be saved. */
    private String computeProblem() {
        if (data == null) return null;
        String sourceRaw = currentSourceRaw();
        String raw = ManualTextCodec.fromDisplay(edit.get());
        if (raw.isBlank()) return host.text(KEY_ERR_EMPTY);
        if (raw.length() > TranslationManagerModel.MAX_TRANSLATION_LENGTH) {
            return host.text(KEY_ERR_LONG, TranslationManagerModel.MAX_TRANSLATION_LENGTH);
        }
        Diff diff = ManualTextCodec.compare(sourceRaw, raw);
        if (!diff.missing().isEmpty()) return host.text(KEY_ERR_MISSING, diff.missingText());
        if (!diff.extra().isEmpty()) return host.text(KEY_ERR_EXTRA, diff.extraText());
        List<Tag> bad = ManualTextCodec.unpairedColorTags(raw);
        if (!bad.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (Tag t : bad) {
                if (sb.length() > 0) sb.append(' ');
                sb.append(t.display());
            }
            return host.text(KEY_ERR_COLOR_PAIRS, sb.toString());
        }
        if (!ManualTextCodec.breakOrderMatches(sourceRaw, raw)) return host.text(KEY_ERR_ORDER);
        int newlines = ManualTextCodec.newlineCount(sourceRaw);
        if (newlines != ManualTextCodec.newlineCount(raw)) return host.text(KEY_ERR_NEWLINES, newlines);
        if (ManualTextCodec.stripColorTags(raw).strip().equals(ManualTextCodec.stripColorTags(sourceRaw).strip())) {
            return host.text(KEY_ERR_SAME);
        }
        return null;
    }

    private void textChanged() {
        message = null;
        discardArmed = false;
        deleteArmedAt = -1;
        liveProblem = computeProblem();
    }

    private void setMessage(String text, int color) {
        message = text;
        messageColor = color;
    }

    private void doSave() {
        if (!canSave()) return;
        String raw = ManualTextCodec.fromDisplay(edit.get());
        SaveOutcome out;
        if (versions.isEmpty()) {
            out = model.save(data.key(), raw, null, null);
        } else {
            out = model.save(data.key(), ManualTextCodec.stripColorTags(raw), versions.get(version).sourceKey(), raw);
        }
        host.playClick();
        if (out.ok()) {
            drafts[version] = edit.get();
            baselines[version] = edit.get();
            host.onChanged();
            EditData fresh = model.editData(data.key());
            if (fresh != null) {
                data = fresh;
                editSource = fresh.source();
            } else {
                editSource = Source.MANUAL;
            }
            setMessage(host.text(KEY_STATUS_SAVED), SettingsPanel.C_GOOD);
            discardArmed = false;
            layout();
        } else {
            setMessage(outcomeMessage(out), C_ERR);
        }
        narrationRequested = true;
    }

    private String outcomeMessage(SaveOutcome o) {
        return switch (o.result()) {
            case OK -> host.text(KEY_STATUS_SAVED);
            case EMPTY -> host.text(KEY_ERR_EMPTY);
            case SAME_AS_SOURCE -> host.text(KEY_ERR_SAME);
            case MISSING_TAGS -> host.text(KEY_ERR_MISSING, String.join(" ", o.tags()));
            case EXTRA_TAGS -> host.text(KEY_ERR_EXTRA, String.join(" ", o.tags()));
            case TAG_ORDER -> host.text(KEY_ERR_ORDER);
            case NEWLINES -> host.text(KEY_ERR_NEWLINES, o.number());
            case PARTIAL -> host.text(KEY_ERR_PARTIAL);
            case CONTROL_CHARS -> host.text(KEY_ERR_CONTROL);
            case TOO_LONG -> host.text(KEY_ERR_LONG, o.number() > 0 ? o.number() : TranslationManagerModel.MAX_TRANSLATION_LENGTH);
            case FULL -> host.text(KEY_ERR_FULL, o.number());
            case IO_ERROR -> host.text(KEY_ERR_IO);
        };
    }

    private void doRevert() {
        if (!editing || data == null || editSource != Source.MANUAL) return;
        host.playClick();
        if (!model.revertToAutomatic(data.key())) {
            setMessage(host.text(KEY_ERR_IO), C_ERR);
            return;
        }
        host.onChanged();
        leaveEdit(host.text(KEY_STATUS_REVERTED));
    }

    private void doDelete() {
        if (!editing || data == null) return;
        host.playClick();
        long now = host.nowMs();
        if (deleteArmedAt < 0 || now - deleteArmedAt > DELETE_CONFIRM_MS) {
            deleteArmedAt = now;
            discardArmed = false;
            narrationRequested = true;
            return;
        }
        deleteArmedAt = -1;
        boolean online = model.deleteAndRetranslate(data.key());
        host.onChanged();
        leaveEdit(host.text(online ? KEY_STATUS_DELETED : KEY_STATUS_DELETED_OFF));
    }

    /** Leaves the editor; with unsaved changes the first call only warns. */
    private int tryLeaveEdit() {
        if (dirty() && !discardArmed) {
            discardArmed = true;
            setMessage(host.text(KEY_DISCARD), SettingsPanel.C_WARN);
            deleteArmedAt = -1;
            narrationRequested = true;
            return NONE;
        }
        leaveEdit(null);
        return NONE;
    }

    private void switchVersion(int delta) {
        if (versions.size() < 2) return;
        drafts[version] = edit.get();
        version = (version + delta + versions.size()) % versions.size();
        edit.setText(drafts[version]);
        message = null;
        discardArmed = false;
        deleteArmedAt = -1;
        liveProblem = computeProblem();
        layout();
        narrationRequested = true;
    }

    private void insertTag(Tag tag) {
        focus = F_EDIT;
        edit.insert(tag.display());
        edit.ensureCaretVisible();
        textChanged();
    }

    // ------------------------------------------------------------------ input: mouse

    /** @return {@link #BACK} when the screen should close. The click is always consumed by the caller. */
    public int mouseClicked(int mx, int my, int button) {
        if (button != 0) return NONE;
        syncPage();
        narrateRow = false;
        return editing ? clickEdit(mx, my) : clickList(mx, my);
    }

    private int clickList(int mx, int my) {
        if (in(mx, my, rects.get(B_BACK))) {
            host.playClick();
            return BACK;
        }
        if (search.text.length() > 0 && in(mx, my, rects.get(B_CLEAR))) {
            search.setText("");
            focus = F_SEARCH;
            host.playClick();
            fireSearch();
            return NONE;
        }
        if (in(mx, my, rects.get(B_ALL)) || in(mx, my, rects.get(B_MANUAL))) {
            boolean wantManual = in(mx, my, rects.get(B_MANUAL));
            host.playClick();
            if (wantManual != manualOnly) {
                manualOnly = wantManual;
                fireSearch();
            }
            return NONE;
        }
        if (mx >= search.x && mx < search.x + search.w && my >= search.y && my < search.y + search.h) {
            focus = F_SEARCH;
            search.all = false;
            search.placeSingle(mx - search.x - 4, search.singleOffset(search.w - 8 - (search.text.length() > 0 ? 12 : 0)));
            narrationRequested = true;
            return NONE;
        }
        int rowW = bodyW - BAR_W - 3;
        if (mx >= bodyX && mx < bodyX + rowW && my >= listTop && my < listTop + visibleRows * ROW_H) {
            int i = scrollRow + (my - listTop) / ROW_H;
            if (i < rows.size()) {
                host.playClick();
                openEdit(i);
            }
            return NONE;
        }
        if (maxScrollRow() > 0 && mx >= bodyX + bodyW - BAR_W - 2 && mx < bodyX + bodyW
                && my >= listTop && my < listTop + visibleRows * ROW_H) {
            dragBar = true;
            dragTo(my);
        }
        return NONE;
    }

    private int trackH() { return visibleRows * ROW_H - 2; }

    private int thumbH() { return Math.max(12, (int) ((long) trackH() * visibleRows / Math.max(1, rows.size()))); }

    /** Moves the list so that the middle of the bar's thumb is under the pointer. */
    private void dragTo(int my) {
        int travel = trackH() - thumbH();
        if (travel <= 0) return;
        scrollRow = Math.round(maxScrollRow() * ((my - listTop - thumbH() / 2) / (float) travel));
        clampScroll();
    }

    private int clickEdit(int mx, int my) {
        int[] r;
        if ((r = rects.get(B_EDIT_BACK)) != null && in(mx, my, r)) {
            host.playClick();
            return tryLeaveEdit();
        }
        if (in(mx, my, rects.get(B_SAVE))) {
            if (canSave()) doSave();
            return NONE;
        }
        if (in(mx, my, rects.get(B_REVERT))) {
            doRevert();
            return NONE;
        }
        if (in(mx, my, rects.get(B_DELETE))) {
            doDelete();
            return NONE;
        }
        if (versions.size() > 1 && in(mx, my, rects.get(B_VPREV))) {
            host.playClick();
            switchVersion(-1);
            return NONE;
        }
        if (versions.size() > 1 && in(mx, my, rects.get(B_VNEXT))) {
            host.playClick();
            switchVersion(1);
            return NONE;
        }
        for (Chip chip : chips) {
            if (in(mx, my, chip.rect())) {
                host.playClick();
                insertTag(chip.tag());
                return NONE;
            }
        }
        if (mx >= edit.x && mx < edit.x + edit.w && my >= edit.y && my < edit.y + edit.h) {
            focus = F_EDIT;
            edit.all = false;
            edit.placeCaret(mx, my);
            narrationRequested = true;
            return NONE;
        }
        return NONE;
    }

    public void mouseDragged(int mx, int my) {
        if (dragBar && !editing) dragTo(my);
    }

    public void mouseReleased() { dragBar = false; }

    public void mouseScrolled(double scrollY) {
        if (scrollY == 0) return;
        int dir = scrollY > 0 ? -1 : 1;
        if (editing) {
            if (edit.lineCount() > edit.visibleLines && lastMx >= edit.x && lastMx < edit.x + edit.w
                    && lastMy >= edit.y && lastMy < edit.y + edit.h) {
                edit.scrollLine = Math.max(0, Math.min(edit.lineCount() - edit.visibleLines, edit.scrollLine + dir));
            }
            return;
        }
        scrollRow += dir * 2;
        clampScroll();
    }

    // ------------------------------------------------------------------ input: keyboard

    /** True while a text box has the keyboard: the game's own letter shortcuts must not run. */
    public boolean isTyping() { return focus == F_SEARCH || focus == F_EDIT; }

    /** @return true when the character went into a text box. */
    public boolean charTyped(char ch) {
        if (!isTyping()) return false;
        Field f = focus == F_EDIT ? edit : search;
        if (f == edit && !editing) return false;
        if (f == search && editing) return false;
        if (ch < 32 && ch != '\n' || ch == 127) return true;
        if (ch == '\n' && !f.multi) return true;
        if (f.insert(String.valueOf(ch))) {
            if (f == search) queryEdited();
            else {
                edit.ensureCaretVisible();
                textChanged();
            }
        }
        return true;
    }

    /**
     * @return {@link #BACK} when the screen should close, else {@link #NONE}; the key is always consumed
     */
    public int keyPressed(int key, boolean ctrl, boolean shift) {
        syncPage();
        return editing ? editKey(key, ctrl, shift) : listKey(key, ctrl, shift);
    }

    public int keyPressed(int key) { return keyPressed(key, false, false); }

    private int listKey(int key, boolean ctrl, boolean shift) {
        switch (key) {
            case SettingsPanel.KEY_ESCAPE -> {
                if (search.text.length() > 0) {
                    search.setText("");
                    focus = F_SEARCH;
                    fireSearch();
                    return NONE;
                }
                return BACK;
            }
            case SettingsPanel.KEY_TAB -> {
                moveFocus(shift ? -1 : 1);
                return NONE;
            }
            case SettingsPanel.KEY_DOWN -> {
                moveSelection(1);
                return NONE;
            }
            case SettingsPanel.KEY_UP -> {
                moveSelection(-1);
                return NONE;
            }
            case SettingsPanel.KEY_PAGE_DOWN -> {
                moveSelection(visibleRows);
                return NONE;
            }
            case SettingsPanel.KEY_PAGE_UP -> {
                moveSelection(-visibleRows);
                return NONE;
            }
            case SettingsPanel.KEY_ENTER, KEY_KP_ENTER -> {
                // a row picked with the arrow keys opens whatever has the focus; the search box opens the first one
                if (focus == F_SEARCH || narrateRow && selected >= 0) {
                    if (searchPending) fireSearch();
                    if (!rows.isEmpty()) openEdit(selected >= 0 ? selected : 0);
                    return NONE;
                }
                return activate(focus);
            }
            case SettingsPanel.KEY_SPACE -> {
                if (focus != F_SEARCH) return activate(focus);
                return NONE;
            }
            default -> { }
        }
        if (ctrl && key == SettingsPanel.KEY_HOME) {
            selectRow(0);
            return NONE;
        }
        if (ctrl && key == SettingsPanel.KEY_END) {
            selectRow(rows.size() - 1);
            return NONE;
        }
        if (focus == F_SEARCH) {
            if (typingKey(search, key, ctrl)) {
                queryEdited();
            }
            return NONE;
        }
        if (key == SettingsPanel.KEY_HOME) {
            selectRow(0);
        } else if (key == SettingsPanel.KEY_END) {
            selectRow(rows.size() - 1);
        }
        return NONE;
    }

    private int editKey(int key, boolean ctrl, boolean shift) {
        switch (key) {
            case SettingsPanel.KEY_ESCAPE -> {
                if (deleteArmedAt >= 0) {
                    deleteArmedAt = -1;
                    return NONE;
                }
                return tryLeaveEdit();
            }
            case SettingsPanel.KEY_TAB -> {
                moveFocus(shift ? -1 : 1);
                return NONE;
            }
            case SettingsPanel.KEY_ENTER, KEY_KP_ENTER -> {
                if (focus != F_EDIT) return activate(focus);
                if (ctrl) {
                    doSave();
                    return NONE;
                }
                int want = ManualTextCodec.newlineCount(ManualTextCodec.fromDisplay(original.get()));
                int have = ManualTextCodec.newlineCount(edit.get());
                if (have < want && edit.insert("\n")) {
                    edit.ensureCaretVisible();
                    textChanged();
                } else {
                    doSave();
                }
                return NONE;
            }
            case SettingsPanel.KEY_SPACE -> {
                if (focus != F_EDIT) return activate(focus);
                return NONE;
            }
            default -> { }
        }
        if (focus == F_EDIT) {
            if (typingKey(edit, key, ctrl)) {
                edit.ensureCaretVisible();
                textChanged();
            }
            return NONE;
        }
        if ((focus == B_VPREV || focus == B_VNEXT) && (key == SettingsPanel.KEY_LEFT || key == SettingsPanel.KEY_RIGHT)) {
            switchVersion(key == SettingsPanel.KEY_RIGHT ? 1 : -1);
        }
        return NONE;
    }

    /**
     * Cursor movement and deletion shared by both boxes.
     *
     * @return true when the text changed
     */
    private boolean typingKey(Field f, int key, boolean ctrl) {
        if (ctrl) {
            switch (key) {
                case KEY_A -> {
                    if (f.text.length() > 0) f.all = true;
                    return false;
                }
                case KEY_C -> {
                    host.copy(f.text.toString());
                    return false;
                }
                case KEY_X -> {
                    host.copy(f.text.toString());
                    if (f.readOnly || f.text.length() == 0) return false;
                    f.setText("");
                    return true;
                }
                case KEY_V -> {
                    String clip = host.clipboard();
                    return clip != null && f.insert(clip);
                }
                default -> {
                    return false;
                }
            }
        }
        switch (key) {
            case SettingsPanel.KEY_BACKSPACE -> {
                return f.backspace();
            }
            case SettingsPanel.KEY_DELETE -> {
                return f.deleteForward();
            }
            case SettingsPanel.KEY_LEFT -> f.left();
            case SettingsPanel.KEY_RIGHT -> f.right();
            case SettingsPanel.KEY_HOME -> f.home();
            case SettingsPanel.KEY_END -> f.end();
            case SettingsPanel.KEY_UP -> f.vertical(-1);
            case SettingsPanel.KEY_DOWN -> f.vertical(1);
            case SettingsPanel.KEY_PAGE_UP -> f.vertical(-Math.max(1, f.visibleLines - 1));
            case SettingsPanel.KEY_PAGE_DOWN -> f.vertical(Math.max(1, f.visibleLines - 1));
            default -> { }
        }
        if (f == edit) edit.ensureCaretVisible();
        return false;
    }

    private void moveSelection(int delta) {
        if (rows.isEmpty()) return;
        if (selected < 0) selectRow(delta > 0 ? 0 : rows.size() - 1);
        else selectRow(selected + delta);
    }

    private void selectRow(int index) {
        if (rows.isEmpty()) return;
        selected = Math.max(0, Math.min(rows.size() - 1, index));
        if (selected < scrollRow) scrollRow = selected;
        if (selected >= scrollRow + visibleRows) scrollRow = selected - visibleRows + 1;
        clampScroll();
        narrateRow = true;
        narrationRequested = true;
    }

    private List<Integer> ring() {
        List<Integer> out = new ArrayList<>();
        if (editing) {
            out.add(F_EDIT);
            if (versions.size() > 1) {
                out.add(B_VPREV);
                out.add(B_VNEXT);
            }
            if (canSave()) out.add(B_SAVE);
            if (editSource == Source.MANUAL) out.add(B_REVERT);
            out.add(B_DELETE);
            out.add(B_EDIT_BACK);
        } else {
            out.add(F_SEARCH);
            out.add(B_ALL);
            out.add(B_MANUAL);
            out.add(B_BACK);
        }
        return out;
    }

    private void moveFocus(int dir) {
        List<Integer> ring = ring();
        int at = ring.indexOf(focus);
        at = at < 0 ? (dir > 0 ? 0 : ring.size() - 1) : (at + dir + ring.size()) % ring.size();
        focus = ring.get(at);
        narrateRow = false;
        narrationRequested = true;
    }

    private int activate(int id) {
        narrationRequested = true;
        switch (id) {
            case B_BACK -> {
                host.playClick();
                return BACK;
            }
            case B_ALL, B_MANUAL -> {
                host.playClick();
                boolean wantManual = id == B_MANUAL;
                if (wantManual != manualOnly) {
                    manualOnly = wantManual;
                    fireSearch();
                }
            }
            case B_SAVE -> doSave();
            case B_REVERT -> doRevert();
            case B_DELETE -> doDelete();
            case B_EDIT_BACK -> {
                host.playClick();
                return tryLeaveEdit();
            }
            case B_VPREV -> switchVersion(-1);
            case B_VNEXT -> switchVersion(1);
            default -> { }
        }
        return NONE;
    }

    // ------------------------------------------------------------------ narration

    /** What the narrator says for the focused item (the list row after the selection moved), or the title. */
    public String narration() {
        if (editing) {
            if (focus == F_EDIT) return host.text(KEY_NARRATE_EDIT);
            return buttonPhrase(focus);
        }
        if (narrateRow && selected >= 0 && selected < rows.size()) {
            RowView v = rows.get(selected);
            return host.text(KEY_NARRATE_ROW, v.original(), v.translation());
        }
        if (focus == F_SEARCH) return host.text(KEY_NARRATE_SEARCH);
        String phrase = buttonPhrase(focus);
        return phrase.isEmpty() ? title : phrase;
    }

    private String buttonPhrase(int id) {
        String label = labelOf(id);
        if (label.isEmpty()) return title;
        if (id == B_VPREV || id == B_VNEXT) label = host.text(KEY_EDIT_COLOR, version + 1, versions.size());
        return host.text(SettingsModel.KEY_NARRATE_BUTTON, label);
    }

    /** True once after the focus moved or a focused item was pressed: the glue should ask the narrator to speak {@link #narration()}. */
    public boolean consumeNarrationRequest() {
        boolean was = narrationRequested;
        narrationRequested = false;
        return was;
    }

    // ------------------------------------------------------------------ shading of tags

    private static int runWeak(int index) { return 0x50000000 | RUN_RGB[index % RUN_RGB.length]; }

    private static int runStrong(int index) { return 0xC0000000 | RUN_RGB[index % RUN_RGB.length]; }

    // ------------------------------------------------------------------ text box

    /**
     * A text box: single-line (search) or multi-line with soft wrap (translation, original), with its
     * own cursor. A multi-line box also shades the aliases and the text between a pair of colour tags.
     */
    private final class Field {
        final boolean multi;
        final int maxLen;
        final boolean styled;
        final StringBuilder text = new StringBuilder();
        int caret;
        boolean all;
        boolean readOnly;
        int x;
        int y;
        int w;
        int h;
        int visibleLines = 1;
        int scrollLine;
        int innerW = 100;
        private boolean stale = true;
        private int laidOutW = -1;
        private final List<int[]> lines = new ArrayList<>();
        private int[] cw = new int[0];
        private int[] bg = new int[0];
        private boolean[] tag = new boolean[0];

        Field(boolean multi, int maxLen, boolean styled) {
            this.multi = multi;
            this.maxLen = maxLen;
            this.styled = styled;
        }

        String get() { return text.toString(); }

        void setText(String s) {
            text.setLength(0);
            text.append(s == null ? "" : s);
            caret = text.length();
            all = false;
            scrollLine = 0;
            stale = true;
        }

        void setInnerWidth(int iw) {
            if (iw != innerW) {
                innerW = iw;
                stale = true;
            }
        }

        /** Inserts text at the cursor (replacing everything when all is selected). Unwanted characters are dropped. */
        boolean insert(String s) {
            if (readOnly || s == null || s.isEmpty()) return false;
            StringBuilder clean = new StringBuilder(s.length());
            for (int i = 0; i < s.length(); i++) {
                char ch = s.charAt(i);
                if (ch == '\r') {
                    if (i + 1 < s.length() && s.charAt(i + 1) == '\n') continue;
                    ch = '\n';
                }
                if (ch == '\n') {
                    if (!multi) ch = ' ';
                } else if (ch < 32 || ch == 127) {
                    continue;
                }
                if (ch == '⟦' || ch == '⟧' || ch == '§') continue;
                clean.append(ch);
            }
            if (clean.length() == 0) return false;
            if (all) {
                text.setLength(0);
                caret = 0;
                all = false;
            }
            int room = maxLen - text.length();
            if (room <= 0) return false;
            if (clean.length() > room) clean.setLength(room);
            text.insert(caret, clean);
            caret += clean.length();
            stale = true;
            return true;
        }

        boolean backspace() {
            if (readOnly) return false;
            if (all) {
                setText("");
                return true;
            }
            if (caret <= 0) return false;
            int n = caret >= 2 && Character.isLowSurrogate(text.charAt(caret - 1))
                    && Character.isHighSurrogate(text.charAt(caret - 2)) ? 2 : 1;
            text.delete(caret - n, caret);
            caret -= n;
            stale = true;
            return true;
        }

        boolean deleteForward() {
            if (readOnly) return false;
            if (all) {
                setText("");
                return true;
            }
            if (caret >= text.length()) return false;
            int n = caret + 1 < text.length() && Character.isHighSurrogate(text.charAt(caret))
                    && Character.isLowSurrogate(text.charAt(caret + 1)) ? 2 : 1;
            text.delete(caret, caret + n);
            stale = true;
            return true;
        }

        void left() {
            all = false;
            if (caret <= 0) return;
            caret -= caret >= 2 && Character.isLowSurrogate(text.charAt(caret - 1))
                    && Character.isHighSurrogate(text.charAt(caret - 2)) ? 2 : 1;
        }

        void right() {
            all = false;
            if (caret >= text.length()) return;
            caret += caret + 1 < text.length() && Character.isHighSurrogate(text.charAt(caret))
                    && Character.isLowSurrogate(text.charAt(caret + 1)) ? 2 : 1;
        }

        void home() {
            all = false;
            if (multi) {
                relayout();
                caret = lines.get(lineOfCaret())[0];
            } else {
                caret = 0;
            }
        }

        void end() {
            all = false;
            if (multi) {
                relayout();
                caret = maxCaretOnLine(lineOfCaret());
            } else {
                caret = text.length();
            }
        }

        void vertical(int delta) {
            all = false;
            if (!multi) return;
            relayout();
            int line = lineOfCaret();
            int target = Math.max(0, Math.min(lines.size() - 1, line + delta));
            if (target == line) {
                caret = delta < 0 ? 0 : text.length();
                return;
            }
            int wanted = widthBetween(lines.get(line)[0], caret);
            caret = indexAtX(target, wanted);
        }

        // ---- layout

        void relayout() {
            if (!multi) return;
            if (!stale && laidOutW == innerW) return;
            stale = false;
            laidOutW = innerW;
            int n = text.length();
            cw = new int[n];
            for (int i = 0; i < n; i++) {
                char ch = text.charAt(i);
                if (ch == '\n') cw[i] = 0;
                else if (Character.isLowSurrogate(ch) && i > 0 && Character.isHighSurrogate(text.charAt(i - 1))) cw[i] = 0;
                else if (Character.isHighSurrogate(ch) && i + 1 < n && Character.isLowSurrogate(text.charAt(i + 1))) {
                    cw[i] = width.applyAsInt(text.substring(i, i + 2));
                } else cw[i] = width.applyAsInt(String.valueOf(ch));
            }
            bg = new int[n];
            tag = new boolean[n];
            int[] atom = new int[n + 1];
            java.util.Arrays.fill(atom, -1);
            if (styled && n > 0) shade(atom);
            lines.clear();
            int max = Math.max(8, innerW);
            int start = 0;
            int x = 0;
            int lastBreak = -1;
            for (int i = 0; i < n; i++) {
                char ch = text.charAt(i);
                if (ch == '\n') {
                    lines.add(new int[] {start, i});
                    start = i + 1;
                    x = 0;
                    lastBreak = -1;
                    continue;
                }
                int cwi = cw[i];
                while (x + cwi > max && i > start) {
                    int brk = i;
                    if (lastBreak > start && isWordChar(ch) && isWordChar(text.charAt(i - 1))) brk = lastBreak;
                    if (atom[brk] > start) brk = atom[brk];
                    lines.add(new int[] {start, brk});
                    start = brk;
                    x = 0;
                    lastBreak = -1;
                    for (int k = start; k < i; k++) {
                        x += cw[k];
                        if (text.charAt(k) == ' ') lastBreak = k + 1;
                    }
                }
                x += cwi;
                if (ch == ' ') lastBreak = i + 1;
            }
            lines.add(new int[] {start, n});
        }

        /** Marks the aliases and the shading of colour runs; {@code atom[i]} is where the alias that contains i (not at its start) begins. */
        private void shade(int[] atom) {
            String s = text.toString();
            int open = -1;
            int cursor = 0;
            for (Span span : ManualTextCodec.displaySpans(s)) {
                for (int i = cursor; i < span.start(); i++) if (open >= 0) bg[i] = runWeak(open);
                Tag t = span.tag();
                int color = t.kind().isColor() ? runStrong(t.index()) : (0xC0000000 | (tagColor(t) & 0xFFFFFF));
                for (int i = span.start(); i < span.end(); i++) {
                    bg[i] = color;
                    tag[i] = true;
                    if (i > span.start()) atom[i] = span.start();
                }
                if (t.kind() == ManualTextCodec.Kind.COLOR_OPEN) open = t.index();
                else if (t.kind() == ManualTextCodec.Kind.COLOR_CLOSE) open = -1;
                cursor = span.end();
            }
            for (int i = cursor; i < s.length(); i++) if (open >= 0) bg[i] = runWeak(open);
        }

        int lineCount() {
            relayout();
            return lines.size();
        }

        int lineOfCaret() {
            for (int k = 0; k < lines.size() - 1; k++) {
                if (caret < lines.get(k + 1)[0]) return k;
            }
            return lines.size() - 1;
        }

        int maxCaretOnLine(int k) {
            int[] l = lines.get(k);
            if (k == lines.size() - 1) return l[1];
            int next = lines.get(k + 1)[0];
            return next == l[1] && l[1] > l[0] ? l[1] - 1 : l[1];
        }

        int widthBetween(int from, int to) {
            int sum = 0;
            for (int i = from; i < to && i < cw.length; i++) sum += cw[i];
            return sum;
        }

        int indexAtX(int line, int px) {
            int[] l = lines.get(line);
            int sum = 0;
            int limit = maxCaretOnLine(line);
            for (int i = l[0]; i < limit; i++) {
                if (px < sum + cw[i] / 2) return i;
                sum += cw[i];
            }
            return limit;
        }

        void ensureCaretVisible() {
            if (!multi) return;
            relayout();
            int line = lineOfCaret();
            if (line < scrollLine) scrollLine = line;
            if (line >= scrollLine + visibleLines) scrollLine = line - visibleLines + 1;
            scrollLine = Math.max(0, Math.min(Math.max(0, lines.size() - visibleLines), scrollLine));
        }

        void placeCaret(int mx, int my) {
            relayout();
            int line = Math.max(0, Math.min(lines.size() - 1, scrollLine + (my - y - BOX_PAD) / LINE));
            caret = indexAtX(line, mx - x - BOX_PAD - 1);
        }

        // ---- single line

        int singleOffset(int viewW) {
            int off = 0;
            int c = Math.min(caret, text.length());
            while (off < c && width.applyAsInt(text.substring(off, c)) > viewW - 2) off++;
            return off;
        }

        String sliceToWidth(int off, int viewW) {
            int end = text.length();
            while (end > off && width.applyAsInt(text.substring(off, end)) > viewW) end--;
            return text.substring(off, end);
        }

        void placeSingle(int px, int off) {
            int sum = 0;
            int i = off;
            while (i < text.length()) {
                int cwi = width.applyAsInt(String.valueOf(text.charAt(i)));
                if (px < sum + cwi / 2) break;
                sum += cwi;
                i++;
            }
            caret = i;
        }

        // ---- drawing

        void draw(UiCanvas c, boolean focused) {
            relayout();
            SettingsPanel.rrect(c, x, y, w, h, readOnly ? C_BOX_RO : C_BOX);
            SettingsPanel.border(c, x, y, w, h, focused ? SettingsPanel.C_ACCENT : SettingsPanel.C_PANEL_EDGE);
            int first = scrollLine;
            int last = Math.min(lines.size(), first + visibleLines);
            for (int k = first; k < last; k++) {
                int[] l = lines.get(k);
                int ly = y + BOX_PAD + (k - first) * LINE;
                int lx = x + BOX_PAD + 1;
                int i = l[0];
                while (i < l[1]) {
                    int j = i + 1;
                    while (j < l[1] && bg[j] == bg[i] && tag[j] == tag[i]) j++;
                    String run = text.substring(i, j);
                    int rw = widthBetween(i, j);
                    if (all) c.fill(lx, ly - 1, rw, LINE, C_SELECT);
                    if (bg[i] != 0) c.fill(lx, ly - 1, rw, LINE, bg[i]);
                    c.text(run, lx, ly, SettingsPanel.C_TITLE);
                    lx += rw;
                    i = j;
                }
                if (focused && !readOnly && caretOn() && caret >= l[0] && caret <= l[1] && lineOfCaret() == k) {
                    c.fill(x + BOX_PAD + 1 + widthBetween(l[0], caret), ly - 1, 1, LINE, SettingsPanel.C_TITLE);
                }
            }
            if (lines.size() > visibleLines) {
                int bx = x + w - BAR_W - 1;
                int trackH = h - 4;
                c.fill(bx, y + 2, BAR_W, trackH, 0x80000000);
                int th = Math.max(8, trackH * visibleLines / lines.size());
                int maxScroll = lines.size() - visibleLines;
                int ty = y + 2 + (maxScroll == 0 ? 0 : (trackH - th) * scrollLine / maxScroll);
                c.fill(bx, ty, BAR_W, th, 0xFFA0A4B0);
            }
        }
    }

    private static boolean isWordChar(char ch) { return ch > 32 && ch < 127; }

    // ------------------------------------------------------------------ test hooks

    boolean isEditing() { return editing; }

    int[] panelRect() { return new int[] {px, py, pw, ph}; }

    /** Every control's rectangle by name, for the layout tests. */
    Map<String, int[]> controlRects() {
        Map<String, int[]> out = new LinkedHashMap<>();
        for (Map.Entry<Integer, int[]> e : rects.entrySet()) {
            int id = e.getKey();
            if (id == B_CLEAR && search.text.length() == 0) continue;
            if (!editing && id >= B_SAVE) continue;
            if (editing && id < B_SAVE) continue;
            out.put(controlName(id), e.getValue());
        }
        if (editing) {
            out.put("original", new int[] {original.x, original.y, original.w, original.h});
            out.put("edit", new int[] {edit.x, edit.y, edit.w, edit.h});
            for (int i = 0; i < chips.size(); i++) out.put("chip" + i, chips.get(i).rect());
        } else {
            out.put("search", new int[] {search.x, search.y, search.w, search.h});
            out.put("list", new int[] {bodyX, listTop, bodyW, visibleRows * ROW_H});
        }
        return out;
    }

    private static String controlName(int id) {
        return switch (id) {
            case B_BACK -> "back";
            case B_ALL -> "all";
            case B_MANUAL -> "manual";
            case B_CLEAR -> "clear";
            case B_SAVE -> "save";
            case B_REVERT -> "revert";
            case B_DELETE -> "delete";
            case B_EDIT_BACK -> "editBack";
            case B_VPREV -> "prev";
            case B_VNEXT -> "next";
            default -> "id" + id;
        };
    }

    int[] rect(String name) { return controlRects().get(name); }

    String queryText() { return search.get(); }

    String editText() { return edit.get(); }

    int editCaret() { return edit.caret; }

    int searchCaret() { return search.caret; }

    String originalText() { return original.get(); }

    boolean saveEnabled() { return canSave(); }

    boolean deleteArmed() { return deleteArmedAt >= 0; }

    String messageText() { return currentMessage(); }

    int rowCount() { return rows.size(); }

    int visibleRowCount() { return visibleRows; }

    int selectedRow() { return selected; }

    int firstRow() { return scrollRow; }

    int versionIndex() { return version; }

    boolean searchFocused() { return focus == F_SEARCH; }

    boolean editFocused() { return focus == F_EDIT; }

    boolean manualOnlyOn() { return manualOnly; }

    boolean dirtyNow() { return editing && dirty(); }

    int editLineCount() { return edit.lineCount(); }

    int editVisibleLines() { return edit.visibleLines; }

    int originalVisibleLines() { return original.visibleLines; }

    int shadeOfEdit(int charIndex) {
        edit.relayout();
        return charIndex >= 0 && charIndex < edit.bg.length ? edit.bg[charIndex] : 0;
    }

    int shadeOfOriginal(int charIndex) {
        original.relayout();
        return charIndex >= 0 && charIndex < original.bg.length ? original.bg[charIndex] : 0;
    }

    String statusLine() { return status; }

    String footer() { return footerText; }

    List<String> chipTexts() {
        List<String> out = new ArrayList<>();
        for (Chip c : chips) out.add(c.text());
        return out;
    }

    int hintLineCount() { return hintLines.size(); }

    boolean autoLineShown() { return showAuto; }

    /** The wrapped lines of the edit box, as drawn. */
    List<String> editLines() {
        edit.relayout();
        List<String> out = new ArrayList<>();
        for (int[] l : edit.lines) out.add(edit.text.substring(l[0], l[1]));
        return out;
    }
}
