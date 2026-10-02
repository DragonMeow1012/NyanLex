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
    public static final String KEY_ALL_TITLE = "nyanlex.ui.all.title";
    public static final String KEY_ALL_DESC = "nyanlex.ui.all.desc";
    public static final String KEY_ALL_COL_MODE = "nyanlex.ui.all.col_mode";
    public static final String KEY_ALL_COL_ENGINE = "nyanlex.ui.all.col_engine";
    public static final String KEY_ALL_MIXED = "nyanlex.ui.all.mixed";
    public static final String KEY_MANUAL_TITLE = "nyanlex.manual.title";
    public static final String KEY_MANUAL_BACK = "nyanlex.manual.back";
    public static final String KEY_MANUAL_CARD = "nyanlex.settings.manual";
    /** Sections of the manual screen ({@code nyanlex.manual.s1.title} ... {@code .body}). */
    public static final int MANUAL_SECTIONS = 10;
    public static final String KEY_BTN_CLEAR = "nyanlex.ui.btn.clear";
    public static final String KEY_DONE_SHORT = "nyanlex.ui.done.short";
    public static final String KEY_ABOUT_TITLE = "nyanlex.ui.about.title";
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

    /** The 關於 > 說明書 button (not a catalog entry: it only exists on this card). */
    private static final SettingEntry MANUAL_ENTRY = new SettingEntry("manual", SettingsPage.GENERAL,
            SettingEntry.Type.SUBSCREEN, KEY_MANUAL_CARD, KEY_MANUAL_CARD + ".tip", null, null,
            SettingAction.OPEN_MANUAL, SettingEntry.SideEffect.NONE, false);

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
                KEY_ALL_TITLE, KEY_ALL_DESC, KEY_ALL_COL_MODE, KEY_ALL_COL_ENGINE, KEY_ALL_MIXED,
                KEY_MANUAL_TITLE, KEY_MANUAL_BACK, KEY_MANUAL_CARD, KEY_MANUAL_CARD + ".tip",
                KEY_PRIVACY_TITLE, KEY_PRIVACY_BODY,
                KEY_ABOUT_TITLE, KEY_ABOUT_VERSION, KEY_WARMUP_START,
                KEY_WARMUP_DETAILS, KEY_WARMUP_IDLE, KEY_WARMUP_HUD_RUNNING, KEY_WARMUP_HUD_PAUSED,
                KEY_WARMUP_HUD_DONE, KEY_WARMUP_RESUMED, KEY_SIDEBAR_TITLE, KEY_STAT_PENDING,
                KEY_FILES_GROUP, KEY_FILES_GROUP_DESC));
        keys.addAll(DialogContent.allLangKeys());
        for (int i = 1; i <= MANUAL_SECTIONS; i++) {
            keys.add(manualTitleKey(i));
            keys.add(manualBodyKey(i));
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

    public static String manualTitleKey(int section) { return "nyanlex.manual.s" + section + ".title"; }

    public static String manualBodyKey(int section) { return "nyanlex.manual.s" + section + ".body"; }

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
                nodes.add(new Node(allCard(), null));
                for (SettingsRow row : SettingsCatalog.rows(SettingsPage.DISPLAY)) {
                    nodes.add(new Node(surfaceCard(row), null));
                }
            } else if (category == SettingsCategory.ABOUT) {
                nodes.add(new Node(new SettingCard("about_info", SettingCard.Kind.INFO, category, null,
                        KEY_ABOUT_TITLE, KEY_ABOUT_VERSION, null, null), null));
                nodes.add(new Node(new SettingCard("about_manual", SettingCard.Kind.BUTTON, category,
                        MANUAL_ENTRY, KEY_MANUAL_CARD, KEY_MANUAL_CARD + ".tip", null, null), null));
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
                mode.labelKey(), mode.tipKey(), null, null, row.secondary());
    }

    /** 顯示 > 全部項目: the same two buttons as a surface row, acting on every surface at once. */
    private static SettingCard allCard() {
        return new SettingCard("all_items", SettingCard.Kind.ALL, SettingsCategory.DISPLAY, null,
                KEY_ALL_TITLE, KEY_ALL_DESC, null, null);
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
