package com.dragonmeow.nyanslate.legacy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Immutable semantic boundary for chat requests that may still complete asynchronously. */
final class LegacyChatRequestProfile {
    private final boolean enabled;
    private final boolean requestsEnabled;
    private final List<String> doNotTranslateTerms;
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

    private LegacyChatRequestProfile(boolean enabled, boolean requestsEnabled,
                                     List<String> doNotTranslateTerms, String targetLanguage,
                                     String sourceLanguage, String machineProvider,
                                     boolean aiEnabled, boolean googleFallbackDisabled,
                                     boolean codex, String aiEndpoint, String aiModel,
                                     String codexModel, String codexEffort) {
        this.enabled = enabled;
        this.requestsEnabled = requestsEnabled;
        this.doNotTranslateTerms = doNotTranslateTerms;
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
            return new LegacyChatRequestProfile(false, true, Collections.<String>emptyList(),
                    "", "auto", "google", false, false, false, "", "", "", "");
        }
        boolean useAi = config.aiEnabled;
        boolean useCodex = useAi && config.aiUseCodex;
        boolean machineSettingsActive = !useAi || !config.disableGoogleFallbackForAi;
        return new LegacyChatRequestProfile(
                config.enabled,
                config.translationRequestsEnabled,
                canonicalTerms(config.doNotTranslateTerms),
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
        return requestsEnabled == that.requestsEnabled && sameExceptRequestSwitch(that);
    }

    /**
     * True when only the master request switch changed. Callers flush waiting chat but keep
     * requests already on the wire (their results are still cached); queued work is dropped
     * by the translator itself while the switch is off.
     */
    boolean differsOnlyInRequestSwitch(LegacyChatRequestProfile other) {
        return other != null && requestsEnabled != other.requestsEnabled
                && sameExceptRequestSwitch(other);
    }

    private boolean sameExceptRequestSwitch(LegacyChatRequestProfile that) {
        return enabled == that.enabled
                && doNotTranslateTerms.equals(that.doNotTranslateTerms)
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
        result = 31 * result + (requestsEnabled ? 1 : 0);
        result = 31 * result + doNotTranslateTerms.hashCode();
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

    /** Matching is case-insensitive and order-free, so only lower-cased set changes matter. */
    private static List<String> canonicalTerms(List<String> terms) {
        List<String> canonical = new ArrayList<String>();
        for (String term : LegacyConfig.normalizeDoNotTranslateTerms(terms)) {
            canonical.add(term.toLowerCase(Locale.ROOT));
        }
        Collections.sort(canonical);
        return Collections.unmodifiableList(canonical);
    }

    private static String normalized(String value, String fallback) {
        String result = safe(value).trim();
        return result.isEmpty() ? fallback : result.toLowerCase(Locale.ROOT);
    }

    private static String safe(String value) { return value == null ? "" : value.trim(); }
}
