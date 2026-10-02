package com.dragonmeow.nyanlex.config;

import java.util.Locale;

/** Left-hand categories of the card-style settings screen, in display order. */
public enum SettingsCategory {
    GENERAL("general", SettingsPage.GENERAL),
    DISPLAY("display", SettingsPage.DISPLAY),
    SERVICE("service", SettingsPage.SERVICE),
    PACK("pack", SettingsPage.PACK),
    MINE("mine", SettingsPage.MINE),
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

    /**
     * The category for a remembered id, including ids of categories that no longer exist:
     * the old AI page became 翻譯服務, the old repository page became 翻譯包, and the old
     * request page (cooldown, batching, delivery order) now lives under 進階. Anything
     * unknown falls back to 一般.
     */
    public static SettingsCategory fromId(String id) {
        if (id == null) return GENERAL;
        String key = id.strip().toLowerCase(Locale.ROOT);
        for (SettingsCategory category : values()) {
            if (category.id.equals(key)) return category;
        }
        return switch (key) {
            case "ai" -> SERVICE;
            case "hub" -> PACK;
            case "requests" -> ADVANCED;
            default -> GENERAL;
        };
    }
}
