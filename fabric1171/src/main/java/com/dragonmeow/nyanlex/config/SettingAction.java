package com.dragonmeow.nyanlex.config;

/**
 * What a {@link SettingEntry.Type#SUBSCREEN} / {@link SettingEntry.Type#ACTION} entry does.
 * The glue layer of each Minecraft version maps these ids to its own screens.
 */
public enum SettingAction {
    OPEN_LANGUAGE,
    OPEN_KEYBINDS,
    OPEN_AI,
    OPEN_PROVIDER,
    OPEN_DO_NOT_TRANSLATE,
    /** Opens the "warm every item" screen; the glue reports it unavailable until wired. */
    OPEN_ITEM_WARMUP,
    HUB_DOWNLOAD,
    HUB_OPEN_REPO,
    /** Destructive: glue must confirm first (with the entry count). */
    HUB_CLEAR,
    EXPORT_TRANSLATIONS,
    IMPORT_TRANSLATIONS,
    /** Destructive: glue must confirm first (with the entry count). */
    CLEAR_CACHE
}
