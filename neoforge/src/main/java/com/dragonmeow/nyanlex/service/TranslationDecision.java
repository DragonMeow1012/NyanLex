package com.dragonmeow.nyanlex.service;

import com.dragonmeow.nyanlex.config.DisplayMode;

/**
 * The outcome of asking the service to translate one line. Carries enough info
 * for the glue to rebuild a styled {@code Text} (original kept for colour
 * preservation in PARALLEL mode), while keeping all the decision logic
 * Minecraft-free and testable.
 *
 * @param changed    whether a translation should be shown
 * @param mode       display mode (only meaningful when {@code changed})
 * @param original   the original plain string
 * @param translated the translated plain string (only when {@code changed})
 */
public record TranslationDecision(boolean changed, DisplayMode mode, String original, String translated) {

    public static TranslationDecision unchanged(String original) {
        return new TranslationDecision(false, null, original, null);
    }

    public static TranslationDecision of(DisplayMode mode, String original, String translated) {
        return new TranslationDecision(true, mode, original, translated);
    }

    /**
     * Plain-text rendering with no colour, used as a fallback and in unit tests.
     * The colour-preserving rendering lives in the glue (it needs Minecraft's
     * {@code Text}). BOTH uses block format: original line(s), then translation.
     */
    public String renderPlain() {
        if (!changed) return original;
        if (mode == DisplayMode.BOTH) {
            return original + "\n" + translated;
        }
        return translated;
    }
}
