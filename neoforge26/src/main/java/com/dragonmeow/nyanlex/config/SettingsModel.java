package com.dragonmeow.nyanlex.config;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * The card layout of the settings screen, derived from {@link SettingsCatalog}: which
 * entry becomes which kind of card, which cards share an accordion group, and the
 * search over titles, descriptions, categories and keywords. Pure data and logic.
 */
public final class SettingsModel {

    /** One row of a category: either a lone card or a group of cards. */
    public record Node(SettingCard card, SettingGroup group) {
        public boolean isGroup() { return group != null; }
    }

    public static final String KEY_PRIVACY_TITLE = "nyanlex.ui.privacy.title";
    public static final String KEY_PRIVACY_BODY = "nyanlex.ui.privacy.body";
    public static final String PRIVACY_ID = "privacy";
    public static final String KEY_REQUESTS_OFF = "message.nyanlex.requests_off";
    public static final String KEY_ENGINE_LABEL = "nyanlex.settings.engine";
    public static final String KEY_MODE = "nyanlex.ui.mode";
    public static final String KEY_MODE_TIP = "nyanlex.ui.mode.tip";
    public static final String KEY_SEARCH_HINT = "nyanlex.ui.search.hint";
    public static final String KEY_SEARCH_EMPTY = "nyanlex.ui.search.empty";
    public static final String KEY_BTN_OPEN = "nyanlex.ui.btn.open";
    public static final String KEY_BTN_RUN = "nyanlex.ui.btn.run";
    public static final String KEY_BTN_SETTINGS = "nyanlex.ui.btn.settings";
    public static final String KEY_BTN_EDIT = "nyanlex.ui.btn.edit";
    public static final String KEY_BTN_DETECT = "nyanlex.ui.btn.detect";
    public static final String KEY_BTN_EXPORT = "nyanlex.ui.btn.export";
    public static final String KEY_BTN_IMPORT = "nyanlex.ui.btn.import";
    public static final String KEY_BULK_ENGINE = "nyanlex.ui.bulk.engine";
    public static final String KEY_BULK_ENGINE_DESC = "nyanlex.ui.bulk.engine.desc";
    public static final String KEY_BULK_AI = "nyanlex.ui.bulk.ai";
    public static final String KEY_BULK_MACHINE = "nyanlex.ui.bulk.machine";
    public static final String KEY_BULK_MODE = "nyanlex.ui.bulk.mode";
    public static final String KEY_BULK_MODE_DESC = "nyanlex.ui.bulk.mode.desc";
    public static final String KEY_BULK_TRANSLATION = "nyanlex.ui.bulk.translation";
    public static final String KEY_BULK_BOTH = "nyanlex.ui.bulk.both";
    public static final String KEY_BULK_ORIGINAL = "nyanlex.ui.bulk.original";
    /** Number of help sections on the 關於 page ({@code nyanlex.ui.about.s1.title} ... {@code .body}). */
    public static final int ABOUT_SECTIONS = 11;
    public static final String KEY_BTN_CLEAR = "nyanlex.ui.btn.clear";
    public static final String KEY_DONE_SHORT = "nyanlex.ui.done.short";
    public static final String KEY_ABOUT_TITLE = "nyanlex.ui.about.title";
    public static final String KEY_ABOUT_DESC = "nyanlex.ui.about.desc";
    public static final String KEY_ABOUT_VERSION = "nyanlex.ui.about.version";
    public static final String KEY_WARMUP_START = "nyanlex.ui.warmup.start";
    public static final String KEY_WARMUP_DETAILS = "nyanlex.ui.warmup.details";
    public static final String KEY_WARMUP_IDLE = "nyanlex.ui.warmup.idle";
    public static final String KEY_WARMUP_HUD_RUNNING = "nyanlex.ui.hud.running";
    public static final String KEY_WARMUP_HUD_PAUSED = "nyanlex.ui.hud.paused";
    public static final String KEY_WARMUP_HUD_DONE = "nyanlex.ui.hud.done";
    public static final String KEY_WARMUP_RESUMED = "nyanlex.ui.warmup.resumed";
    public static final String KEY_SIDEBAR_TITLE = "nyanlex.ui.sidebar_title";
    public static final String KEY_STAT_PENDING = "nyanlex.ui.stat.pending";
    public static final String KEY_FILES_GROUP = "nyanlex.ui.files.group";
    public static final String KEY_FILES_GROUP_DESC = "nyanlex.ui.files.group.desc";
    public static final String FILES_GROUP_ID = "files";

    private static final Map<SettingsCategory, List<Node>> NODES = build();

    private SettingsModel() {}

    public static List<SettingsCategory> categories() { return List.of(SettingsCategory.values()); }

    public static List<Node> nodes(SettingsCategory category) { return NODES.get(category); }

    /** Every card in category order (group members in place). */
    public static List<SettingCard> allCards() {
        List<SettingCard> out = new ArrayList<>();
        for (SettingsCategory category : SettingsCategory.values()) out.addAll(cards(category));
        return out;
    }

    public static List<SettingCard> cards(SettingsCategory category) {
        List<SettingCard> out = new ArrayList<>();
        for (Node node : NODES.get(category)) {
            if (node.isGroup()) out.addAll(node.group().cards());
            else out.add(node.card());
        }
        return out;
    }

    public static SettingCard byId(String id) {
        for (SettingCard card : allCards()) if (card.id().equals(id)) return card;
        return null;
    }

    public static SettingGroup groupById(String id) {
        for (SettingsCategory category : SettingsCategory.values()) {
            for (Node node : NODES.get(category)) {
                if (node.isGroup() && node.group().id().equals(id)) return node.group();
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ text

    /** Card title with the state part removed. */
    public static String title(SettingCard card, Function<String, String> lang) {
        return SettingCard.stripState(lang.apply(card.titleKey()));
    }

    /** Description shown under the title. */
    public static String description(SettingCard card, Function<String, String> lang) {
        return card.descKey() == null ? "" : lang.apply(card.descKey());
    }

    public static String groupTitle(SettingGroup group, Function<String, String> lang) {
        return SettingCard.stripState(lang.apply(group.titleKey()));
    }

    // ------------------------------------------------------------------ search

    /**
     * Cards matching {@code query}: every whitespace-separated word must occur (case-insensitive)
     * in the title, description, category name, group title, keywords or id. Blank query: none.
     */
    public static List<SettingCard> search(String query, Function<String, String> lang) {
        List<SettingCard> out = new ArrayList<>();
        String[] words = tokens(query);
        if (words.length == 0) return out;
        for (SettingCard card : allCards()) {
            // the guide and the privacy notice are read, not searched
            if (card.kind() == SettingCard.Kind.INFO || card.kind() == SettingCard.Kind.NOTICE) continue;
            String hay = haystack(card, lang);
            boolean all = true;
            for (String w : words) {
                if (!hay.contains(w)) {
                    all = false;
                    break;
                }
            }
            if (all) out.add(card);
        }
        return out;
    }

    /** Lower-cased search words of {@code query} (empty for a blank query). */
    public static String[] tokens(String query) {
        if (query == null) return new String[0];
        String q = query.trim().toLowerCase(Locale.ROOT);
        return q.isEmpty() ? new String[0] : q.split("\\s+");
    }

    private static String haystack(SettingCard card, Function<String, String> lang) {
        StringBuilder sb = new StringBuilder();
        sb.append(title(card, lang)).append('\n').append(description(card, lang)).append('\n')
                .append(lang.apply(card.category().nameKey())).append('\n').append(card.id()).append('\n');
        if (card.groupTitleKey() != null) {
            sb.append(SettingCard.stripState(lang.apply(card.groupTitleKey()))).append('\n');
        }
        if (card.entry() != null) for (String k : card.entry().keywords()) sb.append(k).append('\n');
        if (card.engineEntry() != null) {
            sb.append(SettingCard.stripState(lang.apply(KEY_ENGINE_LABEL))).append('\n')
                    .append(lang.apply(card.engineEntry().tipKey())).append('\n');
        }
        return sb.toString().toLowerCase(Locale.ROOT);
    }

    // ------------------------------------------------------------------ lang keys

    /** Every lang key the card UI uses beyond {@link SettingsCatalog#allLangKeys()} (for the lang test). */
    public static List<String> allLangKeys() {
        List<String> keys = new ArrayList<>(List.of(KEY_MODE, KEY_MODE_TIP, KEY_SEARCH_HINT,
                KEY_SEARCH_EMPTY, KEY_BTN_OPEN, KEY_BTN_RUN, KEY_BTN_CLEAR, KEY_DONE_SHORT,
                KEY_BTN_SETTINGS, KEY_BTN_EDIT, KEY_BTN_DETECT, KEY_BTN_EXPORT, KEY_BTN_IMPORT,
                KEY_BULK_ENGINE, KEY_BULK_ENGINE_DESC, KEY_BULK_AI, KEY_BULK_MACHINE, KEY_BULK_MODE,
                KEY_BULK_MODE_DESC, KEY_BULK_TRANSLATION, KEY_BULK_BOTH, KEY_BULK_ORIGINAL,
                KEY_PRIVACY_TITLE, KEY_PRIVACY_BODY, KEY_REQUESTS_OFF,
                KEY_ABOUT_TITLE, KEY_ABOUT_DESC, KEY_ABOUT_VERSION, KEY_WARMUP_START,
                KEY_WARMUP_DETAILS, KEY_WARMUP_IDLE, KEY_WARMUP_HUD_RUNNING, KEY_WARMUP_HUD_PAUSED,
                KEY_WARMUP_HUD_DONE, KEY_WARMUP_RESUMED, KEY_SIDEBAR_TITLE, KEY_STAT_PENDING,
                KEY_FILES_GROUP, KEY_FILES_GROUP_DESC));
        for (int i = 1; i <= ABOUT_SECTIONS; i++) {
            keys.add(aboutTitleKey(i));
            keys.add(aboutBodyKey(i));
        }
        for (String id : FileLocations.IDS) {
            keys.add(FileLocations.titleKey(id));
            keys.add(FileLocations.descKey(id));
        }
        for (SettingsCategory c : SettingsCategory.values()) {
            keys.add(c.nameKey());
            keys.add(c.shortKey());
        }
        return keys;
    }

    /**
     * "請先至 設定 → 一般 開啟「送出翻譯請求」": the reminder shown when R, P, the warm-up or a
     * tooltip hint needs requests while the master switch is off. The three names come from
     * the lang file, so a renamed setting follows automatically.
     */
    public static String requestsOffReminder(Function<String, String> lang) {
        String master = SettingCard.stripState(lang.apply(SettingsCatalog.byId("master").labelKey()));
        return String.format(lang.apply(KEY_REQUESTS_OFF), lang.apply(KEY_BTN_SETTINGS),
                lang.apply(SettingsCategory.GENERAL.nameKey()), master);
    }

    public static String aboutTitleKey(int section) { return "nyanlex.ui.about.s" + section + ".title"; }

    public static String aboutBodyKey(int section) { return "nyanlex.ui.about.s" + section + ".body"; }

    // ------------------------------------------------------------------ construction

    private static Map<SettingsCategory, List<Node>> build() {
        Map<SettingsCategory, List<Node>> map = new EnumMap<>(SettingsCategory.class);
        for (SettingsCategory category : SettingsCategory.values()) {
            List<Node> nodes = new ArrayList<>();
            if (category == SettingsCategory.GENERAL) {
                // First thing on the first page, readable without opening anything.
                // It carries the master switch (the very same setting as the 一般 card below).
                nodes.add(new Node(new SettingCard(PRIVACY_ID, SettingCard.Kind.NOTICE, category,
                        SettingsCatalog.byId("master"), KEY_PRIVACY_TITLE, KEY_PRIVACY_BODY, null, null), null));
            }
            if (category == SettingsCategory.DISPLAY) {
                nodes.add(new Node(bulkEngineCard(), null));
                nodes.add(new Node(bulkModeCard(), null));
                for (SettingsRow row : SettingsCatalog.rows(SettingsPage.DISPLAY)) {
                    nodes.add(new Node(surfaceCard(row), null));
                }
            } else if (category == SettingsCategory.ABOUT) {
                nodes.add(new Node(new SettingCard("about_info", SettingCard.Kind.INFO, category, null,
                        KEY_ABOUT_TITLE, KEY_ABOUT_DESC, null, null), null));
                for (int i = 1; i <= ABOUT_SECTIONS; i++) {
                    nodes.add(new Node(new SettingCard("about_s" + i, SettingCard.Kind.INFO, category, null,
                            aboutTitleKey(i), aboutBodyKey(i), null, null), null));
                }
            } else {
                for (SettingEntry entry : SettingsCatalog.entries(category.page())) {
                    nodes.add(new Node(card(entry, category), null));
                }
            }
            if (category == SettingsCategory.ADVANCED) nodes.add(new Node(null, filesGroup()));
            map.put(category, List.copyOf(nodes));
        }
        return map;
    }

    /** Advanced > file locations: one FILE card per entry of {@link FileLocations#IDS}. */
    private static SettingGroup filesGroup() {
        List<SettingCard> cards = new ArrayList<>();
        for (String id : FileLocations.IDS) {
            cards.add(new SettingCard("file." + id, SettingCard.Kind.FILE, SettingsCategory.ADVANCED, null,
                    FileLocations.titleKey(id), FileLocations.descKey(id), FILES_GROUP_ID, KEY_FILES_GROUP));
        }
        return new SettingGroup(FILES_GROUP_ID, SettingsCategory.ADVANCED, KEY_FILES_GROUP,
                KEY_FILES_GROUP_DESC, List.copyOf(cards));
    }

    /** 顯示 > one surface: name, one-line description, mode button and engine button on the right. */
    private static SettingCard surfaceCard(SettingsRow row) {
        SettingEntry mode = row.primary();
        return new SettingCard(mode.id(), SettingCard.Kind.SURFACE, SettingsCategory.DISPLAY, mode,
                mode.labelKey(), mode.tipKey(), null, null, row.secondary(), List.of());
    }

    private static SettingCard bulkEngineCard() {
        return new SettingCard("bulk_engine", SettingCard.Kind.BULK, SettingsCategory.DISPLAY, null,
                KEY_BULK_ENGINE, KEY_BULK_ENGINE_DESC, null, null, null, List.of(
                new SettingCard.BulkButton(KEY_BULK_AI, c -> SettingsCatalog.setAllEngines(c, true),
                        c -> SettingsCatalog.allEnginesAre(c, true)),
                new SettingCard.BulkButton(KEY_BULK_MACHINE, c -> SettingsCatalog.setAllEngines(c, false),
                        c -> SettingsCatalog.allEnginesAre(c, false))));
    }

    private static SettingCard bulkModeCard() {
        return new SettingCard("bulk_mode", SettingCard.Kind.BULK, SettingsCategory.DISPLAY, null,
                KEY_BULK_MODE, KEY_BULK_MODE_DESC, null, null, null, List.of(
                new SettingCard.BulkButton(KEY_BULK_TRANSLATION,
                        c -> SettingsCatalog.setAllModes(c, DisplayMode.TRANSLATION),
                        c -> SettingsCatalog.allModesAre(c, DisplayMode.TRANSLATION)),
                new SettingCard.BulkButton(KEY_BULK_BOTH,
                        c -> SettingsCatalog.setAllModes(c, DisplayMode.BOTH),
                        c -> SettingsCatalog.allModesAre(c, DisplayMode.BOTH)),
                new SettingCard.BulkButton(KEY_BULK_ORIGINAL,
                        c -> SettingsCatalog.setAllModes(c, DisplayMode.ORIGINAL_ONLY),
                        c -> SettingsCatalog.allModesAre(c, DisplayMode.ORIGINAL_ONLY))));
    }

    private static SettingCard card(SettingEntry entry, SettingsCategory category) {
        SettingCard.Kind kind;
        if (entry.action() == SettingAction.OPEN_ITEM_WARMUP) kind = SettingCard.Kind.WARMUP;
        else if (entry.slider() != null) kind = SettingCard.Kind.SLIDER;
        else if (entry.type() == SettingEntry.Type.TOGGLE) kind = SettingCard.Kind.TOGGLE;
        else kind = SettingCard.Kind.BUTTON;
        return new SettingCard(entry.id(), kind, category, entry, entry.labelKey(), entry.tipKey(), null, null);
    }
}
