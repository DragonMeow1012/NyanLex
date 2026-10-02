package com.dragonmeow.nyanlex.config;

/** Left-hand categories of the card-style settings screen, in display order. */
public enum SettingsCategory {
    GENERAL("general", SettingsPage.GENERAL),
    DISPLAY("display", SettingsPage.DISPLAY),
    AI("ai", SettingsPage.AI),
    REQUESTS("requests", SettingsPage.REQUESTS),
    HUB("hub", SettingsPage.HUB),
    ADVANCED("advanced", SettingsPage.ADVANCED),
    ABOUT("about", null);

    private final String id;
    private final SettingsPage page;

    SettingsCategory(String id, SettingsPage page) {
        this.id = id;
        this.page = page;
    }

    public String id() { return id; }

    /** The catalog page whose entries fill this category; null for 關於. */
    public SettingsPage page() { return page; }

    /** Lang key of the full category name. */
    public String nameKey() { return "nyanlex.ui.cat." + id; }

    /** Lang key of the one-or-two character label used when the sidebar is collapsed. */
    public String shortKey() { return "nyanlex.ui.cat." + id + ".short"; }
}
