package com.dragonmeow.nyanlex.config;

/** What the warm-up card asks the glue to do (the glue owns the driver and its screens). */
public enum WarmupCommand {
    /** Open the scan-and-confirm screen (a run never starts without that confirmation). */
    START,
    PAUSE,
    RESUME,
    STOP,
    /** Open the full progress screen. */
    OPEN_PROGRESS,
    /** The items are not on AI: take the player to 翻譯服務 (handled by the panel itself). */
    TOGGLE_CATEGORIES,
    TOGGLE_ITEMS,
    TOGGLE_SCREEN,
    OPEN_SERVICE
}
