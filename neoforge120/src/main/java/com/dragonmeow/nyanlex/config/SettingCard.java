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
        /** Text only. */
        INFO
    }

    private final String id;
    private final Kind kind;
    private final SettingsCategory category;
    private final SettingEntry entry;
    private final String titleKey;
    private final String descKey;
    private final String groupId;
    private final String groupTitleKey;

    SettingCard(String id, Kind kind, SettingsCategory category, SettingEntry entry,
                String titleKey, String descKey, String groupId, String groupTitleKey) {
        this.id = id;
        this.kind = kind;
        this.category = category;
        this.entry = entry;
        this.titleKey = titleKey;
        this.descKey = descKey;
        this.groupId = groupId;
        this.groupTitleKey = groupTitleKey;
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
