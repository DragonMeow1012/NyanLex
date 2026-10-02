package com.dragonmeow.nyanlex.config;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Declarative description of the six settings pages (一般／顯示／AI／請求／倉庫／進階).
 * Every entry names its lang keys, its type and how it reads/writes
 * {@link TranslatorConfig}; the Minecraft glue only renders the table. Inverted storage
 * ({@code disableGoogleFallbackForAi}, {@code hubStartupPromptDisabled}) and the master
 * switch semantics live here, so every glue shows the same "on = allowed" wording.
 */
public final class SettingsCatalog {

    public static final String STATE_ON = "nyanlex.settings.state.on";
    public static final String STATE_OFF = "nyanlex.settings.state.off";
    public static final String STATE_ORIGINAL = "nyanlex.settings.state.original";
    public static final String STATE_BOTH = "nyanlex.settings.state.both";
    public static final String STATE_TRANSLATION = "nyanlex.settings.state.translation";
    public static final String STATE_MACHINE = "nyanlex.settings.state.machine";
    public static final String STATE_AI = "nyanlex.settings.state.ai";
    public static final String STATE_ORDERED = "nyanlex.settings.state.ordered";
    public static final String STATE_READY_FIRST = "nyanlex.settings.state.ready_first";
    public static final String UNIT_SECONDS = "nyanlex.settings.unit.seconds";

    /** Lang keys used by the screen frame itself (not tied to one entry). */
    public static final String KEY_TITLE = "nyanlex.settings.frame_title";
    public static final String KEY_HELP_BUTTON = "nyanlex.settings.help_button";
    public static final String KEY_HELP_BUTTON_TIP = "nyanlex.settings.help_button.tip";
    public static final String KEY_DEFAULT_TIP = "nyanlex.settings.default_tip";
    public static final String KEY_TIP_PREFIX = "nyanlex.settings.tip_prefix";
    public static final String KEY_NEEDS_AI = "nyanlex.settings.needs_ai";
    public static final String KEY_CONFIRM_YES = "nyanlex.settings.confirm.yes";
    public static final String KEY_CLEAR_CACHE_CONFIRM_TITLE = "nyanlex.settings.clear_cache.confirm.title";
    public static final String KEY_CLEAR_CACHE_CONFIRM_MESSAGE = "nyanlex.settings.clear_cache.confirm.message";
    public static final String KEY_CLEAR_HUB_CONFIRM_TITLE = "nyanlex.settings.clear_hub.confirm.title";
    public static final String KEY_CLEAR_HUB_CONFIRM_MESSAGE = "nyanlex.settings.clear_hub.confirm.message";

    /** Cooldown values the button cycles through, in ms; 0 = pacing off (a valid value). */
    public static final int[] COOLDOWN_STEPS = {0, 1000, 2000, 4000, 6000, 8000, 10000};
    /** Batch collection windows, in ms; 0 disables batching. */
    public static final int[] BATCH_WINDOW_STEPS = {0, 1000, 2000, 3000, 5000, 8000, 10000};

    private static final Map<SettingsPage, List<SettingsRow>> ROWS = build();

    private SettingsCatalog() {}

    public static List<SettingsPage> pages() { return List.of(SettingsPage.values()); }

    /** Rows of a page as laid out in two columns (display rows pair mode + engine). */
    public static List<SettingsRow> rows(SettingsPage page) { return ROWS.get(page); }

    /** All entries of a page in reading order. */
    public static List<SettingEntry> entries(SettingsPage page) {
        List<SettingEntry> out = new ArrayList<>();
        for (SettingsRow row : ROWS.get(page)) out.addAll(row.entries());
        return out;
    }

    /** Rows for a one-column layout: every entry on its own row. */
    public static List<SettingsRow> singleColumnRows(SettingsPage page) {
        List<SettingsRow> out = new ArrayList<>();
        for (SettingEntry entry : entries(page)) out.add(new SettingsRow(entry));
        return out;
    }

    public static List<SettingEntry> allEntries() {
        List<SettingEntry> out = new ArrayList<>();
        for (SettingsPage page : SettingsPage.values()) out.addAll(entries(page));
        return out;
    }

    public static SettingEntry byId(String id) {
        for (SettingEntry entry : allEntries()) if (entry.id().equals(id)) return entry;
        return null;
    }

    /** Next value above {@code current} in {@code steps}; wraps to the first. Off-list values snap up. */
    public static int nextStep(int[] steps, int current) {
        for (int v : steps) if (v > current) return v;
        return steps[0];
    }

    /** "關" for 0, otherwise "N 秒" (one decimal only when not a whole second). */
    public static StateText millisState(int ms) {
        if (ms <= 0) return StateText.of(STATE_OFF);
        String number = ms % 1000 == 0
                ? Integer.toString(ms / 1000)
                : String.format(Locale.ROOT, "%.1f", ms / 1000f);
        return StateText.of(UNIT_SECONDS, number);
    }

    public static StateText onOff(boolean on) { return StateText.of(on ? STATE_ON : STATE_OFF); }

    public static StateText modeState(DisplayMode mode) {
        return StateText.of(switch (mode) {
            case ORIGINAL_ONLY -> STATE_ORIGINAL;
            case BOTH -> STATE_BOTH;
            case TRANSLATION -> STATE_TRANSLATION;
        });
    }

    public static StateText engineState(boolean ai) { return StateText.of(ai ? STATE_AI : STATE_MACHINE); }

    /** Every lang key the catalog and the screen frame rely on (for the lang-file test). */
    public static List<String> allLangKeys() {
        List<String> keys = new ArrayList<>(List.of(
                KEY_TITLE, KEY_HELP_BUTTON, KEY_HELP_BUTTON_TIP, KEY_DEFAULT_TIP,
                KEY_TIP_PREFIX, KEY_NEEDS_AI, KEY_CONFIRM_YES,
                KEY_CLEAR_CACHE_CONFIRM_TITLE, KEY_CLEAR_CACHE_CONFIRM_MESSAGE,
                KEY_CLEAR_HUB_CONFIRM_TITLE, KEY_CLEAR_HUB_CONFIRM_MESSAGE,
                STATE_ON, STATE_OFF, STATE_ORIGINAL, STATE_BOTH, STATE_TRANSLATION, STATE_MACHINE,
                STATE_AI, STATE_ORDERED, STATE_READY_FIRST, UNIT_SECONDS));
        for (SettingsPage page : SettingsPage.values()) keys.add(page.tabKey());
        for (SettingEntry entry : allEntries()) {
            if (!keys.contains(entry.labelKey())) keys.add(entry.labelKey());
            if (!keys.contains(entry.tipKey())) keys.add(entry.tipKey());
        }
        return keys;
    }

    // ---------------------------------------------------------------- construction

    private static Map<SettingsPage, List<SettingsRow>> build() {
        Map<SettingsPage, List<SettingsRow>> map = new EnumMap<>(SettingsPage.class);

        map.put(SettingsPage.GENERAL, pairs(List.of(
                toggle(SettingsPage.GENERAL, "master", c -> c.translationRequestsEnabled,
                        c -> onOff(c.translationRequestsEnabled),
                        c -> c.translationRequestsEnabled = !c.translationRequestsEnabled,
                        SettingEntry.SideEffect.CLEAR_PENDING)
                        .withKeywords("master", "master switch", "總開關", "总开关", "privacy", "隱私", "隐私"),
                sub(SettingsPage.GENERAL, "language", SettingAction.OPEN_LANGUAGE,
                        SettingsCatalog::languageState),
                sub(SettingsPage.GENERAL, "keybind", SettingAction.OPEN_KEYBINDS, null))));

        List<SettingsRow> display = new ArrayList<>();
        display.add(surface("chat", c -> c.chatMode, (c, m) -> c.chatMode = m,
                c -> c.aiChat, (c, v) -> c.aiChat = v));
        display.add(surface("tooltip", c -> c.tooltipMode, (c, m) -> c.tooltipMode = m,
                c -> c.aiTooltip, (c, v) -> c.aiTooltip = v));
        display.add(surface("scoreboard", c -> c.scoreboardMode, (c, m) -> c.scoreboardMode = m,
                c -> c.aiScoreboard, (c, v) -> c.aiScoreboard = v));
        display.add(surface("name", c -> c.nameMode, (c, m) -> c.nameMode = m,
                c -> c.aiName, (c, v) -> c.aiName = v));
        display.add(surface("bossbar", c -> c.bossBarMode, (c, m) -> c.bossBarMode = m,
                c -> c.aiBossBar, (c, v) -> c.aiBossBar = v));
        display.add(surface("title", c -> c.titleMode, (c, m) -> c.titleMode = m,
                c -> c.aiTitle, (c, v) -> c.aiTitle = v));
        display.add(surface("actionbar", c -> c.actionBarMode, (c, m) -> c.actionBarMode = m,
                c -> c.aiActionBar, (c, v) -> c.aiActionBar = v));
        display.add(surface("book", c -> c.bookMode, (c, m) -> c.bookMode = m,
                c -> c.aiBook, (c, v) -> c.aiBook = v));
        display.add(surface("screen", c -> c.screenTextMode, (c, m) -> c.screenTextMode = m,
                c -> c.aiScreenText, (c, v) -> c.aiScreenText = v));
        map.put(SettingsPage.DISPLAY, List.copyOf(display));

        map.put(SettingsPage.AI, pairs(List.of(
                sub(SettingsPage.AI, "ai", SettingAction.OPEN_AI, null),
                sub(SettingsPage.AI, "provider", SettingAction.OPEN_PROVIDER,
                        c -> StateText.of("screen.nyanlex.provider."
                                + MachineTranslationProvider.fromId(c.machineTranslationProvider).id())),
                // Stored inverted (disableGoogleFallbackForAi); shown as "on = fallback allowed".
                toggle(SettingsPage.AI, "ai_fallback", c -> !c.disableGoogleFallbackForAi,
                        c -> onOff(!c.disableGoogleFallbackForAi),
                        c -> c.disableGoogleFallbackForAi = !c.disableGoogleFallbackForAi,
                        SettingEntry.SideEffect.NONE))));

        map.put(SettingsPage.REQUESTS, pairs(List.of(
                cycle(SettingsPage.REQUESTS, "cooldown",
                        c -> millisState(c.requestCooldownMs),
                        c -> c.requestCooldownMs = nextStep(COOLDOWN_STEPS, c.requestCooldownMs))
                        .withSlider(new SettingEntry.Slider(COOLDOWN_STEPS,
                                c -> c.requestCooldownMs, (c, v) -> c.requestCooldownMs = v))
                        .withKeywords("cooldown", "429", "rate limit", "delay"),
                cycle(SettingsPage.REQUESTS, "batch",
                        c -> millisState(c.batchWindowMs),
                        c -> c.batchWindowMs = nextStep(BATCH_WINDOW_STEPS, c.batchWindowMs))
                        .withSlider(new SettingEntry.Slider(BATCH_WINDOW_STEPS,
                                c -> c.batchWindowMs, (c, v) -> c.batchWindowMs = v))
                        .withKeywords("batch", "window"),
                toggle(SettingsPage.REQUESTS, "chat_delivery", c -> c.deliverChatTranslationsInOrder,
                        c -> StateText.of(c.deliverChatTranslationsInOrder ? STATE_ORDERED : STATE_READY_FIRST),
                        c -> c.deliverChatTranslationsInOrder = !c.deliverChatTranslationsInOrder,
                        SettingEntry.SideEffect.NONE),
                sub(SettingsPage.REQUESTS, "dnt", SettingAction.OPEN_DO_NOT_TRANSLATE, null),
                action(SettingsPage.REQUESTS, "warmup", SettingAction.OPEN_ITEM_WARMUP)
                        .withKeywords("warm", "warmup", "preload", "item"),
                toggle(SettingsPage.REQUESTS, "warmup_hud", c -> c.itemWarmupHud,
                        c -> onOff(c.itemWarmupHud),
                        c -> c.itemWarmupHud = !c.itemWarmupHud,
                        SettingEntry.SideEffect.NONE))));

        map.put(SettingsPage.HUB, pairs(List.of(
                // Stored inverted (hubStartupPromptDisabled); shown as "on = check at startup".
                toggle(SettingsPage.HUB, "startup", c -> !c.hubStartupPromptDisabled,
                        c -> onOff(!c.hubStartupPromptDisabled),
                        c -> c.hubStartupPromptDisabled = !c.hubStartupPromptDisabled,
                        SettingEntry.SideEffect.NONE),
                action(SettingsPage.HUB, "download", SettingAction.HUB_DOWNLOAD),
                action(SettingsPage.HUB, "open_repo", SettingAction.HUB_OPEN_REPO),
                action(SettingsPage.HUB, "clear_hub", SettingAction.HUB_CLEAR))));

        map.put(SettingsPage.ADVANCED, pairs(List.of(
                action(SettingsPage.ADVANCED, "export", SettingAction.EXPORT_TRANSLATIONS),
                action(SettingsPage.ADVANCED, "import", SettingAction.IMPORT_TRANSLATIONS),
                action(SettingsPage.ADVANCED, "clear_cache", SettingAction.CLEAR_CACHE),
                toggle(SettingsPage.ADVANCED, "debug", c -> c.debugTranslationOverlay,
                        c -> onOff(c.debugTranslationOverlay),
                        c -> c.debugTranslationOverlay = !c.debugTranslationOverlay,
                        SettingEntry.SideEffect.CLEAR_DEBUG_LOG_WHEN_OFF))));

        return map;
    }

    private static StateText languageState(TranslatorConfig c) {
        return c.followGameLanguage
                ? StateText.of("config.nyanlex.language.follow", c.targetLang)
                : StateText.literal(c.targetLang);
    }

    private static List<SettingsRow> pairs(List<SettingEntry> entries) {
        List<SettingsRow> rows = new ArrayList<>();
        for (int i = 0; i < entries.size(); i += 2) {
            rows.add(i + 1 < entries.size()
                    ? new SettingsRow(entries.get(i), entries.get(i + 1))
                    : new SettingsRow(entries.get(i)));
        }
        return List.copyOf(rows);
    }

    /** Drop-down order of the display modes: 譯文, 雙語, 原文. */
    private static final DisplayMode[] MODE_ORDER =
            {DisplayMode.TRANSLATION, DisplayMode.BOTH, DisplayMode.ORIGINAL_ONLY};

    private static int modeOrder(DisplayMode mode) {
        for (int i = 0; i < MODE_ORDER.length; i++) if (MODE_ORDER[i] == mode) return i;
        return 0;
    }

    /** Button cycle of the settings screen: 原文 → 雙語 → 譯文 → 原文. */
    static DisplayMode nextUiMode(DisplayMode mode) {
        return switch (mode) {
            case ORIGINAL_ONLY -> DisplayMode.BOTH;
            case BOTH -> DisplayMode.TRANSLATION;
            case TRANSLATION -> DisplayMode.ORIGINAL_ONLY;
        };
    }

    /** Engine (機翻／AI) of every display surface, in display order. */
    public static boolean allEnginesAre(TranslatorConfig c, boolean ai) {
        for (SettingsRow row : ROWS.get(SettingsPage.DISPLAY)) {
            if (row.secondary().isOn(c) != ai) return false;
        }
        return true;
    }

    /** Sets the engine of every display surface at once. */
    public static void setAllEngines(TranslatorConfig c, boolean ai) {
        for (SettingsRow row : ROWS.get(SettingsPage.DISPLAY)) {
            SettingEntry engine = row.secondary();
            if (engine.isOn(c) != ai) engine.press(c);
        }
    }

    /** Whether every display surface shows {@code mode}. */
    public static boolean allModesAre(TranslatorConfig c, DisplayMode mode) {
        int index = modeOrder(mode);
        for (SettingsRow row : ROWS.get(SettingsPage.DISPLAY)) {
            if (row.primary().options().index().applyAsInt(c) != index) return false;
        }
        return true;
    }

    /** Sets the display mode of every surface at once. */
    public static void setAllModes(TranslatorConfig c, DisplayMode mode) {
        int index = modeOrder(mode);
        for (SettingsRow row : ROWS.get(SettingsPage.DISPLAY)) {
            row.primary().options().select().accept(c, index);
        }
    }

    private static String label(String id) { return "nyanlex.settings." + id; }
    private static String tip(String id) { return "nyanlex.settings." + id + ".tip"; }

    private static SettingEntry toggle(SettingsPage page, String id,
                                       Predicate<TranslatorConfig> on,
                                       Function<TranslatorConfig, StateText> state,
                                       Consumer<TranslatorConfig> press,
                                       SettingEntry.SideEffect effect) {
        return new SettingEntry(id, page, SettingEntry.Type.TOGGLE, label(id), tip(id),
                state, press, null, effect, false).withOn(on);
    }

    private static SettingEntry cycle(SettingsPage page, String id,
                                      Function<TranslatorConfig, StateText> state,
                                      Consumer<TranslatorConfig> press) {
        return new SettingEntry(id, page, SettingEntry.Type.CYCLE, label(id), tip(id),
                state, press, null, SettingEntry.SideEffect.NONE, false);
    }

    private static SettingEntry sub(SettingsPage page, String id, SettingAction action,
                                    Function<TranslatorConfig, StateText> state) {
        return new SettingEntry(id, page, SettingEntry.Type.SUBSCREEN, label(id), tip(id),
                state, null, action, SettingEntry.SideEffect.NONE, false);
    }

    private static SettingEntry action(SettingsPage page, String id, SettingAction action) {
        return new SettingEntry(id, page, SettingEntry.Type.ACTION, label(id), tip(id),
                null, null, action, SettingEntry.SideEffect.NONE, false);
    }

    /**
     * A display row: the mode cycle button plus its engine (機翻／AI) toggle on the right.
     * The engine entry has its own per-surface label ("聊天引擎：%s") so that in the one-column
     * layout (where it is not compact) the nine engine buttons stay distinguishable.
     */
    private static SettingsRow surface(String id,
                                       Function<TranslatorConfig, DisplayMode> getMode,
                                       java.util.function.BiConsumer<TranslatorConfig, DisplayMode> setMode,
                                       java.util.function.Predicate<TranslatorConfig> getAi,
                                       java.util.function.BiConsumer<TranslatorConfig, Boolean> setAi) {
        SettingEntry mode = new SettingEntry(id, SettingsPage.DISPLAY, SettingEntry.Type.CYCLE,
                label(id), tip(id), c -> modeState(getMode.apply(c)),
                c -> setMode.accept(c, nextUiMode(getMode.apply(c))),
                null, SettingEntry.SideEffect.NONE, false)
                .withOptions(new SettingEntry.Options(
                        List.of(modeState(DisplayMode.TRANSLATION), modeState(DisplayMode.BOTH),
                                modeState(DisplayMode.ORIGINAL_ONLY)),
                        c -> modeOrder(getMode.apply(c)),
                        (c, i) -> setMode.accept(c, MODE_ORDER[Math.max(0, Math.min(2, i))])));
        SettingEntry engine = new SettingEntry(id + ".engine", SettingsPage.DISPLAY,
                SettingEntry.Type.TOGGLE, label(id + ".engine"), tip("engine"),
                c -> engineState(getAi.test(c)),
                c -> setAi.accept(c, !getAi.test(c)),
                null, SettingEntry.SideEffect.NONE, true).withOn(getAi::test);
        return new SettingsRow(mode, engine);
    }
}
