package com.dragonmeow.mctranslator.forgelegacy;

import java.util.Locale;

/** Immutable semantic boundary for chat requests that may still complete asynchronously. */
final class LegacyChatRequestProfile {
    private final boolean enabled;
    private final String targetLanguage;
    private final String sourceLanguage;
    private final String machineProvider;
    private final boolean aiEnabled;
    private final boolean googleFallbackDisabled;
    private final boolean codex;
    private final String aiEndpoint;
    private final String aiModel;
    private final String codexModel;
    private final String codexEffort;

    private LegacyChatRequestProfile(boolean enabled, String targetLanguage,
                                     String sourceLanguage, String machineProvider,
                                     boolean aiEnabled, boolean googleFallbackDisabled,
                                     boolean codex, String aiEndpoint, String aiModel,
                                     String codexModel, String codexEffort) {
        this.enabled = enabled;
        this.targetLanguage = targetLanguage;
        this.sourceLanguage = sourceLanguage;
        this.machineProvider = machineProvider;
        this.aiEnabled = aiEnabled;
        this.googleFallbackDisabled = googleFallbackDisabled;
        this.codex = codex;
        this.aiEndpoint = aiEndpoint;
        this.aiModel = aiModel;
        this.codexModel = codexModel;
        this.codexEffort = codexEffort;
    }

    static LegacyChatRequestProfile capture(LegacyConfig config, String targetLanguage) {
        if (config == null) {
            return new LegacyChatRequestProfile(false, "", "auto", "google",
                    false, false, false, "", "", "", "");
        }
        boolean useAi = config.aiEnabled;
        boolean useCodex = useAi && config.aiUseCodex;
        boolean machineSettingsActive = !useAi || !config.disableGoogleFallbackForAi;
        return new LegacyChatRequestProfile(
                config.enabled,
                normalized(targetLanguage, ""),
                machineSettingsActive ? normalized(config.sourceLang, "auto") : "",
                machineSettingsActive
                        ? LegacyConfig.normalizeMachineProvider(config.machineTranslationProvider)
                        : "",
                useAi,
                useAi && config.disableGoogleFallbackForAi,
                useCodex,
                useAi && !useCodex ? safe(config.aiBaseUrl) : "",
                useAi && !useCodex ? safe(config.aiModel) : "",
                useCodex ? safe(config.codexModel) : "",
                useCodex ? safe(config.codexReasoningEffort) : "");
    }

    @Override public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof LegacyChatRequestProfile)) return false;
        LegacyChatRequestProfile that = (LegacyChatRequestProfile) other;
        return enabled == that.enabled
                && aiEnabled == that.aiEnabled
                && googleFallbackDisabled == that.googleFallbackDisabled
                && codex == that.codex
                && targetLanguage.equals(that.targetLanguage)
                && sourceLanguage.equals(that.sourceLanguage)
                && machineProvider.equals(that.machineProvider)
                && aiEndpoint.equals(that.aiEndpoint)
                && aiModel.equals(that.aiModel)
                && codexModel.equals(that.codexModel)
                && codexEffort.equals(that.codexEffort);
    }

    @Override public int hashCode() {
        int result = enabled ? 1 : 0;
        result = 31 * result + targetLanguage.hashCode();
        result = 31 * result + sourceLanguage.hashCode();
        result = 31 * result + machineProvider.hashCode();
        result = 31 * result + (aiEnabled ? 1 : 0);
        result = 31 * result + (googleFallbackDisabled ? 1 : 0);
        result = 31 * result + (codex ? 1 : 0);
        result = 31 * result + aiEndpoint.hashCode();
        result = 31 * result + aiModel.hashCode();
        result = 31 * result + codexModel.hashCode();
        return 31 * result + codexEffort.hashCode();
    }

    private static String normalized(String value, String fallback) {
        String result = safe(value).trim();
        return result.isEmpty() ? fallback : result.toLowerCase(Locale.ROOT);
    }

    private static String safe(String value) { return value == null ? "" : value.trim(); }
}
