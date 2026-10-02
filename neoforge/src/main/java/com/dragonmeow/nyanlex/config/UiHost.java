package com.dragonmeow.nyanlex.config;

import java.util.List;

/**
 * Everything the settings panel needs from the surrounding game: config access, text,
 * actions and the warm-up driver. Implemented by each loader's screen glue.
 */
public interface UiHost {

    TranslatorConfig config();

    void saveConfig();

    /** Localized text for {@code key} ({@code %s} filled from {@code args}); the key itself if unknown. */
    String text(String key, Object... args);

    /** Width in GUI pixels of {@code text} in the font the canvas draws with. */
    int textWidth(String text);

    /** Opens a sub-screen / runs an action (destructive ones must be confirmed by the glue). */
    void runAction(SettingAction action);

    /** Extra work after a toggle press (clear pending requests, clear the debug log...). */
    void sideEffect(SettingEntry.SideEffect effect);

    /** Called before a toggle changes; return false when the glue handled it itself (e.g. a confirmation dialog). */
    default boolean beforeToggle(SettingEntry entry) { return true; }

    /** Replacement button text for an action entry (e.g. download progress), or null. */
    default String buttonLabel(SettingEntry entry) { return null; }

    /** False greys the entry's control out. */
    default boolean enabled(SettingEntry entry) { return true; }

    default WarmupStatus warmupStatus() { return WarmupStatus.UNAVAILABLE; }

    default void warmupCommand(WarmupCommand command) { }

    /** Whether the first-open hint is still to be shown. */
    default boolean showIntro() { return false; }

    /** Short transient message (e.g. "cache cleared") drawn at the bottom of the list, or null. */
    default String statusText() { return null; }

    default String modVersion() { return ""; }

    default int translatedCount() { return 0; }

    default int pendingCount() { return 0; }

    default String clipboard() { return ""; }

    default void playClick() { }

    /** Every file/folder the mod keeps, for the 進階 > 檔案位置 group; empty when the glue has none. */
    default List<FileLocations.Entry> fileLocations() { return List.of(); }

    /** Opens the file manager on {@code entry} (selecting the file). Glue falls back to the
     *  game's own folder opener when {@link FileOpener#reveal} fails. */
    default void openFileLocation(FileLocations.Entry entry) { }

    /** Leave the settings screen (the "完成" button). */
    void close();

    default long nowMs() { return System.currentTimeMillis(); }
}
