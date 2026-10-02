package com.dragonmeow.nyanlex.config;

/**
 * What a {@link SettingEntry.Type#SUBSCREEN} / {@link SettingEntry.Type#ACTION} entry does.
 * The glue layer of each Minecraft version maps these ids to its own screens.
 */
public enum SettingAction {
    /** Starts the 快速設定 questionnaire. */
    OPEN_QUICK_SETUP,
    OPEN_LANGUAGE,
    OPEN_KEYBINDS,
    /** Opens the stand-alone manual screen (說明書). */
    OPEN_MANUAL,
    /** Opens the manual on its privacy chapter. */
    OPEN_PRIVACY,
    /** Opens the project page on GitHub (after the game's own link confirmation). */
    OPEN_GITHUB,
    OPEN_AI,
    OPEN_DO_NOT_TRANSLATE,
    /** Opens the "warm every item" screen; the glue reports it unavailable until wired. */
    OPEN_ITEM_WARMUP,
    HUB_DOWNLOAD,
    EXPORT_TRANSLATIONS,
    IMPORT_TRANSLATIONS,
    /** Destructive: glue must confirm first (with the entry count). */
    CLEAR_CACHE
}
