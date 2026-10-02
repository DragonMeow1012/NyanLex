package com.dragonmeow.nyanlex.translate;

import java.util.Set;

/**
 * Decides whether broad screen-text hooks may collect from the current screen.
 *
 * <p>Vanilla already localizes its options UI. Translating those pages again creates
 * noisy duplicate requests (the language list is the worst case), so known vanilla
 * options titles are blocked as a whole. Video/display settings are intentionally not
 * listed and remain translatable. Unknown keys and literal titles default to allowed so
 * modded settings screens continue to work.</p>
 */
public final class ScreenTranslationPolicy {
    private ScreenTranslationPolicy() {}

    private static final Set<String> BLOCKED_TITLE_KEYS = Set.of(
            "options.title",
            "options.language", "options.language.title",
            "options.skinCustomisation", "options.skinCustomisation.title",
            "options.sounds", "options.sounds.title",
            "options.controls", "controls.title",
            "controls.keybinds", "controls.keybinds.title",
            "options.mouse_settings", "options.mouse_settings.title",
            "options.chat", "options.chat.title",
            "options.resourcepack", "resourcePack.title",
            "options.accessibility", "options.accessibility.title",
            "options.font", "options.font.title",
            "options.telemetry", "telemetry_info.screen.title",
            "options.credits_and_attribution", "credits_and_attribution.screen.title",
            "options.multiplayer.title", "options.online.title",
            "debug.options.title",
            "accessibility.onboarding.screen.title"
    );

    public static boolean allowsTranslation(String titleTranslationKey) {
        return titleTranslationKey == null || titleTranslationKey.isBlank()
                || !BLOCKED_TITLE_KEYS.contains(titleTranslationKey);
    }
}
