package com.dragonmeow.nyanlex.config;

/** One card of the settings screen: a title, a description and (usually) one control. */
public final class SettingCard {

    public enum Kind {
        /** On/off switch. */
        TOGGLE,
        /** Drop-down list of named values. */
        DROPDOWN,
        /** Slider over discrete steps. */
        SLIDER,
        /** Button that opens a screen or runs an action. */
        BUTTON,
        /** Warm-up card: status, progress bar and pause/resume/stop buttons. */
        WARMUP,
        /** A file or folder the mod keeps: purpose, full path and an "open" button (Advanced > file locations). */
        FILE,
        /** Text only. */
        INFO,
        /** One display surface: the mode cycle button and the engine (機翻／AI) button on the right. */
        SURFACE,
        /** A highlighted text-only notice (the privacy card on 一般). */
        NOTICE,
        /** A row of quick buttons that set one value on every surface at once. */
        BULK
    }

    /** One quick button of a {@link Kind#BULK} card; {@code active} tells whether the config already matches it. */
    public record BulkButton(String labelKey, java.util.function.Consumer<TranslatorConfig> apply,
                             java.util.function.Predicate<TranslatorConfig> active) {
    }

    private final String id;
    private final Kind kind;
    private final SettingsCategory category;
    private final SettingEntry entry;
    private final String titleKey;
    private final String descKey;
    private final String groupId;
    private final String groupTitleKey;
    private final SettingEntry engineEntry;
    private final java.util.List<BulkButton> buttons;

    SettingCard(String id, Kind kind, SettingsCategory category, SettingEntry entry,
                String titleKey, String descKey, String groupId, String groupTitleKey) {
        this(id, kind, category, entry, titleKey, descKey, groupId, groupTitleKey, null, java.util.List.of());
    }

    SettingCard(String id, Kind kind, SettingsCategory category, SettingEntry entry,
                String titleKey, String descKey, String groupId, String groupTitleKey,
                SettingEntry engineEntry, java.util.List<BulkButton> buttons) {
        this.id = id;
        this.kind = kind;
        this.category = category;
        this.entry = entry;
        this.titleKey = titleKey;
        this.descKey = descKey;
        this.groupId = groupId;
        this.groupTitleKey = groupTitleKey;
        this.engineEntry = engineEntry;
        this.buttons = buttons;
    }

    public String id() { return id; }
    public Kind kind() { return kind; }
    public SettingsCategory category() { return category; }
    /** The catalog entry behind the control; null for {@link Kind#INFO}. */
    public SettingEntry entry() { return entry; }
    /** Lang key of the title; may still carry a trailing state part that {@link #stripState} removes. */
    public String titleKey() { return titleKey; }
    public String descKey() { return descKey; }
    /** Id of the accordion group this card sits in, or null. */
    public String groupId() { return groupId; }
    public String groupTitleKey() { return groupTitleKey; }
    /** {@link Kind#SURFACE}: the engine toggle next to the mode button ({@link #entry()}); else null. */
    public SettingEntry engineEntry() { return engineEntry; }
    /** {@link Kind#BULK}: the quick buttons, in order; else empty. */
    public java.util.List<BulkButton> buttons() { return buttons; }

    /** Removes a trailing "：%s" state part and "…" from a button-style label ("聊天：%s" becomes "聊天"). */
    public static String stripState(String label) {
        String s = label.replaceAll("\\s*[：:]\\s*%s.*$", "").replace("%s", "").trim();
        while (s.endsWith("…") || s.endsWith("...")) {
            s = s.endsWith("…") ? s.substring(0, s.length() - 1) : s.substring(0, s.length() - 3);
            s = s.trim();
        }
        return s;
    }
}
