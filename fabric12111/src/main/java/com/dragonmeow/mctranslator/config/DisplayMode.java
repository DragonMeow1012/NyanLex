package com.dragonmeow.mctranslator.config;

/**
 * How translated text is shown. Cycled by the in-game button / keybind.
 *
 * <ul>
 *   <li>{@link #ORIGINAL_ONLY} – 原文：no translation shown.</li>
 *   <li>{@link #BOTH} – 原文+翻譯：all original lines, then all translated lines (block format).</li>
 *   <li>{@link #TRANSLATION} – 只有翻譯：original replaced by the translation.</li>
 * </ul>
 */
public enum DisplayMode {
    ORIGINAL_ONLY,
    BOTH,
    TRANSLATION;

    /**
     * Next mode in the cycle. Order keeps a translating mode after the first press
     * from the default (只有翻譯 → 原文＋翻譯 → 原文 → …), so toggling doesn't
     * immediately turn translation off.
     */
    public DisplayMode next() {
        return switch (this) {
            case TRANSLATION -> BOTH;
            case BOTH -> ORIGINAL_ONLY;
            case ORIGINAL_ONLY -> TRANSLATION;
        };
    }

    /** Human label (Traditional Chinese) for the button. */
    public String displayName() {
        return switch (this) {
            case ORIGINAL_ONLY -> "原文";
            case BOTH -> "原文＋翻譯";
            case TRANSLATION -> "只有翻譯";
        };
    }
}
