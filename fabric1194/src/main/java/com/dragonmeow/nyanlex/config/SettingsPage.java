package com.dragonmeow.nyanlex.config;

/** The six pages of catalog entries behind the settings categories (關於 has none), in display order. Pure data, no Minecraft types. */
public enum SettingsPage {
    GENERAL("general"),
    DISPLAY("display"),
    SERVICE("service"),
    PACK("pack"),
    MINE("mine"),
    ADVANCED("advanced");

    private final String id;

    SettingsPage(String id) { this.id = id; }

    public String id() { return id; }
}
