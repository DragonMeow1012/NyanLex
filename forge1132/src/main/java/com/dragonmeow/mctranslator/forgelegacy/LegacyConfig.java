package com.dragonmeow.mctranslator.forgelegacy;

import java.util.Locale;

final class LegacyConfig {
    boolean enabled = true;
    boolean followGameLanguage = true;
    boolean showOriginal = true;
    String targetLang = "zh-TW";
    String sourceLang = "auto";
    /** Key-free machine source configured in mctranslator-forge-legacy.json. */
    String machineTranslationProvider = "google";
    boolean aiEnabled = false;
    boolean disableGoogleFallbackForAi = false;
    String aiBaseUrl = "https://generativelanguage.googleapis.com/v1beta/openai";
    String aiModel = "gemini-3.1-flash-lite";
    java.util.List<String> aiApiKeys = new java.util.ArrayList<String>();
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
