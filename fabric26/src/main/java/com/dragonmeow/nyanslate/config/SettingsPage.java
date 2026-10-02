package com.dragonmeow.nyanslate.config;

/** The six tabs of the settings screen, in display order. Pure data, no Minecraft types. */
public enum SettingsPage {
    GENERAL("general"),
    DISPLAY("display"),
    AI("ai"),
    REQUESTS("requests"),
    HUB("hub"),
    ADVANCED("advanced");

    private final String id;

    SettingsPage(String id) { this.id = id; }

    public String id() { return id; }

    /** Lang key of the tab label. */
    public String tabKey() { return "nyanslate.settings.tab." + id; }
}
