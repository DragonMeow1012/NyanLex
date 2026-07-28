package com.dragonmeow.mctranslator.legacy;

import java.util.Locale;

final class LegacyConfig {
    static final String DEFAULT_CODEX_MODEL = "gpt-5.6-terra";
    static final String DEFAULT_CODEX_REASONING_EFFORT = "medium";
    boolean enabled = true;
    boolean followGameLanguage = true;
    boolean showOriginal = true;
    String targetLang = "zh-TW";
    String sourceLang = "auto";
    /** Key-free machine source: google, youdao, deepl, or microsoft. */
    String machineTranslationProvider = "google";
    boolean aiEnabled = false;
    boolean disableGoogleFallbackForAi = false;
    String aiBaseUrl = "https://generativelanguage.googleapis.com/v1beta/openai";
    String aiModel = "gemini-3.1-flash-lite";
    boolean aiUseCodex = false;
    String codexModel = DEFAULT_CODEX_MODEL;
    String codexReasoningEffort = DEFAULT_CODEX_REASONING_EFFORT;
    java.util.List<String> aiApiKeys = new java.util.ArrayList<String>();
    java.util.Map<String, String> aiKeysByEndpoint = new java.util.LinkedHashMap<String, String>();
    /** One-time migration marker for the safer Gemini 3.1 Flash-Lite pacing default. */
    int pacingDefaultsVersion = 0;
    int requestCooldownMs = 10000;
    /** Ordinary misses collect for this long; zero flushes on the next client tick. */
    int batchWindowMs = 5000;
    int failureBackoffMs = 10000;
    boolean debugTranslationOverlay = false;

    static String normalizeMachineProvider(String value) {
        String provider = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if ("youdao".equals(provider) || "deepl".equals(provider)
                || "microsoft".equals(provider)) return provider;
        return "google";
    }

    static LegacyConfig normalizeLoaded(LegacyConfig loaded) {
        if (loaded == null) return null;
        if (loaded.aiApiKeys == null) loaded.aiApiKeys = new java.util.ArrayList<String>();
        if (loaded.aiKeysByEndpoint == null)
            loaded.aiKeysByEndpoint = new java.util.LinkedHashMap<String, String>();
        if (loaded.codexModel == null || loaded.codexModel.trim().isEmpty())
            loaded.codexModel = DEFAULT_CODEX_MODEL;
        if (loaded.codexReasoningEffort == null || loaded.codexReasoningEffort.trim().isEmpty())
            loaded.codexReasoningEffort = DEFAULT_CODEX_REASONING_EFFORT;
        loaded.machineTranslationProvider = normalizeMachineProvider(loaded.machineTranslationProvider);
        if (loaded.pacingDefaultsVersion < 1) {
            if (loaded.requestCooldownMs == 6000) loaded.requestCooldownMs = 10000;
            loaded.pacingDefaultsVersion = 1;
        }
        if (loaded.requestCooldownMs < 0) loaded.requestCooldownMs = 10000;
        if (loaded.batchWindowMs < 0) loaded.batchWindowMs = 5000;
        if (loaded.failureBackoffMs < 0) loaded.failureBackoffMs = 10000;
        return loaded;
    }
}
