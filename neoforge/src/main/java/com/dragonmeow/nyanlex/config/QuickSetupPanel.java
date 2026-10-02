package com.dragonmeow.nyanlex.config;

import java.util.ArrayList;
import java.util.List;

/**
 * 快速設定: the questionnaire (welcome, language, how to translate, how to show, translation
 * packs when some exist, done), drawn on a {@link DialogPanel} without any Minecraft type.
 *
 * <p>Nothing is applied until 完成: every answer is only remembered here, the configuration
 * is not touched and nothing is sent. 稍後再設定 and Escape change nothing except marking the
 * questionnaire as answered. The way-to-translate page starts with no option chosen (it is the
 * consent page); choosing AI opens the AI settings and only moves on when they are complete.</p>
 */
public final class QuickSetupPanel {

    public enum Page { WELCOME, LANGUAGE, METHOD, DISPLAY, PACKS, DONE }

    public enum Method { MACHINE, AI, NONE }

    public enum PackState { IDLE, DETECTING, FOUND, NONE, FAILED }

    public enum KeyAction { TRANSLATE_ITEM, TRANSLATE_SCREEN, TOGGLE }

    /** A found translation pack list: how many mods, the preformatted total size and one text line per mod. */
    public record PackInfo(int count, String totalSize, List<String> lines) {}

    /** Everything the questionnaire needs from the game. */
    public interface Host {
        TranslatorConfig config();

        void saveConfig();

        String text(String key, Object... args);

        int textWidth(String text);

        /** An API key or a ChatGPT sign-in is set up and usable. */
        boolean aiConfigured();

        /** Opens the AI settings with this questionnaire as the screen to return to. */
        void openAiSettings();

        /** Opens the language list; the answer comes back through {@link QuickSetupPanel#languageChosen}. */
        void openLanguagePicker(boolean followGame, String tag);

        /** Opens the key binding screen; {@link QuickSetupPanel#refresh} is called when it closes. */
        void openKeybinds();

        /** Name of the current game language, e.g. "繁體中文". */
        String gameLanguageName();

        /** Name of the language with this tag. */
        String languageName(String tag);

        /** The key bound to the action ("R"), or the lang text for "not set". */
        String keyName(KeyAction action);

        /** Makes the translation target follow the game language ({@code followGame}) or {@code tag}. */
        void applyLanguage(boolean followGame, String tag);

        /** Online translation has just been switched on or off by 完成. */
        default void onlineChanged() { }

        /** Starts looking for translation packs in the background (only ever downloads a public index). */
        void startPackDetection();

        PackState packState();

        PackInfo packInfo();

        boolean packDownloading();

        /** Starts downloading the found packs in the background (no player text is sent). */
        void startPackDownload();

        /** The screen is done: close it. */
        void close();

        long nowMs();
    }

    static final int ID_LATER = 1;
    static final int ID_START = 2;
    static final int ID_BACK = 3;
    static final int ID_NEXT = 4;
    static final int ID_DONE = 5;
    static final int ID_LANG_FOLLOW = 10;
    static final int ID_LANG_OTHER = 11;
    static final int ID_METHOD_MACHINE = 20;
    static final int ID_METHOD_AI = 21;
    static final int ID_METHOD_NONE = 22;
    /** The display grid: row r (0 is 全部項目) has its mode button at base + 2r and its engine button next to it. */
    static final int ID_GRID_BASE = 100;
    static final int ID_PACK_SKIP = 50;
    static final int ID_PACK_DOWNLOAD = 51;
    static final int ID_REBIND = 60;

    /** How long the questionnaire waits for the pack check before it treats it as "nothing found". */
    static final long PACK_WAIT_MS = 4000L;

    private static final int C_MUTED = 0xFFA4A9B8;

    private final Host host;
    private final DialogPanel dialog;
    private Page page = Page.WELCOME;
    private Method method;
    private boolean followGame;
    private String tag;
    /** The way to translate the page was opened with (null on a first start), to tell whether the player changed it. */
    private Method startMethod;
    /** What 完成 will apply to the display grid: the current settings, edited here; the real configuration is not touched. */
    private TranslatorConfig draft;
    private boolean packSkipped;
    private boolean packWaiting;
    private long packWaitStart;
    private boolean packsShown;
    private boolean finished;
    private boolean downloadStarted;
    private boolean shownDownloading;

    public QuickSetupPanel(Host host) {
        this.host = host;
        this.dialog = new DialogPanel(host::textWidth);
        this.dialog.setNarration(DialogContent.narration(host::text));
        prefill();
        rebuild(true);
    }

    /** True while the first-start questionnaire is due: never answered and 線上翻譯 still off. */
    public static boolean firstStartDue(TranslatorConfig cfg) {
        return cfg != null && !cfg.firstRunDone && !cfg.translationRequestsEnabled;
    }

    // ------------------------------------------------------------------ state

    private void prefill() {
        TranslatorConfig cfg = host.config();
        followGame = cfg.followGameLanguage;
        tag = cfg.targetLang;
        method = null;
        if (cfg.translationRequestsEnabled) {
            Boolean ai = SettingsCatalog.commonEngine(cfg);
            method = ai != null && ai ? Method.AI : Method.MACHINE;
        }
        startMethod = method;
        draft = cfg.copy();
    }

    public Page page() { return page; }

    /** The chosen way to translate, or null while the question is unanswered. */
    public Method method() { return method; }

    public boolean followGame() { return followGame; }

    public String tag() { return tag; }

    /** The display choices made so far (a copy of the settings with the questionnaire's edits). */
    public TranslatorConfig draft() { return draft; }

    public boolean isFinished() { return finished; }

    // ------------------------------------------------------------------ navigation

    private void go(Page next) {
        page = next;
        packWaiting = false;
        rebuild(true);
    }

    private void back() {
        switch (page) {
            case LANGUAGE -> go(Page.WELCOME);
            case METHOD -> go(Page.LANGUAGE);
            case DISPLAY -> go(Page.METHOD);
            case PACKS -> go(Page.DISPLAY);
            case DONE -> go(packsShown && !downloadStarted ? Page.PACKS : Page.DISPLAY);
            default -> { }
        }
    }

    private void next() {
        switch (page) {
            case LANGUAGE -> go(Page.METHOD);
            case METHOD -> {
                if (method != null) enterDisplay();
            }
            case DISPLAY -> afterDisplay();
            case PACKS -> go(Page.DONE);
            default -> { }
        }
    }

    /**
     * On to the display page. The translation service of every row follows the way chosen on the
     * page before (AI for AI, machine for machine or 先不要); when the player did not change that
     * choice (re-entering from the settings), the rows keep what they have.
     */
    private void enterDisplay() {
        if (method != startMethod) {
            SettingsCatalog.setAllEngines(draft, method == Method.AI);
            startMethod = method;
        }
        go(Page.DISPLAY);
    }

    private void afterDisplay() {
        PackState state = host.packState();
        packsShown = false;
        if (state == PackState.FOUND && !downloadStarted) {
            packsShown = true;
            packSkipped = false;
            go(Page.PACKS);
        } else if (state == PackState.DETECTING && !downloadStarted) {
            packWaiting = true;
            packWaitStart = host.nowMs();
            page = Page.PACKS;
            rebuild(true);
        } else {
            go(Page.DONE);
        }
    }

    /** Called every frame: moves on from the "checking your mods" wait when the answer or the time limit arrives. */
    public void tick() {
        if (page == Page.PACKS && packWaiting) {
            PackState state = host.packState();
            if (state == PackState.FOUND) {
                packWaiting = false;
                packsShown = true;
                packSkipped = false;
                rebuild(true);
            } else if (state != PackState.DETECTING || host.nowMs() - packWaitStart > PACK_WAIT_MS) {
                go(Page.DONE);
            }
        }
        if (page == Page.DONE && downloadStarted && host.packDownloading() != shownDownloading) rebuild(false);
    }

    // ------------------------------------------------------------------ results from other screens

    /** The AI settings were closed: on to the next question when they are complete, otherwise back to the unanswered question. */
    public void aiSettingsClosed() {
        if (page != Page.METHOD) return;
        if (host.aiConfigured()) {
            method = Method.AI;
            enterDisplay();
        } else {
            method = null;
            rebuild(false);
        }
    }

    /** The language list returned: {@code follow} the game language, or the language with {@code newTag}. */
    public void languageChosen(boolean follow, String newTag) {
        followGame = follow;
        if (!follow && newTag != null) tag = newTag;
        rebuild(false);
    }

    /** A screen that was opened from here closed: re-read what it may have changed (key names). */
    public void refresh() { rebuild(false); }

    // ------------------------------------------------------------------ apply

    /** 稍後再設定 / Escape: nothing is applied; the questionnaire counts as answered and will not open by itself again. */
    private void later() {
        host.config().firstRunDone = true;
        host.saveConfig();
        finished = true;
        host.close();
    }

    /** 完成: only now are the answers applied. */
    private void finish() {
        TranslatorConfig cfg = host.config();
        boolean wasOnline = cfg.translationRequestsEnabled;
        host.applyLanguage(followGame, tag);
        if (method == Method.MACHINE || method == Method.AI) cfg.translationRequestsEnabled = true;
        else if (method == Method.NONE) cfg.translationRequestsEnabled = false;
        for (SettingsRow row : SettingsCatalog.rows(SettingsPage.DISPLAY)) {
            SettingEntry mode = row.primary();
            SettingEntry engine = row.secondary();
            mode.options().select().accept(cfg, mode.options().index().applyAsInt(draft));
            // 先不要 sends nothing, so it leaves the services as they are
            if (method != Method.NONE && engine.isOn(cfg) != engine.isOn(draft)) engine.press(cfg);
        }
        cfg.firstRunDone = true;
        host.saveConfig();
        if (wasOnline != cfg.translationRequestsEnabled) host.onlineChanged();
        finished = true;
        host.close();
    }

    // ------------------------------------------------------------------ input

    public void resize(int w, int h) { dialog.resize(w, h); }

    public void render(UiCanvas c, int mx, int my) {
        tick();
        dialog.render(c, mx, my);
    }

    public void mouseClicked(int mx, int my, int button) { handle(dialog.mouseClicked(mx, my, button)); }

    public void mouseDragged(int mx, int my) { dialog.mouseDragged(mx, my); }

    public void mouseReleased() { dialog.mouseReleased(); }

    public void mouseScrolled(int mx, int my, double dy) { dialog.mouseScrolled(mx, my, dy); }

    public void keyPressed(int key, boolean shift) { handle(dialog.keyPressed(key, shift)); }

    public String narration() { return dialog.narration(); }

    public boolean consumeNarrationRequest() { return dialog.consumeNarrationRequest(); }

    private void handle(int id) {
        switch (id) {
            case ID_LATER -> later();
            case ID_START -> {
                host.startPackDetection();
                go(Page.LANGUAGE);
            }
            case ID_BACK -> back();
            case ID_NEXT -> next();
            case ID_DONE -> finish();
            case ID_LANG_FOLLOW -> {
                followGame = true;
                rebuild(false);
            }
            case ID_LANG_OTHER -> host.openLanguagePicker(followGame, tag);
            case ID_METHOD_MACHINE -> {
                method = Method.MACHINE;
                rebuild(false);
            }
            case ID_METHOD_NONE -> {
                method = Method.NONE;
                rebuild(false);
            }
            case ID_METHOD_AI -> host.openAiSettings();
            case ID_PACK_SKIP -> {
                packSkipped = true;
                rebuild(false);
            }
            case ID_PACK_DOWNLOAD -> {
                downloadStarted = true;
                host.startPackDownload();
                go(Page.DONE);
            }
            case ID_REBIND -> host.openKeybinds();
            default -> {
                if (id >= ID_GRID_BASE) pressGrid(id - ID_GRID_BASE);
            }
        }
    }

    /** A button of the display grid was pressed: {@code cell / 2} is the row, an odd {@code cell} the engine button. */
    private void pressGrid(int cell) {
        int row = cell / 2;
        boolean engine = cell % 2 == 1;
        if (row == 0) {
            if (engine) SettingsCatalog.toggleAllEngines(draft);
            else SettingsCatalog.cycleAllModes(draft);
        } else {
            List<SettingsRow> rows = SettingsCatalog.rows(SettingsPage.DISPLAY);
            if (row - 1 >= rows.size()) return;
            SettingsRow r = rows.get(row - 1);
            if (engine) r.secondary().press(draft);
            else r.primary().press(draft);
        }
        rebuild(false);
    }

    // ------------------------------------------------------------------ content

    private void rebuild(boolean newPage) {
        DialogPanel.Content content = content();
        if (newPage) dialog.set(content);
        else dialog.update(content);
    }

    private String t(String key, Object... args) { return host.text(key, args); }

    private int totalSteps() {
        return 4 + (packsShown || host.packState() == PackState.FOUND ? 1 : 0);
    }

    private DialogPanel.Content content() {
        List<DialogPanel.Block> blocks = new ArrayList<>();
        String title = t("nyanlex.setup.title");
        int step = 0;
        DialogPanel.Footer footer;
        int steps = 0;
        switch (page) {
            case WELCOME -> {
                title = t("nyanlex.setup.welcome.title");
                blocks.add(new DialogPanel.Text(t("nyanlex.setup.welcome.body"), 0));
                blocks.add(new DialogPanel.Text(t("nyanlex.setup.welcome.note"), C_MUTED));
                footer = DialogPanel.Footer.of(new DialogPanel.Btn(ID_LATER, t("nyanlex.setup.later")),
                        new DialogPanel.Btn(ID_START, t("nyanlex.setup.start"), true));
            }
            case LANGUAGE -> {
                steps = totalSteps();
                step = 1;
                blocks.add(new DialogPanel.Text(t("nyanlex.setup.lang.title"), DialogPanel.C_TITLE));
                blocks.add(new DialogPanel.Choice(ID_LANG_FOLLOW,
                        t("nyanlex.setup.lang.follow", host.gameLanguageName()),
                        t("nyanlex.setup.lang.follow.desc"), followGame));
                String other = followGame ? t("nyanlex.setup.lang.other.desc")
                        : t(SettingsModel.KEY_CURRENT, host.languageName(tag));
                blocks.add(new DialogPanel.Choice(ID_LANG_OTHER, t("nyanlex.setup.lang.other"), other, !followGame));
                footer = navFooter(true);
            }
            case METHOD -> {
                steps = totalSteps();
                step = 2;
                blocks.add(new DialogPanel.Text(t("nyanlex.setup.method.title"), DialogPanel.C_TITLE));
                blocks.add(new DialogPanel.Choice(ID_METHOD_MACHINE, t("nyanlex.setup.method.machine"),
                        t("nyanlex.setup.method.machine.desc", host.keyName(KeyAction.TRANSLATE_ITEM),
                                host.keyName(KeyAction.TRANSLATE_SCREEN)), method == Method.MACHINE));
                blocks.add(new DialogPanel.Choice(ID_METHOD_AI, t("nyanlex.setup.method.ai"),
                        t("nyanlex.setup.method.ai.desc"), method == Method.AI));
                blocks.add(new DialogPanel.Choice(ID_METHOD_NONE, t("nyanlex.setup.method.none"),
                        t("nyanlex.setup.method.none.desc"), method == Method.NONE));
                blocks.add(new DialogPanel.Text(t("nyanlex.setup.method.note"), C_MUTED));
                footer = navFooter(method != null);
            }
            case DISPLAY -> {
                steps = totalSteps();
                step = 3;
                blocks.add(new DialogPanel.Text(t("nyanlex.setup.display.title"), DialogPanel.C_TITLE));
                blocks.add(displayGrid());
                footer = navFooter(true);
            }
            case PACKS -> {
                steps = totalSteps();
                step = 4;
                if (packWaiting) {
                    blocks.add(new DialogPanel.Text(t("nyanlex.setup.packs.checking"), DialogPanel.C_TITLE));
                    footer = DialogPanel.Footer.of(new DialogPanel.Btn(ID_BACK, t("nyanlex.setup.back")), null);
                } else {
                    PackInfo info = host.packInfo();
                    blocks.add(new DialogPanel.Text(t("nyanlex.setup.packs.title", info.count(), info.totalSize()),
                            DialogPanel.C_TITLE));
                    blocks.add(new DialogPanel.ListBox(info.lines(), 5));
                    if (packSkipped) {
                        blocks.add(new DialogPanel.Text(t("nyanlex.setup.packs.skipped"), 0));
                        blocks.add(new DialogPanel.Text(t("nyanlex.setup.packs.note"), C_MUTED));
                        footer = navFooter(true);
                    } else {
                        blocks.add(new DialogPanel.Text(t("nyanlex.setup.packs.note"), C_MUTED));
                        footer = DialogPanel.Footer.of(new DialogPanel.Btn(ID_PACK_SKIP, t("nyanlex.setup.packs.skip")),
                                new DialogPanel.Btn(ID_PACK_DOWNLOAD, t("nyanlex.setup.packs.download"), true));
                    }
                }
            }
            default -> {
                title = t("nyanlex.setup.done.title");
                steps = totalSteps();
                step = steps;
                blocks.add(new DialogPanel.Text(t("nyanlex.setup.done.keys"), DialogPanel.C_TITLE));
                blocks.add(new DialogPanel.KeyLine(host.keyName(KeyAction.TRANSLATE_ITEM), t("nyanlex.setup.done.key.item")));
                blocks.add(new DialogPanel.KeyLine(host.keyName(KeyAction.TRANSLATE_SCREEN), t("nyanlex.setup.done.key.screen")));
                blocks.add(new DialogPanel.KeyLine(host.keyName(KeyAction.TOGGLE), t("nyanlex.setup.done.key.toggle")));
                blocks.add(new DialogPanel.Text(t("nyanlex.setup.done.rebind"), 0));
                blocks.add(new DialogPanel.Row(List.of(new DialogPanel.Btn(ID_REBIND, t("nyanlex.setup.done.rebind_btn")))));
                blocks.add(new DialogPanel.Text(t("nyanlex.setup.done.menu"), 0));
                shownDownloading = downloadStarted && host.packDownloading();
                if (shownDownloading) {
                    blocks.add(new DialogPanel.Text(t("nyanlex.setup.done.downloading"), DialogPanel.C_TITLE));
                }
                blocks.add(new DialogPanel.Text(t("nyanlex.setup.done.note"), C_MUTED));
                footer = DialogPanel.Footer.of(new DialogPanel.Btn(ID_BACK, t("nyanlex.setup.back")),
                        new DialogPanel.Btn(ID_DONE, t("nyanlex.setup.done"), true));
            }
        }
        return new DialogPanel.Content(title, steps, step, blocks, footer, ID_LATER);
    }

    /** The same rows as the settings screen's 顯示 category: 全部項目, then one row per surface. */
    private DialogPanel.Grid displayGrid() {
        List<String> modeLabels = new ArrayList<>();
        for (DisplayMode m : DisplayMode.values()) modeLabels.add(t(SettingsCatalog.modeState(m).key()));
        List<String> engineLabels = List.of(t(SettingsCatalog.engineState(true).key()),
                t(SettingsCatalog.engineState(false).key()));
        String mixed = t(SettingsModel.KEY_ALL_MIXED);
        List<DialogPanel.GridRow> rows = new ArrayList<>();
        DisplayMode commonMode = SettingsCatalog.commonMode(draft);
        Boolean commonAi = SettingsCatalog.commonEngine(draft);
        rows.add(new DialogPanel.GridRow(t(SettingsModel.KEY_ALL_TITLE),
                new DialogPanel.Cell(ID_GRID_BASE, commonMode == null ? mixed : t(SettingsCatalog.modeState(commonMode).key()),
                        commonMode == null ? SettingsPanel.C_WARN : SettingsPanel.modeColor(modeOrder(commonMode)), false),
                new DialogPanel.Cell(ID_GRID_BASE + 1,
                        commonAi == null ? mixed : t(SettingsCatalog.engineState(commonAi).key()),
                        commonAi == null ? SettingsPanel.C_WARN : SettingsPanel.C_TITLE, commonAi != null && commonAi)));
        List<SettingsRow> surfaces = SettingsCatalog.rows(SettingsPage.DISPLAY);
        int chatRow = -1;
        for (int i = 0; i < surfaces.size(); i++) {
            SettingsRow r = surfaces.get(i);
            SettingEntry mode = r.primary();
            SettingEntry engine = r.secondary();
            if (mode.id().equals("chat")) chatRow = i + 1;
            int idx = Math.max(0, Math.min(mode.options().labels().size() - 1, mode.options().index().applyAsInt(draft)));
            boolean ai = engine.isOn(draft);
            int base = ID_GRID_BASE + 2 * (i + 1);
            rows.add(new DialogPanel.GridRow(SettingCard.stripState(t(mode.labelKey())),
                    new DialogPanel.Cell(base, t(mode.options().labels().get(idx).key()), SettingsPanel.modeColor(idx), false),
                    new DialogPanel.Cell(base + 1, t(SettingsCatalog.engineState(ai).key()), SettingsPanel.C_TITLE, ai)));
        }
        return new DialogPanel.Grid(t(SettingsModel.KEY_ALL_COL_MODE), t(SettingsModel.KEY_ALL_COL_ENGINE), modeLabels,
                engineLabels, mixed, rows, chatRow, t("nyanlex.setup.display.chat.note"));
    }

    /** Position of a mode in the settings buttons: 譯文, 雙語, 不翻譯. */
    private static int modeOrder(DisplayMode mode) {
        return mode == DisplayMode.TRANSLATION ? 0 : mode == DisplayMode.BOTH ? 1 : 2;
    }

    private DialogPanel.Footer navFooter(boolean nextEnabled) {
        return DialogPanel.Footer.of(new DialogPanel.Btn(ID_BACK, t("nyanlex.setup.back")),
                new DialogPanel.Btn(ID_NEXT, t("nyanlex.setup.next"), true).withEnabled(nextEnabled));
    }

    /** Every lang key the questionnaire uses (for the lang-file test). */
    public static List<String> allLangKeys() {
        return List.of("nyanlex.setup.title", "nyanlex.setup.later", "nyanlex.setup.start", "nyanlex.setup.back",
                "nyanlex.setup.next", "nyanlex.setup.done",
                "nyanlex.setup.welcome.title", "nyanlex.setup.welcome.body", "nyanlex.setup.welcome.note",
                "nyanlex.setup.lang.title", "nyanlex.setup.lang.follow", "nyanlex.setup.lang.follow.desc",
                "nyanlex.setup.lang.other", "nyanlex.setup.lang.other.desc",
                "nyanlex.setup.method.title", "nyanlex.setup.method.machine", "nyanlex.setup.method.machine.desc",
                "nyanlex.setup.method.ai", "nyanlex.setup.method.ai.desc",
                "nyanlex.setup.method.none", "nyanlex.setup.method.none.desc", "nyanlex.setup.method.note",
                "nyanlex.setup.display.title", "nyanlex.setup.display.chat.note",
                "nyanlex.setup.packs.checking", "nyanlex.setup.packs.title", "nyanlex.setup.packs.skip",
                "nyanlex.setup.packs.download", "nyanlex.setup.packs.skipped", "nyanlex.setup.packs.note",
                "nyanlex.setup.done.title", "nyanlex.setup.done.keys", "nyanlex.setup.done.key.item",
                "nyanlex.setup.done.key.screen", "nyanlex.setup.done.key.toggle", "nyanlex.setup.done.rebind",
                "nyanlex.setup.done.rebind_btn", "nyanlex.setup.done.menu", "nyanlex.setup.done.downloading",
                "nyanlex.setup.done.note", "nyanlex.setup.key.unset");
    }

    // ------------------------------------------------------------------ test hooks

    DialogPanel dialog() { return dialog; }
}
